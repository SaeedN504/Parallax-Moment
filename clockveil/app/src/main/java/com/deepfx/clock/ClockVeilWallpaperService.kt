package com.deepfx.clock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import org.json.JSONObject
import java.io.File

/**
 * Live wallpaper engine for Deep FX Clock.
 *
 * Frame loop: Choreographer driven and throttled to the configured fps while the
 * wallpaper is visible, and fully stopped when it is not, so an idle home screen
 * costs nothing.
 *
 * Render order is background (drifting) -> clock (fixed anchor) -> cutout (on top),
 * which is what lets the clock sit behind the subject instead of on top of it.
 */
class ClockVeilWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DeepFxEngine()

    private inner class DeepFxEngine : Engine(), Choreographer.FrameCallback {
        private val renderer = LayerRenderer()
        private val clockPainter = ClockPainter()
        private val store = SettingsStore(applicationContext)
        private val choreographer: Choreographer = Choreographer.getInstance()

        private var background: Bitmap? = null
        private var cutout: Bitmap? = null
        private var depth: Bitmap? = null
        private var cfg: JSONObject = JSONObject()

        private var visible = false
        private var frameScheduled = false
        private var startedAt = 0L
        private var lastFrameAt = 0L
        private var lastSettingsCheck = 0L
        private var settingsStamp = -1L
        private var frameCount = 0L

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            startedAt = System.currentTimeMillis()
            reload()
        }

        private fun reload() {
            val stamp = store.settingsFile.lastModified()
            if (stamp != settingsStamp) {
                settingsStamp = stamp
                cfg = store.read()
            }
            background?.recycle()
            cutout?.recycle()
            depth?.recycle()
            background = decode(store.backgroundFile)
            cutout = decode(store.cutoutFile)
            depth = decode(store.depthFile)
            renderer.reset()
        }

        private fun decode(file: File): Bitmap? = try {
            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (error: Exception) {
            null
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                reload()
                startedAt = System.currentTimeMillis()
                lastFrameAt = 0L
                scheduleNextFrame()
            } else {
                choreographer.removeFrameCallback(this)
                frameScheduled = false
                Log.i(TAG, "rendering paused after $frameCount frames")
            }
        }

        override fun onDestroy() {
            choreographer.removeFrameCallback(this)
            background?.recycle()
            cutout?.recycle()
            depth?.recycle()
            background = null
            cutout = null
            depth = null
            super.onDestroy()
        }

        private fun scheduleNextFrame() {
            if (!visible || frameScheduled) return
            frameScheduled = true
            choreographer.postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!visible) return

            val now = System.currentTimeMillis()
            if (now - lastSettingsCheck > SETTINGS_POLL_MS) {
                lastSettingsCheck = now
                val stamp = store.settingsFile.lastModified()
                if (stamp != settingsStamp) reload()
            }

            if (now - lastFrameAt >= frameIntervalMs()) {
                lastFrameAt = now
                drawFrame()
            }
            scheduleNextFrame()
        }

        private fun frameIntervalMs(): Long = (1000L / cfg.optInt("fps", 30).coerceIn(10, 60))

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    render(canvas)
                    frameCount++
                }
            } catch (error: Exception) {
                Log.w(TAG, "frame skipped: ${error.message}")
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (error: Exception) {
                        Log.w(TAG, "unlock failed: ${error.message}")
                    }
                }
            }
        }

        private fun render(canvas: Canvas) {
            val width = canvas.width
            val height = canvas.height
            canvas.drawColor(Color.rgb(5, 6, 10))

            val photo = background ?: return
            val speed = cfg.optDouble("driftSpeed", 1.0).toFloat().coerceIn(0.05f, 4f)
            val periodMs = (16000f / speed).toLong().coerceAtLeast(2000L)
            val phase = ((System.currentTimeMillis() - startedAt) % periodMs) / periodMs.toFloat()
            val strength = cfg.optDouble("parallaxStrength", 0.55).toFloat().coerceIn(0f, 2f)
            val amplitude = cfg.optDouble("driftAmplitude", 1.0).toFloat().coerceIn(0f, 3f)
            val direction = cfg.optString("driftDirection", "horizontal")

            renderer.drawBackground(canvas, photo, depth, phase, strength, amplitude, direction, width, height)
            renderer.drawDim(canvas, width, height, cfg.optDouble("bgDim", 18.0).toFloat() / 100f)
            clockPainter.draw(canvas, cfg, width, height)
            renderer.drawCutout(
                canvas,
                cutout,
                photo,
                width,
                height,
                (cfg.optDouble("subjectScale", 100.0).toFloat() / 100f).coerceIn(0.2f, 3f)
            )
        }
    }

    private companion object {
        const val TAG = "DeepFX"
        const val SETTINGS_POLL_MS = 1500L
    }
}
