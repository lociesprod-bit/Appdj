package com.xdj.flx10test.browse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/** Màu/kích thước dùng chung cho hàng và tiêu đề BROWSE (giống tông màu app 0.15.0). */
object BrowserStyle {
    val SELECTED_BG: Int = Color.rgb(80, 170, 255)
    val ROW_ALT_BG: Int = Color.argb(18, 255, 255, 255)
    val TEXT: Int = Color.WHITE
    val TEXT_DIM: Int = Color.rgb(170, 176, 186)
    val TEXT_SELECTED: Int = Color.BLACK

    /** Xanh như rekordbox: cùng key đậm hơn, hợp key nhạt hơn một chút. */
    val KEY_SAME: Int = Color.rgb(64, 255, 122)
    val KEY_COMPATIBLE: Int = Color.rgb(46, 204, 96)
    val KEY_SAME_ON_SELECTED: Int = Color.rgb(0, 80, 20)
    val KEY_COMPATIBLE_ON_SELECTED: Int = Color.rgb(0, 100, 30)

    val STEMS_BADGE: Int = Color.rgb(120, 90, 220)
    val HEADER_BG: Int = Color.rgb(28, 30, 36)
    val HEADER_TEXT: Int = Color.rgb(200, 205, 214)
    val DIVIDER: Int = Color.rgb(48, 52, 60)

    const val ROW_HEIGHT_DP = 40f
    const val HEADER_HEIGHT_DP = 30f
    const val TEXT_SP = 15f
    const val HEADER_TEXT_SP = 12.5f
    const val CELL_PAD_DP = 6f
    const val SIDE_PAD_DP = 10f

    fun keyColor(match: KeyMatch, selected: Boolean, fallback: Int): Int = when (match) {
        KeyMatch.SAME -> if (selected) KEY_SAME_ON_SELECTED else KEY_SAME
        KeyMatch.COMPATIBLE -> if (selected) KEY_COMPATIBLE_ON_SELECTED else KEY_COMPATIBLE
        KeyMatch.NONE -> fallback
    }
}

private fun View.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Cắt [text] cho vừa [avail] px, thêm "…" khi bị cắt (không phụ thuộc TextUtils.ellipsize). */
internal fun fitText(text: String, paint: Paint, avail: Float): String {
    if (avail <= 0f || text.isEmpty()) return ""
    if (paint.measureText(text) <= avail) return text
    val ellipsis = "…"
    val room = avail - paint.measureText(ellipsis)
    if (room <= 0f) return ""
    val n = paint.breakText(text, true, room, null)
    return text.substring(0, n).trimEnd() + ellipsis
}

/**
 * Một hàng BROWSE chia cột, tự vẽ (không lồng nhiều TextView) để cuộn mượt với hàng nghìn bài.
 * Dùng trong `BaseAdapter.getView`: tái sử dụng convertView rồi gọi [bind].
 */
class TrackRowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(BrowserStyle.TEXT_SP) }
    private val boldPaint = TextPaint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val rect = RectF()

    private var row: BrowserRow? = null
    private var columns: List<ColumnId> = ColumnId.DEFAULT
    private var widths = IntArray(0)

    /** Chữ đã định dạng từng cột của lần bind gần nhất (dùng cho test và contentDescription). */
    var texts: List<String> = emptyList()
        private set

    var keyMatch: KeyMatch = KeyMatch.NONE
        private set

    var rowSelected: Boolean = false
        private set

    private var alternate = false

    /** Màu chữ cột Key của lần bind gần nhất. */
    val keyTextColor: Int
        get() = BrowserStyle.keyColor(keyMatch, rowSelected, if (rowSelected) BrowserStyle.TEXT_SELECTED else BrowserStyle.TEXT)

    fun bind(
        row: BrowserRow,
        columns: List<ColumnId>,
        match: KeyMatch,
        selected: Boolean,
        alternate: Boolean = false,
    ) {
        val columnsChanged = columns != this.columns
        this.row = row
        this.columns = columns
        this.keyMatch = match
        this.rowSelected = selected
        this.alternate = alternate
        texts = if (row.isFolder) listOf("📁 ${row.title}  ›") else columns.map { BrowserColumns.text(it, row) }
        contentDescription = texts.filter { it.isNotBlank() }.joinToString(", ")
        if (columnsChanged) relayout(width)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (BrowserStyle.ROW_HEIGHT_DP * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout(w)
    }

    private fun relayout(w: Int) {
        val side = (BrowserStyle.SIDE_PAD_DP * density).toInt()
        widths = BrowserColumns.layout(columns, w - 2 * side, density)
    }

    override fun onDraw(canvas: Canvas) {
        val r = row ?: return
        // drawRect (không dùng drawColor) để nền chỉ phủ đúng hàng, kể cả khi canvas không bị clip.
        when {
            rowSelected -> bgPaint.color = BrowserStyle.SELECTED_BG
            alternate -> bgPaint.color = BrowserStyle.ROW_ALT_BG
            else -> bgPaint.color = Color.TRANSPARENT
        }
        if (bgPaint.color != Color.TRANSPARENT) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val base = if (rowSelected) BrowserStyle.TEXT_SELECTED else BrowserStyle.TEXT
        val dim = if (rowSelected) BrowserStyle.TEXT_SELECTED else BrowserStyle.TEXT_DIM
        val side = BrowserStyle.SIDE_PAD_DP * density
        val pad = BrowserStyle.CELL_PAD_DP * density
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        if (r.isFolder) {
            textPaint.color = base
            val t = fitText(texts.firstOrNull() ?: "", textPaint, width - 2 * side)
            canvas.drawText(t, side, baseline, textPaint)
            return
        }
        if (widths.size != columns.size) relayout(width)

        var x = side
        columns.forEachIndexed { i, col ->
            val w = widths.getOrElse(i) { 0 }.toFloat()
            val text = texts.getOrElse(i) { "" }
            val inner = (w - 2 * pad).coerceAtLeast(0f)
            if (col == ColumnId.STEMS) {
                if (r.hasStems) drawBadge(canvas, x + w / 2f, height / 2f)
            } else if (text.isNotEmpty() && inner > 0f) {
                val paint = if (col == ColumnId.KEY && keyMatch == KeyMatch.SAME) boldPaint else textPaint
                paint.color = when (col) {
                    ColumnId.KEY -> keyTextColor
                    ColumnId.TITLE -> base
                    ColumnId.RATING -> if (rowSelected) base else Color.rgb(255, 196, 0)
                    else -> dim
                }
                val t = fitText(text, paint, inner)
                val tw = paint.measureText(t)
                val tx = when (col.align) {
                    Align.START -> x + pad
                    Align.CENTER -> x + (w - tw) / 2f
                    Align.END -> x + w - pad - tw
                }
                canvas.save()
                canvas.clipRect(x, 0f, x + w, height.toFloat())
                canvas.drawText(t, tx, baseline, paint)
                canvas.restore()
            }
            x += w
        }
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float) {
        val label = "STEMS"
        val tw = badgeText.measureText(label)
        val hw = tw / 2f + 6f * density
        val hh = 9f * density
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        badgePaint.color = if (rowSelected) Color.rgb(40, 20, 120) else BrowserStyle.STEMS_BADGE
        canvas.drawRoundRect(rect, hh, hh, badgePaint)
        val by = cy - (badgeText.descent() + badgeText.ascent()) / 2f
        canvas.drawText(label, cx - tw / 2f, by, badgeText)
    }
}

/**
 * Hàng tiêu đề cột cố định phía trên danh sách. Bấm một cột → [onSort] với [SortState] mới
 * (cột mới tăng dần, bấm lại đảo chiều). Mũi tên ▲/▼ chỉ cột đang sắp xếp.
 */
class BrowserHeaderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(BrowserStyle.HEADER_TEXT_SP)
        typeface = Typeface.DEFAULT_BOLD
        color = BrowserStyle.HEADER_TEXT
    }
    private val line = Paint().apply { color = BrowserStyle.DIVIDER }

    var columns: List<ColumnId> = ColumnId.DEFAULT
        set(value) {
            field = value
            relayout(width)
            invalidate()
        }

    var sort: SortState = SortState()
        set(value) {
            field = value
            invalidate()
        }

    var onSort: ((SortState) -> Unit)? = null

    private var widths = IntArray(0)

    init {
        isClickable = true
        setBackgroundColor(BrowserStyle.HEADER_BG)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (BrowserStyle.HEADER_HEIGHT_DP * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout(w)

    private fun relayout(w: Int) {
        val side = (BrowserStyle.SIDE_PAD_DP * density).toInt()
        widths = BrowserColumns.layout(columns, w - 2 * side, density)
    }

    /** Cột tại toạ độ x (px), hoặc null. */
    fun columnAt(x: Float): ColumnId? {
        if (widths.size != columns.size) relayout(width)
        var left = BrowserStyle.SIDE_PAD_DP * density
        columns.forEachIndexed { i, c ->
            val right = left + widths[i]
            if (x >= left && x < right) return c
            left = right
        }
        return null
    }

    /** Mô phỏng bấm vào cột (dùng cho test, phím tắt hoặc MIDI). */
    fun tap(column: ColumnId) {
        sort = sort.tap(column)
        onSort?.invoke(sort)
    }

    private var downX = -1f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> downX = event.x
            android.view.MotionEvent.ACTION_UP -> {
                val col = columnAt(event.x)
                if (col != null && col == columnAt(downX)) {
                    tap(col)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        if (widths.size != columns.size) relayout(width)
        val pad = BrowserStyle.CELL_PAD_DP * density
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        var x = BrowserStyle.SIDE_PAD_DP * density
        columns.forEachIndexed { i, col ->
            val w = widths[i].toFloat()
            val arrow = if (sort.column == col) (if (sort.descending) " ▼" else " ▲") else ""
            val label = fitText(col.label + arrow, paint, (w - 2 * pad).coerceAtLeast(0f))
            val tw = paint.measureText(label)
            val tx = when (col.align) {
                Align.START -> x + pad
                Align.CENTER -> x + (w - tw) / 2f
                Align.END -> x + w - pad - tw
            }
            canvas.drawText(label, tx, baseline, paint)
            x += w
            if (i < columns.lastIndex) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
        }
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, line)
    }
}
