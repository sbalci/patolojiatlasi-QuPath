package com.patolojiatlasi.qupath.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FocusMapTest {

    @Test
    void gridSizedToAspectRatioWithLongestSideCapped() {
        FocusMap wide = new FocusMap(1000, 500, 100);
        assertEquals(100, wide.getGridWidth());
        assertEquals(50, wide.getGridHeight());

        FocusMap tall = new FocusMap(500, 1000, 100);
        assertEquals(50, tall.getGridWidth());
        assertEquals(100, tall.getGridHeight());
    }

    @Test
    void eachDepositAddsTotalWeightOfOne() {
        FocusMap m = new FocusMap(1000, 500, 100);
        assertTrue(m.deposit(0, 0, 1000, 500)); // whole image
        float sum = 0f;
        for (float v : m.getGrid())
            sum += v;
        assertEquals(1.0f, sum, 1e-3f);
        assertEquals(1, m.getSampleCount());
    }

    @Test
    void zoomedInViewHeatsFewCellsMuchMoreThanZoomedOut() {
        FocusMap zoomIn = new FocusMap(1000, 500, 100);
        zoomIn.deposit(0, 0, 10, 5);         // tiny region → few cells
        FocusMap zoomOut = new FocusMap(1000, 500, 100);
        zoomOut.deposit(0, 0, 1000, 500);    // whole image → many cells
        assertTrue(zoomIn.max() > zoomOut.max() * 100,
                "focused view should heat its cells far faster: " + zoomIn.max() + " vs " + zoomOut.max());
    }

    @Test
    void depositOutsideImageIsIgnored() {
        FocusMap m = new FocusMap(1000, 500, 100);
        assertFalse(m.deposit(2000, 0, 100, 100));   // entirely right of the image
        assertFalse(m.deposit(0, 0, 0, 0));          // empty region
        assertTrue(m.isEmpty());
    }

    @Test
    void clearResetsGridAndCount() {
        FocusMap m = new FocusMap(1000, 500, 100);
        m.deposit(100, 100, 50, 50);
        assertFalse(m.isEmpty());
        m.clear();
        assertTrue(m.isEmpty());
        assertEquals(0f, m.max());
    }

    @Test
    void emptyMapRendersFullyTransparent() {
        FocusMap m = new FocusMap(1000, 500, 100);
        var img = m.toImage();
        assertEquals(100, img.getWidth());
        assertEquals(50, img.getHeight());
        assertEquals(0, img.getRGB(0, 0) >>> 24, "alpha of an untouched cell should be 0");
    }

    @Test
    void heatColorAlphaGrowsWithIntensity() {
        int cold = FocusMap.heatColor(0f);
        int hot = FocusMap.heatColor(1f);
        assertTrue((hot >>> 24) > (cold >>> 24), "hotter cells should be more opaque");
        assertEquals(240, hot >>> 24);
    }
}
