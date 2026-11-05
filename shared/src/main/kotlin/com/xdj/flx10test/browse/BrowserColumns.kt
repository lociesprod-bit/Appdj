package com.xdj.flx10test.browse

import java.text.Collator
import java.util.Locale

/** Dữ liệu một hàng trong BROWSE. Trên app thật map từ `Library.Track` + `RekordboxTrack`. */
data class BrowserRow(
    val id: Long,
    val title: String,
    val artist: String = "",
    val bpm: Double = 0.0,
    /** Chuỗi key gốc (Camelot hoặc tên nốt); dùng khi không đọc được Camelot. */
    val key: String = "",
    val camelot: CamelotKey? = CamelotKey.parse(key),
    val myTags: List<String> = emptyList(),
    val durationMs: Long = 0,
    /** 0–5 sao. */
    val rating: Int = 0,
    val genre: String = "",
    val comment: String = "",
    val hasStems: Boolean = false,
    /** Hàng thư mục ("📁 …  ›") ở chế độ duyệt thư mục. */
    val isFolder: Boolean = false,
) {
    companion object {
        /**
         * Tạo hàng từ dữ liệu thô của rekordbox: tự lấy My Tag từ Comment/Genre nếu [myTags] trống,
         * và bỏ khối `/* … */` khỏi Comment/Genre khi hiển thị.
         */
        fun fromRekordbox(
            id: Long,
            title: String,
            artist: String,
            bpm: Double,
            key: String,
            myTags: List<String>?,
            comment: String,
            genre: String,
            durationMs: Long,
            rating: Int,
            hasStems: Boolean,
            keyIndex: Int = -1,
        ): BrowserRow {
            val tags = MyTags.resolve(myTags, comment, genre)
            val camelot = CamelotKey.parse(key) ?: CamelotKey.fromKeyIndex(keyIndex)
            return BrowserRow(
                id = id,
                title = title,
                artist = artist,
                bpm = bpm,
                key = key,
                camelot = camelot,
                myTags = tags,
                durationMs = durationMs,
                rating = rating.coerceIn(0, 5),
                genre = MyTags.split(genre).rest,
                comment = MyTags.split(comment).rest,
                hasStems = hasStems,
            )
        }
    }
}

enum class Align { START, CENTER, END }

/**
 * Cột của BROWSE. [weight] > 0: cột giãn chia phần còn lại; [minDp]: độ rộng tối thiểu (cột cố định dùng đúng giá trị này).
 */
enum class ColumnId(
    val key: String,
    val label: String,
    val weight: Float,
    val minDp: Int,
    val align: Align,
    val defaultOn: Boolean,
) {
    TITLE("title", "Track Title", 3.2f, 150, Align.START, true),
    ARTIST("artist", "Artist", 1.7f, 90, Align.START, true),
    BPM("bpm", "BPM", 0f, 66, Align.END, true),
    KEY("key", "Key", 0f, 50, Align.CENTER, true),
    MYTAG("mytag", "MyTag", 1.6f, 90, Align.START, true),
    TIME("time", "Time", 0f, 56, Align.END, true),
    RATING("rating", "Rating", 0f, 76, Align.START, false),
    GENRE("genre", "Genre", 1.0f, 80, Align.START, false),
    COMMENT("comment", "Comment", 1.4f, 90, Align.START, false),
    STEMS("stems", "Stems", 0f, 62, Align.CENTER, true);

    companion object {
        val DEFAULT: List<ColumnId> = entries.filter { it.defaultOn }
        fun byKey(key: String): ColumnId? = entries.firstOrNull { it.key == key }
    }
}

/** Cột đang sắp xếp. */
data class SortState(val column: ColumnId? = null, val descending: Boolean = false) {
    /** Bấm tiêu đề: cột mới → tăng dần; bấm lại cùng cột → đảo chiều. */
    fun tap(column: ColumnId): SortState =
        if (this.column == column) copy(descending = !descending) else SortState(column, false)

    fun encode(): String = column?.let { it.key + if (descending) ":desc" else ":asc" } ?: ""

    companion object {
        fun decode(raw: String?): SortState {
            if (raw.isNullOrBlank()) return SortState()
            val col = ColumnId.byKey(raw.substringBefore(':')) ?: return SortState()
            return SortState(col, raw.substringAfter(':', "asc") == "desc")
        }
    }
}

object BrowserColumns {
    fun encode(columns: List<ColumnId>): String = columns.joinToString(",") { it.key }

    /** Đọc danh sách cột đã lưu; luôn giữ Track Title; lỗi/trống → mặc định. */
    fun decode(raw: String?): List<ColumnId> {
        if (raw.isNullOrBlank()) return ColumnId.DEFAULT
        val cols = raw.split(',').mapNotNull { ColumnId.byKey(it.trim()) }.distinct()
        if (cols.isEmpty()) return ColumnId.DEFAULT
        return if (ColumnId.TITLE in cols) cols else listOf(ColumnId.TITLE) + cols
    }

    fun formatBpm(bpm: Double): String {
        if (!(bpm > 0.0)) return "—"
        val cents = Math.rint(bpm * 100).toLong()
        return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "—"
        val s = (ms + 500) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun formatRating(rating: Int): String {
        val r = rating.coerceIn(0, 5)
        return "★".repeat(r) + "☆".repeat(5 - r)
    }

    fun text(column: ColumnId, row: BrowserRow): String = when (column) {
        ColumnId.TITLE -> row.title
        ColumnId.ARTIST -> row.artist
        ColumnId.BPM -> if (row.isFolder) "" else formatBpm(row.bpm)
        ColumnId.KEY -> if (row.isFolder) "" else row.camelot?.label ?: row.key.ifBlank { "—" }
        ColumnId.MYTAG -> row.myTags.joinToString(" · ")
        ColumnId.TIME -> if (row.isFolder) "" else formatTime(row.durationMs)
        ColumnId.RATING -> if (row.isFolder) "" else formatRating(row.rating)
        ColumnId.GENRE -> row.genre
        ColumnId.COMMENT -> row.comment
        ColumnId.STEMS -> if (row.hasStems) "STEMS" else ""
    }

    private val collator: Collator = Collator.getInstance(Locale("vi")).apply { strength = Collator.PRIMARY }

    /** Thư mục luôn đứng đầu; giá trị trống luôn đứng cuối (kể cả khi đảo chiều), như rekordbox. */
    fun comparator(column: ColumnId, descending: Boolean): Comparator<BrowserRow> {
        val base: Comparator<BrowserRow> = when (column) {
            ColumnId.BPM -> compareBy { it.bpm }
            ColumnId.TIME -> compareBy { it.durationMs }
            ColumnId.RATING -> compareBy { it.rating }
            ColumnId.STEMS -> compareBy { if (it.hasStems) 0 else 1 }
            ColumnId.KEY -> compareBy<BrowserRow> { it.camelot?.number ?: 99 }.thenBy { if (it.camelot?.minor == false) 1 else 0 }
            else -> Comparator { a, b -> collator.compare(text(column, a), text(column, b)) }
        }
        val directed = if (descending) base.reversed() else base
        val tie = Comparator<BrowserRow> { a, b -> collator.compare(a.title, b.title) }
        return Comparator { a, b ->
            when {
                a.isFolder != b.isFolder -> if (a.isFolder) -1 else 1
                isEmpty(column, a) != isEmpty(column, b) -> if (isEmpty(column, a)) 1 else -1
                else -> directed.compare(a, b).let { if (it != 0) it else tie.compare(a, b) }
            }
        }
    }

    private fun isEmpty(column: ColumnId, row: BrowserRow): Boolean = when (column) {
        ColumnId.BPM -> !(row.bpm > 0.0)
        ColumnId.TIME -> row.durationMs <= 0
        ColumnId.KEY -> row.camelot == null
        ColumnId.RATING, ColumnId.STEMS -> false
        else -> text(column, row).isBlank()
    }

    fun sort(rows: List<BrowserRow>, state: SortState): List<BrowserRow> {
        val col = state.column ?: return rows
        return rows.sortedWith(comparator(col, state.descending))
    }

    /**
     * Độ rộng (px) từng cột: cột cố định lấy minDp; cột giãn chia phần còn lại theo weight nhưng không nhỏ hơn minDp.
     * Tổng luôn bằng [totalPx] (phần lẻ dồn vào cột giãn cuối) trừ khi màn hình quá hẹp.
     */
    fun layout(columns: List<ColumnId>, totalPx: Int, density: Float): IntArray {
        val out = IntArray(columns.size)
        if (columns.isEmpty() || totalPx <= 0) return out
        var fixed = 0
        var weights = 0f
        columns.forEachIndexed { i, c ->
            if (c.weight <= 0f) {
                out[i] = (c.minDp * density).toInt()
                fixed += out[i]
            } else {
                weights += c.weight
            }
        }
        val flexible = (totalPx - fixed).coerceAtLeast(0)
        var used = fixed
        var lastFlex = -1
        columns.forEachIndexed { i, c ->
            if (c.weight > 0f) {
                out[i] = maxOf((flexible * c.weight / weights).toInt(), (c.minDp * density).toInt())
                used += out[i]
                lastFlex = i
            }
        }
        if (lastFlex >= 0 && used < totalPx) out[lastFlex] += totalPx - used
        return out
    }
}
