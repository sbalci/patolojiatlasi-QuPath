package com.patolojiatlasi.qupath.pathologycot;

/**
 * Pure sampling gate for an authored Pathology-CoT take: decides, for one timer tick, whether to
 * emit a scanpath point, how many milliseconds of dwell may be credited, and — when nothing is
 * credited — why.
 *
 * <p>No JavaFX, no QuPath, no clock of its own: every input is passed in, so the whole gate is
 * unit-testable (same discipline as {@code focus.FocusMap#activeDwellMs}, which this generalizes).
 *
 * <h2>Why this exists</h2>
 * {@code FocusHeatmap} already clamps each tick's credit to {@link #MAX_CREDIT_MS} before
 * depositing into the dwell <i>grid</i> ({@code FocusMap.activeDwellMs(now, last, active, DT_CAP_MS)}
 * with {@code DT_CAP_MS = 2 * SAMPLE_MS}). The <b>scanpath</b> it writes carries no such clamp — it
 * is a bare list of timestamps — and {@link PathologyCotDiscretizer} credits the raw gap between
 * consecutive points as dwell for any gap under its 60-second idle threshold. So a take in which
 * the author alt-tabbed away for 30 seconds yields a 30-second "inspect" at whatever region was on
 * screen. This gate makes the authored path's own accounting explicit, and the matching cap in the
 * discretizer ({@code PathologyCotDiscretizer.MAX_CREDIT_MS}) stops the inflation at the point of
 * use for blinded and authored paths alike.
 */
public final class CotRecordingGate {

    /** Sampling interval of the authoring recorder, in milliseconds (4 Hz, as blinded recording). */
    public static final long SAMPLE_MS = 250L;

    /**
     * Most dwell one tick may contribute: two sample intervals, so a stalled or late tick can never
     * bank up elapsed wall-clock time and credit it in a lump. Deliberately the same value as
     * {@code FocusHeatmap.DT_CAP_MS}.
     */
    public static final long MAX_CREDIT_MS = 2L * SAMPLE_MS;

    private CotRecordingGate() {
    }

    /**
     * One tick's verdict.
     *
     * @param emit        whether a scanpath point should be appended for this tick
     * @param creditedMs  dwell to credit, already clamped to {@link #MAX_CREDIT_MS}; {@code 0} when
     *                    {@code emit} is false
     * @param pauseReason why time was not (fully) credited, or {@code null} when the tick was both
     *                    active and punctual. Note this is non-null <b>with</b> {@code emit == true}
     *                    for a {@link CotRecording.PauseReason#GAP}: the point is still real, but
     *                    the interval between {@code lastTickMs + MAX_CREDIT_MS} and {@code nowMs}
     *                    is uncredited and should be logged as a pause
     */
    public record Decision(boolean emit, long creditedMs, CotRecording.PauseReason pauseReason) {
    }

    /**
     * Decides what to do with one tick.
     *
     * <p>Precedence when several conditions block credit is fixed and deliberate:
     * {@link CotRecording.PauseReason#NO_IMAGE} ▸ {@link CotRecording.PauseReason#SUPPRESSED} ▸
     * {@link CotRecording.PauseReason#UNFOCUSED}. No open image dominates every other explanation,
     * because nothing was being viewed at all; a programmatic sweep is more specific than mere loss
     * of window focus.
     *
     * @param nowMs        this tick's timestamp
     * @param lastTickMs   the previous tick's timestamp
     * @param stageFocused whether QuPath's main window has focus (the gate blinded recording uses —
     *                     {@code FocusHeatmap.mainStageFocused()})
     * @param suppressed   whether tracking is suppressed by a programmatic viewer sweep
     *                     ({@code FocusHeatmap.isTrackingSuppressed()})
     * @param imagePresent whether the bound viewer currently has image data
     */
    public static Decision decide(long nowMs, long lastTickMs, boolean stageFocused,
                                  boolean suppressed, boolean imagePresent) {
        if (!imagePresent)
            return new Decision(false, 0L, CotRecording.PauseReason.NO_IMAGE);
        if (suppressed)
            return new Decision(false, 0L, CotRecording.PauseReason.SUPPRESSED);
        if (!stageFocused)
            return new Decision(false, 0L, CotRecording.PauseReason.UNFOCUSED);

        long dt = nowMs - lastTickMs;
        if (dt <= 0L)
            return new Decision(false, 0L, null);
        if (dt > MAX_CREDIT_MS)
            return new Decision(true, MAX_CREDIT_MS, CotRecording.PauseReason.GAP);
        return new Decision(true, dt, null);
    }

    /**
     * Start of the uncredited interval implied by a {@link CotRecording.PauseReason#GAP} verdict:
     * everything after the credited window is time the author was not observed.
     */
    public static long gapPauseStartMs(long lastTickMs) {
        return lastTickMs + MAX_CREDIT_MS;
    }
}
