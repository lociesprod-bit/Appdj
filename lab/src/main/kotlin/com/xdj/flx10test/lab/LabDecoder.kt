package com.xdj.flx10test.lab

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.xdj.flx10test.sd.SdAccess
import com.xdj.flx10test.wave.DetailWave
import com.xdj.flx10test.wave.DetailWaveBuilder
import com.xdj.flx10test.wave.Mp3Gapless
import java.nio.ByteOrder

/** Giải mã cả bài bằng MediaCodec (giống app) để Lab vẽ sóng chi tiết và đo lệch grid. */
object LabDecoder {
    class Decoded(
        val mono: ShortArray,
        val count: Int,
        val sampleRate: Int,
        val channels: Int,
        val detail: DetailWave?,
        val mime: String,
        val codec: String,
        /** Khoá "encoder-delay"/"encoder-padding" của MediaExtractor (−1 nếu không có). */
        val formatDelay: Int,
        val formatPadding: Int,
        val mp3: Mp3Gapless.Info?,
        val millis: Long,
    ) {
        val durationSec: Double get() = count.toDouble() / sampleRate

        fun describeFormat(): String {
            val ext = buildString {
                append(mime.substringAfter('/'))
                if (formatDelay >= 0) append(" · format delay $formatDelay/padding $formatPadding")
                mp3?.let { append(" · ").append(it.describe()) }
            }
            return "$ext · $codec · $sampleRate Hz"
        }
    }

    fun decode(ctx: Context, access: SdAccess, track: LabTrack, buildDetail: Boolean, cancelled: () -> Boolean = { false }): Decoded {
        val started = System.nanoTime()
        val mp3 = if (track.relPath.endsWith(".mp3", true)) {
            access.openInputStream(track.documentId).use { input ->
                val head = ByteArray(256 * 1024)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                Mp3Gapless.parse(head.copyOf(n))
            }
        } else {
            null
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            access.setExtractorSource(extractor, ctx, track.documentId)
            val trackIndex = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val delay = if (format.containsKey("encoder-delay")) format.getInteger("encoder-delay") else -1
            val padding = if (format.containsKey("encoder-padding")) format.getInteger("encoder-padding") else -1
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var mono = ShortArray(((durationUs / 1_000_000.0 + 2) * rate).toInt().coerceIn(rate, 60 * 60 * rate))
            var count = 0
            var builder: DetailWaveBuilder? = null
            val info = MediaCodec.BufferInfo()
            var shorts = ShortArray(0)
            var inputDone = false
            var done = false
            while (!done) {
                if (cancelled()) throw InterruptedException()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = sb.remaining()
                    if (shorts.size < n) shorts = ShortArray(n)
                    sb.get(shorts, 0, n)
                    c.releaseOutputBuffer(o, false)
                    if (buildDetail && builder == null) builder = DetailWaveBuilder(rate, 16, durationUs * rate / 1_000_000)
                    builder?.feed(shorts, n, channels)
                    val ch = channels.coerceAtLeast(1)
                    val frames = n / ch
                    if (count + frames > mono.size) mono = mono.copyOf((mono.size * 3 / 2).coerceAtLeast(count + frames))
                    var k = 0
                    for (f in 0 until frames) {
                        var s = 0
                        for (cc in 0 until ch) s += shorts[k++]
                        mono[count++] = (s / ch).toShort()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            return Decoded(
                mono, count, rate, channels, builder?.build(), mime, c.name, delay, padding, mp3,
                (System.nanoTime() - started) / 1_000_000,
            )
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
