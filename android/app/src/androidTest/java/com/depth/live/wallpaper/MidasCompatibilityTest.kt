package com.depth.live.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MidasCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun modelAssetIsBundledAndProducesFiniteDepth() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            val shade = ((x + y) and 255)
            bitmap.setPixel(x, y, Color.rgb(shade, 255 - shade, 128))
        }
        val estimator = MidasDepthEstimator(context)
        val result = estimator.estimate(bitmap)
        assertEquals(256, result.width)
        assertEquals(256, result.normalized.size)
        assertTrue(result.normalized.flatten().all { it.isFinite() && it in 0f..1f })
        estimator.close()
        bitmap.recycle()
    }

    @Test
    fun benchmarkRunsWithoutNetworkPermission() {
        val report = OfflineDepthBenchmark.run(context, warmups = 1, samples = 2)
        assertEquals(2, report.samples)
        assertTrue(report.medianMs >= 0)
    }
}
