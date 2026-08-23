package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Pure-helper coverage for {@link QuizBrowseWindow}: {@code pickStop} (click hit-testing) and
 *  {@code stopRegionRoi} (per-stop geometry precedence). Both are static and UI-free, so they're
 *  tested directly with no QuPathGUI/JavaFX fixture. */
class QuizBrowseWindowTest {

    @Test
    void pickStopPrefersSmallestContainingRegion() {
        ROI big = ROIs.createRectangleROI(0, 0, 1000, 1000, ImagePlane.getDefaultPlane());
        ROI small = ROIs.createRectangleROI(400, 400, 100, 100, ImagePlane.getDefaultPlane());
        assertEquals(1, QuizBrowseWindow.pickStop(List.of(big, small), 450, 450));
        assertEquals(0, QuizBrowseWindow.pickStop(List.of(big, small), 10, 10));
        assertEquals(-1, QuizBrowseWindow.pickStop(List.of(big, small), 5000, 5000));
        assertEquals(-1, QuizBrowseWindow.pickStop(java.util.Arrays.asList(null, null), 10, 10)); // null-safe
    }

    @Test
    void stopRegionPrefersHighlightThenReferenceThenTarget() {
        QuizQuestion q = new QuizQuestion();
        assertNull(QuizBrowseWindow.stopRegionRoi(q));
        q.setTargetGeometryGeoJson(QuizGeometry.toGeoJson(ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane())));
        assertNotNull(QuizBrowseWindow.stopRegionRoi(q));
        q.setHighlightGeoJson(QuizGeometry.toGeoJson(ROIs.createRectangleROI(50, 50, 10, 10, ImagePlane.getDefaultPlane())));
        assertEquals(50.0, QuizBrowseWindow.stopRegionRoi(q).getBoundsX(), 1e-9); // highlight wins
    }
}
