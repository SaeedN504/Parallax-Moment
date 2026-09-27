package com.deepfx.clock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Draws the clock layer using the editor's own settings schema, so the live
 * wallpaper matches the WebView preview exactly:
 *
 *   size        clock size slider, 0.06..0.7 of the screen height (480 = 0.48)
 *   opacity     0..100
 *   stretchX/Y  0..100 based percentages (100 = natural)
 *   tracking    per character spacing in slider units referenced to 220px
 *   dateEnabled / dateFmt / dateUpper / dateSize / dateX / dateY
 *   glowStr / shadowStr, fontId, fontW, ink, fmt24, showSeconds
 *   clockX / clockY  normalised anchor of the digits
 *
 * Legacy keys (clockScale, clockOpacity, letterSpacing, showDate) are still
 * honoured as fallbacks.
 */
class ClockPainter {
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val date = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, cfg: JSONObject, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val fmt24 = cfg.optBoolean("fmt24", false)
        val showSeconds = cfg.optBoolean("showSeconds", false)
        val showDate = if (cfg.has("dateEnabled")) cfg.optBoolean("dateEnabled", true) else cfg.optBoolean("showDate", true)
        val anchorX = cfg.optDouble("clockX", 0.5).toFloat()
        val anchorY = cfg.optDouble("clockY", 0.40).toFloat()

        val sizeFraction = if (cfg.has("size")) {
            cfg.optDouble("size", 480.0).toFloat() / 1000f
        } else {
            cfg.optDouble("clockScale", 0.48).toFloat()
        }
        val size = (sizeFraction * height).coerceAtLeast(8f)

        val opacity = (percent(cfg, "opacity", "clockOpacity", 100f) / 100f).coerceIn(0f, 1f)
        val stretchX = (percent(cfg, "stretchX", null, 100f) / 100f).coerceIn(0.2f, 5f)
        val stretchY = (percent(cfg, "stretchY", null, 100f) / 100f).coerceIn(0.2f, 5f)
        val tracking = if (cfg.has("tracking")) cfg.optDouble("tracking", 0.0).toFloat() else cfg.optDouble("letterSpacing", 0.0).toFloat() * TRACKING_REFERENCE
        val ink = parseColor(cfg.optString("ink", "#ffffff"), Color.WHITE)
        val glowStrength = cfg.optDouble("glowStr", 0.0).toFloat().coerceIn(0f, 100f) / 100f
        val shadowStrength = cfg.optDouble("shadowStr", 35.0).toFloat().coerceIn(0f, 100f) / 100f
        val weight = cfg.optDouble("fontW", 400.0).toInt()
        val fontId = cfg.optString("fontId", "")

        val now = Calendar.getInstance()
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val suffix = if (hour24 >= 12) "PM" else "AM"
        val hour = if (fmt24) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val blink = now.get(Calendar.SECOND) % 2 == 0
        val separator = if (blink) ":" else " "

        var text = pad2(hour) + separator + pad2(now.get(Calendar.MINUTE))
        if (showSeconds) text += separator + pad2(now.get(Calendar.SECOND))

        digit.reset()
        digit.isAntiAlias = true
        digit.color = ink
        digit.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        digit.typeface = typefaceFor(fontId, weight)
        digit.textAlign = Paint.Align.CENTER
        digit.textSize = size
        digit.letterSpacing = (tracking / TRACKING_REFERENCE).coerceIn(0f, 0.6f)
        applyShadow(digit, size, glowStrength, shadowStrength)

        canvas.save()
        canvas.translate(anchorX * width, anchorY * height)
        canvas.scale(stretchX, stretchY)
        canvas.drawText(text, 0f, 0f, digit)

