package com.patolojiatlasi.qupath.autoview;

import java.util.Arrays;

/**
 * A coarse "is there anything here?" grid, built once per slide from its thumbnail, so an automated
 * sweep can skip blank glass instead of spending minutes gliding across it.
 * <p>
 * Pure: takes packed ARGB pixels rather than a {@code BufferedImage}, so it is unit-testable with a
 * plain {@code int[]} and no AWT — the same pure-kernel convention as {@code focus.FocusMap}.
 * <p>
 * Background polarity is <em>detected</em>, not assumed: the median luminance of the border cells
 * decides whether this is bright-field (dark tissue on white glass) or dark-field/fluorescence
 * (bright signal on black). That is the whole reason this works on more than H&amp;E.
 * <p>
 * This is deliberately a coarse screen, not a segmentation. A false positive only costs a little
 * sweep time; a false negative skips tissue, so the thresholds lean towards keeping things.
 */
public final class TissueMask {

    /** Longest side of the coarse mask grid, in cells. */
    public static final int DEFAULT_GRID_MAX = 192;

    /** Luminance tolerance at sensitivity 0 (strictest) and 1 (loosest), in 0-255 units. */
    private static final double LUM_TOL_MAX = 45;
    private static final double LUM_TOL_MIN = 12;

    /** Saturation tolerance at sensitivity 0 (strictest) and 1 (loosest). */
    private static final double SAT_MAX = 0.22;
    private static final double SAT_MIN = 0.06;

    /** Border luminance below this reads as a dark-background image. */
    private static final double DARK_BACKGROUND_L = 128;

    private final int imageWidth;
    private final int imageHeight;
    private final int cols;
    private final int rows;
    private final float[] tissue;
    private final boolean darkBackground;

