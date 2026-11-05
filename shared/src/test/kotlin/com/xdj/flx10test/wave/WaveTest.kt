package com.xdj.flx10test.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class BeatGridTest {
    private val tempo = BeatGrid.Companion.Tempo(inizio = 0.026, bpm = 126.0, battito = 1)

    @Test
    fun singleTempoGivesConstantGrid() {
        val g = BeatGrid.fromTempos(listOf(tempo), durationSec = 60.0)!!
        val step = 60.0 / 126
        assertEquals(0.026, g.beatTime(0), 1e-9)
        assertEquals(0.026 + 10 * step, g.beatTime(10), 1e-9)
        assertEquals(10.25, g.indexAt(0.026 + 10.25 * step), 1e-9)
        assertEquals(126.0, g.bpmAt(30.0), 1e-6)
        assertEquals(1, g.beatInBar(0))
        assertEquals(2, g.beatInBar(1))
        assertEquals(1, g.beatInBar(4))
        // Ngoại suy ngoài phạm vi.
        assertEquals(0.026 - step, g.beatTime(-1), 1e-9)
        assertEquals(4, g.beatInBar(-1))
    }

    @Test
    fun battitoShiftsBarPositionAndPrependsBeats() {
        // Mốc đầu ở 1,0 s là beat 3 của ô nhịp → có beat trước đó (beat 2, beat 1) vẫn ≥ 0.
        val g = BeatGrid.fromTempos(listOf(BeatGrid.Companion.Tempo(1.0, 120.0, 3)), 10.0)!!
        assertEquals(0.0, g.beatTime(0), 1e-9)
        assertEquals(1, g.beatInBar(0))
        assertEquals(3, g.beatInBar(2))
        assertEquals(1.0, g.beatTime(2), 1e-9)
        val fit = g.fitConstant()
        assertEquals(120.0, fit.bpm, 1e-6)
        assertEquals(0.0, fit.beat0, 1e-6)
    }

    @Test
    fun allTempoAnchorsAreUsed() {
        // 0.15.0 chỉ dùng TEMPO đầu; ở đây đoạn 2 đổi tempo và mốc mới được tôn trọng.
        val g = BeatGrid.fromTempos(
            listOf(BeatGrid.Companion.Tempo(0.5, 120.0, 1), BeatGrid.Companion.Tempo(8.5, 128.0, 1)),
            durationSec = 20.0,
        )!!
        assertEquals(120.0, g.bpmAt(4.0), 1e-6)
        assertEquals(128.0, g.bpmAt(12.0), 1e-6)
        // 8 s ở 120 BPM = 16 beat → beat 16 đúng ở mốc thứ hai.
        assertEquals(8.5, g.beatTime(17), 1e-9) // beat 0 ở 0,0 (lùi 1 beat từ 0,5)
        assertTrue(g.fitConstant().maxErrorMs > 5)
    }

    @Test
    fun fitRecoversPreciseBpmFromPqtzBeats() {
        // rekordbox lưu tempo ×100 (126,00) nhưng thời điểm từng beat theo BPM thật 125,996.
        val bpm = 125.996
        val n = 640
        val ms = LongArray(n) { BeatGrid.roundMs(0.0312 + it * 60.0 / bpm) }
        val bars = IntArray(n) { it % 4 + 1 }
        val g = BeatGrid.fromBeats(ms, bars)!!
        val fit = g.fitConstant()
        assertEquals(bpm, fit.bpm, 0.0005)
        assertTrue(fit.maxErrorMs < 1.0)
        // Grid 126,00 từ cùng mốc đầu thì lệch dần: tới beat cuối lệch > 9 ms.
        val rounded = BeatGrid.constant(126.0, 0.0312, 400.0)!!
        assertTrue(abs(rounded.beatTime((n - 1).toLong()) - g.beatTime((n - 1).toLong())) * 1000 > 9)
    }

    @Test
    fun linesInReportsBarsAndShift() {
        val g = BeatGrid.fromTempos(listOf(tempo), 60.0)!!
        val step = 60.0 / 126
        val lines = g.linesIn(0.0, 4 * step + 0.026)
        assertEquals(5, lines.size)
        assertTrue(lines[0].downbeat)
        assertEquals(1L, lines[0].barNumber)
        assertEquals(2L, lines[4].barNumber)
        val shifted = g.shifted(0.012)
        assertEquals(0.038, shifted.beatTime(0), 1e-9)
        assertEquals(g.nearestBeat(1.0) + 0.012, shifted.nearestBeat(1.012), 1e-9)
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(BeatGrid.fromTempos(emptyList(), 10.0))
        assertNull(BeatGrid.fromTempos(listOf(BeatGrid.Companion.Tempo(0.0, 0.0)), 10.0))
        assertNull(BeatGrid.fromBeats(longArrayOf(10), intArrayOf(1)))
        assertNull(BeatGrid.fromBeats(longArrayOf(10, 5), intArrayOf(1, 2)))
    }
}

