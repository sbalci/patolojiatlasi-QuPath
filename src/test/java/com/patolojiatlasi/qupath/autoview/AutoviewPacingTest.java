package com.patolojiatlasi.qupath.autoview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoviewPacingTest {

    @Test
    void fullyCachedStaysAtFullSpeed() {
        assertEquals(1.0, AutoviewPacing.speedFactor(1.0, 1.0, 0.016), 1e-9);
        assertEquals(1.0, AutoviewPacing.targetFactor(1.0), 1e-9);
        assertEquals(1.0, AutoviewPacing.targetFactor(AutoviewPacing.READY_FULL), 1e-9);
    }

    @Test
    void emptyCacheDeceleratesRatherThanStopping() {
        // The regression guard for glide-vs-stutter: hitting an uncached region must ease the sweep
        // down over ~a third of a second, not drop it to a dead stop in one frame.
        assertEquals(0.7, AutoviewPacing.speedFactor(0.0, 1.0, 0.1), 1e-9);
        assertTrue(AutoviewPacing.speedFactor(0.0, 1.0, 0.016) > 0.9,
                "a single 16 ms frame should barely slow the sweep");
    }

    @Test
    void anEmptyCacheEventuallyReachesAFullStop() {
        double f = 1.0;
        for (int i = 0; i < 60; i++)
            f = AutoviewPacing.speedFactor(0.0, f, 0.016);
        assertEquals(0.0, f, 1e-9, "sustained cache misses should settle at a hold");
    }

    @Test
    void aWarmCacheAcceleratesBackToFullSpeed() {
        double f = 0.0;
        for (int i = 0; i < 60; i++)
            f = AutoviewPacing.speedFactor(1.0, f, 0.016);
        assertEquals(1.0, f, 1e-9, "once tiles arrive the sweep must resume, not stay stalled");
    }

    @Test
    void rampIsMonotonicInReadyFraction() {
        double previous = -1;
        for (double ready = 0.0; ready <= 1.0001; ready += 0.05) {
            double t = AutoviewPacing.targetFactor(ready);
            assertTrue(t >= previous, "targetFactor should never decrease as readiness rises");
            assertTrue(t >= 0 && t <= 1, "targetFactor out of range at ready=" + ready);
            previous = t;
        }
        assertEquals(0.0, AutoviewPacing.targetFactor(AutoviewPacing.READY_STOP), 1e-9);
        assertEquals(0.5, AutoviewPacing.targetFactor(0.75), 1e-9, "midpoint of the ramp");
    }

    @Test
    void slewLimitIsRespectedAcrossVaryingFrameTimes() {
        // One 100 ms frame and ten 10 ms frames must land in the same place, or the sweep speed
        // would depend on the frame rate.
        double oneStep = AutoviewPacing.speedFactor(0.0, 1.0, 0.1);
        double manySteps = 1.0;
        for (int i = 0; i < 10; i++)
            manySteps = AutoviewPacing.speedFactor(0.0, manySteps, 0.01);
        assertEquals(oneStep, manySteps, 1e-9);
    }

    @Test
    void largeDtIsClampedSoAStallCannotJumpTheFactor() {
        // A GC pause or a slide load must not let the factor swing the whole way in one frame.
        assertEquals(0.25, AutoviewPacing.speedFactor(0.0, 1.0, 10.0), 1e-9);
        assertEquals(AutoviewPacing.speedFactor(0.0, 1.0, AutoviewPacing.MAX_DT),
                AutoviewPacing.speedFactor(0.0, 1.0, 100.0), 1e-9);
    }

    @Test
    void nanReadyFractionFailsOpenTowardsFullSpeed() {
        assertEquals(1.0, AutoviewPacing.targetFactor(Double.NaN), 1e-9);
        assertTrue(AutoviewPacing.speedFactor(Double.NaN, 0.5, 0.1) > 0.5,
                "a failed probe must let the sweep speed up, never wedge it");
    }

    @Test
    void nonAdvancingFrameTimesLeaveTheFactorUnchanged() {
        assertEquals(0.42, AutoviewPacing.speedFactor(1.0, 0.42, 0.0), 1e-9);
        assertEquals(0.42, AutoviewPacing.speedFactor(1.0, 0.42, -1.0), 1e-9);
        assertEquals(0.42, AutoviewPacing.speedFactor(1.0, 0.42, Double.NaN), 1e-9);
    }

    @Test
    void outOfRangePreviousFactorIsClampedNotPropagated() {
        assertTrue(AutoviewPacing.speedFactor(1.0, 5.0, 0.1) <= 1.0);
        assertTrue(AutoviewPacing.speedFactor(0.0, -5.0, 0.1) >= 0.0);
    }
}
