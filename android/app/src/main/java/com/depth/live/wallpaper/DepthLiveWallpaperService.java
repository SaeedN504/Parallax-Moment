package com.depth.live.wallpaper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Live wallpaper engine:
 *   1. depth-warped background (mesh parallax driven by time + device tilt)
 *   2. optional dim layer
 *   3. rich clock layer (ClockPainter)
 *   4. subject cutout drawn on top, so the clock sits behind the subject
 *
 * The parallax responds to physical device tilt via the accelerometer, with a
 * low-pass filter and throttled redraws to keep battery use reasonable.
 */
public class DepthLiveWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new DepthEngine(); }
    private static JSONObject loadSettings(File f) { try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; in.read(b); return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (Exception e) { return null; } }

    private class DepthEngine extends Engine implements SensorEventListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DepthParallaxRenderer parallax = new DepthParallaxRenderer();
        private final ClockPainter clockPainter = new ClockPainter();
        private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dimPaint = new Paint();
        private Bitmap wall, depth, cutout; private JSONObject cfg; private boolean visible; private long startedAt;

        private SensorManager sensors;
        private float tiltX, tiltY;          // smoothed, [-1, 1]
        private float drawnTiltX, drawnTiltY;
        private long lastSensorDraw;

        private final Runnable tick = new Runnable() { @Override public void run() { drawFrame(); if (visible) handler.postDelayed(this, 1000); } };

        @Override public void onCreate(SurfaceHolder holder) { super.onCreate(holder); startedAt = System.currentTimeMillis(); sensors = (SensorManager) getSystemService(SENSOR_SERVICE); reloadAssets(); }
        private void reloadAssets() {
            Bitmap b = BitmapFactory.decodeFile(new File(getFilesDir(), "depth-wallpaper.jpg").getAbsolutePath()); if (b != null) { if (wall != null) wall.recycle(); wall = b; }
            Bitmap d = BitmapFactory.decodeFile(new File(getFilesDir(), "depth-map.png").getAbsolutePath()); if (d != null) { if (depth != null) depth.recycle(); depth = d; }
            Bitmap c = BitmapFactory.decodeFile(new File(getFilesDir(), "cutout.png").getAbsolutePath()); if (c != null) { if (cutout != null) cutout.recycle(); cutout = c; }
            JSONObject j = loadSettings(new File(getFilesDir(), "depth-settings.json")); if (j != null) cfg = j;
        }
        @Override public void onSurfaceChanged(SurfaceHolder h, int f, int w, int he) { super.onSurfaceChanged(h, f, w, he); drawFrame(); }
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder h) { super.onSurfaceRedrawNeeded(h); drawFrame(); }
        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                reloadAssets();
                startSensors();
                handler.removeCallbacks(tick);
                tick.run();
            } else {
                stopSensors();
                handler.removeCallbacks(tick);
            }
        }
        @Override public void onDestroy() { stopSensors(); handler.removeCallbacks(tick); if (wall != null) wall.recycle(); if (depth != null) depth.recycle(); if (cutout != null) cutout.recycle(); super.onDestroy(); }

        private void startSensors() {
            if (sensors == null) return;
            boolean tiltEnabled = cfg == null || cfg.optBoolean("parallaxTilt", true);
            if (!tiltEnabled) return;
            Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }

        private void stopSensors() {
            if (sensors != null) sensors.unregisterListener(this);
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
            // Gravity in the device frame. Portrait upright: ay ~ +9.81.
            float ax = event.values[0];
            float ay = event.values[1];
            // Roll drives horizontal parallax; pitch drives a subtler vertical one.
            float rawX = clampTilt(ax / 9.81f * 1.2f);
            float rawY = clampTilt((ay / 9.81f - 1f) * 0.8f);
            // Low-pass filter so the motion feels heavy and smooth.
            tiltX = 0.85f * tiltX + 0.15f * rawX;
            tiltY = 0.85f * tiltY + 0.15f * rawY;

            long now = System.currentTimeMillis();
            if (now - lastSensorDraw >= 50
                    && (Math.abs(tiltX - drawnTiltX) > 0.02f || Math.abs(tiltY - drawnTiltY) > 0.02f)) {
                lastSensorDraw = now;
                drawFrame();
            }
        }

        private float clampTilt(float v) { return Math.max(-1f, Math.min(1f, v)); }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        private void drawFrame() { Canvas c = null; try { c = getSurfaceHolder().lockCanvas(); if (c != null) render(c); } finally { if (c != null) try { getSurfaceHolder().unlockCanvasAndPost(c); } catch (Exception ignored) {} } }
        private void render(Canvas c) {
            int W = c.getWidth(), H = c.getHeight(); c.drawColor(Color.rgb(5,6,10));
            if (wall == null) return;
            float strength = cfg != null ? (float) cfg.optDouble("parallaxStrength", 0.55) : 0.55f;
            float phase = ((System.currentTimeMillis() - startedAt) % 6000L) / 6000f;
            float wave = (float) Math.sin(phase * Math.PI * 2.0);
            drawnTiltX = tiltX; drawnTiltY = tiltY;
            parallax.draw(c, wall, depth, phase, strength, tiltX, tiltY);

            float dim = cfg != null ? (float) cfg.optDouble("bgDim", 0.0) / 100f : 0f;
            int dimAlpha = (int) (Math.max(0f, Math.min(dim, 0.85f)) * 255f);
            if (dimAlpha > 0) { dimPaint.setColor(Color.argb(dimAlpha, 0, 0, 0)); c.drawRect(0, 0, W, H, dimPaint); }

            clockPainter.draw(c, cfg != null ? cfg : new JSONObject(), W, H);

            if (cutout != null && !cutout.isRecycled()) {
                // Tracks the same depth/wave/tilt displacement as the mesh, so
                // the subject stays glued to the photo behind the clock.
                RectF frame = parallax.cutoutFrame(wall, depth, W, H,
                        parallax.maxOffset(W, strength), wave, tiltX, tiltY);
                c.drawBitmap(cutout, null, frame, cutoutPaint);
            }
        }
    }
}
