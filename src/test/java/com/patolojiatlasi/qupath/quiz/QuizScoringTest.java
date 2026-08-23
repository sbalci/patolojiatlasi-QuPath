package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class QuizScoringTest {
    private static Geometry rect(double x, double y, double w, double h) {
        return ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()).getGeometry();
    }

    @Test void identicalIsPerfectHit() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(0,0,100,100));
        assertEquals(1.0, s.iou(), 1e-9);
        assertTrue(s.hit());
    }
    @Test void disjointIsMiss() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(500,500,100,100));
        assertEquals(0.0, s.iou(), 1e-9);
        assertFalse(s.hit());
    }
    @Test void smallInsideBigIsHitByContainmentDespiteLowIou() {
        QuizScoring.Score s = QuizScoring.score(rect(40,40,10,10), rect(0,0,100,100));
        assertTrue(s.iou() < QuizScoring.HIT_IOU);
        assertTrue(s.containment());
        assertTrue(s.hit());
    }
    @Test void partialOverlapAboveThresholdIsHit() {
        // overlap 50x100=5000; union 15000 -> IoU 1/3 > 0.3
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(50,0,100,100));
        assertTrue(s.iou() > QuizScoring.HIT_IOU);
        assertTrue(s.hit());
    }
    @Test void nullInputsAreSafe() {
        assertNull(QuizScoring.score(null, rect(0,0,10,10)));
        assertNull(QuizScoring.score(rect(0,0,10,10), null));
    }
    @Test void formatUsesUsLocaleDecimal() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(50,0,100,100));
        String line = QuizScoring.formatScoreLine(s);
        assertTrue(line.contains("33.3"), line);   // never "33,3"
    }
}
