package com.depth.live.wallpaper

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Offline MiDaS Small v2.1 adapter. */
class MidasDepthEstimator(
    context: Context,
    assetName: String = MODEL_ASSET,
    private val inputSize: Int = 256
) : Closeable {
    companion object {
        const val MODEL_ASSET = "midas_small_256_fp16.tflite"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val modelBuffer: ByteBuffer
    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(inputSize) { FloatArray(inputSize) } }

    init {
        val modelBytes = try {
            context.assets.open(assetName).use { it.readBytes() }
        } catch (error: Exception) {
            throw IllegalStateException("Missing packaged MiDaS model asset: $assetName", error)
        }
        modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })

        val inputShape = interpreter.getInputTensor(0).shape()
        require(inputShape.contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
            "Unexpected MiDaS input shape: ${inputShape.contentToString()}"
        }
        val outputShape = interpreter.getOutputTensor(0).shape()
        require(outputShape.contentEquals(intArrayOf(1, inputSize, inputSize))) {
            "Unexpected MiDaS output shape: ${outputShape.contentToString()}"
        }
    }

    fun estimate(bitmap: Bitmap): DepthResult {
        require(!bitmap.isRecycled) { "Cannot estimate depth from a recycled bitmap" }
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        try {
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
            input.rewind()
            interpreter.run(input, output)

            var minValue = Float.POSITIVE_INFINITY
            var maxValue = Float.NEGATIVE_INFINITY
            for (row in output[0]) for (value in row) {
                require(value.isFinite()) { "MiDaS returned a non-finite depth value" }
                minValue = min(minValue, value)
                maxValue = max(maxValue, value)
            }
            val range = max(1e-6f, maxValue - minValue)
            val normalized = Array(inputSize) { y ->
                FloatArray(inputSize) { x ->
                    ((output[0][y][x] - minValue) / range).coerceIn(0f, 1f)
                }
            }
            return DepthResult(normalized, inputSize, minValue, maxValue)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    override fun close() {
        interpreter.close()
    }

    data class DepthResult(
        val normalized: Array<FloatArray>,
        val width: Int,
        val rawMin: Float,
        val rawMax: Float
    )
}
