package com.xdj.flx10test.lab

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.xdj.flx10test.browse.BrowserHeaderView
import com.xdj.flx10test.browse.BrowserRow
import com.xdj.flx10test.browse.CamelotKey
import com.xdj.flx10test.browse.ColumnId
import com.xdj.flx10test.browse.KeyMatch
import com.xdj.flx10test.browse.SortState
import com.xdj.flx10test.browse.TrackRowView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h625dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewRender {
    @Test
    fun render() {
        val out = System.getProperty("preview.out") ?: return
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val w = 2000
        val master = CamelotKey.parse("10A")
        fun r(id: Long, t: String, ar: String, bpm: Double, k: String, c: String, d: Long, st: Boolean, rating: Int = 0) =
            BrowserRow.fromRekordbox(id, t, ar, bpm, k, null, c, "", d, rating, st)
        val rows = listOf(
            r(1, "11A - Ai Chung Tinh Duoc Mai HD", "", 130.0, "11A", "/* Club / House Lak / Bass Music / Remix / Grooving Up / Viet Nam */", 470_000, true),
            r(2, "8A - 128 - BOOM BOOM POW - THE BLACK EYED PEAS (MINK BOOTLEG)", "The Black Eyed Peas", 128.0, "11B", "/* Club / Bass House */", 245_000, true),
            r(3, "2A - Buon Nhung Khong The Buong x HaiAnh HD", "HaiAnh", 128.0, "2A", "/* Vina House */", 301_000, true, 3),
            r(4, "Bagigi Fix", "", 130.0, "9A", "/* Afro House */", 288_000, true),
            r(5, "10A - Ben Tren Tang Lau x Iconic HD - (Deep House)", "Iconic", 127.0, "10A", "/* Deep House / Viet Nam */", 257_000, true, 4),
            r(6, "2A - Manamia Edit Lak", "", 135.0, "2A", "/* House Lak */", 266_000, false),
            r(7, "Am - Betrayal x Harvey Nelson Remix", "Harvey Nelson", 130.0, "8A", "", 240_000, true),
            r(8, "12A - Be My Love Lak", "", 131.0, "12A", "/* House Lak / Remix */", 312_000, true),
            r(9, "4A - Falling In Love HD - (DeepHouse)", "", 130.0, "10B", "/* Deep House */", 280_000, true, 5),
        )
        val header = BrowserHeaderView(a).apply { columns = ColumnId.DEFAULT; sort = SortState(ColumnId.BPM, false) }
        fun lay(v: View) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        }
        lay(header)
        val views = rows.mapIndexed { i, row ->
            TrackRowView(a).apply { bind(row, ColumnId.DEFAULT, KeyMatch.of(master, row.camelot), i == 4, i % 2 == 1); lay(this) }
        }
        val h = header.measuredHeight + views.sumOf { it.measuredHeight }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 19, 23))
        header.draw(c)
        c.translate(0f, header.measuredHeight.toFloat())
        views.forEach { it.draw(c); c.translate(0f, it.measuredHeight.toFloat()) }
        FileOutputStream(File(out)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Ảnh xem trước sóng chi tiết + grid đậm (tín hiệu giả: kick 55 Hz, clap, hi-hat ở 126 BPM). */
    @Test
    fun renderWave() {
        val out = System.getProperty("preview.wave") ?: return
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val rate = 44100
        val step = 60.0 / 126
        val n = rate * 6
        val pcm = ShortArray(n * 2)
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val k = (t - 0.2 + 100 * step) % step
            val beat = ((t - 0.2) / step).toInt()
            var v = 22000 * Math.exp(-k * 14) * Math.sin(2 * Math.PI * (48 + 60 * Math.exp(-k * 40)) * k)
            if (beat % 2 == 1) v += 9000 * Math.exp(-k * 30) * (rnd.nextDouble() * 2 - 1) * (1 - Math.exp(-k * 400))
            val h = (t - 0.2 - step / 2 + 100 * step) % step
            v += 4500 * Math.exp(-h * 60) * (rnd.nextDouble() * 2 - 1)
            pcm[2 * i] = v.toInt().coerceIn(-32767, 32767).toShort(); pcm[2 * i + 1] = pcm[2 * i]
        }
        val b = com.xdj.flx10test.wave.DetailWaveBuilder(rate)
        b.feed(pcm, pcm.size, 2)
        val view = com.xdj.flx10test.wave.DetailWaveView(a).apply {
            wave = b.build()
            grid = com.xdj.flx10test.wave.BeatGrid.constant(126.0, 0.2, 6.0)
            compareGrid = com.xdj.flx10test.wave.BeatGrid.constant(126.0, 0.214, 6.0)
            secondsVisible = System.getProperty("preview.seconds")?.toDoubleOrNull() ?: 1.2
            positionSec = 0.2 + 5 * step
            label = "DECK 1 · sóng chi tiết · %.2fs zoom".format(secondsVisible)
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2000, 560)
        val bmp = Bitmap.createBitmap(2000, 560, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(out)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
