package com.patolojiatlasi.qupath.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FocusMapTemporalTest {

    @Test
    void activeDwellMsClampsAndGuards() {
        assertEquals(0L, FocusMap.activeDwellMs(1000, 900, false, 500));   // inactive -> 0
        assertEquals(100L, FocusMap.activeDwellMs(1000, 900, true, 500));  // normal dt
        assertEquals(500L, FocusMap.activeDwellMs(2000, 900, true, 500));  // dt > cap -> cap
        assertEquals(0L, FocusMap.activeDwellMs(900, 1000, true, 500));    // backwards clock -> 0
        assertEquals(0L, FocusMap.activeDwellMs(1000, 1000, true, 500));   // zero dt -> 0
    }

    @Test
    void weightedDepositAccumulatesTotalWeight() {
        FocusMap m = new FocusMap(1000, 1000, 10);   // 10x10 grid
        assertTrue(m.deposit(0, 0, 1000, 1000, 250.0));   // whole image, 250 ms
        assertEquals(250.0, m.getTotalWeight(), 1e-6);
        m.deposit(0, 0, 1000, 1000, 250.0);
        assertEquals(500.0, m.getTotalWeight(), 1e-6);
        // grid sum equals total deposited weight (full weight spread over covered cells)
        double sum = 0;
        for (float v : m.getGrid()) sum += v;
        assertEquals(500.0, sum, 1e-3);
    }

    @Test
    void legacyDepositIsWeightOne() {
        FocusMap m = new FocusMap(1000, 1000, 10);
        m.deposit(0, 0, 1000, 1000);                 // legacy -> weight 1
        assertEquals(1.0, m.getTotalWeight(), 1e-6);
    }

    @Test
    void nonPositiveWeightIsNoOp() {
        FocusMap m = new FocusMap(1000, 1000, 10);
        assertFalse(m.deposit(0, 0, 1000, 1000, 0.0));
        assertFalse(m.deposit(0, 0, 1000, 1000, -5.0));
        assertEquals(0.0, m.getTotalWeight(), 1e-6);
    }
}
