package com.xdj.flx10test.wave

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Ước lượng grid lệch bao nhiêu so với tiếng kick/transient thật trong audio đã giải mã trên máy.
 *
 * - Lệch **cố định** (cùng một số ms từ đầu tới cuối bài): thường do bộ giải mã MP3 của Android và của rekordbox
 *   xử lý encoder delay / decoder delay khác nhau.
 * - Lệch **trôi dần** (đầu bài đúng, cuối bài sai): BPM bị làm tròn 2 số lẻ hoặc chỉ dùng mốc TEMPO đầu.
 *
 * Cách làm:
 * 1. Lọc thông thấp ~150 Hz (kick).
 * 2. Tính năng lượng mỗi 1 ms.
 * 3. Hàm onset = độ tăng log-năng lượng.
 * 4. Với mỗi beat của grid, tìm đỉnh onset trong ±[windowMs], rồi lấy trung vị độ lệch.
 *    Độ lệch dương nghĩa là audio đến muộn hơn grid, tức grid cần dời muộn hơn.
 *
 * Đỉnh onset thường muộn hơn điểm bắt đầu tiếng kick vài ms, nên khi so các định dạng (MP3/WAV/FLAC/M4A)
 * với nhau, chênh lệch giữa chúng mới là con số đáng tin.
 */
object GridOffsetEstimator {
    data class Result(
        /** Trung vị độ lệch (ms), dương = audio muộn hơn grid. */
        val medianMs: Double,
        /** Khoảng tứ phân vị (ms): nhỏ = ổn định. */
        val iqrMs: Double,
        /** Trung vị 1/3 đầu và 1/3 cuối số beat (ms): khác nhau nhiều là grid trôi. */
        val startMs: Double,
        val endMs: Double,
        /** Độ trôi ước tính (ms mỗi phút). */
        val driftMsPerMin: Double,
        val usedBeats: Int,
        val totalBeats: Int,
    ) {
        fun describe(): String =
            "lệch %+.1f ms (IQR %.1f) · đầu %+.1f · cuối %+.1f · trôi %+.2f ms/phút · %d/%d beat".format(
                medianMs, iqrMs, startMs, endMs, driftMsPerMin, usedBeats, totalBeats,
            )
    }

    /** Năng lượng thông thấp mỗi [hopMs] ms từ PCM mono 16-bit. */
    fun lowEnergy(mono: ShortArray, count: Int, sampleRate: Int, cutoffHz: Double = 150.0, hopMs: Double = 1.0): FloatArray {
        val hop = max(1, (sampleRate * hopMs / 1000.0).toInt())
        val out = FloatArray(count / hop + 1)
        // Hai tầng lọc một cực ≈ dốc 12 dB/oct.
        val a = 1.0 - kotlin.math.exp(-2.0 * Math.PI * cutoffHz / sampleRate)
        var y1 = 0.0
        var y2 = 0.0
        var acc = 0.0
        var k = 0
        var j = 0
        for (i in 0 until count) {
            val x = mono[i] / 32768.0
            y1 += a * (x - y1)
            y2 += a * (y1 - y2)
            acc += y2 * y2
            if (++k == hop) {
                out[j++] = acc.toFloat()
                acc = 0.0
                k = 0
            }
        }
        if (k > 0 && j < out.size) out[j++] = acc.toFloat()
        return if (j == out.size) out else out.copyOf(j)
    }

    /** Hàm onset: độ tăng log-năng lượng so với [lagMs] trước, chỉ giữ phần dương. */
    fun onsetCurve(energy: FloatArray, lagMs: Int = 4): FloatArray {
        val eps = 1e-9
        val log = DoubleArray(energy.size) { ln(energy[it] + eps) }
        return FloatArray(energy.size) { i ->
            if (i < lagMs) 0f else max(0.0, log[i] - log[i - lagMs]).toFloat()
        }
    }

