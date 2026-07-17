package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class QuizGeometryTest {

    @Test
    void rectangleRoiRoundTripsThroughGeoJson() {
        ROI roi = ROIs.createRectangleROI(10, 20, 100, 50, ImagePlane.getDefaultPlane());
        String json = QuizGeometry.toGeoJson(roi);
        ROI back = QuizGeometry.fromGeoJson(json, ImagePlane.getDefaultPlane());
        double eps = 0.5;
        assertEquals(10, back.getBoundsX(), eps);
        assertEquals(20, back.getBoundsY(), eps);
        assertEquals(100, back.getBoundsWidth(), eps);
        assertEquals(50, back.getBoundsHeight(), eps);
    }

    @Test
    void deserializedRoiKeepsItsEncodedPlaneRegardlessOfPlaneArgument() {
        // toGeoJson always encodes its own c/z/t plane member; fromGeoJson must return the ROI on
        // that encoded plane even when called with a different plane argument -- the argument
        // exists for signature symmetry (Task 7/8 pass the question's plane) and must never
        // silently relocate an already-authored geometry to a different plane.
        ImagePlane originalPlane = ImagePlane.getPlane(2, 3);
        ROI roi = ROIs.createRectangleROI(10, 20, 100, 50, originalPlane);
        String json = QuizGeometry.toGeoJson(roi);

        ROI back = QuizGeometry.fromGeoJson(json, ImagePlane.getDefaultPlane());

        assertEquals(originalPlane, back.getImagePlane());
    }
}
