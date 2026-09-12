package com.depth.live.wallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Lightweight depth-aware renderer for the live wallpaper path.
 * It uses vertical strips with depth-weighted horizontal offsets. This is
 * deliberately conservative: depth gives the scene motion, while the
 * original bitmap remains the visual source of truth.
 */
public final class DepthParallaxRenderer {
    private static final int STRIPS = 48;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public void draw(Canvas canvas, Bitmap photo, Bitmap depth, float phase, float strength) {
        if (photo == null || photo.isRecycled()) return;
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        float scale = Math.max(width / (float) photo.getWidth(), height / (float) photo.getHeight());
        int renderedWidth = Math.round(photo.getWidth() * scale);
        int renderedHeight = Math.round(photo.getHeight() * scale);
        float left = (width - renderedWidth) / 2f;
        float top = (height - renderedHeight) / 2f;

        if (depth == null || depth.isRecycled()) {
            canvas.drawBitmap(photo, null, new Rect(Math.round(left), Math.round(top),
                    Math.round(left + renderedWidth), Math.round(top + renderedHeight)), paint);
            return;
        }

        float maxOffset = Math.min(width * 0.035f, 42f) * Math.max(0f, Math.min(strength, 1f));
        int stripWidth = Math.max(1, renderedWidth / STRIPS);
        for (int i = 0; i < STRIPS; i++) {
            float u0 = i / (float) STRIPS;
            float u1 = (i + 1) / (float) STRIPS;
            int sx0 = Math.round(u0 * photo.getWidth());
            int sx1 = Math.max(sx0 + 1, Math.round(u1 * photo.getWidth()));
            float sample = sampleDepth(depth, (u0 + u1) * 0.5f, 0.5f);
            float centered = (sample - 0.5f) * 2f;
            float wave = (float) Math.sin(phase * Math.PI * 2.0);
            float offset = centered * maxOffset * wave;
            float dx0 = left + u0 * renderedWidth + offset;
            float dx1 = left + u1 * renderedWidth + offset;
            canvas.drawBitmap(photo,
                    new Rect(sx0, 0, sx1, photo.getHeight()),
                    new Rect(Math.round(dx0), Math.round(top), Math.round(dx1), Math.round(top + renderedHeight)),
                    paint);
        }
    }

    private float sampleDepth(Bitmap depth, float u, float v) {
        int x = Math.max(0, Math.min(depth.getWidth() - 1, Math.round(u * (depth.getWidth() - 1))));
        int y = Math.max(0, Math.min(depth.getHeight() - 1, Math.round(v * (depth.getHeight() - 1))));
        int pixel = depth.getPixel(x, y);
        return ((pixel >> 16) & 0xff) / 255f;
    }
}
