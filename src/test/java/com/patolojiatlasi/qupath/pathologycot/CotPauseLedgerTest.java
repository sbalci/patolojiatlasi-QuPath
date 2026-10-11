package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pause state machine, extracted from the recorder's timer callback precisely so these cases are
 * reachable. The first inline version produced intervals that overlapped the gate's credited
 * windows and dropped the leading tick of every pause; the no-overlap test below is the regression.
 */
class CotPauseLedgerTest {

    /** Drives the ledger over a tick sequence and returns everything it emitted. */
    private static final class Run {
        final CotPauseLedger ledger = new CotPauseLedger();
        final List<long[]> emitted = new ArrayList<>();
        final List<long[]> credited = new ArrayList<>();
        long lastTickMs;

        Run(long startMs) {
            this.lastTickMs = startMs;
        }

        /** One tick with the gate closed for {@code reason}. */
        void closed(long now, CotRecording.PauseReason reason) {
            add(ledger.closedTick(lastTickMs, reason));
            lastTickMs = now;
        }

        /** One tick with the gate open; credits [lastTick, now] exactly as CotRecordingGate does. */
        void open(long now) {
            add(ledger.openTick(lastTickMs));
            credited.add(new long[]{lastTickMs, now});
            lastTickMs = now;
        }

        /** One open tick that the timer delivered late, so credit is capped and the rest is a gap. */
        void stalled(long now) {
            add(ledger.openTick(lastTickMs));
            add(ledger.gap(CotRecordingGate.gapPauseStartMs(lastTickMs), now));
            credited.add(new long[]{lastTickMs, lastTickMs + CotRecordingGate.MAX_CREDIT_MS});
            lastTickMs = now;
        }

        void stop(long now) {
            add(ledger.stop(now));
        }

        private void add(long[] interval) {
            if (interval != null)
                emitted.add(interval);
        }

        long pausedMs() {
            long t = 0;
            for (long[] p : emitted)
                t += p[1] - p[0];
            return t;
        }

        void assertNoOverlapWithCredit() {
            for (long[] p : emitted)
                for (long[] c : credited)
                    assertFalse(p[0] < c[1] && c[0] < p[1],
                            "pause [" + p[0] + "," + p[1] + "] overlaps credited ["
                                    + c[0] + "," + c[1] + "]");
        }
    }

    @Test void aPauseStartsAtTheLastOpenTickNotTheFirstClosedOne() {
        // Gate open at 1000, closed at 1250 and 1500, open again at 1750. The window [1000,1250]
        // was not credited, so it must belong to the pause — an earlier version started the pause
        // at 1250 and lost it from both accounts.
        Run r = new Run(1000);
        r.closed(1250, CotRecording.PauseReason.UNFOCUSED);
        r.closed(1500, CotRecording.PauseReason.UNFOCUSED);
        r.open(1750);

        assertEquals(1, r.emitted.size());
        assertArrayEquals(new long[]{1000, 1500, CotRecording.PauseReason.UNFOCUSED.ordinal()},
                r.emitted.get(0));
    }

    @Test void theReopeningTicksCreditIsNotAlsoCountedAsPause() {
        // The reopening tick is credited [1500,1750] by the gate, so the pause must end at 1500.
        Run r = new Run(1000);
        r.closed(1250, CotRecording.PauseReason.UNFOCUSED);
        r.closed(1500, CotRecording.PauseReason.UNFOCUSED);
        r.open(1750);
        r.open(2000);
        r.assertNoOverlapWithCredit();
        assertEquals(500, r.pausedMs(), "[1000,1500]; the credited [1500,1750] is not paused time");
    }

    @Test void pauseAndCreditTileTheTakeWithoutGapsOrOverlaps() {
        Run r = new Run(0);
        r.open(250);
        r.open(500);
        r.closed(750, CotRecording.PauseReason.UNFOCUSED);
        r.closed(1000, CotRecording.PauseReason.UNFOCUSED);
        r.open(1250);
        r.open(1500);
        r.assertNoOverlapWithCredit();

        long credited = 0;
        for (long[] c : r.credited)
            credited += c[1] - c[0];
        // Every millisecond from the first tick to the last is in exactly one account.
        assertEquals(1500, credited + r.pausedMs());
    }