        if (!fmt24) {
            badge.reset()
            badge.isAntiAlias = true
            badge.color = ink
            badge.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            badge.typeface = typefaceFor(fontId, weight)
            badge.textAlign = Paint.Align.LEFT
            badge.textSize = size * 0.22f
            badge.letterSpacing = (tracking / TRACKING_REFERENCE * 0.5f).coerceIn(0f, 0.3f)
            applyShadow(badge, size, glowStrength * 0.5f, shadowStrength)
            canvas.drawText(suffix, size * 0.62f, 0f, badge)
        }
        canvas.restore()

        if (showDate) drawDate(canvas, cfg, now, width, height, ink, opacity)
    }

    private fun drawDate(
        canvas: Canvas,
        cfg: JSONObject,
        now: Calendar,
        width: Int,
        height: Int,
        ink: Int,
        opacity: Float
    ) {
        val long = cfg.optString("dateFmt", "long") == "long"
        val monthStyle = if (long) Calendar.LONG else Calendar.SHORT
        val label = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) + ", " +
            now.getDisplayName(Calendar.MONTH, monthStyle, Locale.US) + " " +
            now.get(Calendar.DAY_OF_MONTH)
        val shown = if (cfg.optBoolean("dateUpper", true)) label.uppercase(Locale.US) else label

        val dateSizeFraction = cfg.optDouble("dateSize", 34.0).toFloat() / 1000f
        val dateSize = (dateSizeFraction * height).coerceIn(8f, height * 0.2f)
        val dateTracking = cfg.optDouble("dateTracking", 6.0).toFloat()

        date.reset()
        date.isAntiAlias = true
        date.color = parseColor(cfg.optString("dateInk", cfg.optString("ink", "#ffffff")), ink)
        date.alpha = (opacity * 200f).toInt().coerceIn(0, 255)
        date.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        date.textAlign = Paint.Align.CENTER
        date.textSize = dateSize
        date.letterSpacing = (dateTracking / TRACKING_REFERENCE).coerceIn(0f, 0.6f)

        canvas.save()
        canvas.translate(cfg.optDouble("dateX", 0.5).toFloat() * width, cfg.optDouble("dateY", 0.27).toFloat() * height)
        canvas.scale((cfg.optDouble("dateStretchX", 100.0).toFloat() / 100f).coerceIn(0.2f, 5f), 1f)
        canvas.drawText(shown, 0f, 0f, date)
        canvas.restore()
    }

    private fun applyShadow(paint: Paint, size: Float, glow: Float, shadow: Float) {
        val strength = maxOf(glow, shadow)
        if (strength <= 0f) {
            paint.setShadowLayer(0f, 0f, 0f, 0)
            return
        }
        paint.setShadowLayer(
            size * (0.02f + 0.06f * glow),
            0f,
            size * 0.02f * shadow,
            0x99000000.toInt()
        )
    }

    /**
     * Reads a 0..100 slider value. [legacyKey] is for keys that older builds wrote as a
     * 0..1 fraction (clockOpacity, clockScale); pass null when no such key exists.
     */
    private fun percent(cfg: JSONObject, key: String, legacyKey: String?, fallback: Float): Float {
        if (cfg.has(key)) return cfg.optDouble(key, fallback.toDouble()).toFloat()
        if (legacyKey != null) {
            val legacy = cfg.optDouble(legacyKey, Double.NaN)
            if (!legacy.isNaN()) return (legacy * 100f).toFloat()
        }
        return fallback
    }

    private fun typefaceFor(fontId: String, weight: Int): Typeface {
        val bold = weight >= 600 || weight == 0
        return when {
            fontId.contains("mono", true) -> Typeface.create("sans-serif-monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
            fontId.contains("thin", true) -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            fontId.contains("light", true) -> Typeface.create("sans-serif-light", if (bold) Typeface.BOLD else Typeface.NORMAL)
            fontId.contains("serif", true) -> Typeface.create("serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            else -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
    }

    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun parseColor(value: String, fallback: Int): Int =
        try {
            Color.parseColor(value)
        } catch (error: Exception) {
            fallback
        }

    private companion object {
        const val TRACKING_REFERENCE = 220f
    }
}
