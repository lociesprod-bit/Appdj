package com.xdj.flx10test.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GridOffsetsTest {
    private val lame = Mp3Gapless.Info(44100, hasXing = true, hasLame = true, encoderDelay = 576, encoderPadding = 1000, firstFrameOffset = 0)
    private val plain = Mp3Gapless.Info(44100, hasXing = false, hasLame = false, encoderDelay = 0, encoderPadding = 0, firstFrameOffset = 0)

    @Test
    fun classifiesFormats() {
        assertEquals(GridOffsets.Kind.LOSSLESS, GridOffsets.kindOf("wav"))
        assertEquals(GridOffsets.Kind.LOSSLESS, GridOffsets.kindOf(".FLAC"))
        assertEquals(GridOffsets.Kind.MP3_PLAIN, GridOffsets.kindOf("mp3", plain))
        assertEquals(GridOffsets.Kind.MP3_PLAIN, GridOffsets.kindOf("mp3", null))
        assertEquals(GridOffsets.Kind.MP3_LAME, GridOffsets.kindOf("mp3", lame))
        assertEquals(GridOffsets.Kind.AAC, GridOffsets.kindOf("m4a", mime = "audio/mp4a-latm"))
        assertEquals(GridOffsets.Kind.LOSSLESS, GridOffsets.kindOf("m4a", mime = "audio/alac"))
        assertEquals(GridOffsets.Kind.OTHER, GridOffsets.kindOf("ogg"))
    }

    @Test
    fun measuredOffsetsMatchLabLog() {
        // MP3 không LAME: −9,2 ms ở 44,1 kHz; M4A: +10,8 ms; lossless: 0.
        assertEquals(-9.2, GridOffsets.seconds(GridOffsets.Kind.MP3_PLAIN, 44100) * 1000, 0.05)
        assertEquals(10.8, GridOffsets.seconds(GridOffsets.Kind.AAC, 44100) * 1000, 0.05)
        assertEquals(0.0, GridOffsets.seconds(GridOffsets.Kind.LOSSLESS, 48000), 1e-12)
        // Theo số mẫu: ở 48 kHz cùng 406 mẫu = 8,46 ms.
        assertEquals(-8.46, GridOffsets.seconds(GridOffsets.Kind.MP3_PLAIN, 48000) * 1000, 0.01)
        // Ghi đè tay (⚙).
        assertEquals(-0.0075, GridOffsets.seconds(GridOffsets.Kind.MP3_PLAIN, 44100, mapOf(GridOffsets.Kind.MP3_PLAIN to -7.5)), 1e-12)
        assertTrue(GridOffsets.describe(GridOffsets.Kind.MP3_PLAIN, 44100).contains("đã đo"))
        assertTrue(GridOffsets.describe(GridOffsets.Kind.AAC, 44100).contains("tạm"))
    }

    @Test
    fun correctedGridRemovesMeasuredMp3Offset() {
        // Audio MP3 sớm 9,2 ms so với grid rekordbox → dời grid sớm 9,2 ms thì vạch trùng audio.
        val grid = BeatGrid.constant(128.0, 0.100, 30.0)!!
        val audioBeat = 0.100 - 0.0092
        val fixed = grid.shifted(GridOffsets.seconds(GridOffsets.Kind.MP3_PLAIN, 44100))
        assertEquals(audioBeat, fixed.beatTime(0), 0.0001)
    }

    @Test
    fun mp3HeadSkipsLargeId3() {
        // ID3 300 KB (ảnh bìa) rồi frame MPEG1 L3 + "Info" + LAME.
        val id3Size = 300 * 1024
        val tag = ByteArray(10 + id3Size)
        "ID3".toByteArray().copyInto(tag); tag[3] = 4
        tag[6] = ((id3Size shr 21) and 0x7f).toByte(); tag[7] = ((id3Size shr 14) and 0x7f).toByte()
        tag[8] = ((id3Size shr 7) and 0x7f).toByte(); tag[9] = (id3Size and 0x7f).toByte()
        val frame = ByteArray(2000)
        frame[0] = 0xff.toByte(); frame[1] = 0xfb.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0
        "Info".toByteArray().copyInto(frame, 36)
        frame[36 + 7] = 0x0f
        val o = 36 + 120
        "LAME3.100".toByteArray().copyInto(frame, o)
        val v = (576 shl 12) or 1500
        frame[o + 21] = (v shr 16).toByte(); frame[o + 22] = (v shr 8).toByte(); frame[o + 23] = v.toByte()
        val head = Mp3Head.read(ByteArrayInputStream(tag + frame))
        assertTrue(head.size <= 16 * 1024)
        val info = Mp3Gapless.parse(head)
        assertNotNull(info)
        assertTrue(info!!.hasLame)
        assertEquals(576, info.encoderDelay)
        assertEquals(1500, info.encoderPadding)
    }
}
