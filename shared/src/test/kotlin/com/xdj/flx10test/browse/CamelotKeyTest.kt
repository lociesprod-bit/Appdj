package com.xdj.flx10test.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CamelotKeyTest {
    private fun k(s: String?) = CamelotKey.parse(s)

    @Test
    fun parsesCamelot() {
        assertEquals(CamelotKey(11, true), k("11A"))
        assertEquals(CamelotKey(8, false), k("8B"))
        assertEquals(CamelotKey(8, true), k("08A"))
        assertEquals(CamelotKey(12, true), k(" 12a "))
        assertEquals(CamelotKey(1, false), k("1B"))
    }

    @Test
    fun parsesMusicalNames() {
        assertEquals(CamelotKey(8, true), k("Am"))
        assertEquals(CamelotKey(8, false), k("C"))
        assertEquals(CamelotKey(1, true), k("A♭m"))
        assertEquals(CamelotKey(1, true), k("Abm"))
        assertEquals(CamelotKey(1, true), k("G#m"))
        assertEquals(CamelotKey(2, false), k("F#"))
        assertEquals(CamelotKey(2, false), k("Gb"))
        assertEquals(CamelotKey(3, true), k("Bbm"))
        assertEquals(CamelotKey(3, true), k("Bbmin"))
        assertEquals(CamelotKey(10, true), k("Bm"))
        assertEquals(CamelotKey(1, false), k("B"))
        assertEquals(CamelotKey(8, false), k("C major"))
        assertEquals(CamelotKey(4, true), k("Fm"))
        assertEquals(CamelotKey(5, false), k("Eb"))
    }

    @Test
    fun parsesOpenKeyAndAppLabel() {
        assertEquals(CamelotKey(8, false), k("1d"))
        assertEquals(CamelotKey(8, true), k("1m"))
        assertEquals(CamelotKey(1, true), k("6m"))
        // Nhãn MusicalKey.label() và nhãn deck "≈".
        assertEquals(CamelotKey(1, true), k("1A · A♭m"))
        assertEquals(CamelotKey(10, true), k("≈10A · Bm"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(k(null))
        assertNull(k(""))
        assertNull(k("—"))
        assertNull(k("13A"))
        assertNull(k("0B"))
        assertNull(k("H"))
        assertNull(k("unknown"))
    }

    @Test
    fun keyIndexRoundTripMatchesMusicalKey() {
        // MusicalKey 0.15.0: index 0 = C (8B), 9 = A (11B), 12 = Cm (5A), 21 = Am (8A), 20 = A♭m (1A).
        assertEquals(CamelotKey(8, false), CamelotKey.fromKeyIndex(0))
        assertEquals(CamelotKey(11, false), CamelotKey.fromKeyIndex(9))
        assertEquals(CamelotKey(5, true), CamelotKey.fromKeyIndex(12))
        assertEquals(CamelotKey(8, true), CamelotKey.fromKeyIndex(21))
        assertEquals(CamelotKey(1, true), CamelotKey.fromKeyIndex(20))
        assertNull(CamelotKey.fromKeyIndex(-1))
        assertNull(CamelotKey.fromKeyIndex(24))
        for (i in 0..23) assertEquals(i, CamelotKey.fromKeyIndex(i)!!.toKeyIndex())
        assertEquals("A♭m", CamelotKey(1, true).noteName)
    }

    @Test
    fun transposeBySemitones() {
        val am = CamelotKey(8, true)
        assertEquals(CamelotKey(3, true), am.transpose(1)) // A#m/Bbm = 3A
        assertEquals(CamelotKey(1, true), am.transpose(-1)) // G#m = 1A
        assertEquals(am, am.transpose(12))
        assertEquals(am, am.transpose(-12))
        assertEquals(CamelotKey(10, true), am.transpose(2)) // Bm
    }

    @Test
    fun semitonesFromTempo() {
        assertEquals(0, CamelotKey.semitonesFor(1.0))
        assertEquals(0, CamelotKey.semitonesFor(1.0079)) // +0,79% như ảnh chụp deck 1
        assertEquals(1, CamelotKey.semitonesFor(1.06))
        assertEquals(-1, CamelotKey.semitonesFor(0.94))
        assertEquals(0, CamelotKey.semitonesFor(0.0))
        assertEquals(0, CamelotKey.semitonesFor(Double.NaN))
    }

    @Test
    fun masterKeyFollowsTempoOnlyWhenMasterTempoOff() {
        val bm = CamelotKey(10, true)
        assertEquals(bm, MasterKey.effective(bm, 1.06, masterTempo = true))
        assertEquals(CamelotKey(5, true), MasterKey.effective(bm, 1.06, masterTempo = false)) // Cm
        assertNull(MasterKey.effective(null, 1.0, false))
    }
}

class KeyMatchTest {
    private fun m(master: String, track: String) = KeyMatch.of(CamelotKey.parse(master), CamelotKey.parse(track))

    @Test
    fun sameKey() {
        assertEquals(KeyMatch.SAME, m("10A", "10A"))
        assertEquals(KeyMatch.SAME, m("Bm", "10A"))
    }

    @Test
    fun neighboursOnWheel() {
        assertEquals(KeyMatch.COMPATIBLE, m("10A", "9A"))
        assertEquals(KeyMatch.COMPATIBLE, m("10A", "11A"))
        assertEquals(KeyMatch.COMPATIBLE, m("12B", "1B"))
        assertEquals(KeyMatch.COMPATIBLE, m("1A", "12A"))
    }

    @Test
    fun relativeMajorMinor() {
        assertEquals(KeyMatch.COMPATIBLE, m("10A", "10B"))
        assertEquals(KeyMatch.COMPATIBLE, m("8B", "8A"))
    }

    @Test
    fun notCompatible() {
        assertEquals(KeyMatch.NONE, m("10A", "11B"))
        assertEquals(KeyMatch.NONE, m("10A", "12A"))
        assertEquals(KeyMatch.NONE, m("10A", "4A"))
        assertEquals(KeyMatch.NONE, m("10A", "—"))
        assertEquals(KeyMatch.NONE, KeyMatch.of(null, CamelotKey(1, true)))
    }
}
