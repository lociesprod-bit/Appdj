package com.xdj.flx10test.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.xdj.flx10test.browse.BrowserHeaderView
import com.xdj.flx10test.browse.BrowserPrefs
import com.xdj.flx10test.browse.BrowserRow
import com.xdj.flx10test.browse.BrowserStyle
import com.xdj.flx10test.browse.CamelotKey
import com.xdj.flx10test.browse.ColumnId
import com.xdj.flx10test.browse.KeyMatch
import com.xdj.flx10test.browse.SortState
import com.xdj.flx10test.browse.TrackRowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewsTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private val row = BrowserRow.fromRekordbox(
        id = 7, title = "10A - Ben Tren Tang Lau x Iconic HD - (Deep House)", artist = "Iconic", bpm = 127.0, key = "10A",
        myTags = null, comment = "/* Deep House / Viet Nam */", genre = "", durationMs = 257_541, rating = 4, hasStems = true,
    )

    private fun layout(v: View, w: Int = 1800) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun draw(v: View) {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
    }

    @Test
    fun rowShowsEnabledColumnsInOrder() {
        val v = TrackRowView(activity)
        v.bind(row, ColumnId.DEFAULT, KeyMatch.NONE, selected = false)
        layout(v)
        draw(v)
        assertEquals(
            listOf("10A - Ben Tren Tang Lau x Iconic HD - (Deep House)", "127.00", "10A", "Deep House · Viet Nam", "4:18", "★★★★☆", "STEMS"),
            v.texts,
        )
        v.bind(row, listOf(ColumnId.TITLE, ColumnId.KEY, ColumnId.RATING), KeyMatch.NONE, false)
        draw(v)
        assertEquals(listOf(row.title, "10A", "★★★★☆"), v.texts)
        assertTrue(v.contentDescription.contains("10A"))
    }

    @Test
    fun keyCellTurnsGreenForMasterMatch() {
        val v = TrackRowView(activity)
        val master = CamelotKey.parse("10A")
        v.bind(row, ColumnId.DEFAULT, KeyMatch.of(master, row.camelot), false)
        assertEquals(KeyMatch.SAME, v.keyMatch)
        assertEquals(BrowserStyle.KEY_SAME, v.keyTextColor)
        v.bind(row, ColumnId.DEFAULT, KeyMatch.of(CamelotKey.parse("11A"), row.camelot), false)
        assertEquals(BrowserStyle.KEY_COMPATIBLE, v.keyTextColor)
        v.bind(row, ColumnId.DEFAULT, KeyMatch.of(CamelotKey.parse("3B"), row.camelot), false)
        assertEquals(BrowserStyle.TEXT, v.keyTextColor)
        // Hàng đang chọn (nền xanh dương): xanh lá đậm để còn đọc được.
        v.bind(row, ColumnId.DEFAULT, KeyMatch.COMPATIBLE, true)
        assertEquals(BrowserStyle.KEY_COMPATIBLE_ON_SELECTED, v.keyTextColor)
        layout(v)
        draw(v)
    }

    @Test
    fun folderRowDrawsSingleLabel() {
        val v = TrackRowView(activity)
        v.bind(BrowserRow(id = 1, title = "Afro House", isFolder = true), ColumnId.DEFAULT, KeyMatch.NONE, false)
        layout(v)
        draw(v)
        assertEquals(listOf("📁 Afro House  ›"), v.texts)
    }

    @Test
    fun headerTapCyclesSortAndReportsColumnAtX() {
        val h = BrowserHeaderView(activity)
        h.columns = ColumnId.DEFAULT
        layout(h)
        draw(h)
        val sorts = ArrayList<SortState>()
        h.onSort = { sorts.add(it) }
        h.tap(ColumnId.BPM)
        h.tap(ColumnId.BPM)
        h.tap(ColumnId.KEY)
        assertEquals(listOf(SortState(ColumnId.BPM, false), SortState(ColumnId.BPM, true), SortState(ColumnId.KEY, false)), sorts)
        draw(h)
        val density = activity.resources.displayMetrics.density
        assertEquals(ColumnId.TITLE, h.columnAt(BrowserStyle.SIDE_PAD_DP * density + 5f))
        assertEquals(ColumnId.STEMS, h.columnAt(h.width - BrowserStyle.SIDE_PAD_DP * density - 5f))
        assertEquals(null, h.columnAt(1f))
    }

    @Test
    fun prefsPersistColumnsAndSort() {
        val prefs = BrowserPrefs(activity.getSharedPreferences("t", Context.MODE_PRIVATE))
        assertEquals(ColumnId.DEFAULT, prefs.columns)
        val cols = prefs.toggle(ColumnId.ARTIST, true)
        assertTrue(ColumnId.ARTIST in cols)
        assertTrue(ColumnId.ARTIST !in prefs.toggle(ColumnId.ARTIST, false))
        prefs.toggle(ColumnId.ARTIST, true)
        assertEquals(cols, prefs.columns)
        assertEquals(ColumnId.entries.filter { it in cols }, cols) // giữ thứ tự chuẩn
        prefs.toggle(ColumnId.TITLE, false)
        assertTrue(ColumnId.TITLE in prefs.columns)
        prefs.sort = SortState(ColumnId.BPM, true)
        assertEquals(SortState(ColumnId.BPM, true), prefs.sort)
    }

    @Test
    fun settingsSectionCallsHandlersAndShowsState() {
        var mixerVisible = true
        var fourDecks = false
        var performClicks = 0
        val section = SettingsToggleSection(activity)
        section.setItems(
            "Chế độ & bố cục",
            listOf(
                SettingsToggleSection.Item(label = { "PERFORM" }, onClick = { performClicks++ }),
                SettingsToggleSection.Item(label = { "2 / 4 DECKS" }, isOn = { fourDecks }, onClick = { fourDecks = !fourDecks }),
                SettingsToggleSection.Item(label = { if (mixerVisible) "ẨN MIXER" else "HIỆN MIXER" }, isOn = { !mixerVisible }, onClick = { mixerVisible = !mixerVisible }),
            ),
        )
        assertEquals(3, section.size)
        assertEquals("ẨN MIXER", section.button(2).text.toString())
        assertFalse(section.button(1).isSelected)
        section.button(1).performClick()
        assertTrue(fourDecks)
        assertTrue(section.button(1).isSelected)
        section.button(2).performClick()
        assertEquals("HIỆN MIXER", section.button(2).text.toString())
        assertTrue(section.button(2).isSelected)
        section.button(0).performClick()
        assertEquals(1, performClicks)
        assertNotNull(section.button(0).contentDescription)
        // Trạng thái đổi từ MIDI → refresh() vẽ lại.
        fourDecks = false
        section.refresh()
        assertFalse(section.button(1).isSelected)
        section.columns = 4
        assertEquals(3, section.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class FitTextTest {
    @Test
    fun cutsWithEllipsisToFit() {
        val p = android.graphics.Paint().apply { textSize = 30f }
        val long = "8A - 128 - BOOM BOOM POW - THE BLACK EYED PEAS (MINK BOOTLEG)"
        val avail = p.measureText(long) / 2f
        val fit = com.xdj.flx10test.browse.fitText(long, p, avail)
        assertTrue(fit.endsWith("…"))
        assertTrue(p.measureText(fit) <= avail)
        assertEquals("short", com.xdj.flx10test.browse.fitText("short", p, 1000f))
        assertEquals("", com.xdj.flx10test.browse.fitText("abc", p, 0f))
    }
}
