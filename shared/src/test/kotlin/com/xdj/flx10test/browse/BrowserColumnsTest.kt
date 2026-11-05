package com.xdj.flx10test.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyTagsTest {
    @Test
    fun splitsRekordboxCommentBlock() {
        val s = MyTags.split(" /* Club / House Lak / Bass Music / Remix / Grooving Up / Viet Nam */")
        assertEquals(listOf("Club", "House Lak", "Bass Music", "Remix", "Grooving Up", "Viet Nam"), s.tags)
        assertEquals("", s.rest)
    }

    @Test
    fun keepsTextAroundBlock() {
        val s = MyTags.split("Bản đẹp /* Vina House / Nhạc Việt */ mix lúc 2h")
        assertEquals(listOf("Vina House", "Nhạc Việt"), s.tags)
        assertEquals("Bản đẹp mix lúc 2h", s.rest)
    }

    @Test
    fun noBlock() {
        val s = MyTags.split("Just a comment / with slash")
        assertTrue(s.tags.isEmpty())
        assertEquals("Just a comment / with slash", s.rest)
        assertTrue(MyTags.split(null).tags.isEmpty())
        assertTrue(MyTags.split("   ").tags.isEmpty())
    }

    @Test
    fun singleTagAndDuplicates() {
        assertEquals(listOf("Techno"), MyTags.split("/* Techno */").tags)
        assertEquals(listOf("A", "B"), MyTags.split("/* A / B */ /* B / A */").tags)
    }

    @Test
    fun resolvePrefersExistingThenCommentThenGenre() {
        assertEquals(listOf("X"), MyTags.resolve(listOf("X"), "/* Y */", "/* Z */"))
        assertEquals(listOf("Y"), MyTags.resolve(emptyList(), "/* Y */", "/* Z */"))
        assertEquals(listOf("Z"), MyTags.resolve(null, "plain", "/* Z */"))
        assertTrue(MyTags.resolve(null, "plain", "House").isEmpty())
    }
}

class BrowserColumnsTest {
    private val rowA = BrowserRow.fromRekordbox(
        id = 1, title = "11A - Ai Chung Tinh Duoc Mai HD", artist = "", bpm = 130.0, key = "11A",
        myTags = null, comment = " /* Club / House Lak / Bass Music / Remix / Grooving Up / Viet Nam */",
        genre = " /* Club / House Lak */", durationMs = 470_000, rating = 0, hasStems = true,
    )

    @Test
    fun rowFromRekordboxExtractsMyTagAndCleansComment() {
        assertEquals(listOf("Club", "House Lak", "Bass Music", "Remix", "Grooving Up", "Viet Nam"), rowA.myTags)
        assertEquals("", rowA.comment)
        assertEquals("", rowA.genre)
        assertEquals(CamelotKey(11, true), rowA.camelot)
    }

    @Test
    fun keyFallsBackToKeyIndex() {
        val r = BrowserRow.fromRekordbox(2, "t", "", 0.0, "", null, "", "", 0, 0, false, keyIndex = 21)
        assertEquals(CamelotKey(8, true), r.camelot)
    }

    @Test
    fun formatsCells() {
        assertEquals("130.00", BrowserColumns.text(ColumnId.BPM, rowA))
        assertEquals("11A", BrowserColumns.text(ColumnId.KEY, rowA))
        assertEquals("7:50", BrowserColumns.text(ColumnId.TIME, rowA))
        assertEquals("STEMS", BrowserColumns.text(ColumnId.STEMS, rowA))
        assertEquals("Club · House Lak · Bass Music · Remix · Grooving Up · Viet Nam", BrowserColumns.text(ColumnId.MYTAG, rowA))
        assertEquals("★★★☆☆", BrowserColumns.formatRating(3))
        assertEquals("—", BrowserColumns.formatBpm(0.0))
        assertEquals("127.50", BrowserColumns.formatBpm(127.5))
        assertEquals("—", BrowserColumns.formatTime(0))
        assertEquals("0:59", BrowserColumns.formatTime(59_400))
    }

