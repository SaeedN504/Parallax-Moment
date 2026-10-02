package com.depth.live.wallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Depth-aware renderer for the live wallpaper path.
 *
 * Instead of hard-edged vertical strips (which produced visible slice/tear
 * artifacts), the photo is drawn through {@link Canvas#drawBitmapMesh} on a
 * grid whose vertices are displaced by a smoothed depth value. Neighbouring
 * vertices share positions, so the warp is continuous and the photo never
 * appears sliced.
 *
 * Motion combines two sources:
 *   - a slow time-based sine wave, so the scene lives even when the phone
 *     is perfectly still
 *   - device tilt (passed in as tiltX/tiltY in [-1, 1]), so the parallax
 *     responds to how the user physically holds the phone
 *
 * The photo is overscanned so a displaced vertex can never expose the
 * background on either axis.
 */
public final class DepthParallaxRenderer {
    private static final int COLS = 48;
    private static final int ROWS = 32;
    private static final int BLUR_RADIUS = 2;
    private static final int BLUR_PASSES = 2;
    private static final int GRID_W = COLS + 1;
    private static final int GRID_H = ROWS + 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] vertices = new float[GRID_W * GRID_H * 2];

    public void draw(Canvas canvas, Bitmap photo, Bitmap depth, float phase, float strength) {
        draw(canvas, photo, depth, phase, strength, 0f, 0f);
    }

    public void draw(Canvas canvas, Bitmap photo, Bitmap depth, float phase, float strength,
                     float tiltX, float tiltY) {
        if (photo == null || photo.isRecycled()) return;
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        float maxOffset = maxOffset(width, strength);
        float clampedTiltX = Math.max(-1f, Math.min(1f, tiltX));
        float clampedTiltY = Math.max(-1f, Math.min(1f, tiltY));

        if (depth == null || depth.isRecycled() || depth.getWidth() < 2 || depth.getHeight() < 2) {
            drawFlat(canvas, photo, width, height);
            return;
        }

        RectF frame = frameOf(photo, width, height, maxOffset);

        float[] grid = depthField(depth);
        float wave = (float) Math.sin(phase * Math.PI * 2.0);
        for (int j = 0; j <= ROWS; j++) {
            float v = j / (float) ROWS;
            for (int i = 0; i <= COLS; i++) {
                float u = i / (float) COLS;
                float centered = (grid[j * GRID_W + i] - 0.5f) * 2f;
                float offsetX = centered * maxOffset * (0.5f * wave + clampedTiltX);
                float offsetY = centered * maxOffset * 0.35f * clampedTiltY;
                int index = (j * GRID_W + i) * 2;
                vertices[index] = frame.left + u * frame.width() + offsetX;
                vertices[index + 1] = frame.top + v * frame.height() + offsetY;
            }
        }
        canvas.drawBitmapMesh(photo, COLS, ROWS, vertices, 0, null, 0, paint);
    }

    /** Peak horizontal displacement for the given settings, clamped for safety. */
    public float maxOffset(int width, float strength) {
        return Math.min(width * 0.035f, 42f) * Math.max(0f, Math.min(strength, 1f));
    }

    /**
     * Base (undisplaced) cover-fit frame, with enough bleed on every moving
     * axis that vertex displacement can never expose the background. NOTE:
     * this is the base frame only — background mesh vertices are additionally
     * displaced by depth x (0.5*wave + tilt); the cutout layer must use
     * {@link #cutoutFrame} to track that displacement instead of this frame.
     */
    public RectF frameOf(Bitmap photo, int width, int height, float maxOffset) {
        // Horizontal displacement reaches 1.5x maxOffset (0.5 wave + 1.0 tilt);
        // vertical reaches 0.35x maxOffset.
        float scale = Math.max((width + 3f * maxOffset) / photo.getWidth(),
                (height + 0.7f * maxOffset) / photo.getHeight());
        float drawWidth = photo.getWidth() * scale;
        float drawHeight = photo.getHeight() * scale;
        float left = (width - drawWidth) / 2f;
        float top = (height - drawHeight) / 2f;
        return new RectF(left, top, left + drawWidth, top + drawHeight);
    }

    /**
     * Frame for the subject cutout layer, translated by the same displacement
     * formula the mesh vertices use, sampled at the cutout's representative
     * point (frame center). Keeps the subject glued to the photo under the
     * idle wave and device tilt. When the depth map is missing or degenerate,
     * the background falls back to {@link #drawFlat} whose frame equals
     * {@code frameOf(photo, width, height, 0f)} — the same frame is returned
     * here so both layers agree in degraded mode.
     */
    public RectF cutoutFrame(Bitmap photo, Bitmap depth, int width, int height,
                             float maxOffset, float wave, float tiltX, float tiltY) {
        if (depth == null || depth.isRecycled() || depth.getWidth() < 2 || depth.getHeight() < 2) {
            return frameOf(photo, width, height, 0f);
        }
        RectF frame = frameOf(photo, width, height, maxOffset);
        // Photo-space position of the canvas center within the overscanned frame.
        float u = (width / 2f - frame.left) / frame.width();
        float v = (height / 2f - frame.top) / frame.height();
        // Sample the same blurred depth grid the mesh vertices use — sampling
        // the raw bitmap would drift the subject across sharp depth edges.
        float centered = (sampleGridBilinear(depthField(depth), u * COLS, v * ROWS) - 0.5f) * 2f;
        float clampedTiltX = Math.max(-1f, Math.min(1f, tiltX));
        float clampedTiltY = Math.max(-1f, Math.min(1f, tiltY));
        RectF out = new RectF(frame);
        out.offset(centered * maxOffset * (0.5f * wave + clampedTiltX),
                centered * maxOffset * 0.35f * clampedTiltY);
        return out;
    }

    private void drawFlat(Canvas canvas, Bitmap photo, int width, int height) {
        // Shares the exact frame the cutout uses in degraded mode.
        canvas.drawBitmap(photo, null, frameOf(photo, width, height, 0f), paint);
    }

    /**
     * Immutable snapshot of the built depth grid, published atomically through
     * the volatile {@link #depthCache} field. Readers either see the previous
     * complete snapshot or the new one — never torn state — so concurrent
     * depthField() callers (draw() and cutoutFrame()) cannot observe a partial
     * grid; the worst case under contention is a redundant rebuild. This does
     * NOT make the renderer as a whole thread-safe: draw() mutates the shared
     * reusable {@code vertices} array (paint is shared read-only), so render
     * calls must stay on a single thread.
     */
    private static final class DepthCache {
        final float[] grid;
        final Bitmap source;
        final int generationId;

        DepthCache(float[] grid, Bitmap source, int generationId) {
            this.grid = grid;
            this.source = source;
            this.generationId = generationId;
        }
    }

    private volatile DepthCache depthCache;

    /**
     * Depth sampled bilinearly at every mesh vertex, then box-blurred over the
     * grid so the motion field stays smooth. Cached per depth bitmap.
     */
    private float[] depthField(Bitmap depth) {
        int generationId = depth.getGenerationId();
        // generationId alone is not a safe key: a freshly decoded bitmap also
        // starts at 0, so the identity of the source bitmap is checked too.
        DepthCache cache = depthCache;
        if (cache != null && cache.source == depth && cache.generationId == generationId) {
            return cache.grid;
        }

        float[] grid = new float[GRID_W * GRID_H];
        for (int j = 0; j <= ROWS; j++) {
            float v = j / (float) ROWS;
            for (int i = 0; i <= COLS; i++) {
                grid[j * GRID_W + i] = sampleBilinear(depth, i / (float) COLS, v);
            }
        }
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            blurX(grid);
            blurY(grid);
        }
        depthCache = new DepthCache(grid, depth, generationId);
        return grid;
    }

    private float sampleBilinear(Bitmap depth, float u, float v) {
        float x = Math.max(0f, Math.min(depth.getWidth() - 1f, u * (depth.getWidth() - 1)));
        float y = Math.max(0f, Math.min(depth.getHeight() - 1f, v * (depth.getHeight() - 1)));
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, depth.getWidth() - 1);
        int y1 = Math.min(y0 + 1, depth.getHeight() - 1);
        float fx = x - x0;
        float fy = y - y0;
        float d00 = red(depth, x0, y0);
        float d10 = red(depth, x1, y0);
        float d01 = red(depth, x0, y1);
        float d11 = red(depth, x1, y1);
        return (d00 * (1 - fx) + d10 * fx) * (1 - fy) + (d01 * (1 - fx) + d11 * fx) * fy;
    }

    private float red(Bitmap depth, int x, int y) {
        return ((depth.getPixel(x, y) >> 16) & 0xff) / 255f;
    }

    /**
     * Bilinear interpolation over the blurred depth grid in grid coordinates
     * (x in [0, COLS], y in [0, ROWS]) — the same field the mesh vertices
     * displace with, so the cutout tracks the background exactly.
     */
    private static float sampleGridBilinear(float[] grid, float gx, float gy) {
        float x = Math.max(0f, Math.min(COLS, gx));
        float y = Math.max(0f, Math.min(ROWS, gy));
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, COLS);
        int y1 = Math.min(y0 + 1, ROWS);
        float fx = x - x0;
        float fy = y - y0;
        float d00 = grid[y0 * GRID_W + x0];
        float d10 = grid[y0 * GRID_W + x1];
        float d01 = grid[y1 * GRID_W + x0];
        float d11 = grid[y1 * GRID_W + x1];
        return (d00 * (1 - fx) + d10 * fx) * (1 - fy) + (d01 * (1 - fx) + d11 * fx) * fy;
    }

    /** Clamped box blur along X; the grid is row-major with width GRID_W. */
    private static void blurX(float[] grid) {
        float[] source = grid.clone();
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                float sum = 0f;
                for (int k = -BLUR_RADIUS; k <= BLUR_RADIUS; k++) {
                    int n = Math.max(0, Math.min(GRID_W - 1, x + k));
                    sum += source[y * GRID_W + n];
                }
                grid[y * GRID_W + x] = sum / (2 * BLUR_RADIUS + 1);
            }
        }
    }

    /** Clamped box blur along Y; the grid is row-major with width GRID_W. */
    private static void blurY(float[] grid) {
        float[] source = grid.clone();
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                float sum = 0f;
                for (int k = -BLUR_RADIUS; k <= BLUR_RADIUS; k++) {
                    int n = Math.max(0, Math.min(GRID_H - 1, y + k));
                    sum += source[n * GRID_W + x];
                }
                grid[y * GRID_W + x] = sum / (2 * BLUR_RADIUS + 1);
            }
        }
    }
}
