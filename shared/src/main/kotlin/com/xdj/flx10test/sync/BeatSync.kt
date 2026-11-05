package com.xdj.flx10test.sync

import com.xdj.flx10test.wave.BeatGrid
import kotlin.math.abs
import kotlin.math.floor

/**
 * Nút SYNC theo yêu cầu 05/11/2026:
 * - **Bấm 1 lần:** như 0.15.0, khoá theo MASTER (`DjController.syncPress` → `Engine.syncTo(d, m, true)`).
 * - **Bấm 2 lần nhanh** (trong [windowMs]): khớp tempo **và** nhịp với MASTER ngay lập tức, rồi **mở khoá**.
 *   Sau đó thanh tempo vẫn chỉnh bình thường.
 * - **Tắt SYNC giữ nguyên tempo đang chạy**: 0.15.0 gọi `tempoSlider(d, slider[d])` nên tempo nhảy về vị trí thanh trượt.
 * - Chỉ **RESET** mới đưa về tempo gốc.
 */
class SyncTapDetector(private val windowMs: Long = 400) {
    enum class Action {
        /** Bật khoá SYNC (như 0.15.0). */
        LOCK,

        /** Tắt khoá SYNC, giữ tempo đang chạy. */
        UNLOCK_KEEP_TEMPO,

        /** Khớp tempo + nhịp một lần, rồi để mở (không khoá). */
        INSTANT_MATCH,
    }

    private var lastPress = Long.MIN_VALUE / 2

    /** Gọi mỗi lần bấm SYNC. [synced] = deck đang khoá SYNC trước lần bấm này. */
    fun press(nowMs: Long, synced: Boolean): Action {
        val second = nowMs - lastPress in 0..windowMs
        return if (second) {
            // Lần bấm thứ hai: khớp một lần rồi mở khoá. Đặt lại để lần thứ ba không bị tính là bấm đôi nữa.
            lastPress = Long.MIN_VALUE / 2
            Action.INSTANT_MATCH
        } else {
            lastPress = nowMs
            if (synced) Action.UNLOCK_KEEP_TEMPO else Action.LOCK
        }
    }
}

/** Ảnh chụp một deck khi tính SYNC. */
data class DeckSnapshot(
    /** Vị trí phát trong bài (giây, theo thời gian gốc của file). */
    val positionSec: Double,
    /** Tốc độ phát hiện tại so với gốc (1,0 = tempo gốc). */
    val tempoRatio: Double,
    val grid: BeatGrid,
)

object BeatSync {
    data class Match(
        /** Tỉ lệ tempo mới cho deck theo (slave). */
        val tempoRatio: Double,
        /** BPM đích (= BPM đang nghe của MASTER, hoặc ×2/÷2). */
        val targetBpm: Double,
        /** Vị trí mới (giây) để nhịp trùng MASTER. */
        val seekToSec: Double,
        /** Số beat dịch (−0,5..0,5, hoặc −2..2 khi khớp cả ô nhịp). */
        val shiftBeats: Double,
    )

