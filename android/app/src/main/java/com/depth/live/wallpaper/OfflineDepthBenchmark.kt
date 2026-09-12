package com.depth.live.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
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

    fun run(context: Context, sampleAsset: String = "benchmark/sample.jpg", warmups: Int = 2, samples: Int = 8): Report {
        val bitmap = context.assets.open(sampleAsset).use { BitmapFactory.decodeStream(it) }
            ?: error("Missing benchmark asset: $sampleAsset")
        val estimator = MidasDepthEstimator(context)
        repeat(warmups) { estimator.estimate(bitmap) }
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val timings = LongArray(samples) {
            val start = SystemClock.elapsedRealtime()
            val result = estimator.estimate(bitmap)
            check(result.width == 256) { "Unexpected depth output" }
            SystemClock.elapsedRealtime() - start
        }
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        estimator.close()
        bitmap.recycle()
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
    }
}
