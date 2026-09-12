package com.depth.live.wallpaper

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Offline MiDaS Small v2.1 adapter.
 *
 * Expected asset: midas_small_256_fp16.tflite
 * Input: 1 x 256 x 256 x 3 RGB float32, ImageNet normalized.
 * Output: 1 x 256 x 256 relative inverse-depth float32.
 *
 * Relative depth is exactly what Parallax Moment needs: ordering pixels by
 * near/far, not measuring real-world distance.
 */
class MidasDepthEstimator(
    context: Context,
    assetName: String = MODEL_ASSET,
    inputSize: Int = 256
) : Closeable {
    companion object {
        const val MODEL_ASSET = "midas_small_256_fp16.tflite"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val inputSize = inputSize
    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(inputSize) { FloatArray(inputSize) } }

    init {
        val model = context.assets.open(assetName).use { it.readBytes() }
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(model, options)
    }

    fun estimate(bitmap: Bitmap): DepthResult {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        input.rewind()
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xff) / 255f
            val g = ((pixel shr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            input.putFloat((r - MEAN[0]) / STD[0])
            input.putFloat((g - MEAN[1]) / STD[1])
            input.putFloat((b - MEAN[2]) / STD[2])
        }
        interpreter.run(input, output)
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (row in output[0]) for (value in row) {
            minValue = min(minValue, value)
            maxValue = max(maxValue, value)
        }
        val range = max(1e-6f, maxValue - minValue)
        val normalized = Array(inputSize) { y -> FloatArray(inputSize) { x ->
            ((output[0][y][x] - minValue) / range).coerceIn(0f, 1f)
        } }
        if (resized !== bitmap) resized.recycle()
        return DepthResult(normalized, inputSize, minValue, maxValue)
    }

    override fun close() { interpreter.close() }

    data class DepthResult(
        val normalized: Array<FloatArray>,
        val width: Int,
        val rawMin: Float,
        val rawMax: Float
    )
}
