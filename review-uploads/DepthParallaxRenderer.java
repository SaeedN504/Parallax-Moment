package com.parallaxmoment;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

public class DepthParallaxRenderer {

    private static final int STRIPS = 48;
    private static final int OVERLAP_PX = 2;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    public void draw(Canvas canvas, Bitmap bg, Bitmap depth,
                     int canvasW, int canvasH,
                     float offsetX, float offsetY,
                     float maxShiftPx) {

        if (bg == null || depth == null || canvas == null) return;

        float scale = Math.max((float) canvasW / bg.getWidth(), (float) canvasH / bg.getHeight());
        int drawW = Math.round(bg.getWidth() * scale);
        int drawH = Math.round(bg.getHeight() * scale);
        int baseLeft = (canvasW - drawW) / 2;
        int baseTop = (canvasH - drawH) / 2;

        int stripWidthSrc = Math.max(1, bg.getWidth() / STRIPS);
        int stripWidthDst = Math.max(1, drawW / STRIPS);

        for (int i = 0; i < STRIPS; i++) {
            int srcLeft = i * stripWidthSrc;
            int srcRight = (i == STRIPS - 1) ? bg.getWidth() : (i + 1) * stripWidthSrc;

            int depthStripW = Math.max(1, depth.getWidth() / STRIPS);
            int sampleX = Math.min(depth.getWidth() - 1, i * depthStripW + depthStripW / 2);
            int sampleY = Math.min(depth.getHeight() - 1, depth.getHeight() / 2);

            int pixel = depth.getPixel(sampleX, sampleY);
            int gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
            float norm = gray / 255f;

            float shiftX = offsetX * norm * maxShiftPx;
            float shiftY = offsetY * norm * (maxShiftPx * 0.5f);

            int dstLeft = baseLeft + Math.round(i * stripWidthDst + shiftX);
            int dstTop = baseTop + Math.round(shiftY);
            int dstRight = dstLeft + stripWidthDst + OVERLAP_PX;
            int dstBottom = dstTop + drawH;

            Rect src = new Rect(srcLeft, 0, srcRight, bg.getHeight());
            Rect dst = new Rect(dstLeft, dstTop, dstRight, dstBottom);

            canvas.drawBitmap(bg, src, dst, paint);
        }
    }
}