class GridOffsetEstimatorTest {
    private val rate = 44100

    /** Bài giả: kick 55 Hz tắt dần ở từng beat, bắt đầu [startSec], tempo [bpm], dời [offsetMs]. */
    private fun kicks(bpm: Double, startSec: Double, seconds: Double, offsetMs: Double = 0.0): ShortArray {
        val n = (seconds * rate).toInt()
        val out = ShortArray(n)
        val step = 60.0 / bpm
        var t = startSec + offsetMs / 1000
        while (t < seconds) {
            val s0 = (t * rate).toInt()
            for (k in 0 until (0.18 * rate).toInt()) {
                val i = s0 + k
                if (i !in 0 until n) continue
                val tt = k.toDouble() / rate
                out[i] = (out[i] + 26000 * exp(-tt * 18) * sin(2 * PI * 55 * tt)).toInt().coerceIn(-32767, 32767).toShort()
            }
            t += step
        }
        // Thêm chút nhiễu hi-hat để không quá "sạch".
        for (i in 0 until n step 7) out[i] = (out[i] + ((i * 7919) % 400 - 200)).coerceIn(-32767, 32767).toShort()
        return out
    }

    @Test
    fun findsConstantOffset() {
        val audio = kicks(126.0, 0.5, 40.0, offsetMs = 12.0)
        val grid = BeatGrid.fromTempos(listOf(BeatGrid.Companion.Tempo(0.5, 126.0)), 40.0)!!
        val r = GridOffsetEstimator.estimate(audio, audio.size, rate, grid)!!
        // Đỉnh onset muộn hơn điểm bắt đầu kick vài ms, nên chỉ so chênh lệch giữa hai lần đo.
        val base = GridOffsetEstimator.estimate(kicks(126.0, 0.5, 40.0), audio.size, rate, grid)!!
        assertEquals(12.0, r.medianMs - base.medianMs, 1.5)
        assertTrue(r.iqrMs < 2.0)
        assertTrue(abs(r.driftMsPerMin) < 2.0)
        assertTrue(r.usedBeats > 70)
    }

    @Test
    fun detectsDriftFromRoundedBpm() {
        // Audio thật 126,05 BPM, grid làm tròn 126,00 → cuối bài audio sớm hơn grid (lệch âm dần).
        val audio = kicks(126.05, 0.5, 60.0)
        val grid = BeatGrid.fromTempos(listOf(BeatGrid.Companion.Tempo(0.5, 126.0)), 60.0)!!
        val r = GridOffsetEstimator.estimate(audio, audio.size, rate, grid)!!
        assertTrue("drift ${r.driftMsPerMin}", r.driftMsPerMin < -10)
        assertTrue(r.endMs < r.startMs - 10)
    }

    @Test
    fun silenceGivesNoResult() {
        val grid = BeatGrid.fromTempos(listOf(BeatGrid.Companion.Tempo(0.5, 126.0)), 10.0)!!
        assertNull(GridOffsetEstimator.estimate(ShortArray(rate * 10), rate * 10, rate, grid))
    }
}

