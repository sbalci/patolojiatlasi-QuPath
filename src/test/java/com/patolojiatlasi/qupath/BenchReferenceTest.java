package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchReferenceTest {

    @Test
    void sameMppKeepsYourDownsample() {
        assertEquals(4.0, BenchReference.matchedDownsample(4.0, 0.25, 0.25), 1e-9);
    }

    @Test
    void finerReferenceUsesSmallerDownsample() {
        // ref is twice as fine (0.125 vs 0.25 µm/px) → needs half the downsample to match on-screen µm/px
        assertEquals(2.0, BenchReference.matchedDownsample(4.0, 0.25, 0.5), 1e-9);
        assertEquals(8.0, BenchReference.matchedDownsample(4.0, 0.5, 0.25), 1e-9);
    }

    @Test
    void unknownMppReturnsNaN() {
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(4.0, 0.0, 0.25)));
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(4.0, 0.25, 0.0)));
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(0.0, 0.25, 0.25)));
    }
}
