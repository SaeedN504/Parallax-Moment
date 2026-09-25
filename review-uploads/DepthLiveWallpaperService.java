package com.parallaxmoment;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DepthLiveWallpaperService extends WallpaperService {

    private static final String TAG = "DepthLWP";
    private static final float PARALLAX_MAX_SHIFT_DP = 60f;

    @Override
    public Engine onCreateEngine() {
        return new DepthEngine();
    }

    class DepthEngine extends Engine implements Choreographer.FrameCallback {

        private final Choreographer choreographer = Choreographer.getInstance();
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final DepthParallaxRenderer parallaxRenderer = new DepthParallaxRenderer();

        private Bitmap bgBitmap;
        private Bitmap cutoutBitmap;
        private Bitmap depthBitmap;

        private int surfaceWidth;
        private int surfaceHeight;
        private float density = 1f;

        private float currentOffsetX = 0f;
        private float currentOffsetY = 0f;

        private float clockPosX = 0.5f;
        private float clockPosY = 0.5f;
        private int clockColor = Color.WHITE;
        private float clockSizeDp = 64f;
        private boolean showDate = true;
        private float glowRadiusDp = 0f;
        private int glowColor = Color.BLACK;

        private String clockText = "";
        private String dateText = "";

        private final File settingsFile;
        private long settingsLastMod = 0L;
        private boolean visible = false;

        DepthEngine() {
            settingsFile = new File(getFilesDir(), "settings.json");
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            loadAssets();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            density = getResources().getDisplayMetrics().density;
            loadSettings();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceWidth = width;
            surfaceHeight = height;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            if (visible) {
                choreographer.postFrameCallback(this);
            } else {
                choreographer.removeFrameCallback(this);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                                     float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            currentOffsetX = (xOffset - 0.5f) * 2f;
            currentOffsetY = (yOffset - 0.5f) * 2f;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!visible) return;
            drawFrame();
            choreographer.postFrameCallback(this);
        }

        private void drawFrame() {
            checkReloadSettings();
            updateTimeStrings();

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) return;

                canvas.drawColor(Color.BLACK);

                final float maxShiftPx = PARALLAX_MAX_SHIFT_DP * density;
                float normX = currentOffsetX;
                float normY = currentOffsetY;

                if (bgBitmap != null && !bgBitmap.isRecycled()) {
                    if (depthBitmap != null && !depthBitmap.isRecycled()) {
                        parallaxRenderer.draw(canvas, bgBitmap, depthBitmap,
                                surfaceWidth, surfaceHeight, normX, normY, maxShiftPx);
                    } else {
                        drawCenterCrop(canvas, bgBitmap);
                    }
                }

                drawClock(canvas);

                if (cutoutBitmap != null && !cutoutBitmap.isRecycled()) {
                    float fgShiftX = normX * maxShiftPx;
                    float fgShiftY = normY * (maxShiftPx * 0.5f);

                    float scale = Math.max((float) surfaceWidth / cutoutBitmap.getWidth(),
                            (float) surfaceHeight / cutoutBitmap.getHeight());
                    int drawW = Math.round(cutoutBitmap.getWidth() * scale);
                    int drawH = Math.round(cutoutBitmap.getHeight() * scale);
                    float left = (surfaceWidth - drawW) / 2f + fgShiftX;
                    float top = (surfaceHeight - drawH) / 2f + fgShiftY;

                    Rect src = new Rect(0, 0, cutoutBitmap.getWidth(), cutoutBitmap.getHeight());
                    RectF dst = new RectF(left, top, left + drawW, top + drawH);
                    canvas.drawBitmap(cutoutBitmap, src, dst, bitmapPaint);
                }

            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawCenterCrop(Canvas canvas, Bitmap bmp) {
            float scale = Math.max((float) canvas.getWidth() / bmp.getWidth(),
                    (float) canvas.getHeight() / bmp.getHeight());
            float w = bmp.getWidth() * scale;
            float h = bmp.getHeight() * scale;
            float left = (canvas.getWidth() - w) / 2f;
            float top = (canvas.getHeight() - h) / 2f;
            canvas.drawBitmap(bmp, null, new RectF(left, top, left + w, top + h), bitmapPaint);
        }

        private void drawClock(Canvas canvas) {
            float cx = surfaceWidth * clockPosX;
            float cy = surfaceHeight * clockPosY;

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(clockColor);
            textPaint.setTextSize(clockSizeDp * density);

            if (glowRadiusDp > 0f) {
                textPaint.setShadowLayer(glowRadiusDp * density, 0f, 0f, glowColor);
            } else {
                textPaint.clearShadowLayer();
            }

            canvas.drawText(clockText, cx, cy, textPaint);

            if (showDate && !dateText.isEmpty()) {
                float dateSize = clockSizeDp * density * 0.35f;
                textPaint.setTextSize(dateSize);
                float lineGap = dateSize * 1.3f;
                canvas.drawText(dateText, cx, cy + lineGap, textPaint);
            }
        }

        private void updateTimeStrings() {
            Date now = new Date();
            clockText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
            dateText = new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now);
        }

        private void loadAssets() {
            File dir = getFilesDir();
            File bg = new File(dir, "bg.jpg");
            File cutout = new File(dir, "cutout.png");
            File depth = new File(dir, "depth.png");

            if (bg.exists()) bgBitmap = BitmapFactory.decodeFile(bg.getAbsolutePath());
            if (cutout.exists()) cutoutBitmap = BitmapFactory.decodeFile(cutout.getAbsolutePath());
            if (depth.exists()) depthBitmap = BitmapFactory.decodeFile(depth.getAbsolutePath());
        }

        private void checkReloadSettings() {
            if (!settingsFile.exists()) return;
            long mod = settingsFile.lastModified();
            if (mod != settingsLastMod) loadSettings();
        }

        private void loadSettings() {
            try {
                String json = readFile(settingsFile);
                JSONObject s = new JSONObject(json);
                settingsLastMod = settingsFile.lastModified();

                clockPosX = (float) s.optDouble("clockPosX", 0.5);
                clockPosY = (float) s.optDouble("clockPosY", 0.5);
                clockColor = parseColor(s.optString("clockColor", "#FFFFFF"));
                clockSizeDp = (float) s.optDouble("clockSizeDp", 64.0);
                showDate = s.optBoolean("showDate", true);
                glowRadiusDp = (float) s.optDouble("glowRadiusDp", 0.0);
                glowColor = parseColor(s.optString("glowColor", "#000000"));
            } catch (Exception e) {
                Log.e(TAG, "Failed to load settings", e);
            }
        }

        private String readFile(File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toString(StandardCharsets.UTF_8.name());
            }
        }

        private int parseColor(String hex) {
            try {
                return Color.parseColor(hex);
            } catch (Exception e) {
                return Color.WHITE;
            }
        }

        @Override
        public void onDestroy() {
            visible = false;
            choreographer.removeFrameCallback(this);
            recycle(bgBitmap);
            recycle(cutoutBitmap);
            recycle(depthBitmap);
            super.onDestroy();
        }

        private void recycle(Bitmap b) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }
}