    /**
     * Khớp [slave] với [master]: tempo bằng BPM đang nghe của MASTER, pha beat trùng nhau.
     * [alignBars] = true thì khớp cả vị trí trong ô nhịp (beat 1 trùng beat 1).
     * [allowHalfDouble] = true thì chọn trong {÷2, ×1, ×2} tỉ lệ gần 1 nhất (ví dụ 70 ↔ 140 BPM).
     * Trả null nếu thiếu BPM hoặc tỉ lệ vượt [maxRatioChange].
     */
    fun instantMatch(
        master: DeckSnapshot,
        slave: DeckSnapshot,
        alignBars: Boolean = false,
        allowHalfDouble: Boolean = true,
        maxRatioChange: Double = 1.0,
    ): Match? {
        val masterBpm = master.grid.bpmAt(master.positionSec) * master.tempoRatio
        val slaveBase = slave.grid.bpmAt(slave.positionSec)
        if (!(masterBpm > 0) || !(slaveBase > 0)) return null
        var ratio = masterBpm / slaveBase
        var target = masterBpm
        var beatScale = 1.0 // số beat slave ứng với 1 beat master
        if (allowHalfDouble) {
            val options = listOf(Triple(ratio, masterBpm, 1.0), Triple(ratio / 2, masterBpm / 2, 0.5), Triple(ratio * 2, masterBpm * 2, 2.0))
            val best = options.minByOrNull { abs(it.first - 1.0) }!!
            ratio = best.first
            target = best.second
            beatScale = best.third
        }
        if (abs(ratio - 1.0) > maxRatioChange) return null

        val mIndex = master.grid.indexAt(master.positionSec)
        val sIndex = slave.grid.indexAt(slave.positionSec)
        val period = if (alignBars) master.grid.beatsPerBar.toDouble() else 1.0
        // Pha của MASTER quy ra đơn vị beat của slave. ×2/÷2: một beat slave = 0,5/2 beat MASTER,
        // nên nhân chỉ số với beatScale rồi lấy dư theo chu kỳ.
        val mPos = if (alignBars) {
            val i = floor(mIndex).toLong()
            (master.grid.beatInBar(i) - 1) + (mIndex - i)
        } else {
            mIndex
        }
        val mPhase = floorMod(mPos * beatScale, period)
        val sPhase = if (alignBars) {
            val i = floor(sIndex).toLong()
            floorMod((slave.grid.beatInBar(i) - 1) + (sIndex - i), period)
        } else {
            floorMod(sIndex, period)
        }
        // Dịch ngắn nhất (−chu kỳ/2 .. +chu kỳ/2).
        var shift = floorMod(mPhase - sPhase, period)
        if (shift > period / 2) shift -= period
        val newIndex = sIndex + shift
        val i = floor(newIndex).toLong()
        val f = newIndex - i
        val seek = slave.grid.beatTime(i) + f * (slave.grid.beatTime(i + 1) - slave.grid.beatTime(i))
        return Match(ratio, target, seek, shift)
    }
}

/**
 * Vị trí thanh tempo (0..1, giữa = tempo gốc) cho một tỉ lệ tempo, giống `DjController.timing()`:
 * `slider = (ratio − 1) / (2·range) + 0,5`. Nếu vượt dải thì chọn dải rộng hơn trong [ranges].
 */
object TempoSlider {
    data class Position(val slider: Float, val range: Float)

    fun positionFor(ratio: Double, currentRange: Float, ranges: FloatArray = floatArrayOf(0.06f, 0.10f, 0.16f, 1.0f)): Position {
        val tempo = ratio - 1.0
        val range = if (abs(tempo) <= currentRange) currentRange else ranges.firstOrNull { abs(tempo) <= it } ?: 1.0f
        val slider = (tempo / (2.0 * range) + 0.5).toFloat().coerceIn(0f, 1f)
        return Position(slider, range)
    }

    fun ratioFor(slider: Float, range: Float): Double = 1.0 + (slider - 0.5) * 2.0 * range
}

/**
 * "Bắt" thanh tempo phần cứng (FLX10) sau khi SYNC đổi tempo bằng phần mềm (soft takeover).
 * Thanh vật lý đang ở chỗ khác, nên bỏ qua giá trị của nó cho tới khi người dùng kéo qua (hoặc tới sát) vị trí tempo mới.
 * Nhờ vậy tempo không nhảy khi vừa chạm thanh. Thanh trên màn hình chỉ cần dời tới vị trí mới, không cần lớp này.
 */
class TempoPickup(private val tolerance: Float = 0.01f) {
    private var target: Float? = null
    private var side = 0

    val armed: Boolean get() = target != null

    /** Tempo vừa được đặt bằng phần mềm, ứng với vị trí thanh [slider]. */
    fun arm(slider: Float) {
        target = slider
        side = 0
    }

    /** Giá trị thanh phần cứng mới. Trả true nếu được phép áp dụng (đã bắt được). */
    fun onFader(value: Float): Boolean {
        val t = target ?: return true
        if (abs(value - t) <= tolerance) {
            target = null
            return true
        }
        val s = if (value > t) 1 else -1
        if (side == 0) {
            side = s
            return false
        }
        if (s != side) {
            target = null
            return true
        }
        return false
    }
}

private fun floorMod(x: Double, m: Double): Double {
    val r = x - floor(x / m) * m
    return if (r >= m) r - m else r
}