    /**
     * So [grid] với audio [mono] (16-bit, [sampleRate] Hz).
     * Chỉ tính beat có onset đủ mạnh (> [strength] lần trung vị hàm onset quanh đó), bỏ đoạn break không có kick.
     */
    fun estimate(
        mono: ShortArray,
        count: Int,
        sampleRate: Int,
        grid: BeatGrid,
        windowMs: Double = 45.0,
        strength: Double = 3.0,
    ): Result? {
        val energy = lowEnergy(mono, count, sampleRate)
        val onset = onsetCurve(energy)
        // Một bước = hop mẫu (44 mẫu ở 44,1 kHz = 0,9977 ms), không đúng 1 ms: quy đổi theo độ dài thật,
        // nếu không thì sai số dồn dần tới hàng chục ms sau vài chục giây.
        val hopMs = max(1, (sampleRate / 1000.0).toInt()) * 1000.0 / sampleRate
        if (onset.isEmpty()) return null
        val sorted = onset.copyOf().also { it.sort() }
        val median = max(sorted[sorted.size / 2].toDouble(), 1e-3)
        val durationSec = count.toDouble() / sampleRate
        val lines = grid.linesIn(0.0, durationSec, limit = 100_000)
        val deltas = ArrayList<Pair<Double, Double>>() // (thời điểm beat, độ lệch ms)
        for (line in lines) {
            val beatMs = line.time * 1000.0
            val center = beatMs / hopMs
            val lo = max(0, (center - windowMs / hopMs).toInt())
            val hi = min(onset.size - 1, (center + windowMs / hopMs).toInt())
            if (hi <= lo) continue
            var best = lo
            for (i in lo..hi) if (onset[i] > onset[best]) best = i
            if (onset[best] < strength * median) continue
            // Nội suy parabol quanh đỉnh để có độ chính xác dưới 1 ms.
            val refined = if (best in 1 until onset.size - 1) {
                val l = onset[best - 1].toDouble()
                val c = onset[best].toDouble()
                val r = onset[best + 1].toDouble()
                val den = l - 2 * c + r
                best + if (abs(den) > 1e-12) (0.5 * (l - r) / den).coerceIn(-0.5, 0.5) else 0.0
            } else {
                best.toDouble()
            }
            deltas.add(line.time to (refined * hopMs - beatMs))
        }
        if (deltas.size < 8) return null
        val values = deltas.map { it.second }.sorted()
        fun q(list: List<Double>, p: Double) = list[((list.size - 1) * p).toInt()]
        val third = max(1, deltas.size / 3)
        val start = deltas.take(third).map { it.second }.sorted()
        val end = deltas.takeLast(third).map { it.second }.sorted()
        val tStart = deltas.take(third).map { it.first }.average()
        val tEnd = deltas.takeLast(third).map { it.first }.average()
        val drift = if (tEnd - tStart > 1.0) (q(end, 0.5) - q(start, 0.5)) / ((tEnd - tStart) / 60.0) else 0.0
        return Result(
            medianMs = q(values, 0.5),
            iqrMs = q(values, 0.75) - q(values, 0.25),
            startMs = q(start, 0.5),
            endMs = q(end, 0.5),
            driftMsPerMin = drift,
            usedBeats = deltas.size,
            totalBeats = lines.size,
        )
    }
}

/**
 * Đọc thông tin gapless của MP3 (Xing/Info + LAME): encoder delay / padding (mẫu).
 * Giải mã MP3 có ~529 mẫu decoder delay. Nếu thêm encoder delay (thường 576), tổng ~1105 mẫu ≈ 25 ms ở 44,1 kHz.
 * Mỗi phần mềm trừ khác nhau, nên grid rekordbox có thể lệch cố định trên Android.
 */
object Mp3Gapless {
    data class Info(
        val sampleRate: Int,
        val hasXing: Boolean,
        val hasLame: Boolean,
        val encoderDelay: Int,
        val encoderPadding: Int,
        val firstFrameOffset: Int,
    ) {
        fun describe(): String = when {
            hasLame -> "LAME delay $encoderDelay · padding $encoderPadding"
            hasXing -> "Xing/Info, không có LAME"
            else -> "không có Xing/LAME"
        }
    }

    private val RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))

    /** [head]: vài KB đầu file (nên ≥ 16 KB nếu có ảnh bìa trong ID3). Trả null nếu không thấy frame MP3. */
    fun parse(head: ByteArray): Info? {
        var p = 0
        if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            p = 10 + size + footer
        }
        while (p + 4 <= head.size) {
            val b1 = head[p].toInt() and 0xff
            val b2 = head[p + 1].toInt() and 0xff
            if (b1 == 0xff && (b2 and 0xe0) == 0xe0) {
                val version = (b2 shr 3) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val layer = (b2 shr 1) and 3 // 1 = Layer III
                val b3 = head[p + 2].toInt() and 0xff
                val rateIdx = (b3 shr 2) and 3
                if (version != 1 && layer == 1 && rateIdx != 3 && ((b3 shr 4) and 0xf) != 0xf) {
                    val rate = RATES[version][rateIdx]
                    val mono = ((head[p + 3].toInt() and 0xff) shr 6) == 3
                    val side = if (version == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                    val x = p + 4 + side
                    val tag = if (x + 4 <= head.size) String(head, x, 4, Charsets.US_ASCII) else ""
                    val xing = tag == "Xing" || tag == "Info"
                    var lame = false
                    var delay = 0
                    var padding = 0
                    if (xing) {
                        // Sau "Xing"+flags(4) là các trường tuỳ chọn; LAME nằm ở x + 120 với bộ trường đầy đủ.
                        val flags = readInt(head, x + 4)
                        var o = x + 8
                        if (flags and 1 != 0) o += 4 // frames
                        if (flags and 2 != 0) o += 4 // bytes
                        if (flags and 4 != 0) o += 100 // TOC
                        if (flags and 8 != 0) o += 4 // quality
                        if (o + 24 <= head.size) {
                            val enc = String(head, o, 4, Charsets.US_ASCII)
                            if (enc == "LAME" || enc.startsWith("Lav")) {
                                lame = true
                                val d = o + 21
                                val v = ((head[d].toInt() and 0xff) shl 16) or ((head[d + 1].toInt() and 0xff) shl 8) or (head[d + 2].toInt() and 0xff)
                                delay = v shr 12
                                padding = v and 0xfff
                            }
                        }
                    }
                    return Info(rate, xing, lame, delay, padding, p)
                }
            }
            p++
        }
        return null
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        if (o + 4 > b.size) 0 else ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
}
