package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The authored-take model: point layout, caps, pauses, and the fragment adapter. */
class CotRecordingTest {

    private static CotRecording fresh() {
        return new CotRecording("auth-1", "file:/slides/a.svs", "a.svs", "sha256:k",
                1000, 800, 40.0, "2026-10-11", "p", "1", "hash");
    }

    @Test void pointLayoutMatchesTheBlindedSchemaExactly() {
        // 8 elements in the order FocusHeatmap.tickBlinded writes for atlas-focus-contribution/5.
        // This is what lets PathologyCotDiscretizer consume an authored take with no branch of its
        // own — so the layout is a contract, not an implementation detail.
        CotRecording rec = fresh();
        assertTrue(rec.addPoint(1234, 10, 20, 30, 40, 4000, 7, 8, 250));
        int[] p = rec.pathSnapshot().get(0);
        assertArrayEquals(new int[]{1234, 10, 20, 30, 40, 4000, 7, 8}, p);
    }

    @Test void creditedTimeAccumulatesAndIgnoresNegativeCredit() {
        CotRecording rec = fresh();
        rec.addPoint(0, 1, 1, 1, 1, 1000, -1, -1, 250);
        rec.addPoint(250, 1, 1, 1, 1, 1000, -1, -1, 250);
        rec.addPoint(500, 1, 1, 1, 1, 1000, -1, -1, -999);
        assertEquals(500, rec.creditedMs());
        assertEquals(3, rec.pointCount());
    }

    @Test void pathSnapshotIsADefensiveCopy() {
        // Serialization may run while sampling continues; a shared array would let a concurrent
        // append corrupt an in-flight write.
        CotRecording rec = fresh();
        rec.addPoint(0, 1, 2, 3, 4, 1000, -1, -1, 250);
        List<int[]> snap = rec.pathSnapshot();
        snap.get(0)[1] = 999;
        rec.addPoint(250, 5, 6, 7, 8, 1000, -1, -1, 250);
        assertEquals(1, snap.size(), "the snapshot must not see later appends");
        assertEquals(1, rec.pathSnapshot().get(0)[1], "mutating the copy must not touch the take");
    }

    @Test void pointCapStopsGrowthAndIsReported() {
        CotRecording rec = fresh();
        for (int i = 0; i < CotRecording.MAX_PATH_POINTS; i++)
            assertTrue(rec.addPoint(i, 1, 1, 1, 1, 1000, -1, -1, 1));
        assertFalse(rec.isPathCapped());
        assertFalse(rec.addPoint(99_999, 1, 1, 1, 1, 1000, -1, -1, 1), "the cap must refuse");
        assertTrue(rec.isPathCapped());
        assertEquals(CotRecording.MAX_PATH_POINTS, rec.pointCount());
    }

    @Test void pausesAreSummedAndDegenerateIntervalsIgnored() {
        CotRecording rec = fresh();
        rec.addPause(1000, 4000, CotRecording.PauseReason.UNFOCUSED);
        rec.addPause(5000, 5500, CotRecording.PauseReason.GAP);
        rec.addPause(9000, 9000, CotRecording.PauseReason.UNFOCUSED);   // zero length
        rec.addPause(9000, 8000, CotRecording.PauseReason.UNFOCUSED);   // backwards
        rec.addPause(9000, 9500, null);                                  // no reason
        assertEquals(2, rec.pauseSnapshot().size());
        assertEquals(3500, rec.pausedMs());
    }

    @Test void toFragmentCarriesProvenanceButNoDiagnosis() {
        // PathologyCotDraft reads only "diagnosis" from the decision map, so a provenance-only map
        // yields the neutral draft description rather than a fabricated recorded diagnosis.
        CotRecording rec = fresh();
        rec.addPoint(0, 1, 2, 3, 4, 4000, -1, -1, 250);
        CotFragment f = rec.toFragment();
        assertEquals("sha256:k", f.slideKey());
        assertEquals("auth-1", f.sessionId());
        assertEquals(1000, f.imageWidth());
        assertEquals(800, f.imageHeight());
        assertEquals(40.0, f.baseMagnification());
        assertEquals(1, f.path().size());
        assertNotNull(f.decision().get("cotProvenance"));
        assertNull(f.decision().get("diagnosis"));
    }

    @Test void provenanceIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> fresh().provenance().put("recordingMode", "blinded"));
    }

    @Test void endReasonIsRecordedSoATruncatedTakeIsNeverReadAsComplete() {
        CotRecording rec = fresh();
        assertNull(rec.endReason());
        assertNull(rec.provenance().get("endReason"));
        rec.setEndReason(CotRecording.EndReason.SHUTDOWN);
        assertEquals("shutdown", rec.provenance().get("endReason"));
    }

    @Test void freshTakeIsEmpty() {
        assertTrue(fresh().isEmpty());
        assertEquals(0, fresh().creditedMs());
    }
}
