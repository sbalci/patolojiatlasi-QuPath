package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The mode firewall's transition table. Two recorders sample the same viewer and at most one may be
 * active: blinded research recording (unaware reader, naturalistic) and authored CoT takes (aware
 * author, demonstration). Mixing them corrupts both datasets, so both directions refuse.
 */
class RecordingModeGuardTest {

    @Test void authoringMayStartWhenNothingElseIsRunning() {
        RecordingModeGuard.Verdict v = RecordingModeGuard.canStartAuthoring(false, false, true);
        assertTrue(v.allowed());
        assertNull(v.message());
    }

    @Test void authoringIsRefusedWhileBlindedRecords() {
        RecordingModeGuard.Verdict v = RecordingModeGuard.canStartAuthoring(true, false, true);
        assertFalse(v.allowed());
        assertNotNull(v.message());
        assertTrue(v.message().contains("Araştırma"), v.message());
    }

    @Test void authoringIsRefusedDuringAProgrammaticSweep() {
        // The viewport is moving on its own, so the recorded path would be the sweep's, not the
        // author's.
        RecordingModeGuard.Verdict v = RecordingModeGuard.canStartAuthoring(false, true, true);
        assertFalse(v.allowed());
        assertNotNull(v.message());
    }

    @Test void authoringIsRefusedWithNoSlideOpen() {
        RecordingModeGuard.Verdict v = RecordingModeGuard.canStartAuthoring(false, false, false);
        assertFalse(v.allowed());
        assertNotNull(v.message());
    }

    @Test void blindedMayStartWhenNoTakeIsRunning() {
        assertTrue(RecordingModeGuard.canStartBlinded(false).allowed());
        assertNull(RecordingModeGuard.canStartBlinded(false).message());
    }

    @Test void blindedIsRefusedWhileATakeRuns() {
        // The symmetric half: starting blinded recording mid-take would mix a demonstration into
        // the study cohort, and would also make the authored HUD appear during a blinded session.
        RecordingModeGuard.Verdict v = RecordingModeGuard.canStartBlinded(true);
        assertFalse(v.allowed());
        assertNotNull(v.message());
        assertTrue(v.message().contains("CoT"), v.message());
    }

    @Test void everyRefusalCarriesAUserFacingMessage() {
        // A silent refusal is a bug: the veto is surfaced in a dialog, so null would show blank.
        for (boolean blinded : new boolean[]{true, false})
            for (boolean suppressed : new boolean[]{true, false})
                for (boolean image : new boolean[]{true, false}) {
                    RecordingModeGuard.Verdict v =
                            RecordingModeGuard.canStartAuthoring(blinded, suppressed, image);
                    assertEquals(v.allowed(), v.message() == null,
                            "allowed=" + v.allowed() + " must agree with message presence");
                    if (!v.allowed())
                        assertFalse(v.message().isBlank());
                }
    }
}
