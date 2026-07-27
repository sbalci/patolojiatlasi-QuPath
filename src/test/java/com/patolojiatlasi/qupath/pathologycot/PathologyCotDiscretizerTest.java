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
        assertEquals(2, PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).size());
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
