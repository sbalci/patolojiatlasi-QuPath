package com.patolojiatlasi.qupath.pathologycot;

/**
 * Pure bookkeeping for the "not observed" intervals of an authored take: when a pause opens, when it
 * closes, and what it is called. Extracted from {@link CotRecorder}'s timer callback so the state
 * machine is unit-testable — the first version of this logic lived inline in the FX shell and
 * produced overlapping intervals that no test could reach.
 *
 * <h2>The invariant</h2>
 * Emitted pause intervals must not overlap the credited windows that {@link CotRecordingGate} hands
 * out, and no time may be silently dropped between the two. That requires both edges to be pinned to
 * <b>tick boundaries</b>, not to "now":
 *
 * <ul>
 *   <li><b>Opening edge = the last tick we know the gate was open</b> ({@code lastTickMs}), not the
 *       first tick we noticed it closed. The gate closed somewhere inside
 *       {@code (lastTickMs, now]}; crediting nothing for that window while also not recording it as
 *       a pause would lose it from both accounts.</li>
 *   <li><b>Closing edge = the last tick the gate was still closed</b> ({@code lastTickMs} as seen by
 *       the reopening tick), not {@code now}. The reopening tick is itself credited
 *       {@code now - lastTickMs} by the gate, so closing the pause at {@code now} would hand the
 *       same window to both the pause and the credit.</li>
 * </ul>
 *
 * <p>With both edges at tick boundaries, pause intervals and credited windows tile the take without
 * gaps or overlaps.
 *
 * <p>Every method returns the interval to record ({@code [startMs, endMs, reasonOrdinal]}) or
 * {@code null}, so the caller simply forwards a non-null result to
 * {@link CotRecording#addPause(long, long, CotRecording.PauseReason)}. No clock, no JavaFX, no
 * mutable shared state beyond the one open pause.
 */
final class CotPauseLedger {

    /** Start of the open pause, or {@code -1} when the gate is open. */
    private long openStartMs = -1;
    private CotRecording.PauseReason openReason;

    /** Whether a pause is currently open. */
    boolean isPaused() {
        return openStartMs >= 0;
    }

    CotRecording.PauseReason openReason() {
        return openReason;
    }

    /**
     * The gate is closed on this tick.
     *
     * @param lastTickMs the previous tick's timestamp — the last moment the gate is known to have
     *                   been open, and therefore this pause's start
     * @param reason      why the gate is closed
     * @return an interval to record when this tick <b>changes</b> the reason (the previous pause is
     *         closed at {@code lastTickMs} and a new one opened there), else {@code null}
     */
    long[] closedTick(long lastTickMs, CotRecording.PauseReason reason) {
        if (!isPaused()) {
            openStartMs = lastTickMs;
            openReason = reason;
            return null;
        }
        if (reason != null && reason != openReason) {
            // The cause changed mid-pause (e.g. the window lost focus, then a sweep started).
            // Split rather than mislabel the whole interval with the first cause seen.
            long[] closed = interval(openStartMs, lastTickMs, openReason);
            openStartMs = lastTickMs;
            openReason = reason;
            return closed;
        }
        return null;
    }

    /**
     * The gate is open on this tick.
     *
     * @param lastTickMs the previous tick's timestamp — the last tick the gate was still closed, and
     *                   therefore where the pause ends (see the class javadoc)
     * @return the pause interval that just ended, or {@code null} if none was open
     */
    long[] openTick(long lastTickMs) {
        if (!isPaused())
            return null;
        long[] closed = interval(openStartMs, lastTickMs, openReason);
        clear();
        return closed;
    }

    /**
     * A {@link CotRecording.PauseReason#GAP}: the gate stayed open but the timer stalled, so credit
     * was capped and the remainder is unobserved. Only meaningful when no pause is open — a stall
     * during a closed gate is already covered by that pause.
     *
     * @return the stall interval, or {@code null} when a pause is already open
     */
    long[] gap(long gapStartMs, long nowMs) {
        if (isPaused())
            return null;
        return interval(gapStartMs, nowMs, CotRecording.PauseReason.GAP);
    }

    /**
     * The take is ending. An open pause runs to the stop itself, so here — and only here — the
     * closing edge is {@code nowMs} rather than a tick boundary: no further tick will be credited.
     *
     * @return the final pause interval, or {@code null} if none was open
     */
    long[] stop(long nowMs) {
        if (!isPaused())
            return null;
        long[] closed = interval(openStartMs, nowMs, openReason);
        clear();
        return closed;
    }

    private void clear() {
        openStartMs = -1;
        openReason = null;
    }

    private static long[] interval(long startMs, long endMs, CotRecording.PauseReason reason) {
        if (reason == null || endMs <= startMs)
            return null;
        return new long[]{startMs, endMs, reason.ordinal()};
    }
}