    @Test void aChangeOfReasonSplitsThePauseRatherThanMislabellingIt() {
        Run r = new Run(1000);
        r.closed(1250, CotRecording.PauseReason.UNFOCUSED);
        r.closed(1500, CotRecording.PauseReason.SUPPRESSED);   // cause changed mid-pause
        r.closed(1750, CotRecording.PauseReason.SUPPRESSED);
        r.open(2000);

        assertEquals(2, r.emitted.size());
        assertArrayEquals(new long[]{1000, 1250, CotRecording.PauseReason.UNFOCUSED.ordinal()},
                r.emitted.get(0));
        assertArrayEquals(new long[]{1250, 1750, CotRecording.PauseReason.SUPPRESSED.ordinal()},
                r.emitted.get(1));
        assertEquals(750, r.pausedMs(), "the split must not change the total");
    }

    @Test void aStallWhileTheGateIsOpenIsRecordedAsAGap() {
        Run r = new Run(1000);
        r.stalled(6000);   // 5 s late: 500 ms credited, the rest unobserved
        assertEquals(1, r.emitted.size());
        assertArrayEquals(new long[]{1500, 6000, CotRecording.PauseReason.GAP.ordinal()},
                r.emitted.get(0));
        r.assertNoOverlapWithCredit();
    }

    @Test void aStallDuringAClosedGateDoesNotAddASecondOverlappingInterval() {
        // The pause already covers the stall window; a GAP on top of it would double-count.
        CotPauseLedger ledger = new CotPauseLedger();
        assertNull(ledger.closedTick(1000, CotRecording.PauseReason.UNFOCUSED));
        assertTrue(ledger.isPaused());
        assertNull(ledger.gap(CotRecordingGate.gapPauseStartMs(1000), 9000),
                "no GAP while a pause is open");
    }

    @Test void stopClosesAnOpenPauseAtTheStopItself() {
        // Only here is "now" the right closing edge: no further tick will be credited.
        Run r = new Run(1000);
        r.closed(1250, CotRecording.PauseReason.UNFOCUSED);
        r.stop(4321);
        assertEquals(1, r.emitted.size());
        assertArrayEquals(new long[]{1000, 4321, CotRecording.PauseReason.UNFOCUSED.ordinal()},
                r.emitted.get(0));
        assertFalse(r.ledger.isPaused());
    }

    @Test void stopWithNoOpenPauseEmitsNothingAndIsIdempotent() {
        CotPauseLedger ledger = new CotPauseLedger();
        assertNull(ledger.stop(5000));
        assertNull(ledger.closedTick(1000, CotRecording.PauseReason.UNFOCUSED));
        assertNotNull(ledger.stop(2000));
        assertNull(ledger.stop(3000), "a second stop must not re-emit the same pause");
    }

    @Test void openTickWithNoPauseEmitsNothing() {
        CotPauseLedger ledger = new CotPauseLedger();
        assertNull(ledger.openTick(1000));
        assertFalse(ledger.isPaused());
    }

    @Test void degenerateIntervalsAreNeverEmitted() {
        // A pause that opens and closes on the same tick boundary has zero length.
        CotPauseLedger ledger = new CotPauseLedger();
        assertNull(ledger.closedTick(1000, CotRecording.PauseReason.UNFOCUSED));
        assertNull(ledger.openTick(1000), "zero-length pause");
        assertFalse(ledger.isPaused(), "and the pause is still cleared");
    }

    @Test void theOpenReasonIsVisibleWhilePaused() {
        CotPauseLedger ledger = new CotPauseLedger();
        assertNull(ledger.openReason());
        ledger.closedTick(1000, CotRecording.PauseReason.SUPPRESSED);
        assertEquals(CotRecording.PauseReason.SUPPRESSED, ledger.openReason());
        ledger.openTick(1250);
        assertNull(ledger.openReason());
    }
}
