package com.xdj.flx10test.wave

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sóng chi tiết để vẽ khi zoom sâu (chỉnh grid): mỗi khối [framesPerBlock] frame (mặc định 16 ≈ 0,36 ms ở 44,1 kHz)
 * lưu min/max **có dấu** của tín hiệu mono và độ mạnh 3 dải tần.
 *
 * App 0.15.0 chỉ có ~300 cột/giây (`ScrollWaveView.drawWave`: `columnsPerPixel = framesPerPixel * 300 / rate`).
 * Khi zoom 0,27 s trên 2000 px, khoảng 80 cột bị kéo giãn thành mảng màu mượt nên không thấy dao động thật.
 * Với 16 frame/khối, kick 50–60 Hz có ~50 điểm mỗi chu kỳ nên hiện đúng hình sóng như rekordbox.
 *
 * Bộ nhớ: 5 byte/khối, bài 5 phút ở 44,1 kHz ≈ 4,1 MB.
 */
class DetailWave internal constructor(
    val sampleRate: Int,
    val framesPerBlock: Int,
    val size: Int,
    internal val min: ByteArray,
    internal val max: ByteArray,
    internal val low: ByteArray,
    internal val mid: ByteArray,
    internal val high: ByteArray,
    /** Đỉnh lớn nhất (0–127) để tự phóng to bài nhỏ tiếng. */
    val peak: Int,
) {
    val durationSec: Double get() = size.toDouble() * framesPerBlock / sampleRate

    fun minAt(i: Int): Int = min[i].toInt()
    fun maxAt(i: Int): Int = max[i].toInt()
    fun lowAt(i: Int): Int = low[i].toInt() and 0xff
    fun midAt(i: Int): Int = mid[i].toInt() and 0xff
    fun highAt(i: Int): Int = high[i].toInt() and 0xff

    companion object {
        /** Màu RGB giống `RgbWaveData.color` của app: đỏ = bass, xanh lá = mid, xanh dương = treble. */
        fun color(low: Int, mid: Int, high: Int): Int {
            val m = max(low, max(mid, high))
            if (m <= 0) return 0xFF121212.toInt()
            fun ch(v: Int) = (220.0 * Math.pow(v.coerceIn(0, m).toDouble() / m, 0.85)).toInt() + 18
            return (0xFF shl 24) or (ch(low) shl 16) or (ch(mid) shl 8) or ch(high)
        }
    }
}

/** Biquad RBJ (Direct Form I). */
internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        private fun make(type: Int, f: Double, rate: Int, q: Double = 0.7071): Biquad {
            val w = 2 * Math.PI * f / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return if (type == 0) {
                Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            } else {
                Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }

        fun lowPass(f: Double, rate: Int) = make(0, f, rate)
        fun highPass(f: Double, rate: Int) = make(1, f, rate)
    }
}

/**
 * Dựng [DetailWave] từ PCM 16-bit interleaved, nạp dần từng mẻ (cùng luồng giải mã đang tạo bands/rgb ở app).
 * Dải tần như waveform 3 dải: bass < 200 Hz, mid 200–2 500 Hz, treble > 2 500 Hz.
 */
class DetailWaveBuilder(
    private val sampleRate: Int,
    private val framesPerBlock: Int = 16,
    expectedFrames: Long = 0,
) {
    private var cap = max(1024, (expectedFrames / framesPerBlock + 16).toInt().coerceAtMost(50_000_000))
    private var aMin = ByteArray(cap)
    private var aMax = ByteArray(cap)
    private var aLow = ByteArray(cap)
    private var aMid = ByteArray(cap)
    private var aHigh = ByteArray(cap)
    private var n = 0

    // Mỗi dải lọc riêng (không lấy phần dư x − bass − treble, vì lệch pha làm kick bị "vàng").
    private val lp1 = Biquad.lowPass(200.0, sampleRate)
    private val lp2 = Biquad.lowPass(200.0, sampleRate)
    private val hp1 = Biquad.highPass(2500.0, sampleRate)
    private val hp2 = Biquad.highPass(2500.0, sampleRate)
    private val bp1 = Biquad.highPass(200.0, sampleRate)
    private val bp2 = Biquad.highPass(200.0, sampleRate)
    private val bp3 = Biquad.lowPass(2500.0, sampleRate)
    private val bp4 = Biquad.lowPass(2500.0, sampleRate)

    private var k = 0
    private var bMin = Double.MAX_VALUE
    private var bMax = -Double.MAX_VALUE
    private var bLow = 0.0
    private var bMid = 0.0
    private var bHigh = 0.0
    private var peak = 0

    /** [pcm]: [count] mẫu interleaved, [channels] kênh. */
    fun feed(pcm: ShortArray, count: Int, channels: Int) {
        val ch = channels.coerceAtLeast(1)
        var i = 0
        while (i + ch <= count) {
            var s = 0.0
            for (c in 0 until ch) s += pcm[i + c]
            val x = s / ch / 32768.0
            val lo = lp2.process(lp1.process(x))
            val hi = hp2.process(hp1.process(x))
            val md = bp4.process(bp3.process(bp2.process(bp1.process(x))))
            if (x < bMin) bMin = x
            if (x > bMax) bMax = x
            bLow = max(bLow, abs(lo))
            bMid = max(bMid, abs(md))
            bHigh = max(bHigh, abs(hi))
            if (++k == framesPerBlock) flush()
            i += ch
        }
    }

    private fun flush() {
        if (k == 0) return
        if (n == cap) grow()
        val mn = (bMin * 127.0).toInt().coerceIn(-127, 127)
        val mx = (bMax * 127.0).toInt().coerceIn(-127, 127)
        aMin[n] = mn.toByte()
        aMax[n] = mx.toByte()
        // Căn bậc hai để dải yếu vẫn góp màu (giống tai người hơn tuyến tính).
        aLow[n] = (sqrt(bLow.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        aMid[n] = (sqrt(bMid.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        aHigh[n] = (sqrt(bHigh.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        peak = max(peak, max(abs(mn), abs(mx)))
        n++
        k = 0
        bMin = Double.MAX_VALUE
        bMax = -Double.MAX_VALUE
        bLow = 0.0
        bMid = 0.0
        bHigh = 0.0
    }

    private fun grow() {
        cap = (cap * 3 / 2).coerceAtLeast(cap + 1024)
        aMin = aMin.copyOf(cap)
        aMax = aMax.copyOf(cap)
        aLow = aLow.copyOf(cap)
        aMid = aMid.copyOf(cap)
        aHigh = aHigh.copyOf(cap)
    }

    fun build(): DetailWave {
        flush()
        return DetailWave(sampleRate, framesPerBlock, n, aMin.copyOf(n), aMax.copyOf(n), aLow.copyOf(n), aMid.copyOf(n), aHigh.copyOf(n), peak)
    }
}
