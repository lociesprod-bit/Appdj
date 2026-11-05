package com.xdj.flx10test.wave

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Beat grid dạng danh sách thời điểm từng beat (giây), không chỉ một cặp (BPM, nhịp đầu).
 *
 * App 0.15.0 lấy grid từ rekordbox bằng **một** BPM 2 số lẻ và **một** mốc đầu
 * (`RekordboxXml`: TEMPO đầu tiên; `RekordboxAnalysis`: beat đầu của PQTZ). Các mốc sau bị bỏ, nên:
 * - BPM thật kiểu 125,996 bị làm tròn thành 126,00, càng về cuối bài grid càng trôi
 *   (~12 ms sau 5 phút với sai số 0,005 BPM);
 * - bài có nhiều mốc TEMPO/PQTZ thì chỉ dùng đoạn đầu.
 *
 * Ở đây mọi mốc đều được giữ. [fitConstant] tính (BPM, nhịp đầu) khớp nhất cho engine native,
 * vì `Engine.setGrid(deck, gen, bpm, beat0)` hiện chỉ nhận grid cố định.
 */
class BeatGrid private constructor(
    private val beats: DoubleArray,
    /** Vị trí trong ô nhịp (1–4) của từng beat. */
    private val bar: ByteArray,
    val beatsPerBar: Int,
) {
    init {
        require(beats.size >= 2) { "BeatGrid cần ít nhất 2 beat" }
    }

    val size: Int get() = beats.size
    val first: Double get() = beats[0]
    val last: Double get() = beats[beats.size - 1]

    private val firstInterval get() = beats[1] - beats[0]
    private val lastInterval get() = beats[beats.size - 1] - beats[beats.size - 2]

    /** Thời điểm beat thứ [i] (có thể âm hoặc vượt cuối: ngoại suy bằng khoảng beat đầu/cuối). */
    fun beatTime(i: Long): Double = when {
        i < 0 -> beats[0] + i * firstInterval
        i >= beats.size -> beats[beats.size - 1] + (i - beats.size + 1) * lastInterval
        else -> beats[i.toInt()]
    }

    /** Chỉ số beat (số thực) tại thời điểm [t]: 10,25 = sau beat 10 một phần tư beat. */
    fun indexAt(t: Double): Double {
        if (t <= beats[0]) return (t - beats[0]) / firstInterval
        val n = beats.size
        if (t >= beats[n - 1]) return (n - 1) + (t - beats[n - 1]) / lastInterval
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (beats[mid] <= t) lo = mid else hi = mid
        }
        return lo + (t - beats[lo]) / (beats[hi] - beats[lo])
    }

    /** Vị trí trong ô nhịp (1..beatsPerBar) của beat [i]. */
    fun beatInBar(i: Long): Int = when {
        i in 0 until beats.size -> bar[i.toInt()].toInt()
        i < 0 -> 1 + Math.floorMod(bar[0] - 1 + i, beatsPerBar.toLong()).toInt()
        else -> 1 + Math.floorMod(bar[beats.size - 1] - 1 + (i - beats.size + 1), beatsPerBar.toLong()).toInt()
    }

    /** BPM tại thời điểm [t] (theo khoảng giữa hai beat quanh t). */
    fun bpmAt(t: Double): Double {
        val i = floor(indexAt(t)).toLong()
        val d = beatTime(i + 1) - beatTime(i)
        return if (d > 0) 60.0 / d else 0.0
    }

    /** Thời điểm beat gần [t] nhất. */
    fun nearestBeat(t: Double): Double = beatTime(Math.round(indexAt(t)))

    data class Line(val time: Double, val index: Long, val beatInBar: Int, val barNumber: Long) {
        val downbeat: Boolean get() = beatInBar == 1
    }

    /** Các vạch grid trong [t0, t1] (giây). barNumber đếm từ 1 ở ô nhịp chứa beat 0. */
    fun linesIn(t0: Double, t1: Double, limit: Int = 4096): List<Line> {
        if (t1 < t0) return emptyList()
        val out = ArrayList<Line>()
        var i = kotlin.math.ceil(indexAt(t0) - 1e-9).toLong()
        val offset0 = bar[0] - 1 // beat 0 nằm ở vị trí offset0 trong ô nhịp đầu
        while (out.size < limit) {
            val t = beatTime(i)
            if (t > t1 + 1e-9) break
            if (t >= t0 - 1e-9) {
                val barNumber = Math.floorDiv(i + offset0, beatsPerBar.toLong()) + 1
                out.add(Line(t, i, beatInBar(i), barNumber))
            }
            i++
        }
        return out
    }

    /** Grid dời [seconds] (dương = muộn hơn), dùng để bù lệch bộ giải mã. */
    fun shifted(seconds: Double): BeatGrid =
        if (seconds == 0.0) this else BeatGrid(DoubleArray(beats.size) { beats[it] + seconds }, bar, beatsPerBar)

    data class ConstantFit(val bpm: Double, val beat0: Double, val maxErrorMs: Double, val firstBeatInBar: Int)

    /**
     * (BPM, nhịp đầu) khớp nhất theo bình phương tối thiểu trên mọi beat, cho engine chỉ nhận grid cố định.
     * Với PQTZ (thời điểm từng beat), cách này lấy lại được BPM chính xác (ví dụ 125,996) thay vì 126,00 đã làm tròn.
     * [ConstantFit.maxErrorMs] lớn (> ~5 ms) nghĩa là bài thật sự đổi tempo.
     * beat0 được đưa về ô nhịp đầu (≥ 0) như cách 0.15.0 lưu.
     */
    fun fitConstant(): ConstantFit {
        val n = beats.size
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            sx += x
            sy += beats[i]
            sxx += x * x
            sxy += x * beats[i]
        }
        val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        val intercept = (sy - slope * sx) / n
        var maxErr = 0.0
        for (i in 0 until n) maxErr = max(maxErr, abs(beats[i] - (intercept + slope * i)))
        val bpm = 60.0 / slope
        // beat 0 ở vị trí bar[0] trong ô nhịp: lùi về beat "1" của ô nhịp rồi đưa về ≥ 0.
        var beat0 = intercept - (bar[0] - 1) * slope
        val barLen = beatsPerBar * slope
        while (beat0 < 0) beat0 += barLen
        while (beat0 - barLen >= 0) beat0 -= barLen
        return ConstantFit(bpm, beat0, maxErr * 1000.0, 1)
    }

    companion object {
        /** Một mốc TEMPO của rekordbox XML: `Inizio` (giây), `Bpm`, `Battito` (1–4). */
        data class Tempo(val inizio: Double, val bpm: Double, val battito: Int = 1, val beatsPerBar: Int = 4)

        /**
         * Grid từ **mọi** mốc TEMPO (đã sắp theo thời gian) tới hết [durationSec].
         * Giữa hai mốc: tempo của mốc trước, beat cuối cùng trước mốc sau bị bỏ nếu cách mốc sau < 1/4 beat
         * (rekordbox đặt mốc mới đúng vào một beat).
         */
        fun fromTempos(tempos: List<Tempo>, durationSec: Double): BeatGrid? {
            val list = tempos.filter { it.bpm in 20.0..400.0 && it.inizio.isFinite() }.sortedBy { it.inizio }
            if (list.isEmpty()) return null
            val bpb = list[0].beatsPerBar.coerceIn(1, 16)
            val times = ArrayList<Double>()
            val bars = ArrayList<Byte>()
            // Trước mốc đầu: lùi theo tempo đầu tới 0.
            run {
                val step = 60.0 / list[0].bpm
                var t = list[0].inizio - step
                var b = list[0].battito
                val pre = ArrayList<Pair<Double, Int>>()
                while (t >= -1e-9) {
                    b = if (b <= 1) bpb else b - 1
                    pre.add(t to b)
                    t -= step
                }
                pre.asReversed().forEach { (tt, bb) ->
                    times.add(tt)
                    bars.add(bb.toByte())
                }
            }
            for ((k, a) in list.withIndex()) {
                val step = 60.0 / a.bpm
                val end = if (k + 1 < list.size) list[k + 1].inizio else max(durationSec, a.inizio + step)
                var b = a.battito.coerceIn(1, bpb)
                var j = 0L
                while (true) {
                    val t = a.inizio + j * step
                    if (k + 1 < list.size) {
                        if (t > end - step * 0.25) break
                    } else if (t > end + 1e-9) {
                        break
                    }
                    times.add(t)
                    bars.add(b.toByte())
                    b = if (b >= bpb) 1 else b + 1
                    j++
                }
            }
            if (times.size < 2) {
                val step = 60.0 / list[0].bpm
                times.add(times.lastOrNull()?.plus(step) ?: (list[0].inizio + step))
                bars.add(((bars.lastOrNull()?.toInt() ?: 1) % bpb + 1).toByte())
                if (times.size < 2) return null
            }
            return BeatGrid(times.toDoubleArray(), bars.toByteArray(), bpb)
        }

        /** Grid cố định (BPM, nhịp đầu) như app 0.15.0, tới hết [durationSec]. */
        fun constant(bpm: Double, beat0: Double, durationSec: Double, beatsPerBar: Int = 4): BeatGrid? =
            fromTempos(listOf(Tempo(beat0, bpm, 1, beatsPerBar)), durationSec)

        /**
         * Grid từ danh sách beat của ANLZ PQTZ: mỗi beat có thời điểm (ms) và vị trí trong ô nhịp (1–4).
         * Đây là grid "thật" của rekordbox; không làm tròn BPM.
         */
        fun fromBeats(timesMs: LongArray, beatInBar: IntArray, beatsPerBar: Int = 4): BeatGrid? {
            if (timesMs.size < 2 || timesMs.size != beatInBar.size) return null
            val t = DoubleArray(timesMs.size) { timesMs[it] / 1000.0 }
            for (i in 1 until t.size) if (t[i] <= t[i - 1]) return null
            return BeatGrid(t, ByteArray(t.size) { beatInBar[it].coerceIn(1, beatsPerBar).toByte() }, beatsPerBar)
        }

        /** Từ mảng giây (dùng cho test/ước lượng). */
        fun fromSeconds(times: DoubleArray, firstBeatInBar: Int = 1, beatsPerBar: Int = 4): BeatGrid? {
            if (times.size < 2) return null
            val bars = ByteArray(times.size) { (Math.floorMod(firstBeatInBar - 1 + it, beatsPerBar) + 1).toByte() }
            return BeatGrid(times.copyOf(), bars, beatsPerBar)
        }

        /** Làm tròn ms như PQTZ lưu (để test). */
        fun roundMs(seconds: Double): Long = (seconds * 1000.0).roundToLong()
    }
}
