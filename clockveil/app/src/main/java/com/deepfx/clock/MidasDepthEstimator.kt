package com.deepfx.clock

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Offline adapter for the official MiDaS v2.1 model_opt.tflite release. */
class MidasDepthEstimator(
    context: Context,
    assetName: String = MODEL_ASSET,
    private val inputSize: Int = 256
) : Closeable {
    companion object {
        const val MODEL_ASSET = "midas_small_256_fp16.tflite"
    }

    private val modelBuffer: ByteBuffer
    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val output: ByteBuffer

    init {
        val modelBytes = try {
            context.assets.open(assetName).use { it.readBytes() }
        } catch (error: Exception) {
            throw IllegalStateException("Missing packaged MiDaS model asset: $assetName", error)
        }
        require(modelBytes.size > 60_000_000) {
            "Invalid MiDaS model asset: expected a real TFLite model, got ${modelBytes.size} bytes"
        }
        require(modelBytes.size >= 8 && modelBytes.copyOfRange(4, 8).contentEquals("TFL3".toByteArray())) {
            "Invalid MiDaS model asset: TensorFlow Lite header is missing"
        }

        modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "Unexpected MiDaS input type: ${inputTensor.dataType()}"
        }
        require(inputShape.contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
            "Unexpected MiDaS input shape: ${inputShape.contentToString()}"
        }

        val outputTensor = interpreter.getOutputTensor(0)
        val outputElements = outputTensor.shape().fold(1) { total, dimension -> total * dimension }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "Unexpected MiDaS output type: ${outputTensor.dataType()}"
        }
        require(outputElements == inputSize * inputSize) {
            "Unexpected MiDaS output shape: ${outputTensor.shape().contentToString()}"
        }
        output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
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
                // The official model_opt.tflite mobile model expects RGB in [-1, 1].
                input.putFloat(r * 2f - 1f)
                input.putFloat(g * 2f - 1f)
                input.putFloat(b * 2f - 1f)
            }
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            output.rewind()

            val raw = FloatArray(inputSize * inputSize)
            output.asFloatBuffer().get(raw)
            var minValue = Float.POSITIVE_INFINITY
            var maxValue = Float.NEGATIVE_INFINITY
            for (value in raw) {
                require(value.isFinite()) { "MiDaS returned a non-finite depth value" }
                minValue = min(minValue, value)
                maxValue = max(maxValue, value)
            }
            val range = max(1e-6f, maxValue - minValue)
            val normalized = Array(inputSize) { y ->
                FloatArray(inputSize) { x ->
                    ((raw[y * inputSize + x] - minValue) / range).coerceIn(0f, 1f)
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
