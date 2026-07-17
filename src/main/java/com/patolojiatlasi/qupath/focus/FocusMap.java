package com.patolojiatlasi.qupath.focus;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A dwell/attention coverage grid for one slide.
 * <p>
 * Each viewport sample deposits a fixed total weight of 1, spread evenly over the grid cells the
 * visible region covers. A zoomed-in (small) view therefore heats its few cells much faster than a
 * zoomed-out browse covering many cells — mirroring "focused zoom-in counts more than zoom-out
 * browsing". Pure logic (no QuPath / JavaFX), so it is unit-testable.
 */
public final class FocusMap {

    private final int imageWidth;
    private final int imageHeight;
    private final int gridW;
    private final int gridH;
    private final float[] grid;
    private long sampleCount;

    /** Grid sized to the image aspect ratio, with the longer side at most {@code maxGridDim} cells. */
    public FocusMap(int imageWidth, int imageHeight, int maxGridDim) {
        if (imageWidth <= 0 || imageHeight <= 0)
            throw new IllegalArgumentException("image dimensions must be positive");
        int max = Math.max(2, maxGridDim);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        if (imageWidth >= imageHeight) {
            this.gridW = max;
            this.gridH = Math.max(1, Math.round((float) max * imageHeight / imageWidth));
        } else {
            this.gridH = max;
            this.gridW = Math.max(1, Math.round((float) max * imageWidth / imageHeight));
        }
        this.grid = new float[gridW * gridH];
    }

    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }
    public int getGridWidth() { return gridW; }
    public int getGridHeight() { return gridH; }
    public long getSampleCount() { return sampleCount; }
    public float[] getGrid() { return grid; }
    public boolean isEmpty() { return sampleCount == 0; }

    /**
     * Deposit one sample. The visible region (image-pixel coords) receives a total weight of 1,
     * spread evenly over the grid cells it covers (clamped to the image bounds). Returns false when
     * the region is empty or doesn't overlap the image.
     */
    public boolean deposit(double x, double y, double w, double h) {
        if (w <= 0 || h <= 0)
            return false;
        if (x + w <= 0 || y + h <= 0 || x >= imageWidth || y >= imageHeight)
            return false;
        int c0 = clamp((int) Math.floor(x / imageWidth * gridW), 0, gridW - 1);
        int c1 = clamp((int) Math.floor((x + w) / imageWidth * gridW), 0, gridW - 1);
        int r0 = clamp((int) Math.floor(y / imageHeight * gridH), 0, gridH - 1);
        int r1 = clamp((int) Math.floor((y + h) / imageHeight * gridH), 0, gridH - 1);
        int n = (c1 - c0 + 1) * (r1 - r0 + 1);
        float per = 1f / n;
        for (int r = r0; r <= r1; r++)
            for (int c = c0; c <= c1; c++)
                grid[r * gridW + c] += per;
        sampleCount++;
        return true;
    }

    public float max() {
        float m = 0f;
        for (float v : grid)
            if (v > m)
                m = v;
        return m;
    }

    public void clear() {
        Arrays.fill(grid, 0f);
        sampleCount = 0;
    }

    /**
     * Dwell (in accumulated sample-weight) at which a cell is considered fully "heated". Using an
     * <b>absolute</b> scale — rather than normalising to the running maximum — is what stops the very
     * first sample from painting everything at full intensity: a brief or zoomed-out glance deposits
     * a tiny weight per cell and stays near-transparent; only sustained <em>focused</em> viewing
     * (a few seconds) builds a cell up to full heat.
     */
    static final float FULL_HEAT = 4f;

    /**
     * Render the grid as an ARGB heatmap image (grid-sized; consumers scale it up). Cells at 0 are
     * fully transparent; a cell's intensity is its dwell relative to {@link #FULL_HEAT} (absolute,
     * not normalised to the max), and both colour (blue→cyan→green→yellow→red) and opacity grow with
     * it — so lightly-viewed areas stay see-through and never obscure the slide.
     */
    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < grid.length; i++) {
            float v = grid[i];
            if (v > 0)
                img.setRGB(i % gridW, i / gridW, heatColor(Math.min(1f, v / FULL_HEAT)));
        }
        return img; // untouched cells stay 0 = fully transparent
    }

    /** t in [0,1] → ARGB heat color (blue→cyan→green→yellow→red); alpha grows from 0 with t. */
    static int heatColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float r, g, b;
        if (t < 0.25f)      { r = 0;                    g = t / 0.25f;                b = 1; }
        else if (t < 0.5f)  { r = 0;                    g = 1;                        b = 1 - (t - 0.25f) / 0.25f; }
        else if (t < 0.75f) { r = (t - 0.5f) / 0.25f;   g = 1;                        b = 0; }
        else                { r = 1;                    g = 1 - (t - 0.75f) / 0.25f;  b = 0; }
        int alpha = (int) (170 * t);   // 0 (cold, transparent) .. 170 (hot, still see-through)
        return (alpha << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
