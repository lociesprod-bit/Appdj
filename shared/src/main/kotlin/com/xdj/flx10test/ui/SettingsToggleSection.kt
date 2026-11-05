package com.xdj.flx10test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mục "Chế độ & bố cục" trong ⚙ Cài đặt, thay cho hàng nút thứ 2 trên màn hình chính:
 * PERFORM · EXPORT / PREPARE · SONG SONG NGANG · 2 / 4 DECKS · ĐIỀU KHIỂN 1/2 ↔ 3/4 · ẨN MIXER · ẨN ĐIỀU KHIỂN · CDJ / XDJ.
 *
 * Mỗi nút gọi đúng handler cũ và hiện trạng thái như trên hàng nút hiện nay.
 * Nhãn có thể đổi theo trạng thái ("ẨN MIXER"/"HIỆN MIXER"); sau mỗi lần bấm, cả mục được vẽ lại.
 */
class SettingsToggleSection(context: Context) : LinearLayout(context) {
    /**
     * @param label nhãn hiện tại (gọi lại sau mỗi lần bấm).
     * @param isOn null = nút hành động (không có trạng thái bật/tắt); khác null = nút bật/tắt.
     */
    class Item(
        val label: () -> String,
        val isOn: (() -> Boolean)? = null,
        val onClick: () -> Unit,
    )

    private val density = resources.displayMetrics.density
    private var items: List<Item> = emptyList()
    private val buttons = ArrayList<TextView>()
    private val title = TextView(context)

    /** Số nút mỗi hàng. */
    var columns: Int = 2
        set(value) {
            field = value.coerceIn(1, 4)
            rebuild()
        }

    init {
        orientation = VERTICAL
        title.setTextColor(Color.rgb(200, 205, 214))
        title.textSize = 13f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(4), dp(10), dp(4), dp(6))
        addView(title)
    }

    fun setItems(heading: String, items: List<Item>) {
        title.text = heading
        this.items = items
        rebuild()
    }

    /** Cập nhật nhãn/màu theo trạng thái hiện tại (gọi khi trạng thái đổi từ nơi khác, ví dụ MIDI). */
    fun refresh() {
        items.forEachIndexed { i, item -> buttons.getOrNull(i)?.let { style(it, item) } }
    }

    /** Nút thứ [index] (dùng cho test). */
    fun button(index: Int): TextView = buttons[index]

    val size: Int get() = buttons.size

    private fun rebuild() {
        while (childCount > 1) removeViewAt(1)
        buttons.clear()
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % columns == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val b = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    item.onClick()
                    refresh()
                }
            }
            style(b, item)
            buttons.add(b)
            row!!.addView(b, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        // Lấp ô trống ở hàng cuối để các nút giữ cùng độ rộng.
        val remainder = items.size % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                row?.addView(TextView(context), LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
        }
    }

    private fun style(b: TextView, item: Item) {
        val on = item.isOn?.invoke()
        b.text = item.label()
        b.isSelected = on == true
        b.contentDescription = b.text.toString() + when (on) {
            true -> " (đang bật)"
            false -> " (đang tắt)"
            null -> ""
        }
        val bg = when (on) {
            true -> ON
            false -> OFF
            null -> ACTION
        }
        b.setTextColor(if (on == true) Color.BLACK else Color.WHITE)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(bg)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        /** Cam như nút MASTER/MT đang bật trên màn hình chính. */
        val ON: Int = Color.rgb(224, 138, 30)
        val OFF: Int = Color.rgb(42, 45, 51)
        val ACTION: Int = Color.rgb(52, 58, 70)
    }
}
