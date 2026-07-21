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
}
