package com.xdj.flx10test.browse

import android.content.SharedPreferences

/** Lưu cột đang bật và cách sắp xếp của BROWSE. */
class BrowserPrefs(private val prefs: SharedPreferences) {
    var columns: List<ColumnId>
        get() = BrowserColumns.decode(prefs.getString(KEY_COLUMNS, null))
        set(value) = prefs.edit().putString(KEY_COLUMNS, BrowserColumns.encode(value)).apply()

    var sort: SortState
        get() = SortState.decode(prefs.getString(KEY_SORT, null))
        set(value) = prefs.edit().putString(KEY_SORT, value.encode()).apply()

    /** Bật/tắt một cột, giữ thứ tự chuẩn của [ColumnId]; Track Title luôn bật. */
    fun toggle(column: ColumnId, on: Boolean): List<ColumnId> {
        if (column == ColumnId.TITLE) return columns
        val set = columns.toMutableSet()
        if (on) set.add(column) else set.remove(column)
        val next = ColumnId.entries.filter { it in set }
        columns = next
        return next
    }

    companion object {
        const val KEY_COLUMNS = "browser.columns"
        const val KEY_SORT = "browser.sort"
    }
}
