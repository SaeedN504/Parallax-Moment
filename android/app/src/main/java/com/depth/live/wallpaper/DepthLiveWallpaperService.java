package com.depth.live.wallpaper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

public class DepthLiveWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new DepthEngine(); }
    private static JSONObject loadSettings(File f) { try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; in.read(b); return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (Exception e) { return null; } }

    private class DepthEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DepthParallaxRenderer parallax = new DepthParallaxRenderer();
        private Bitmap wall, depth; private JSONObject cfg; private boolean visible; private long startedAt;
        private final Runnable tick = new Runnable() { @Override public void run() { drawFrame(); if (visible) handler.postDelayed(this, 1000); } };
        @Override public void onCreate(SurfaceHolder holder) { super.onCreate(holder); startedAt = System.currentTimeMillis(); reloadAssets(); }
        private void reloadAssets() {
            Bitmap b = BitmapFactory.decodeFile(new File(getFilesDir(), "depth-wallpaper.jpg").getAbsolutePath()); if (b != null) { if (wall != null) wall.recycle(); wall = b; }
            Bitmap d = BitmapFactory.decodeFile(new File(getFilesDir(), "depth-map.png").getAbsolutePath()); if (d != null) { if (depth != null) depth.recycle(); depth = d; }
            JSONObject j = loadSettings(new File(getFilesDir(), "depth-settings.json")); if (j != null) cfg = j;
        }
        @Override public void onSurfaceChanged(SurfaceHolder h, int f, int w, int he) { super.onSurfaceChanged(h, f, w, he); drawFrame(); }
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder h) { super.onSurfaceRedrawNeeded(h); drawFrame(); }
        @Override public void onVisibilityChanged(boolean v) { visible = v; if (v) { reloadAssets(); handler.removeCallbacks(tick); tick.run(); } else handler.removeCallbacks(tick); }
        @Override public void onDestroy() { handler.removeCallbacks(tick); if (wall != null) wall.recycle(); if (depth != null) depth.recycle(); super.onDestroy(); }
        private void drawFrame() { Canvas c = null; try { c = getSurfaceHolder().lockCanvas(); if (c != null) render(c); } finally { if (c != null) try { getSurfaceHolder().unlockCanvasAndPost(c); } catch (Exception ignored) {} } }
        private void render(Canvas c) {
            int W = c.getWidth(), H = c.getHeight(); c.drawColor(Color.rgb(5,6,10));
            float strength = cfg != null ? (float) cfg.optDouble("parallaxStrength", 0.55) : 0.55f;
            float phase = ((System.currentTimeMillis() - startedAt) % 6000L) / 6000f;
            parallax.draw(c, wall, depth, phase, strength);
            drawClock(c, W, H);
        }
        private void drawClock(Canvas c, int W, int H) {
            boolean fmt24 = cfg != null && cfg.optBoolean("fmt24", false); boolean showDate = cfg == null || cfg.optBoolean("showDate", true);
            float cx = cfg != null ? (float) cfg.optDouble("clockX", .5) : .5f; float cy = cfg != null ? (float) cfg.optDouble("clockY", .4) : .4f; float scale = cfg != null ? (float) cfg.optDouble("clockScale", .48) : .48f; String ink = cfg != null ? cfg.optString("ink", "#ffffff") : "#ffffff";
            Calendar now = Calendar.getInstance(); int h24 = now.get(Calendar.HOUR_OF_DAY), mm = now.get(Calendar.MINUTE); String ap = ""; int h = h24; if (!fmt24) { ap = h24 >= 12 ? "PM" : "AM"; h = h24 % 12; if (h == 0) h = 12; }
            float size = scale * H; Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(parseColorSafe(ink, Color.WHITE)); p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(size); p.setShadowLayer(size*.06f,0,size*.02f,0x99000000);
            String text = pad2(h) + (now.get(Calendar.SECOND)%2==0 ? ":" : " ") + pad2(mm); c.drawText(text, cx*W, cy*H, p);
            if (!fmt24) { Paint apPaint = new Paint(p); apPaint.setTextSize(size*.22f); apPaint.setTextAlign(Paint.Align.LEFT); apPaint.setShadowLayer(0,0,0,0); c.drawText(ap, cx*W+size*.62f, cy*H, apPaint); }
            if (showDate) { String d = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.US)+", "+pad2(now.get(Calendar.DAY_OF_MONTH))+" "+now.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.US)+" "+now.get(Calendar.YEAR); Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG); dp.setColor(parseColorSafe(ink, Color.WHITE)); dp.setTextAlign(Paint.Align.CENTER); dp.setTextSize(H*.022f); dp.setLetterSpacing(.06f); c.drawText(d.toUpperCase(java.util.Locale.US), (float)(cfg != null ? cfg.optDouble("dateX",.5) : .5)*W, (float)(cfg != null ? cfg.optDouble("dateY",.63) : .63)*H, dp); }
        }
        private String pad2(int n) { return n<10 ? "0"+n : String.valueOf(n); }
        private int parseColorSafe(String v, int f) { try { return Color.parseColor(v); } catch (Exception e) { return f; } }
    }
}
