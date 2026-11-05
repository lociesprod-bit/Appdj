package com.xdj.flx10test.wave

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailWaveViewTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun wave(): DetailWave {
        val rate = 44100
        val b = DetailWaveBuilder(rate)
        val n = rate * 4
        val pcm = ShortArray(n * 2)
        val step = 60.0 / 126
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val k = t % step
            val v = 20000 * exp(-k * 18) * sin(2 * PI * 55 * k) + 3000 * sin(2 * PI * 7000 * t)
            pcm[2 * i] = v.toInt().toShort(); pcm[2 * i + 1] = v.toInt().toShort()
        }
        b.feed(pcm, pcm.size, 2)
        return b.build()
    }

    private fun render(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1600, 400)
        v.draw(Canvas(Bitmap.createBitmap(1600, 400, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun drawsWaveAndGridAtDeepAndWideZoom() {
        val view = DetailWaveView(activity)
        view.wave = wave()
        view.grid = BeatGrid.constant(126.0, 0.0, 4.0)
        view.positionSec = 2.0
        view.secondsVisible = 0.27 // như màn PREPARE trong ảnh chụp
        render(view)
        assertTrue(view.drawnColumns > 1500)
        assertEquals(1, view.drawnGridLines) // 0,27 s ≈ 0,57 beat ở 126 BPM
        view.secondsVisible = 4.0
        render(view)
        assertTrue(view.drawnGridLines in 8..9) // 4 s ≈ 8,4 beat
        // Zoom bị giới hạn.
        view.secondsVisible = 0.001
        assertEquals(DetailWaveView.MIN_SECONDS, view.secondsVisible, 1e-9)
        assertEquals(2.0, view.timeAt(800f), 1e-9)
        assertEquals(800f, view.xOf(2.0), 0.01f)
    }
}
