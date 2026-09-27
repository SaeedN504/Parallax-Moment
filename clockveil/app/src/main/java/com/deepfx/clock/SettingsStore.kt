package com.deepfx.clock

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * On-device layer contract shared by the editor and the live wallpaper.
 *
 * filesDir/bg.jpg        photo without the clock (background layer)
 * filesDir/cutout.png    subject with a transparent background (foreground layer)
 * filesDir/depth.png     MiDaS grayscale depth map of bg.jpg
 * filesDir/settings.json clock anchor, style and motion parameters
 */
class SettingsStore(context: Context) {
    private val dir: File = context.filesDir

    val backgroundFile: File get() = File(dir, "bg.jpg")
    val cutoutFile: File get() = File(dir, "cutout.png")
    val depthFile: File get() = File(dir, "depth.png")
    val settingsFile: File get() = File(dir, "settings.json")
    val staticFile: File get() = File(dir, "deepfx-static.jpg")

    fun read(): JSONObject {
        val file = settingsFile
        if (!file.isFile) return JSONObject()
        return try {
            JSONObject(String(file.readBytes(), StandardCharsets.UTF_8))
        } catch (error: Exception) {
            JSONObject()
        }
    }

    fun write(json: JSONObject) {
        settingsFile.writeText(json.toString(), StandardCharsets.UTF_8)
    }

    fun hasContent(): Boolean = backgroundFile.isFile
}
