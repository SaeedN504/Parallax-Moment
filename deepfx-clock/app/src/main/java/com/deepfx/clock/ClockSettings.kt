package com.deepfx.clock

import org.json.JSONObject
import java.util.Locale

data class ClockSettings(
    val x: Float = .5f, val y: Float = .35f, val scale: Float = .18f,
    val dateX: Float = .5f, val dateY: Float = .46f,
    val badgeX: Float = .85f, val badgeY: Float = .35f,
    val opacity: Float = 1f, val glow: Float = .2f, val spacing: Float = 0f,
    val color: String = "#ffffff", val style: String = "condensed",
    val format24: Boolean = false, val showDate: Boolean = true,
    val amplitude: Float = .015f, val speed: Float = 12f,
    val direction: String = "horizontal", val depthStrength: Float = .6f,
    val fps: Int = 24, val feather: Float = 1f, val threshold: Float = .5f
) {
    fun validated(): ClockSettings {
        fun bound(v: Float, lo: Float, hi: Float, fallback: Float) = if (v.isFinite()) v.coerceIn(lo, hi) else fallback
        return copy(x=bound(x,0f,1f,.5f), y=bound(y,0f,1f,.35f), scale=bound(scale,.04f,.5f,.18f),
            dateX=bound(dateX,0f,1f,.5f), dateY=bound(dateY,0f,1f,.46f),
            badgeX=bound(badgeX,0f,1f,.85f), badgeY=bound(badgeY,0f,1f,.35f),
            opacity=bound(opacity,0f,1f,1f), glow=bound(glow,0f,1f,.2f), spacing=bound(spacing,0f,.2f,0f),
            color=if (Regex("#[0-9a-fA-F]{6}").matches(color)) color else "#ffffff",
            style=if (style == "segments") style else "condensed",
            amplitude=bound(amplitude,0f,.04f,.015f), speed=bound(speed,4f,40f,12f),
            direction=if (direction in setOf("horizontal","vertical","diagonal")) direction else "horizontal",
            depthStrength=bound(depthStrength,0f,1f,.6f), fps=fps.coerceIn(24,30),
            feather=bound(feather,0f,5f,1f), threshold=bound(threshold,.1f,.9f,.5f))
    }
    fun json(): String = JSONObject().apply {
        put("x",x); put("y",y); put("scale",scale); put("dateX",dateX); put("dateY",dateY)
        put("badgeX",badgeX); put("badgeY",badgeY); put("opacity",opacity); put("glow",glow)
        put("spacing",spacing); put("color",color); put("style",style); put("format24",format24)
        put("showDate",showDate); put("amplitude",amplitude); put("speed",speed); put("direction",direction)
        put("depthStrength",depthStrength); put("fps",fps); put("feather",feather); put("threshold",threshold)
    }.toString()
    companion object {
        fun timeText(hour: Int, minute: Int, format24: Boolean): String = String.format(Locale.US,"%02d:%02d",if(format24) hour else (hour % 12).let { if(it==0) 12 else it },minute)
        fun parse(text: String): ClockSettings {
            require(text.length < 8192) { "Settings are too large" }
            val j=JSONObject(text); val d=ClockSettings()
            fun f(k:String,v:Float)=j.optDouble(k,v.toDouble()).toFloat()
            return ClockSettings(f("x",d.x),f("y",d.y),f("scale",d.scale),f("dateX",d.dateX),f("dateY",d.dateY),
                f("badgeX",d.badgeX),f("badgeY",d.badgeY),f("opacity",d.opacity),f("glow",d.glow),f("spacing",d.spacing),
                j.optString("color",d.color),j.optString("style",d.style),j.optBoolean("format24",d.format24),j.optBoolean("showDate",d.showDate),
                f("amplitude",d.amplitude),f("speed",d.speed),j.optString("direction",d.direction),f("depthStrength",d.depthStrength),
                j.optInt("fps",d.fps),f("feather",d.feather),f("threshold",d.threshold)).validated()
        }
    }
}
