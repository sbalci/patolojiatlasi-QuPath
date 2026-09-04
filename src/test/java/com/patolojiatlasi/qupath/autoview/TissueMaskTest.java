package com.patolojiatlasi.qupath.autoview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TissueMaskTest {

    // An 8x8 thumbnail over an 800x800 image with gridMax 8 maps one thumbnail pixel to one grid
    // cell to a 100x100 block of image pixels -- so every assertion below is in whole cells.
    private static final int T = 8;
    private static final int IMG = 800;
    private static final int CELL = IMG / T;

    private static final int WHITE = rgb(255, 255, 255);
    private static final int BLACK = rgb(0, 0, 0);

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int[] filled(int colour) {
        int[] px = new int[T * T];
        java.util.Arrays.fill(px, colour);
        return px;
    }

    private static void paint(int[] px, int col0, int row0, int col1, int row1, int colour) {
        for (int r = row0; r <= row1; r++)
            for (int c = col0; c <= col1; c++)
                px[r * T + c] = colour;
    }

    private static TissueMask build(int[] px) {
        return build(px, 0.5);
    }

    private static TissueMask build(int[] px, double sensitivity) {
        return TissueMask.fromThumbnail(px, T, T, IMG, IMG, T, sensitivity);
    }

    /** Coverage of the whole of grid cell (col, row). */
    private static double cell(TissueMask mask, int col, int row) {
        return mask.coverage(col * CELL, row * CELL, CELL, CELL);
    }

    @Test
    void gridIsAspectPreservingWithLongestSideCapped() {
        TissueMask wide = TissueMask.fromThumbnail(new int[10 * 10], 10, 10, 1000, 500, 100, 0.5);
        assertEquals(100, wide.getGridWidth());
        assertEquals(50, wide.getGridHeight());

        TissueMask tall = TissueMask.fromThumbnail(new int[10 * 10], 10, 10, 500, 1000, 100, 0.5);
        assertEquals(50, tall.getGridWidth());
        assertEquals(100, tall.getGridHeight());
    }

    @Test
    void allWhiteThumbnailHasNoTissue() {
        TissueMask mask = build(filled(WHITE));
        assertTrue(mask.isEmpty());
        assertFalse(mask.isDarkBackground());
        assertEquals(0.0, mask.coverage(0, 0, IMG, IMG), 1e-9);
        assertEquals(0.0, cell(mask, 4, 4), 1e-9);
    }

    @Test
    void centredDarkBlobIsTissueAtTheCentreNotAtTheCorners() {
        int[] px = filled(WHITE);
        paint(px, 3, 3, 4, 4, BLACK);
        TissueMask mask = build(px);

        assertFalse(mask.isDarkBackground());
        assertEquals(1.0, cell(mask, 3, 3), 1e-9);
        assertEquals(1.0, cell(mask, 4, 4), 1e-9);
        assertEquals(0.0, cell(mask, 0, 0), 1e-9);
        assertEquals(0.0, cell(mask, 7, 7), 1e-9);
    }

    @Test
    void darkBackgroundPolarityIsDetectedAndInverted() {
        // The same geometry with the polarity flipped -- a fluorescence/dark-field slide. Detecting
        // the background from the border cells rather than assuming white is what makes this work.
        int[] px = filled(BLACK);
        paint(px, 3, 3, 4, 4, WHITE);
        TissueMask mask = build(px);

        assertTrue(mask.isDarkBackground());
        assertEquals(1.0, cell(mask, 3, 3), 1e-9);
        assertEquals(0.0, cell(mask, 0, 0), 1e-9);
    }

    @Test
    void saturatedColourCountsAsTissueOnAWhiteBackground() {
        // Pale eosin pink: luminance 218 is too bright to trip the luminance test against a white
        // background, but its saturation (0.20) is well above the 0.14 default tolerance.
        int[] px = filled(WHITE);
        paint(px, 2, 2, 2, 2, rgb(250, 200, 230));
        TissueMask mask = build(px);

        assertEquals(1.0, cell(mask, 2, 2), 1e-9, "saturated stain should count even when it is pale");
        assertEquals(0.0, cell(mask, 5, 5), 1e-9);
    }

    @Test
    void partialOverlapIsAreaWeighted() {
        int[] px = filled(WHITE);
        paint(px, 3, 3, 4, 4, BLACK);
        TissueMask mask = build(px);

        // A field spanning one blank cell (col 2) and one tissue cell (col 3) on row 3.
        double half = mask.coverage(2 * CELL, 3 * CELL, 2 * CELL, CELL);
        assertEquals(0.5, half, 1e-9);

        // Half of a tissue cell.
        double quarterOfTwo = mask.coverage(3 * CELL + CELL / 2.0, 3 * CELL, CELL, CELL);
        assertEquals(1.0, quarterOfTwo, 1e-9, "both halves here are tissue (cells 3 and 4)");
    }

    @Test
    void coverageDividesByTheUnclippedAreaSoOffSlideFieldsReadLower() {
        // Tissue against the left edge, then a field hanging half off the slide. The on-slide half
        // is entirely tissue, but the field is only half on the slide, so it must report 0.5.
        int[] px = filled(WHITE);
        paint(px, 0, 3, 0, 3, BLACK);
        TissueMask mask = build(px);

        assertEquals(1.0, cell(mask, 0, 3), 1e-9);
        assertEquals(0.5, mask.coverage(-CELL, 3 * CELL, 2 * CELL, CELL), 1e-9);
    }

    @Test
    void rectOutsideTheImageOrDegenerateIsZero() {
        int[] px = filled(WHITE);
        paint(px, 3, 3, 4, 4, BLACK);
        TissueMask mask = build(px);

        assertEquals(0.0, mask.coverage(-500, -500, 100, 100), 1e-9);
        assertEquals(0.0, mask.coverage(IMG + 10, IMG + 10, 100, 100), 1e-9);
        assertEquals(0.0, mask.coverage(0, 0, 0, 0), 1e-9);
        assertEquals(0.0, mask.coverage(300, 300, -50, 50), 1e-9);
    }

    @Test
    void higherSensitivityKeepsFainterTissue() {
        // A faint grey blob: luminance 225 against white. Too faint at sensitivity 0 (tolerance 45),
        // kept at sensitivity 1 (tolerance 12).
        int[] px = filled(WHITE);
        paint(px, 3, 3, 3, 3, rgb(225, 225, 225));

        assertEquals(0.0, cell(build(px, 0.0), 3, 3), 1e-9);
        assertEquals(1.0, cell(build(px, 1.0), 3, 3), 1e-9);
    }

    @Test
    void unusableInputYieldsAnEmptyMaskRatherThanThrowing() {
        assertTrue(TissueMask.fromThumbnail(null, T, T, IMG, IMG, T, 0.5).isEmpty());
        assertTrue(TissueMask.fromThumbnail(new int[4], 0, 0, IMG, IMG, T, 0.5).isEmpty());
        // Pixel array shorter than the declared thumbnail size.
        assertTrue(TissueMask.fromThumbnail(new int[4], T, T, IMG, IMG, T, 0.5).isEmpty());
        assertEquals(0.0, TissueMask.fromThumbnail(null, T, T, IMG, IMG, T, 0.5)
                .coverage(0, 0, IMG, IMG), 1e-9);
    }
}
