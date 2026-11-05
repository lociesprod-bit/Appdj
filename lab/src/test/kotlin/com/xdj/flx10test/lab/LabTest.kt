package com.xdj.flx10test.lab

import android.widget.ListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.text.Normalizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabTest {
    @get:Rule val tmp = TemporaryFolder()

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<DJ_PLAYLISTS Version="1.0.0">
  <PRODUCT Name="rekordbox" Version="7.2.18" Company="AlphaTheta"/>
  <COLLECTION Entries="2">
    <TRACK TrackID="1" Name="Ai Chung Tinh Duoc Mai HD" Artist="" Genre=" /* Club / House Lak */" Kind="WAV File" TotalTime="470"
      AverageBpm="130.00" Rating="0" Comments=" /* Club / House Lak / Bass Music / Remix / Grooving Up / Viet Nam */"
      Location="file://localhost/Users/locies.prod/Music/All%20Music%20DJ/11A%20-%20Ai%20Chung%20Tinh%20Duoc%20Mai%20HD.wav" Tonality="11A">
      <TEMPO Inizio="0.025" Bpm="130.00" Metro="4/4" Battito="1"/>
      <POSITION_MARK Name="" Type="0" Start="0.025" Num="0"/>
    </TRACK>
    <TRACK TrackID="2" Name="Bên Trên Tầng Lầu" Artist="Tăng Duy Tân" AverageBpm="127.00" Rating="204" TotalTime="257"
      Location="file://localhost/Users/x/All%20Music%20DJ/B%C3%AAn%20Tr%C3%AAn%20T%E1%BA%A7ng%20L%E1%BA%A7u%2B.mp3" Tonality="Bm"/>
  </COLLECTION>
  <PLAYLISTS><NODE Type="0" Name="ROOT" Count="0"/></PLAYLISTS>
</DJ_PLAYLISTS>"""

    @Test
    fun parsesRekordboxXmlForDisplay() {
        val r = RekordboxXmlLite.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("rekordbox 7.2.18", r.product)
        assertEquals(2, r.tracks.size)
        val a = r.tracks[0]
        assertEquals("11A - Ai Chung Tinh Duoc Mai HD.wav", a.fileName)
        assertEquals(130.0, a.bpm, 0.001)
        assertEquals(470, a.totalTimeSec)
        val b = r.tracks[1]
        assertEquals("Bên Trên Tầng Lầu+.mp3", b.fileName) // %2B → '+', không thành dấu cách
        assertEquals(4, b.rating)
        assertEquals("Bm", b.tonality)
    }

    @Test
    fun readsEveryTempoAnchorForRequestedTracks() {
        val multi = xml.replace(
            """<TEMPO Inizio="0.025" Bpm="130.00" Metro="4/4" Battito="1"/>""",
            """<TEMPO Inizio="0.025" Bpm="130.00" Metro="4/4" Battito="1"/><TEMPO Inizio="60.025" Bpm="130.00" Metro="4/4" Battito="1"/><TEMPO Inizio="120.025" Bpm="128.00" Metro="4/4" Battito="3"/>""",
        )
        val key = RekordboxXmlLite.matchKey("11A - Ai Chung Tinh Duoc Mai HD.wav")
        val grids = RekordboxXmlLite.grids(ByteArrayInputStream(multi.toByteArray()), setOf(key, "missing.mp3"))
        val g = grids[key]!!
        assertEquals(3, g.tempos.size)
        assertEquals(120.025, g.tempos[2].inizio, 1e-9)
        assertEquals(128.0, g.tempos[2].bpm, 1e-9)
        assertEquals(3, g.tempos[2].battito)
        assertEquals("WAV File", g.kind)
        assertEquals(1, grids.size)
        val grid = com.xdj.flx10test.wave.BeatGrid.fromTempos(g.tempos, 300.0)!!
        assertEquals(128.0, grid.bpmAt(200.0), 1e-6)
    }

    @Test
    fun matchKeyIgnoresUnicodeFormAndCase() {
        val nfd = Normalizer.normalize("Bên Trên Tầng Lầu.mp3", Normalizer.Form.NFD)
        assertEquals(RekordboxXmlLite.matchKey("BÊN TRÊN TẦNG LẦU.mp3"), RekordboxXmlLite.matchKey(nfd))
        assertEquals(3, RekordboxXmlLite.ratingStars(153))
        assertEquals(0, RekordboxXmlLite.ratingStars(0))
        assertEquals(5, RekordboxXmlLite.ratingStars(255))
    }

    @Test
    fun metaRoundTripThroughCache() {
        val tracks = RekordboxXmlLite.parse(ByteArrayInputStream(xml.toByteArray())).tracks
        assertEquals(tracks, LabLibrary.decodeMeta(LabLibrary.encodeMeta(tracks)))
    }

    @Test
    fun stemsPatternMatchesSeratoNames() {
        assertTrue(LabLibrary.STEMS.containsMatchIn("11A - Ai Chung Tinh Duoc Mai HD.1.0.serato-stems"))
        assertEquals("Song", LabLibrary.STEMS.replace("Song.12.3.SERATO-STEMS", ""))
        assertTrue(!LabLibrary.STEMS.containsMatchIn("Song.serato-stems"))
    }

    private fun fakeStems(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeBytes("srtshead")
        out.writeInt(16)
        out.writeShort(1)
        out.writeShort(2)
        out.writeInt(4)
        out.writeInt(44100 * 3)
        out.writeInt(44100)
        for (type in listOf(3, 0, 2, 1)) {
            val payload = ByteArray(10) { (it + type * 16).toByte() }
            out.writeBytes("stem")
            out.writeInt(payload.size + 4)
            out.writeInt(type)
            out.write(payload.map { (it.toInt() xor 0x26).toByte() }.toByteArray())
        }
        return bytes.toByteArray()
    }

    @Test
    fun extractsSeratoStemsContainer() {
        val folder = tmp.newFolder("stems")
        val e = SeratoStemsFile.extract(ByteArrayInputStream(fakeStems()), folder)
        assertEquals(44100, e.rate)
        assertEquals(listOf(0, 1, 2, 3), e.stems.map { it.type })
        assertEquals(ByteArray(10) { (it + 2 * 16).toByte() }.toList(), e.stems[2].file.readBytes().toList())
        assertEquals("VOCAL", SeratoStemsFile.typeName(0))
        assertEquals("MELODY", SeratoStemsFile.typeName(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonStemsFile() {
        SeratoStemsFile.extract(ByteArrayInputStream("not a stems file at all".toByteArray()), tmp.newFolder("x"))
    }

    @Test
    fun activityStartsWithoutFolder() {
        val activity = Robolectric.buildActivity(LabActivity::class.java).setup().get()
        val list = activity.window.decorView.findViewsByType(ListView::class.java)
        assertNotNull(list.firstOrNull())
        assertEquals(0, list.first().adapter.count)
    }

    private fun <T> android.view.View.findViewsByType(type: Class<T>): List<T> {
        val out = ArrayList<T>()
        fun walk(v: android.view.View) {
            if (type.isInstance(v)) out.add(type.cast(v)!!)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }
}