    private TissueMask(int imageWidth, int imageHeight, int cols, int rows,
                       float[] tissue, boolean darkBackground) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.cols = cols;
        this.rows = rows;
        this.tissue = tissue;
        this.darkBackground = darkBackground;
    }

    /**
     * Build a mask from a slide thumbnail.
     *
     * @param argb        packed ARGB pixels, row-major, {@code thumbW * thumbH} long
     * @param sensitivity 0..1; higher keeps fainter tissue. 0.5 is the default.
     * @return a mask; one reporting no tissue anywhere if the input is unusable (never {@code null})
     */
    public static TissueMask fromThumbnail(int[] argb, int thumbW, int thumbH,
                                           int imageWidth, int imageHeight,
                                           int gridMax, double sensitivity) {

        int max = gridMax > 0 ? gridMax : DEFAULT_GRID_MAX;
        int iw = Math.max(1, imageWidth);
        int ih = Math.max(1, imageHeight);

        // Aspect-preserving grid, longest side capped -- matching FocusMap's sizing.
        int cols;
        int rows;
        if (iw >= ih) {
            cols = max;
            rows = Math.max(1, (int) Math.round(max * (double) ih / iw));
        } else {
            rows = max;
            cols = Math.max(1, (int) Math.round(max * (double) iw / ih));
        }

        float[] tissue = new float[cols * rows];
        if (argb == null || thumbW <= 0 || thumbH <= 0 || argb.length < thumbW * thumbH)
            return new TissueMask(iw, ih, cols, rows, tissue, false);

        // Box-downsample the thumbnail onto the grid.
        long[] sumR = new long[cols * rows];
        long[] sumG = new long[cols * rows];
        long[] sumB = new long[cols * rows];
        int[] count = new int[cols * rows];
        for (int py = 0; py < thumbH; py++) {
            int r = (int) ((long) py * rows / thumbH);
            if (r >= rows)
                r = rows - 1;
            int rowBase = r * cols;
            int pixBase = py * thumbW;
            for (int px = 0; px < thumbW; px++) {
                int c = (int) ((long) px * cols / thumbW);
                if (c >= cols)
                    c = cols - 1;
                int cell = rowBase + c;
                int p = argb[pixBase + px];
                sumR[cell] += (p >> 16) & 0xFF;
                sumG[cell] += (p >> 8) & 0xFF;
                sumB[cell] += p & 0xFF;
                count[cell]++;
            }
        }

        double[] lum = new double[cols * rows];
        double[] sat = new double[cols * rows];
        for (int i = 0; i < lum.length; i++) {
            if (count[i] == 0) {
                lum[i] = Double.NaN;
                continue;
            }
            double r = (double) sumR[i] / count[i];
            double g = (double) sumG[i] / count[i];
            double b = (double) sumB[i] / count[i];
            lum[i] = 0.299 * r + 0.587 * g + 0.114 * b;
            double hi = Math.max(r, Math.max(g, b));
            double lo = Math.min(r, Math.min(g, b));
            sat[i] = hi <= 0 ? 0 : (hi - lo) / hi;
        }

        double backgroundL = borderMedianLuminance(lum, cols, rows);
        boolean dark = backgroundL < DARK_BACKGROUND_L;

        double s = clamp(sensitivity, 0, 1);
        double lumTol = LUM_TOL_MAX - s * (LUM_TOL_MAX - LUM_TOL_MIN);
        double satTol = SAT_MAX - s * (SAT_MAX - SAT_MIN);

        for (int i = 0; i < lum.length; i++) {
            if (Double.isNaN(lum[i]))
                continue;
            boolean byLuminance = dark
                    ? lum[i] >= backgroundL + lumTol
                    : lum[i] <= backgroundL - lumTol;
            if (byLuminance || sat[i] >= satTol)
                tissue[i] = 1f;
        }

        return new TissueMask(iw, ih, cols, rows, tissue, dark);
    }

    /**
     * Fraction of the given image-pixel rectangle that is tissue, in 0..1.
     * <p>
     * The result is divided by the rectangle's <em>unclipped</em> area, so a field hanging half off
     * the slide can never report more than half coverage however dense the on-slide half is.
     */
    public double coverage(double x, double y, double w, double h) {
        double rectArea = w * h;
        if (!(rectArea > 0))
            return 0;

        double x0 = Math.max(x, 0);
        double y0 = Math.max(y, 0);
        double x1 = Math.min(x + w, imageWidth);
        double y1 = Math.min(y + h, imageHeight);
        if (!(x1 > x0) || !(y1 > y0))
            return 0;

        double cellW = (double) imageWidth / cols;
        double cellH = (double) imageHeight / rows;

        int cStart = clampInt((int) Math.floor(x0 / cellW), 0, cols - 1);
        int cEnd = clampInt((int) Math.ceil(x1 / cellW) - 1, 0, cols - 1);
        int rStart = clampInt((int) Math.floor(y0 / cellH), 0, rows - 1);
        int rEnd = clampInt((int) Math.ceil(y1 / cellH) - 1, 0, rows - 1);

        double acc = 0;
        for (int r = rStart; r <= rEnd; r++) {
            double overlapY = Math.min(y1, (r + 1) * cellH) - Math.max(y0, r * cellH);
            if (overlapY <= 0)
                continue;
            int rowBase = r * cols;
            for (int c = cStart; c <= cEnd; c++) {
                float t = tissue[rowBase + c];
                if (t <= 0)
                    continue;
                double overlapX = Math.min(x1, (c + 1) * cellW) - Math.max(x0, c * cellW);
                if (overlapX <= 0)
                    continue;
                acc += t * overlapX * overlapY;
            }
        }
        return acc / rectArea;
    }

    /** True when the slide reads as bright signal on a dark background rather than the reverse. */
    public boolean isDarkBackground() {
        return darkBackground;
    }

    public int getGridWidth() {
        return cols;
    }

    public int getGridHeight() {
        return rows;
    }

    /** True when no cell was classified as tissue -- the caller should then not filter at all. */
    public boolean isEmpty() {
        for (float t : tissue) {
            if (t > 0)
                return false;
        }
        return true;
    }

    /** The raw grid, for tests and diagnostics. */
    float[] getGrid() {
        return tissue;
    }

    /**
     * Median luminance of the outermost ring of cells. A median rather than a mean so that tissue
     * running off the edge of the slide cannot drag the background estimate towards it.
     */
    private static double borderMedianLuminance(double[] lum, int cols, int rows) {
        double[] border = new double[2 * cols + 2 * rows];
        int n = 0;
        for (int c = 0; c < cols; c++) {
            n = addIfFinite(border, n, lum[c]);
            n = addIfFinite(border, n, lum[(rows - 1) * cols + c]);
        }
        for (int r = 0; r < rows; r++) {
            n = addIfFinite(border, n, lum[r * cols]);
            n = addIfFinite(border, n, lum[r * cols + cols - 1]);
        }
        if (n == 0)
            return 255;
        double[] used = Arrays.copyOf(border, n);
        Arrays.sort(used);
        return n % 2 == 1 ? used[n / 2] : (used[n / 2 - 1] + used[n / 2]) / 2.0;
    }

    private static int addIfFinite(double[] buf, int n, double v) {
        if (Double.isNaN(v))
            return n;
        buf[n] = v;
        return n + 1;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v))
            return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
