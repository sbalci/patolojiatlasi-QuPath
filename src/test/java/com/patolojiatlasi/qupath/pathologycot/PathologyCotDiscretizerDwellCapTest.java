package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the per-point dwell cap ({@link PathologyCotDiscretizer#MAX_CREDIT_MS}).
 *
 * <p>Before the cap, the gap between two consecutive path points was credited in full for any gap
 * under the 60-second idle threshold — but a gap in the recorded path is not viewing time: the
 * recorder appends a point only while QuPath's main window has focus, so looking away leaves a hole
 * that the next point absorbed as dwell. These tests pin both halves of the fix: inflated dwell is
 * gone, and a normally-sampled (gap-free) path is completely unaffected.
 */
class PathologyCotDiscretizerDwellCapTest {

    private static int[] p(int t, int cx, int cy, int w, int h, int ds) {
        return new int[]{t, cx, cy, w, h, ds, -1, -1};
    }

    /** 7 points at 250 ms over one region, i.e. 1500 ms of sampling. */
    private static void addRun(List<int[]> path, int startMs) {
        for (int i = 0; i < 7; i++)
            path.add(p(startMs + i * 250, 5000, 5000, 4096, 4096, 4000));
    }

    @Test void gapIsNoLongerCreditedAsDwell() {
        // 7 points, a 30-second window with no points (the author looked away), then 7 more over the
        // same region. Measured on the pre-fix code this produced a single INSPECT with
        // dwellMs = 33000 for 3000 ms of actual viewing.
        List<int[]> path = new ArrayList<>();
        addRun(path, 0);
        addRun(path, 1500 + 30_000);

        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100_000, 80_000);
        assertEquals(1, out.size(), "same region, so the runs still merge into one action");
        Behavior b = out.get(0);

        // 7 points per run = 6 intra-run gaps each, so 12 x 250 = 3000 ms of real sampling, plus
        // the single capped 500 ms for the last point before the hole.
        assertEquals(3500, b.dwellMs());
        assertTrue(b.dwellMs() < 5_000, "a 30 s absence must not read as dwell");
        assertEquals(0, b.startMs());
        assertEquals(1500 + 30_000 + 1500, b.endMs(), "the span still covers the real elapsed time");
    }

    @Test void gapFreePathIsUnchangedByTheCap() {
        // Consecutive points are 250 ms apart and min(250, 500) = 250, so a well-behaved path
        // produces exactly the dwell it always did.
        List<int[]> path = new ArrayList<>();
        for (int t = 0; t <= 4000; t += 250)
            path.add(p(t, 5000, 5000, 4096, 4096, 4000));

        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100_000, 80_000);
        assertEquals(1, out.size());
        assertEquals(4000, out.get(0).dwellMs(), "16 gaps of 250 ms, none capped");
    }

    @Test void dwellNeverExceedsTheSpan() {
        // A general invariant the cap must not break: credited dwell can never exceed wall-clock
        // span, whatever the sampling pattern.
        List<int[]> path = new ArrayList<>();
        path.add(p(0, 5000, 5000, 4096, 4096, 4000));
        path.add(p(400, 5000, 5000, 4096, 4096, 4000));
        path.add(p(10_000, 5000, 5000, 4096, 4096, 4000));
        path.add(p(10_250, 5000, 5000, 4096, 4096, 4000));

        for (Behavior b : PathologyCotDiscretizer.discretize(path, 40.0, 100_000, 80_000))
            assertTrue(b.dwellMs() <= b.endMs() - b.startMs(),
                    "dwell " + b.dwellMs() + " exceeded span " + (b.endMs() - b.startMs()));
    }

    @Test void idleGapStillZeroesAndStillSplitsTheRun() {
        // The pre-existing >60 s rule is untouched: no credit across the gap, and the run splits.
        assertEquals(0, PathologyCotDiscretizer.creditedDt(70_000));
        assertEquals(0, PathologyCotDiscretizer.creditedDt(PathologyCotDiscretizer.IDLE_GAP_MS + 1));
    }

    @Test void creditedDtKernel() {
        assertEquals(0, PathologyCotDiscretizer.creditedDt(0), "no next point");
        assertEquals(0, PathologyCotDiscretizer.creditedDt(-5), "backwards timestamps credit nothing");
        assertEquals(250, PathologyCotDiscretizer.creditedDt(250), "nominal sample interval");
        assertEquals(500, PathologyCotDiscretizer.creditedDt(500), "exactly at the cap");
        assertEquals(500, PathologyCotDiscretizer.creditedDt(501), "just over the cap");
        assertEquals(500, PathologyCotDiscretizer.creditedDt(59_000), "under the idle gap, still capped");
        assertEquals(500, PathologyCotDiscretizer.creditedDt(PathologyCotDiscretizer.IDLE_GAP_MS));
    }
}
