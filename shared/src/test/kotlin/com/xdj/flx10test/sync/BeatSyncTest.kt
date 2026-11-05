package com.xdj.flx10test.sync

import com.xdj.flx10test.wave.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class SyncTapDetectorTest {
    @Test
    fun singleAndDoublePress() {
        val d = SyncTapDetector(400)
        assertEquals(SyncTapDetector.Action.LOCK, d.press(1_000, synced = false))
        assertEquals(SyncTapDetector.Action.INSTANT_MATCH, d.press(1_250, synced = true))
        // Lần thứ ba ngay sau đó không bị coi là bấm đôi nữa.
        assertEquals(SyncTapDetector.Action.LOCK, d.press(1_400, synced = false))
        assertEquals(SyncTapDetector.Action.UNLOCK_KEEP_TEMPO, d.press(3_000, synced = true))
        assertEquals(SyncTapDetector.Action.LOCK, d.press(5_000, synced = false))
        // Quá cửa sổ → không phải bấm đôi.
        assertEquals(SyncTapDetector.Action.UNLOCK_KEEP_TEMPO, d.press(5_500, synced = true))
    }

    @Test
    fun doublePressWhileLockedAlsoMatches() {
        val d = SyncTapDetector(400)
        assertEquals(SyncTapDetector.Action.UNLOCK_KEEP_TEMPO, d.press(10_000, synced = true))
        assertEquals(SyncTapDetector.Action.INSTANT_MATCH, d.press(10_300, synced = false))
    }
}

class BeatSyncTest {
    private fun grid(bpm: Double, beat0: Double = 0.1) = BeatGrid.constant(bpm, beat0, 600.0)!!

    @Test
    fun matchesTempoAndBeatPhase() {
        val master = DeckSnapshot(positionSec = 30.0, tempoRatio = 1.02, grid = grid(126.0))
        val slave = DeckSnapshot(positionSec = 45.3, tempoRatio = 1.0, grid = grid(124.0, 0.05))
        val m = BeatSync.instantMatch(master, slave)!!
        assertEquals(126.0 * 1.02 / 124.0, m.tempoRatio, 1e-9)
        assertEquals(126.0 * 1.02, m.targetBpm, 1e-9)
        val mPhase = master.grid.indexAt(30.0).let { it - floor(it) }
        val sPhase = slave.grid.indexAt(m.seekToSec).let { it - floor(it) }
        assertEquals(mPhase, sPhase, 1e-9)
        assertTrue(abs(m.shiftBeats) <= 0.5)
        assertTrue(abs(m.seekToSec - 45.3) < 60.0 / 124.0)
    }

    @Test
    fun alignsBarsWhenAsked() {
        val master = DeckSnapshot(10.0, 1.0, grid(128.0, 0.0))
        val slave = DeckSnapshot(20.0, 1.0, grid(128.0, 0.0))
        val m = BeatSync.instantMatch(master, slave, alignBars = true)!!
        val mi = master.grid.indexAt(10.0)
        val si = slave.grid.indexAt(m.seekToSec)
        assertEquals(master.grid.beatInBar(floor(mi).toLong()), slave.grid.beatInBar(floor(si).toLong()))
        assertEquals(mi - floor(mi), si - floor(si), 1e-9)
        assertTrue(abs(m.shiftBeats) <= 2.0)
    }

    @Test
    fun halfAndDoubleTempo() {
        val master = DeckSnapshot(30.0, 1.0, grid(140.0))
        val slave = DeckSnapshot(30.0, 1.0, grid(72.0))
        val m = BeatSync.instantMatch(master, slave)!!
        assertEquals(70.0 / 72.0, m.tempoRatio, 1e-9)
        assertEquals(70.0, m.targetBpm, 1e-9)
        // Không cho ×2/÷2 và vượt giới hạn → null.
        assertNull(BeatSync.instantMatch(master, slave, allowHalfDouble = false, maxRatioChange = 0.5))
    }

    @Test
    fun sliderPositionAndPickup() {
        val p = TempoSlider.positionFor(1.016, currentRange = 0.06f)
        assertEquals(0.06f, p.range)
        assertEquals(1.016, TempoSlider.ratioFor(p.slider, p.range), 1e-6)
        val wide = TempoSlider.positionFor(1.12, currentRange = 0.06f)
        assertEquals(0.16f, wide.range)
        val pick = TempoPickup(0.01f)
        pick.arm(0.63f)
        assertFalse(pick.onFader(0.50f)) // thanh vật lý đang ở giữa → bỏ qua
        assertFalse(pick.onFader(0.55f))
        assertTrue(pick.onFader(0.65f)) // kéo qua vị trí mới → bắt
        assertFalse(pick.armed)
        assertTrue(pick.onFader(0.40f))
        pick.arm(0.3f)
        assertTrue(pick.onFader(0.305f)) // trong dung sai
    }
}