    @Test
    fun encodeDecodeColumns() {
        assertEquals(ColumnId.DEFAULT, BrowserColumns.decode(null))
        assertEquals(ColumnId.DEFAULT, BrowserColumns.decode("nope,also-nope"))
        val cols = listOf(ColumnId.TITLE, ColumnId.BPM, ColumnId.KEY)
        assertEquals(cols, BrowserColumns.decode(BrowserColumns.encode(cols)))
        // Track Title luôn có.
        assertEquals(listOf(ColumnId.TITLE, ColumnId.BPM), BrowserColumns.decode("bpm"))
    }

    @Test
    fun sortStateTapCycles() {
        val s1 = SortState().tap(ColumnId.BPM)
        assertEquals(SortState(ColumnId.BPM, false), s1)
        assertEquals(SortState(ColumnId.BPM, true), s1.tap(ColumnId.BPM))
        assertEquals(SortState(ColumnId.KEY, false), s1.tap(ColumnId.BPM).tap(ColumnId.KEY))
        assertEquals(SortState(ColumnId.KEY, true), SortState.decode(SortState(ColumnId.KEY, true).encode()))
        assertEquals(SortState(), SortState.decode("garbage"))
    }

    private fun row(id: Long, title: String, bpm: Double = 0.0, key: String = "", folder: Boolean = false, artist: String = "") =
        BrowserRow(id = id, title = title, bpm = bpm, key = key, isFolder = folder, artist = artist)

    @Test
    fun sortsByBpmWithEmptyLastAndFoldersFirst() {
        val rows = listOf(row(1, "c", 128.0), row(2, "a", 0.0), row(3, "b", 124.0), row(4, "Folder", folder = true), row(5, "d", 130.0))
        val asc = BrowserColumns.sort(rows, SortState(ColumnId.BPM, false)).map { it.id }
        assertEquals(listOf(4L, 3L, 1L, 5L, 2L), asc)
        val desc = BrowserColumns.sort(rows, SortState(ColumnId.BPM, true)).map { it.id }
        assertEquals(listOf(4L, 5L, 1L, 3L, 2L), desc)
    }

    @Test
    fun sortsByKeyAlongWheel() {
        val rows = listOf(row(1, "x", key = "10A"), row(2, "y", key = "2B"), row(3, "z", key = "2A"), row(4, "w", key = ""))
        assertEquals(listOf(3L, 2L, 1L, 4L), BrowserColumns.sort(rows, SortState(ColumnId.KEY, false)).map { it.id })
    }

    @Test
    fun sortsTitlesIgnoringCase() {
        val rows = listOf(row(1, "beta"), row(2, "Alpha"), row(3, "Charlie"), row(4, "alpha 2"))
        val titles = BrowserColumns.sort(rows, SortState(ColumnId.TITLE, false)).map { it.title }
        assertEquals(listOf("Alpha", "alpha 2", "beta", "Charlie"), titles)
        val desc = BrowserColumns.sort(rows, SortState(ColumnId.TITLE, true)).map { it.title }
        assertEquals(listOf("Charlie", "beta", "alpha 2", "Alpha"), desc)
    }

    @Test
    fun layoutFillsWidth() {
        val cols = ColumnId.DEFAULT
        val widths = BrowserColumns.layout(cols, 1800, 2f)
        assertEquals(1800, widths.sum())
        assertEquals((66 * 2f).toInt(), widths[cols.indexOf(ColumnId.BPM)])
        assertTrue(widths[cols.indexOf(ColumnId.TITLE)] > widths[cols.indexOf(ColumnId.ARTIST)])
        assertEquals(0, BrowserColumns.layout(emptyList(), 100, 1f).size)
    }

    @Test
    fun layoutNeverBelowMinimum() {
        val widths = BrowserColumns.layout(ColumnId.entries.toList(), 600, 2f)
        ColumnId.entries.forEachIndexed { i, c -> assertTrue("$c", widths[i] >= (c.minDp * 2f).toInt()) }
    }
}