class Mp3GaplessTest {
    private fun frame(withLame: Boolean, delay: Int = 576, padding: Int = 1152, id3: Int = 0): ByteArray {
        val b = ByteArray(id3 + 1200)
        if (id3 > 0) {
            "ID3".toByteArray().copyInto(b)
            b[3] = 4
            val size = id3 - 10
            b[6] = ((size shr 21) and 0x7f).toByte(); b[7] = ((size shr 14) and 0x7f).toByte()
            b[8] = ((size shr 7) and 0x7f).toByte(); b[9] = (size and 0x7f).toByte()
        }
        val p = id3
        b[p] = 0xff.toByte(); b[p + 1] = 0xfb.toByte(); b[p + 2] = 0x90.toByte(); b[p + 3] = 0x00 // MPEG1 L3 128k 44.1k stereo
        val x = p + 4 + 32
        "Info".toByteArray().copyInto(b, x)
        b[x + 7] = 0x0f // frames|bytes|toc|quality
        val o = x + 8 + 4 + 4 + 100 + 4
        if (withLame) {
            "LAME3.100".toByteArray().copyInto(b, o)
            val v = (delay shl 12) or padding
            b[o + 21] = (v shr 16).toByte(); b[o + 22] = (v shr 8).toByte(); b[o + 23] = v.toByte()
        }
        return b
    }

    @Test
    fun readsLameDelayAndPadding() {
        val info = Mp3Gapless.parse(frame(true, 576, 1234))!!
        assertTrue(info.hasXing)
        assertTrue(info.hasLame)
        assertEquals(576, info.encoderDelay)
        assertEquals(1234, info.encoderPadding)
        assertEquals(44100, info.sampleRate)
    }

    @Test
    fun skipsId3AndHandlesMissingLame() {
        val info = Mp3Gapless.parse(frame(false, id3 = 300))!!
        assertEquals(300, info.firstFrameOffset)
        assertTrue(info.hasXing)
        assertFalse(info.hasLame)
        assertNull(Mp3Gapless.parse(ByteArray(64)))
    }
}

class DetailWaveBuilderTest {
    private fun tone(freq: Double, seconds: Double, rate: Int = 44100, amp: Double = 0.5): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n * 2) { i -> (amp * 32767 * sin(2 * PI * freq * (i / 2) / rate)).toInt().toShort() }
    }

    @Test
    fun keepsSignedShapeAndBandColors() {
        val b = DetailWaveBuilder(44100)
        val bass = tone(50.0, 1.0)
        b.feed(bass, bass.size, 2)
        val w = b.build()
        assertEquals((44100 + 15) / 16, w.size) // khối cuối chưa đủ 16 frame vẫn được giữ
        assertEquals(1.0, w.durationSec, 0.01)
        assertTrue(w.peak in 60..64) // 0,5 × 127
        // 50 Hz: mỗi chu kỳ 55 khối, nên có khối toàn dương và khối toàn âm (hình sóng thật, không phải đường bao).
        val mid = w.size / 2
        val signs = (mid until mid + 60).map { if (w.minAt(it) > 0) 1 else if (w.maxAt(it) < 0) -1 else 0 }.toSet()
        assertTrue(signs.containsAll(listOf(1, -1)))
        assertTrue(w.lowAt(mid) > w.highAt(mid) * 3)
        val hb = DetailWaveBuilder(44100)
        val hat = tone(6000.0, 0.5)
        hb.feed(hat, hat.size, 2)
        val hw = hb.build()
        assertTrue(hw.highAt(hw.size / 2) > hw.lowAt(hw.size / 2) * 3)
    }

    @Test
    fun colorMapsBandsToRgb() {
        val red = DetailWave.color(255, 0, 0)
        assertEquals(238, (red shr 16) and 0xff)
        assertEquals(18, (red shr 8) and 0xff)
        assertEquals(18, red and 0xff)
        assertNotNull(DetailWave.color(0, 0, 0))
    }
}
