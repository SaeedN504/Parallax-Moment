package com.depth.live.wallpaper;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * Depth Clock live wallpaper.
 * Draws the wallpaper image exported by the editor and paints a LIVE ticking
 * clock (and optional date line) on top, matching the on-screen composition:
 *   bitmap  -> filesDir/depth-wallpaper.jpg
 *   config  -> filesDir/depth-settings.json
 *              { fmt24, showDate, clockX, clockY, clockScale, ink,
 *                layout, dateX, dateY, stretchX }
 */
public class DepthLiveWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new DepthEngine();
    }

    private static JSONObject loadSettings(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            //noinspection ResultOfMethodCallIgnored
            in.read(buf);
            in.close();
            return new JSONObject(new String(buf, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private class DepthEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Bitmap wall;
        private JSONObject cfg;
        private boolean visible;
        private long lastMinute = -1;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                drawFrame();
                if (visible) {
                    handler.postDelayed(this, 1000);
                }
            }
        };

        @Override public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            reloadAssets();
        }

        private void reloadAssets() {
            File f = new File(getFilesDir(), "depth-wallpaper.jpg");
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) {
                    if (wall != null && !wall.isRecycled()) wall.recycle();
                    wall = b;
                }
            }
            File s = new File(getFilesDir(), "depth-settings.json");
            if (s.exists()) {
                JSONObject j = loadSettings(s);
                if (j != null) cfg = j;
            }
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            drawFrame();
        }

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                reloadAssets();          // pick up newly applied wallpapers
                lastMinute = -1;
                handler.removeCallbacks(tick);
                tick.run();
            } else {
                handler.removeCallbacks(tick);
            }
        }

        @Override public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacks(tick);
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    render(c);
                }
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
                }
            }
        }

        private void render(Canvas c) {
            int W = c.getWidth(), H = c.getHeight();
            c.drawColor(Color.parseColor("#05060a"));

            /* wallpaper photo, cover-fit */
            if (wall != null && !wall.isRecycled()) {
                float sc = Math.max(W / (float) wall.getWidth(), H / (float) wall.getHeight());
                int dw = Math.round(wall.getWidth() * sc);
                int dh = Math.round(wall.getHeight() * sc);
                c.drawBitmap(wall, (W - dw) / 2f, (H - dh) / 2f, null);
            }

            Calendar now = Calendar.getInstance();
            boolean fmt24 = cfg != null && cfg.optBoolean("fmt24", false);
            boolean showDate = cfg != null && cfg.optBoolean("showDate", true);
            float cx = cfg != null ? (float) cfg.optDouble("clockX", 0.5) : 0.5f;
            float cy = cfg != null ? (float) cfg.optDouble("clockY", 0.40) : 0.40f;
            float scale = cfg != null ? (float) cfg.optDouble("clockScale", 0.48) : 0.48f;
            float stretchX = cfg != null ? (float) cfg.optDouble("stretchX", 1.0) : 1.0f;
            boolean stack = cfg != null && "stack".equals(cfg.optString("layout", "inline"));
            String ink = cfg != null ? cfg.optString("ink", "#ffffff") : "#ffffff";
            float dateX = cfg != null ? (float) cfg.optDouble("dateX", 0.5) : 0.5f;
            float dateY = cfg != null ? (float) cfg.optDouble("dateY", 0.63) : 0.63f;

            int h24 = now.get(Calendar.HOUR_OF_DAY);
            int mm = now.get(Calendar.MINUTE);
            String ap = "";
            int h = h24;
            if (!fmt24) { ap = h24 >= 12 ? "PM" : "AM"; h = h24 % 12; if (h == 0) h = 12; }

            float textSize = scale * H;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(parseColorSafe(ink, Color.WHITE));
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            p.setShadowLayer(textSize * 0.06f, 0, textSize * 0.02f, 0x99000000);

            float lineH = textSize * 0.95f;
            String[] lines;
            if (stack) {
                lines = new String[]{ pad2(h), pad2(mm) };
            } else {
                boolean colonOn = now.get(Calendar.SECOND) % 2 == 0;
                lines = new String[]{ pad2(h) + (colonOn ? ":" : " ") + pad2(mm) };
            }
            float blockH = lines.length * lineH;
            float baseline0 = cy * H - blockH / 2f + lineH * 0.78f;
            int saved = c.save();
            c.scale(stretchX, 1f, cx * W, 0f);
            for (int i = 0; i < lines.length; i++) {
                p.setTextSize(textSize);
                c.drawText(lines[i], cx * W, baseline0 + i * lineH, p);
            }
            if (!stack && !fmt24) {
                Paint ap1 = new Paint(p);
                ap1.setTextSize(textSize * 0.22f);
                ap1.setTextAlign(Paint.Align.LEFT);
                ap1.setShadowLayer(0, 0, 0, 0);
                c.drawText(ap, cx * W + textSize * 0.62f, cy * H - blockH / 2f + textSize * 0.78f, ap1);
            }
            c.restoreToCount(saved);

            if (showDate) {
                String d = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.US)
                        + ", " + pad2(now.get(Calendar.DAY_OF_MONTH)) + " "
                        + now.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.US)
                        + " " + now.get(Calendar.YEAR);
                d = d.toUpperCase(java.util.Locale.US);
                Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
                dp.setColor(parseColorSafe(ink, Color.WHITE));
                dp.setTextAlign(Paint.Align.CENTER);
                dp.setLetterSpacing(0.06f);
                dp.setTextSize(H * 0.022f);
                dp.setShadowLayer(dp.getTextSize() * 0.5f, 0, 0, 0x88000000);
                c.drawText(d, dateX * W, dateY * H, dp);
            }

            long minute = now.getTimeInMillis() / 60000L;
            lastMinute = minute; // ticking handled by 1s loop (keeps the colon blinking)
        }

        private String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

        private int parseColorSafe(String hex, int fallback) {
            try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
        }
    }
}
