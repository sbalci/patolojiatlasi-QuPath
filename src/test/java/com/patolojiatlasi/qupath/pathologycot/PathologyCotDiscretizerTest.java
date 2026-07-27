package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PathologyCotDiscretizerTest {
    private static int[] p(int t,int cx,int cy,int w,int h,int ds){return new int[]{t,cx,cy,w,h,ds,-1,-1};}

    @Test void staticDwellBecomesInspect() {
        // held ~static at 10x (baseMag 40 -> downsample 4 -> dsMilli 4000) for 1.5s
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(1, out.size());
        assertEquals(Behavior.Type.INSPECT, out.get(0).type());
        assertEquals("10x", out.get(0).magBin());
    }

    @Test void shortStaticIsDropped() {  // < STATIC_INSPECT_MS
        List<int[]> path = List.of(p(0,5000,5000,4096,4096,4000), p(250,5000,5000,4096,4096,4000));
        assertTrue(PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).isEmpty());
    }

    @Test void nativeZoomBecomesPeek() {   // downsample ~1 (dsMilli 1000) -> 40x peek
        List<int[]> path = List.of(p(0,7000,7000,1024,1024,1000), p(250,7000,7000,1024,1024,1000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(1, out.size());
        assertEquals(Behavior.Type.PEEK, out.get(0).type());
        assertEquals("40x", out.get(0).magBin());
    }

    @Test void highIoUActionsMerge() {
        // two nearly-identical inspects separated by an idle gap → two actions that then merge
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        int base = 1500 + 70_000;   // > IDLE_GAP_MS: forces a distinct second action (IoU > 0.8)
        for (int t=base;t<=base+1500;t+=250) path.add(p(t, 5100,5100, 4096,4096, 4000));
        assertEquals(1, PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).size());
    }

    @Test void idleGapSplitsSegments() {
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        int base = 1500 + 70_000;  // > IDLE_GAP_MS
        for (int t=base;t<=base+1500;t+=250) path.add(p(t, 20000,20000, 4096,4096, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(2, out.size());
        List<Behavior> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparingLong(Behavior::startMs));
        Behavior first = sorted.get(0), second = sorted.get(1);
        assertEquals(5000.0, first.centerX(), 120.0, "first action centerX should stay near the pre-gap centroid");
        assertEquals(5000.0, first.centerY(), 120.0, "first action centerY should stay near the pre-gap centroid");
        assertEquals(20000.0, second.centerX(), 120.0, "second action centerX should not be pulled toward the boundary point");
        assertEquals(20000.0, second.centerY(), 120.0, "second action centerY should not be pulled toward the boundary point");
        assertTrue(second.startMs() >= 71000,
                "second action's startMs should not inherit the pre-gap timestamp 1500, was " + second.startMs());
    }

    @Test void pruneContainingRemovesLargerLowerMag() {
        // a large 5x inspect (viewport 8192px @ dsMilli 8000, baseMag 40 -> 40/8=5x) held ~1.5s,
        // immediately followed by a native-res (dsMilli 1000 -> 40x) peek at the same center: the
        // 5x box fully contains the 40x box, so pruneContaining should drop the lower-mag one.
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 7000,7000, 8192,8192, 8000));
        for (int t=1750;t<=2000;t+=250) path.add(p(t, 7000,7000, 1024,1024, 1000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(1, out.size());
        assertEquals(Behavior.Type.PEEK, out.get(0).type());
    }

    @Test void overviewAreaFilteredWhenBaseMagNull() {
        // huge viewport (90000x90000 over a 100000x80000 image = 101% of image area) held static;
        // baseMag is null so the low-mag half of the overview filter (in flush) is skipped, but the
        // area-based half (stage 2, magBin-independent) must still drop it.
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 50000,40000, 90000,90000, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, null, 100000, 80000);
        assertTrue(out.isEmpty());
    }

    @Test void emptyAndSinglePointAreSafe() {
        assertTrue(PathologyCotDiscretizer.discretize(List.of(), 40.0, 100, 100).isEmpty());
        assertTrue(PathologyCotDiscretizer.discretize(List.of(p(0,1,1,2,2,4000)), 40.0, 100,100).isEmpty());
    }

    @Test void nullBaseMagLeavesMagBinNull() {
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, null, 100000, 80000);
        assertFalse(out.isEmpty());
        assertNull(out.get(0).magBin());
    }
}
