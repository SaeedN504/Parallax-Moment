package com.deepfx.clock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the three-layer sandwich:
 *   1. background photo, drifting with depth weighted strips
 *   2. clock, drawn at a fixed anchor (never offset by the parallax phase)
 *   3. subject cutout, drawn on top so the clock peeks out from behind it
 *
 * The background is deliberately overscanned so the strip offsets can never
 * expose an empty edge, and the cutout is drawn 1:1 against the unshifted
 * frame so the subject stays glued to the scene while the photo drifts.
 */
class LayerRenderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()
    private var cachedDepth: FloatArray? = null
    private var cachedDepthId: Int = -1

    private val strips = STRIPS

    fun reset() {
        cachedDepth = null
        cachedDepthId = -1
    }

    fun drawBackground(
        canvas: Canvas,
        background: Bitmap,
        depth: Bitmap?,
        phase: Float,
        strength: Float,
        amplitude: Float,
        direction: String,
        width: Int,
        height: Int
    ) {
        val frame = frameOf(background, width, height)
        val maxOffset = min(width * 0.035f, 42f) * strength * amplitude
        val wave = sin(phase * TWO_PI)
        val vertical = direction == "vertical" || direction == "diagonal"
        val horizontal = direction != "vertical"
        val columns = depthColumns(depth, strips)
        val sourceWidth = background.width.toFloat() / strips

        for (index in 0 until strips) {
            val weight = (columns[index] - 0.5f) * 2f
            val offsetX = if (horizontal) maxOffset * weight * wave else 0f
            val offsetY = if (vertical) maxOffset * 0.5f * weight * wave else 0f
            val left = index * sourceWidth
            val source = Rect(
                left.toInt().coerceIn(0, background.width - 1),
                0,
                (left + sourceWidth).toInt().coerceIn(1, background.width),
                background.height
            )
            val destination = RectF(
                frame.left + index * frame.width() / strips + offsetX,
                frame.top + offsetY,
                frame.left + (index + 1) * frame.width() / strips + offsetX,
                frame.bottom + offsetY
            )
            canvas.drawBitmap(background, source, destination, paint)
        }
    }

    fun drawCutout(
        canvas: Canvas,
        cutout: Bitmap?,
        background: Bitmap,
        width: Int,
        height: Int,
        subjectScale: Float = 1f
    ) {
        if (cutout == null) return
        val frame = frameOf(background, width, height)
        if (subjectScale == 1f) {
            canvas.drawBitmap(cutout, null, frame, paint)
            return
        }
        val scaledWidth = frame.width() * subjectScale
        val scaledHeight = frame.height() * subjectScale
        val left = frame.centerX() - scaledWidth / 2f
        val top = frame.centerY() - scaledHeight / 2f
        canvas.drawBitmap(cutout, null, RectF(left, top, left + scaledWidth, top + scaledHeight), paint)
    }

    /** The editor's bgDim treatment, drawn between the photo and the clock. */
    fun drawDim(canvas: Canvas, width: Int, height: Int, dim: Float) {
        val alpha = (dim.coerceIn(0f, 0.85f) * 255f).toInt()
        if (alpha <= 0) return
        dimPaint.color = Color.argb(alpha, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
    }

    /** Cover-fit rectangle with a 6% overscan so strip offsets never expose an edge. */
    private fun frameOf(background: Bitmap, width: Int, height: Int): RectF {
        val scale = max(width / background.width.toFloat(), height / background.height.toFloat()) * OVERSCAN
        val drawWidth = background.width * scale
        val drawHeight = background.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    /**
     * Per-strip depth averaged over several rows, which keeps the whole strip
     * coherent instead of sampling a single midline row like the original
     * renderer did.
     */
    private fun depthColumns(depth: Bitmap?, stripCount: Int): FloatArray {
        val depthId = depth?.generationId ?: -1
        val cached = cachedDepth
        if (cached != null && cached.size == stripCount && cachedDepthId == depthId) return cached

        val values = FloatArray(stripCount)
        if (depth == null || depth.width <= 0 || depth.height <= 0) {
            values.fill(0.5f)
        } else {
            val stepX = depth.width.toFloat() / stripCount
            for (index in 0 until stripCount) {
                val startX = (index * stepX).toInt().coerceIn(0, depth.width - 1)
                val endX = ((index + 1) * stepX).toInt().coerceIn(startX + 1, depth.width)
                var sum = 0f
                var count = 0
                for (row in 0 until DEPTH_SAMPLE_ROWS) {
                    val y = (((row + 0.5f) / DEPTH_SAMPLE_ROWS) * depth.height).toInt().coerceIn(0, depth.height - 1)
                    var x = startX
                    while (x < endX) {
                        sum += (depth.getPixel(x, y) and 0xFF) / 255f
                        count++
                        x += DEPTH_SAMPLE_STEP
                    }
                }
                values[index] = if (count > 0) sum / count else 0.5f
            }
        }
        cachedDepth = values
        cachedDepthId = depthId
        return values
    }

    private companion object {
        const val STRIPS = 48
        const val DEPTH_SAMPLE_ROWS = 8
        const val DEPTH_SAMPLE_STEP = 2
        const val OVERSCAN = 1.06f
        const val TWO_PI = 6.2831855f
    }
}
