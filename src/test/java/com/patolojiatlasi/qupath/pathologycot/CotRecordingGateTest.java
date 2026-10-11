package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The authoring recorder's sampling gate: what counts as observed time, and why it did not. */
class CotRecordingGateTest {

    private static CotRecordingGate.Decision decide(long now, long last, boolean focused,
                                                    boolean suppressed, boolean image) {
        return CotRecordingGate.decide(now, last, focused, suppressed, image);
    }

    @Test void punctualActiveTickCreditsTheFullInterval() {
        CotRecordingGate.Decision d = decide(1250, 1000, true, false, true);
        assertTrue(d.emit());
        assertEquals(250, d.creditedMs());
        assertNull(d.pauseReason());
    }

    @Test void lateTickIsCappedAndReportedAsAGap() {
        // The timer stalled for 5 s. Credit is capped; the rest is a gap, not viewing time.
        CotRecordingGate.Decision d = decide(6000, 1000, true, false, true);
        assertTrue(d.emit(), "the point itself is still real");
        assertEquals(CotRecordingGate.MAX_CREDIT_MS, d.creditedMs());
        assertEquals(CotRecording.PauseReason.GAP, d.pauseReason());
        assertEquals(1500, CotRecordingGate.gapPauseStartMs(1000));
    }

    @Test void exactlyAtTheCapIsNotYetAGap() {
        CotRecordingGate.Decision d = decide(1000 + CotRecordingGate.MAX_CREDIT_MS, 1000, true, false, true);
        assertTrue(d.emit());
        assertEquals(CotRecordingGate.MAX_CREDIT_MS, d.creditedMs());
        assertNull(d.pauseReason(), "dt == cap is punctual-enough; only dt > cap is a stall");
    }

    @Test void unfocusedWindowCreditsNothing() {
        CotRecordingGate.Decision d = decide(1250, 1000, false, false, true);
        assertFalse(d.emit());
        assertEquals(0, d.creditedMs());
        assertEquals(CotRecording.PauseReason.UNFOCUSED, d.pauseReason());
    }

    @Test void suppressedSweepCreditsNothing() {
        CotRecordingGate.Decision d = decide(1250, 1000, true, true, true);
        assertFalse(d.emit());
        assertEquals(CotRecording.PauseReason.SUPPRESSED, d.pauseReason());
    }

    @Test void noImageCreditsNothing() {
        CotRecordingGate.Decision d = decide(1250, 1000, true, false, false);
        assertFalse(d.emit());
        assertEquals(CotRecording.PauseReason.NO_IMAGE, d.pauseReason());
    }

    @Test void pauseReasonPrecedenceIsNoImageThenSuppressedThenUnfocused() {
        // All three blockers at once.
        assertEquals(CotRecording.PauseReason.NO_IMAGE,
                decide(1250, 1000, false, true, false).pauseReason());
        // Suppressed beats merely unfocused.
        assertEquals(CotRecording.PauseReason.SUPPRESSED,
                decide(1250, 1000, false, true, true).pauseReason());
        assertEquals(CotRecording.PauseReason.UNFOCUSED,
                decide(1250, 1000, false, false, true).pauseReason());
    }

    @Test void nonAdvancingClockEmitsNothingAndIsNotAPause() {
        // A duplicate or backwards timestamp is neither observed time nor a pause.
        CotRecordingGate.Decision same = decide(1000, 1000, true, false, true);
        assertFalse(same.emit());
        assertEquals(0, same.creditedMs());
        assertNull(same.pauseReason());

        CotRecordingGate.Decision backwards = decide(900, 1000, true, false, true);
        assertFalse(backwards.emit());
        assertNull(backwards.pauseReason());
    }

    @Test void capMatchesTheSampleIntervalContract() {
        // The whole point of the cap is that it equals FocusHeatmap.DT_CAP_MS (2 x SAMPLE_MS) and
        // PathologyCotDiscretizer.MAX_CREDIT_MS, so all three agree what one sample is worth.
        assertEquals(2L * CotRecordingGate.SAMPLE_MS, CotRecordingGate.MAX_CREDIT_MS);
        assertEquals(PathologyCotDiscretizer.MAX_CREDIT_MS, CotRecordingGate.MAX_CREDIT_MS);
    }
}
