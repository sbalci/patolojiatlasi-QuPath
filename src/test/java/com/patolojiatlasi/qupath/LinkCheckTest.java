package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LinkCheckTest {
    @Test void statusInterpretation() {
        assertTrue(LinkCheck.reachable(200));
        assertTrue(LinkCheck.reachable(301));
        assertTrue(LinkCheck.reachable(405));   // method not allowed = URL resolves
        assertFalse(LinkCheck.reachable(404));
        assertFalse(LinkCheck.reachable(500));
        assertFalse(LinkCheck.reachable(0));     // our sentinel for "no response"
    }
}
