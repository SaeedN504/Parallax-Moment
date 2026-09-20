package com.depth.live.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import java.util.Locale

/** Offline smoke benchmark. Run from an instrumentation test or debug screen. */
object OfflineDepthBenchmark {
    data class Report(
        val model: String,
        val samples: Int,
        val warmups: Int,
        val medianMs: Long,
        val p95Ms: Long,
        val minMs: Long,
        val maxMs: Long,
        val heapDeltaMb: Long
    ) {
        override fun toString(): String = String.format(
            Locale.US,
            "%s samples=%d warmups=%d medianMs=%d p95Ms=%d minMs=%d maxMs=%d heapDeltaMb=%d",
            model, samples, warmups, medianMs, p95Ms, minMs, maxMs, heapDeltaMb
        )
    }

    fun run(
        context: Context,
        sampleAsset: String = "benchmark/sample.jpg",
        warmups: Int = 2,
        samples: Int = 8
    ): Report {
        require(warmups >= 0) { "warmups must not be negative" }
        require(samples > 0) { "samples must be greater than zero" }

        val bitmap = loadSampleOrCreateFallback(context, sampleAsset)
        val estimator = MidasDepthEstimator(context)
        try {
            repeat(warmups) { estimator.estimate(bitmap) }
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val timings = LongArray(samples) {
                val start = SystemClock.elapsedRealtime()
                val result = estimator.estimate(bitmap)
                check(result.width == 256) { "Unexpected depth output" }
                SystemClock.elapsedRealtime() - start
            }
            val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            timings.sort()
            return Report(
                "MiDaS Small v2.1",
                samples,
                warmups,
                timings[timings.size / 2],
                timings[((timings.size - 1) * 95) / 100],
                timings.first(),
                timings.last(),
                (after - before) / (1024 * 1024)
            )
        } finally {
            estimator.close()
            bitmap.recycle()
        }
    }

    private fun loadSampleOrCreateFallback(context: Context, sampleAsset: String): Bitmap {
        try {
            context.assets.open(sampleAsset).use { stream ->
                BitmapFactory.decodeStream(stream)?.let { return it }
            }
        } catch (_: Exception) {
            // A deterministic generated image keeps the compatibility test self-contained.
        }
        return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(256 * 256) { index ->
                val x = index % 256
                val y = index / 256
                Color.rgb(x, y, (x + y) / 2)
            }
            bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        }
    }
}
