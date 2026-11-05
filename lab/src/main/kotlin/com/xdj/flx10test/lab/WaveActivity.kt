package com.xdj.flx10test.lab

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xdj.flx10test.wave.BeatGrid
import com.xdj.flx10test.wave.DetailWaveView
import com.xdj.flx10test.wave.GridOffsetEstimator
import com.xdj.flx10test.wave.GridOffsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * Màn "Sóng & grid": sóng chi tiết thật (RGB 3 dải) với grid đậm từ **mọi** mốc TEMPO của rekordbox XML.
 * - So với grid cũ 0.15.0 (vạch xanh ngọc mảnh).
 * - Bù lệch tay ±1/±10 ms để xem vạch trùng kick.
 * - Đo lệch tự động.
 */
class WaveActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "wave-worker") }
    @Volatile private var closed = false

    private lateinit var wave: DetailWaveView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var compareButton: TextView
    private val logLines = ArrayDeque<String>()

    private var track: LabTrack? = null
    private var grid: BeatGrid? = null
    private var oldGrid: BeatGrid? = null
    private var measured: GridOffsetEstimator.Result? = null
    private var offsetMs = 0.0
    private var formatOffsetMs = 0.0
    private var showOld = true
    private var baseInfo = ""
    private var duration = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val lib = LabSession.library
        val access = LabSession.access
        val rel = intent.getStringExtra(EXTRA_PATH)
        val t = lib?.tracks?.firstOrNull { it.relPath == rel }
        if (lib == null || access == null || t == null) {
            info.text = "Không tìm thấy bài. Quay lại màn chính, chọn bài rồi bấm Sóng & grid."
            return
        }
        track = t
        info.text = "Đang giải mã ${t.row.title}…"
        worker.execute { load(t) }
    }

    override fun onResume() {
        super.onResume()
        LabSession.log?.onLine = { line -> ui.post { appendLog(line) } }
    }

    override fun onDestroy() {
        closed = true
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load(t: LabTrack) {
        val log = LabSession.log ?: return
        val access = LabSession.access ?: return
        val lib = LabSession.library ?: return
        try {
            val d = LabDecoder.decode(this, access, t, buildDetail = true) { closed }
            log.line("Sóng & grid: ${t.row.title} · giải mã ${d.millis} ms · ${"%.1f".format(d.durationSec)} s · ${d.describeFormat()}")
            val key = RekordboxXmlLite.matchKey(t.relPath.substringAfterLast('/'))
            val xml = lib.openNewestXml()?.second?.use { RekordboxXmlLite.grids(it, setOf(key)) }?.get(key)
            val tempos = xml?.tempos.orEmpty()
            val g = BeatGrid.fromTempos(tempos, d.durationSec)
            // Grid như 0.15.0: chỉ TEMPO đầu, beat0 = Inizio − (Battito−1)·60/BPM, đưa về ≥ 0.
            val old = tempos.firstOrNull()?.let { f ->
                var beat0 = f.inizio - (f.battito - 1) * 60.0 / f.bpm
                while (beat0 < 0) beat0 += 240.0 / f.bpm
                BeatGrid.constant(f.bpm, beat0, d.durationSec)
            }
            val r = g?.let { GridOffsetEstimator.estimate(d.mono, d.count, d.sampleRate, it) }
            val fit = g?.fitConstant()
            val head = tempos.firstOrNull()
            val kind = GridOffsets.kindOf(t.relPath.substringAfterLast('.'), d.mp3, d.mime)
            val formatMs = GridOffsets.seconds(kind, d.sampleRate) * 1000
            baseInfo = "${t.row.title} · ${d.describeFormat()}\n" +
                (if (head == null) "Không có grid trong XML" else "XML: ${tempos.size} mốc TEMPO · BPM đầu ${head.bpm} · Inizio ${head.inizio} s · Battito ${head.battito}") +
                (fit?.let { " · BPM khớp mọi mốc %.3f (lệch tối đa %.1f ms)".format(it.bpm, it.maxErrorMs) } ?: "") +
                "\nBù theo định dạng: " + GridOffsets.describe(kind, d.sampleRate) +
                "\nĐo lệch so với kick (grid chưa bù): " + (r?.describe() ?: "không đo được") +
                (r?.let { " → sau bù định dạng còn %+.1f ms (lossless đo ~+1,2 ms)".format(it.medianMs - formatMs) } ?: "")
            log.line("  grid XML ${tempos.size} mốc · bù định dạng " + GridOffsets.describe(kind, d.sampleRate) + " · " + (r?.describe() ?: "không đo được lệch"))
            log.flush(contentResolver, access.saf)
            ui.post {
                if (closed) return@post
                duration = d.durationSec
                grid = g
                oldGrid = old
                measured = r
                formatOffsetMs = (formatMs * 10).roundToLong() / 10.0
                offsetMs = formatOffsetMs
                wave.wave = d.detail
                wave.secondsVisible = 1.0
                wave.positionSec = g?.let { firstDownbeatAfter(it, 30.0.coerceAtMost(d.durationSec / 3)) } ?: (d.durationSec / 3)
                applyGrid()
            }
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            log.line("⚠ Sóng & grid: ${e.message ?: e.javaClass.simpleName}")
            ui.post { info.text = "Lỗi: ${e.message}" }
        }
    }

    private fun firstDownbeatAfter(g: BeatGrid, t: Double): Double =
        g.linesIn(t, t + 10).firstOrNull { it.downbeat }?.time ?: g.nearestBeat(t)

    private fun applyGrid() {
        wave.grid = grid?.shifted(offsetMs / 1000.0)
        wave.compareGrid = if (showOld) oldGrid?.shifted(offsetMs / 1000.0) else null
        compareButton.text = "Grid cũ 0.15.0: ${if (showOld) "BẬT" else "TẮT"}"
        info.text = baseInfo + "\nBù lệch đang xem: %+.1f ms · zoom %.2f s".format(offsetMs, wave.secondsVisible)
    }

    private fun nudge(ms: Double) {
        offsetMs = ((offsetMs + ms) * 10).roundToLong() / 10.0
        applyGrid()
    }

    private fun stepBeat(dir: Int) {
        val g = grid ?: return
        val i = Math.round(g.indexAt(wave.positionSec - offsetMs / 1000.0)) + dir
        wave.positionSec = g.beatTime(i) + offsetMs / 1000.0
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(Color.rgb(42, 45, 51))
        }
        setOnClickListener { action() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 19, 23))
            setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        add(button("◀ Beat") { stepBeat(-1) })
        add(button("Beat ▶") { stepBeat(1) })
        add(button("− Zoom") { wave.secondsVisible *= 2; applyGrid() })
        add(button("+ Zoom") { wave.secondsVisible /= 2; applyGrid() })
        add(button("0,27 s") { wave.secondsVisible = 0.27; applyGrid() })
        add(button("Đầu bài") { grid?.let { wave.positionSec = it.beatTime(0) + offsetMs / 1000 } })
        add(button("Giữa") { grid?.let { wave.positionSec = firstDownbeatAfter(it, duration / 2) + offsetMs / 1000 } })
        add(button("Cuối") { grid?.let { wave.positionSec = it.nearestBeat(duration - 20) + offsetMs / 1000 } })
        add(button("Bù −10") { nudge(-10.0) })
        add(button("Bù −1") { nudge(-1.0) })
        add(button("Bù +1") { nudge(1.0) })
        add(button("Bù +10") { nudge(10.0) })
        add(button("Bù theo định dạng") { offsetMs = formatOffsetMs; applyGrid() })
        add(button("Bù theo đo") { measured?.let { offsetMs = ((it.medianMs - 1.2) * 10).roundToLong() / 10.0; applyGrid() } })
        add(button("Bù = 0") { offsetMs = 0.0; applyGrid() })
        compareButton = button("Grid cũ 0.15.0: BẬT") { showOld = !showOld; applyGrid() }
        add(compareButton)
        add(button("Đóng") {
            track?.let {
                LabSession.log?.line(
                    "Sóng & grid: đóng ${it.row.title.take(40)} · bù đang xem %+.1f ms (bù định dạng %+.1f ms, chỉnh thêm %+.1f ms)".format(
                        offsetMs, formatOffsetMs, offsetMs - formatOffsetMs,
                    ),
                )
            }
            finish()
        })
        root.addView(HorizontalScrollView(this).apply { addView(bar) })
        info = TextView(this).apply {
            setTextColor(Color.rgb(170, 230, 170))
            textSize = 13f
            setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
        }
        root.addView(info)
        wave = DetailWaveView(this).apply { onViewChanged = { _, _ -> ui.removeCallbacks(infoUpdate); ui.postDelayed(infoUpdate, 120) } }
        root.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        logView = TextView(this).apply {
            setTextColor(Color.rgb(120, 230, 120))
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110f)))
        return root
    }

    private val infoUpdate = Runnable { applyGrid() }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 60) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    companion object {
        const val EXTRA_PATH = "relPath"
    }
}
