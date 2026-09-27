package com.deepfx.clock

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Native side of the editor. The interface is still called "DepthAndroid" so the
 * existing editor page and its cutout hook keep working unchanged.
 */
class DeepFxBridge(private val activity: MainActivity, private val webView: WebView) {
    private val store = SettingsStore(activity)

    /** Background layer: the photo without the clock. */
    @JavascriptInterface
    fun savePhoto(base64: String?): Boolean = writeImage(store.backgroundFile, base64, Bitmap.CompressFormat.JPEG)

    /** Foreground layer: subject with a transparent background. */
    @JavascriptInterface
    fun saveCutout(base64: String?): Boolean = writeImage(store.cutoutFile, base64, Bitmap.CompressFormat.PNG)

    @JavascriptInterface
    fun saveSettings(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            store.write(JSONObject(json))
            true
        } catch (error: Exception) {
            Log.w(TAG, "settings rejected: ${error.message}")
            false
        }
    }

    /** Merges the motion panel values into the clock settings the editor already sent. */
    @JavascriptInterface
    fun saveMotion(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val merged = store.read()
            val motion = JSONObject(json)
            val keys = motion.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, motion.get(key))
            }
            store.write(merged)
            true
        } catch (error: Exception) {
            Log.w(TAG, "motion rejected: ${error.message}")
            false
        }
    }

    /**
     * Called by the editor when the user taps Apply. Stores the three layers plus
     * settings, then generates the depth map. It intentionally does not touch the
     * static wallpaper: the clock belongs to the live wallpaper service.
     */
    @JavascriptInterface
    fun apply(base64Image: String?, settingsJson: String?): String {
        return try {
            if (!store.backgroundFile.isFile && !writeImage(store.backgroundFile, base64Image, Bitmap.CompressFormat.JPEG)) {
                return fail("could not decode the exported image")
            }
            if (!settingsJson.isNullOrBlank()) saveSettings(settingsJson)
            val depthOk = generateDepth()
            notifyWeb("window.__depthApplied&&window.__depthApplied('live');")
            "ok:depth=" + depthOk
        } catch (error: Exception) {
            fail(error.message ?: "apply failed")
        }
    }

    @JavascriptInterface
    fun generateDepth(): Boolean {
        val file = store.backgroundFile
        if (!file.isFile) return false
        val photo = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            MidasDepthEstimator(activity, MidasDepthEstimator.MODEL_ASSET, 256).use { estimator ->
                val values = estimator.estimate(photo).normalized
                val height = values.size
                val width = values[0].size
                val map = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val row = IntArray(width)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val v = (values[y][x] * 255f).roundToInt().coerceIn(0, 255)
                        row[x] = Color.rgb(v, v, v)
                    }
                    map.setPixels(row, 0, width, 0, y, width, 1)
                }
                val ok = try {
                    FileOutputStream(store.depthFile).use { out ->
                        map.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (error: Exception) {
                    false
                }
                map.recycle()
                Log.i(TAG, "depth map generated: $ok")
                ok
            }
        } catch (error: Exception) {
            Log.w(TAG, "depth generation failed: ${error.message}")
            store.depthFile.delete()
            false
        } finally {
            photo.recycle()
        }
    }

    @JavascriptInterface
    fun applyLive() {
        activity.runOnUiThread {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                intent.putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(activity, ClockVeilWallpaperService::class.java)
                )
                activity.startActivity(intent)
            } catch (error: Exception) {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        }
    }

    /** Flattens one frame of the same sandwich into a static wallpaper. */
    @JavascriptInterface
    fun applyStatic(): String {
        val photo = BitmapFactory.decodeFile(store.backgroundFile.absolutePath)
            ?: return fail("no photo saved yet")
        val depth = BitmapFactory.decodeFile(store.depthFile.absolutePath)
        val cutout = BitmapFactory.decodeFile(store.cutoutFile.absolutePath)
        val cfg = store.read()
        var composed: Bitmap? = null
        return try {
            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            composed = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(5, 6, 10))
            val renderer = LayerRenderer()
            renderer.drawBackground(
                canvas, photo, depth, 0f,
                cfg.optDouble("parallaxStrength", 0.55).toFloat(),
                cfg.optDouble("driftAmplitude", 1.0).toFloat(),
                cfg.optString("driftDirection", "horizontal"),
                width, height
            )
            renderer.drawDim(canvas, width, height, cfg.optDouble("bgDim", 18.0).toFloat() / 100f)
            ClockPainter().draw(canvas, cfg, width, height)
            renderer.drawCutout(
                canvas,
                cutout,
                photo,
                width,
                height,
                (cfg.optDouble("subjectScale", 100.0).toFloat() / 100f).coerceIn(0.2f, 3f)
            )

            try {
                FileOutputStream(store.staticFile).use { output.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } catch (error: Exception) {
                Log.w(TAG, "static snapshot not saved: ${error.message}")
            }
            WallpaperManager.getInstance(activity).setBitmap(output)
            notifyWeb("window.__deepfxStatic&&window.__deepfxStatic();")
            "ok"
        } catch (error: Exception) {
            fail(error.message ?: "static wallpaper failed")
        } finally {
            composed?.recycle()
            photo.recycle()
            depth?.recycle()
            cutout?.recycle()
        }
    }

    @JavascriptInterface
    fun hasContent(): Boolean = store.hasContent()

    @JavascriptInterface
    fun hasCutout(): Boolean = store.cutoutFile.isFile

    @JavascriptInterface
    fun getSettings(): String = store.read().toString()

    @JavascriptInterface
    fun log(message: String?) {
        Log.i(TAG, message ?: "")
    }

    private fun writeImage(target: File, base64: String?, format: Bitmap.CompressFormat): Boolean {
        if (base64.isNullOrBlank()) return false
        return try {
            val payload = if (base64.contains(",")) base64.substringAfterLast(",") else base64
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            val ok = try {
                FileOutputStream(target).use { bitmap.compress(format, 92, it) }
            } finally {
                bitmap.recycle()
            }
            ok
        } catch (error: Exception) {
            Log.w(TAG, "image rejected: ${error.message}")
            false
        }
    }

    private fun fail(message: String): String {
        notifyWeb("window.__depthFailed&&window.__depthFailed('" + escape(message) + "');")
        return "error:$message"
    }

    private fun notifyWeb(script: String) {
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")
        .replace("\r", " ")

    private companion object {
        const val TAG = "DeepFX"
    }
}
