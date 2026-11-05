package com.xdj.flx10test.wave

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kiểu vạch grid đậm hơn 0.15.0.
 *
 * 0.15.0 (`ScrollWaveView`) vẽ vạch cả chiều cao với alpha 55/255 (beat) và 95/255 (ô nhịp), nét 2–3 px.
 * Trên tablet (mật độ ~2), nét đó chỉ ~1 dp và gần như không thấy trên sóng sáng.
 * Ở đây vạch có viền tối để nổi trên mọi màu sóng.
 */
object GridStyle {
    const val BEAT_DP = 1.6f
    const val DOWNBEAT_DP = 2.8f
    const val SHADOW_EXTRA_DP = 2.0f
    const val TRIANGLE_DP = 8f
    val BEAT: Int = Color.argb(225, 255, 255, 255)
    val DOWNBEAT: Int = Color.argb(255, 255, 64, 64)
    val SHADOW: Int = Color.argb(150, 0, 0, 0)
    val TRIANGLE: Int = Color.rgb(255, 204, 0)
    val LABEL: Int = Color.rgb(255, 214, 64)
    /** Vạch phát: trắng, mũi tên đỏ (vạch ô nhịp đã là đỏ). */
    val PLAYHEAD: Int = Color.rgb(250, 250, 250)
    val PLAYHEAD_CAP: Int = Color.rgb(255, 40, 40)
    val COMPARE: Int = Color.argb(230, 0, 230, 230)
    val BACKGROUND: Int = Color.rgb(6, 6, 8)
}

/**
 * Sóng chi tiết + grid cho màn chỉnh grid (EXPORT / PREPARE).
 * - Kéo ngang để tua.
 * - Chụm/mở hai ngón để zoom ([MIN_SECONDS]–[MAX_SECONDS] giây trên toàn chiều rộng).
 * - Vạch giữa màn hình là vị trí phát.
 */
