package com.patolojiatlasi.qupath.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlindedResearchTest {
    @Test void flagRoundTrips(@TempDir File dir) {
        assertFalse(BlindedResearch.readBlinded(dir));       // no sidecar -> not blinded
        BlindedResearch.writeFlag(dir, true);
        assertTrue(BlindedResearch.readBlinded(dir));
    }
    @Test void consentRoundTrips(@TempDir File dir) {
        BlindedResearch.writeFlag(dir, true);
        assertFalse(BlindedResearch.readConsented(dir));
        BlindedResearch.markConsented(dir);
        assertTrue(BlindedResearch.readConsented(dir));
        assertTrue(BlindedResearch.readBlinded(dir));        // marking consent preserves the flag
    }
    @Test void corruptSidecarIsNotBlinded(@TempDir File dir) throws Exception {
        java.nio.file.Files.writeString(new File(dir, "atlas-research.json").toPath(), "{ bad");
        assertFalse(BlindedResearch.readBlinded(dir));        // fail-soft, no throw
    }

    // --- decisionPromptOnLeave: defaults true (absent sidecar, absent key, corrupt sidecar), only
    // an explicit false in the sidecar turns the leave-prompt off. ---------------------------------

    @Test void decisionPromptOnLeaveDefaultsTrueWhenSidecarAbsent(@TempDir File dir) {
        assertTrue(BlindedResearch.decisionPromptOnLeave(dir));   // no sidecar at all -> true
    }

    @Test void decisionPromptOnLeaveDefaultsTrueWhenKeyAbsent(@TempDir File dir) {
        BlindedResearch.writeFlag(dir, true);   // existing write path never sets decisionPromptOnLeave
        assertTrue(BlindedResearch.decisionPromptOnLeave(dir));
    }

    @Test void decisionPromptOnLeaveDefaultsTrueOnCorruptSidecar(@TempDir File dir) throws Exception {
        java.nio.file.Files.writeString(new File(dir, "atlas-research.json").toPath(), "{ bad");
        assertTrue(BlindedResearch.decisionPromptOnLeave(dir));   // fail-soft, no throw
    }

    @Test void decisionPromptOnLeaveHonoursExplicitFalse(@TempDir File dir) throws Exception {
        java.nio.file.Files.writeString(new File(dir, "atlas-research.json").toPath(),
                "{\"schema\":\"atlas-research/1\",\"blindedTracking\":true,\"consented\":true,"
                        + "\"decisionPromptOnLeave\":false}");
        assertFalse(BlindedResearch.decisionPromptOnLeave(dir));
    }

    @Test void decisionPromptOnLeaveHonoursExplicitTrue(@TempDir File dir) throws Exception {
        java.nio.file.Files.writeString(new File(dir, "atlas-research.json").toPath(),
                "{\"schema\":\"atlas-research/1\",\"blindedTracking\":true,\"consented\":true,"
                        + "\"decisionPromptOnLeave\":true}");
        assertTrue(BlindedResearch.decisionPromptOnLeave(dir));
    }
}
