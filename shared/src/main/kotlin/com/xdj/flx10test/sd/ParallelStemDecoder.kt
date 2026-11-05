package com.xdj.flx10test.sd

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Nơi nhận PCM 16-bit interleaved. Trả false từ [write] để dừng sớm. */
interface PcmSink {
    fun format(sampleRate: Int, channels: Int) {}
    fun write(pcm: ShortArray, count: Int): Boolean
}

/** Sink chỉ đếm số mẫu (Lab dùng để đo tốc độ giải mã). */
class CountingSink : PcmSink {
    @Volatile var samples: Long = 0
        private set
    @Volatile var channels: Int = 2
        private set
    @Volatile var sampleRate: Int = 0
        private set

    override fun format(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceAtLeast(1)
    }

    override fun write(pcm: ShortArray, count: Int): Boolean {
        samples += count
        return true
    }

    val frames: Long get() = samples / channels
}

/**
 * Giải mã nhiều file audio (4 stem) cùng lúc bằng MediaExtractor + MediaCodec.
 * Mỗi job dùng extractor và codec riêng, chạy trên pool [parallel] luồng.
 */
class ParallelStemDecoder(private val parallel: Int, private val pauseMs: Long = 0L) {
    class Job(val label: String, val open: (MediaExtractor) -> Unit, val sink: PcmSink)

    data class Result(
        val label: String,
        val ok: Boolean,
        val frames: Long,
        val millis: Long,
        val codec: String,
        val mime: String,
        val sampleRate: Int,
        val error: String?,
    )

    fun decodeAll(jobs: List<Job>, cancelled: () -> Boolean = { false }): List<Result> {
        if (jobs.isEmpty()) return emptyList()
        val n = parallel.coerceIn(1, jobs.size)
        val pool = Executors.newFixedThreadPool(n) { r -> Thread(r, "stem-decode").apply { isDaemon = true } }
        return try {
            val futures = jobs.map { job -> pool.submit(Callable { decodeOne(job, pauseMs, cancelled) }) }
            futures.map { it.get() }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L

        fun decodeOne(job: Job, pauseMs: Long = 0L, cancelled: () -> Boolean = { false }): Result {
            val started = System.nanoTime()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var codecName = ""
            var mime = ""
            var rate = 0
            var frames = 0L
            try {
                job.open(extractor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("Không có track audio")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                codecName = c.name
                c.configure(format, null, null, 0)
                c.start()
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var float = false
                job.sink.format(rate, channels)
                val info = MediaCodec.BufferInfo()
                var shorts = ShortArray(0)
                var inputDone = false
                var outputDone = false
                var batches = 0
                while (!outputDone) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (!inputDone) {
                        val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = c.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            job.sink.format(rate, channels)
                        }
                        outIndex >= 0 -> {
                            val out = c.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val count: Int
                            if (float) {
                                val fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                count = fb.remaining()
                                if (shorts.size < count) shorts = ShortArray(count)
                                for (i in 0 until count) {
                                    shorts[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            } else {
                                val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                                count = sb.remaining()
                                if (shorts.size < count) shorts = ShortArray(count)
                                sb.get(shorts, 0, count)
                            }
                            c.releaseOutputBuffer(outIndex, false)
                            if (count > 0) {
                                frames += count / channels.coerceAtLeast(1)
                                if (!job.sink.write(shorts, count)) outputDone = true
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (pauseMs > 0 && ++batches % 16 == 0) Thread.sleep(pauseMs)
                        }
                    }
                }
                return Result(job.label, true, frames, (System.nanoTime() - started) / 1_000_000, codecName, mime, rate, null)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(job.label, false, frames, (System.nanoTime() - started) / 1_000_000, codecName, mime, rate, "đã huỷ")
            } catch (e: Exception) {
                return Result(job.label, false, frames, (System.nanoTime() - started) / 1_000_000, codecName, mime, rate, e.message ?: e.javaClass.simpleName)
            } finally {
                try {
                    codec?.stop()
                } catch (_: Exception) {
                }
                codec?.release()
                extractor.release()
            }
        }
    }
}