class DetailWaveView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    var wave: DetailWave? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Grid đã bù lệch (nếu có). */
    var grid: BeatGrid? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Grid để so sánh (ví dụ grid cũ 0.15.0), vẽ mảnh màu xanh ngọc phía dưới grid chính. */
    var compareGrid: BeatGrid? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Vị trí phát (giây) ở giữa màn hình. */
    var positionSec: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, wave?.durationSec ?: Double.MAX_VALUE)
            invalidate()
        }

    /** Số giây hiển thị trên toàn chiều rộng (zoom). */
    var secondsVisible: Double = 2.0
        set(value) {
            field = value.coerceIn(MIN_SECONDS, MAX_SECONDS)
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Gọi khi người dùng kéo/zoom. */
    var onViewChanged: ((positionSec: Double, secondsVisible: Double) -> Unit)? = null

    private val wavePaint = Paint().apply { strokeWidth = 1f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GridStyle.TRIANGLE }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GridStyle.LABEL
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(12f)
    }
    private val beatTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = sp(10f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(15f)
    }
    private val path = Path()

    /** Số vạch grid vẽ ở lần vẽ gần nhất (cho test). */
    var drawnGridLines: Int = 0
        private set

    /** Số cột sóng vẽ ở lần vẽ gần nhất (cho test). */
    var drawnColumns: Int = 0
        private set

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            secondsVisible /= detector.scaleFactor.toDouble()
            onViewChanged?.invoke(positionSec, secondsVisible)
            return true
        }
    })
    private var lastX = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(260f).toInt() else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastX = event.x
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                val dx = event.x - lastX
                lastX = event.x
                positionSec -= dx * secondsVisible / width.coerceAtLeast(1)
                onViewChanged?.invoke(positionSec, secondsVisible)
            }
            MotionEvent.ACTION_POINTER_UP -> lastX = event.getX(if (event.actionIndex == 0) 1 else 0)
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Thời điểm (giây) tại toạ độ x. */
    fun timeAt(x: Float): Double = positionSec + (x - width / 2.0) * secondsVisible / width.coerceAtLeast(1)

    /** Toạ độ x của thời điểm t. */
    fun xOf(t: Double): Float = (width / 2.0 + (t - positionSec) * width / secondsVisible).toFloat()

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawColor(GridStyle.BACKGROUND)
        drawWave(canvas, w, h)
        compareGrid?.let { g ->
            linePaint.color = GridStyle.COMPARE
            linePaint.strokeWidth = dp(1.4f)
            for (line in g.linesIn(timeAt(0f), timeAt(w.toFloat()), limit = 2000)) {
                val x = xOf(line.time)
                canvas.drawLine(x, h * 0.18f, x, h * 0.82f, linePaint)
            }
        }
        drawGrid(canvas, w, h)
        // Vạch phát trắng có viền tối và mũi tên đỏ, để không lẫn với vạch ô nhịp đỏ khi căn grid.
        linePaint.color = GridStyle.SHADOW
        linePaint.strokeWidth = dp(4f)
        canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), linePaint)
        linePaint.color = GridStyle.PLAYHEAD
        linePaint.strokeWidth = dp(2f)
        canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), linePaint)
        val cap = dp(9f)
        path.reset()
        path.moveTo(w / 2f - cap, 0f); path.lineTo(w / 2f + cap, 0f); path.lineTo(w / 2f, cap * 1.2f); path.close()
        path.moveTo(w / 2f - cap, h.toFloat()); path.lineTo(w / 2f + cap, h.toFloat()); path.lineTo(w / 2f, h - cap * 1.2f); path.close()
        capPaint.color = GridStyle.PLAYHEAD_CAP
        canvas.drawPath(path, capPaint)
        if (label.isNotEmpty()) canvas.drawText(label, dp(10f), dp(22f), labelPaint)
    }

    private fun drawWave(canvas: Canvas, w: Int, h: Int) {
        val wv = wave ?: run {
            drawnColumns = 0
            return
        }
        val mid = h / 2f
        val scale = (h / 2f * 0.92f) / max(24, wv.peak)
        val blocksPerSec = wv.sampleRate.toDouble() / wv.framesPerBlock
        val bpp = secondsVisible / w * blocksPerSec // khối mỗi pixel
        var columns = 0
        for (x in 0 until w) {
            val p0 = timeAt(x.toFloat()) * blocksPerSec
            val p1 = timeAt((x + 1).toFloat()) * blocksPerSec
            if (p1 < 0 || p0 >= wv.size) continue
            var lo: Int
            var hi: Int
            var l: Int
            var m: Int
            var t: Int
            if (bpp >= 1.0) {
                val i0 = max(0, floor(p0).toInt())
                val i1 = min(wv.size, max(i0 + 1, floor(p1).toInt()))
                lo = 127; hi = -127; l = 0; m = 0; t = 0
                for (i in i0 until i1) {
                    lo = min(lo, wv.minAt(i)); hi = max(hi, wv.maxAt(i))
                    l = max(l, wv.lowAt(i)); m = max(m, wv.midAt(i)); t = max(t, wv.highAt(i))
                }
            } else {
                // Zoom sâu: nội suy giữa tâm các khối ở hai mép cột để nét liền, không bậc thang.
                val a = sampleAt(wv, p0 - 0.5)
                val b = sampleAt(wv, p1 - 0.5)
                lo = min(a[0], b[0]); hi = max(a[1], b[1])
                l = max(a[2], b[2]); m = max(a[3], b[3]); t = max(a[4], b[4])
            }
            if (hi < lo) continue
            var yTop = mid - hi * scale
            var yBot = mid - lo * scale
            // Nét tối thiểu 2,5 dp để đường sóng mảnh (zoom sâu) vẫn rõ như rekordbox.
            val minThick = dp(2.5f)
            if (yBot - yTop < minThick) {
                val c = (yTop + yBot) / 2
                yTop = c - minThick / 2
                yBot = c + minThick / 2
            }
            // Gần im lặng: tô xám tối thay vì màu thuần (tránh vạch đỏ giữa đoạn lặng).
            wavePaint.color = if (max(l, max(m, t)) < 12) SILENT else DetailWave.color(l, m, t)
            canvas.drawRect(x.toFloat(), yTop, x + 1f, yBot, wavePaint)
            columns++
        }
        drawnColumns = columns
    }

    private val tmp = IntArray(5)

    private fun sampleAt(wv: DetailWave, p: Double): IntArray {
        val i = floor(p).toInt().coerceIn(0, wv.size - 1)
        val j = (i + 1).coerceAtMost(wv.size - 1)
        val f = (p - floor(p)).coerceIn(0.0, 1.0)
        fun lerp(a: Int, b: Int) = (a + (b - a) * f).toInt()
        tmp[0] = lerp(wv.minAt(i), wv.minAt(j))
        tmp[1] = lerp(wv.maxAt(i), wv.maxAt(j))
        tmp[2] = lerp(wv.lowAt(i), wv.lowAt(j))
        tmp[3] = lerp(wv.midAt(i), wv.midAt(j))
        tmp[4] = lerp(wv.highAt(i), wv.highAt(j))
        return tmp.copyOf()
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int) {
        val g = grid ?: run {
            drawnGridLines = 0
            return
        }
        val t0 = timeAt(0f)
        val t1 = timeAt(w.toFloat())
        val lines = g.linesIn(t0, t1, limit = 2000)
        drawnGridLines = lines.size
        if (lines.isEmpty()) return
        val beatPx = if (lines.size >= 2) xOf(lines[1].time) - xOf(lines[0].time) else w.toFloat()
        val showBeatNumbers = beatPx >= dp(34f)
        val showBarNumbers = beatPx * g.beatsPerBar >= dp(56f)
        val tri = dp(GridStyle.TRIANGLE_DP)
        for (line in lines) {
            val x = xOf(line.time)
            val width = dp(if (line.downbeat) GridStyle.DOWNBEAT_DP else GridStyle.BEAT_DP)
            linePaint.color = GridStyle.SHADOW
            linePaint.strokeWidth = width + dp(GridStyle.SHADOW_EXTRA_DP)
            canvas.drawLine(x, 0f, x, h.toFloat(), linePaint)
            linePaint.color = if (line.downbeat) GridStyle.DOWNBEAT else GridStyle.BEAT
            linePaint.strokeWidth = width
            canvas.drawLine(x, 0f, x, h.toFloat(), linePaint)
            if (line.downbeat) {
                path.reset()
                path.moveTo(x - tri / 1.4f, 0f); path.lineTo(x + tri / 1.4f, 0f); path.lineTo(x, tri); path.close()
                path.moveTo(x - tri / 1.4f, h.toFloat()); path.lineTo(x + tri / 1.4f, h.toFloat()); path.lineTo(x, h - tri); path.close()
                canvas.drawPath(path, trianglePaint)
                if (showBarNumbers) canvas.drawText(line.barNumber.toString(), x + dp(4f), tri + dp(12f), textPaint)
            }
            if (showBeatNumbers) canvas.drawText(line.beatInBar.toString(), x + dp(3f), h - dp(6f), beatTextPaint)
        }
    }

    companion object {
        private val SILENT = Color.rgb(70, 70, 76)
        const val MIN_SECONDS = 0.05
        const val MAX_SECONDS = 16.0
    }
}
