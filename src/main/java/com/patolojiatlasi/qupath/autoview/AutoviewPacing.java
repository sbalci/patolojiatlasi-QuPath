package com.patolojiatlasi.qupath.autoview;

/**
 * Turns "how much of the next view is already in the tile cache?" into "how fast may the sweep move
 * this frame?".
 * <p>
 * Pure and unit-testable, following the same kernel convention as {@code focus.FocusMap}.
 * <p>
 * Two properties matter and both are deliberate:
 * <ul>
 *   <li><b>It ramps rather than switches.</b> A binary glide/hold would read as stutter, not glide,
 *       every time the sweep crossed a tile boundary. The factor slews towards its target at a
 *       bounded rate, so a cache miss decelerates the sweep and a cache hit accelerates it.</li>
 *   <li><b>It fails open.</b> A {@code NaN} ready fraction -- which is what a probe failure produces
 *       -- targets full speed. A broken probe must never be able to wedge the sweep to a halt.</li>
 * </ul>
 */
public final class AutoviewPacing {

    /** Ready fraction at or above which the sweep glides at full speed. */
    public static final double READY_FULL = 0.90;

    /** Ready fraction at or below which the sweep stops advancing and waits for tiles. */
    public static final double READY_STOP = 0.60;

    /** Maximum change in the speed factor per second -- this is what turns a hold into a decelerate. */
    public static final double SLEW_PER_SEC = 3.0;

    /** Frame times longer than this are treated as this long, so a stall cannot jump the factor. */
    public static final double MAX_DT = 0.25;

    private AutoviewPacing() {}

    /**
     * The speed factor implied by tile readiness alone, ignoring how fast we can get there.
     *
     * @param readyFraction fraction of the next view's tiles already cached, or {@code NaN} if unknown
     * @return 0..1
     */
    public static double targetFactor(double readyFraction) {
        if (Double.isNaN(readyFraction))
            return 1.0;                       // fail open -- see the class javadoc
        if (readyFraction >= READY_FULL)
            return 1.0;
        if (readyFraction <= READY_STOP)
            return 0.0;
        return (readyFraction - READY_STOP) / (READY_FULL - READY_STOP);
    }

    /**
     * The speed factor to actually use this frame: {@link #targetFactor} approached at no more than
     * {@link #SLEW_PER_SEC} per second.
     *
     * @param previousFactor last frame's factor
     * @param dtSeconds      real elapsed time since the last frame
     * @return 0..1
     */
    public static double speedFactor(double readyFraction, double previousFactor, double dtSeconds) {
        double prev = clamp(previousFactor, 0, 1);
        if (Double.isNaN(dtSeconds) || dtSeconds <= 0)
            return prev;
        double dt = Math.min(dtSeconds, MAX_DT);
        double target = targetFactor(readyFraction);
        double maxStep = SLEW_PER_SEC * dt;
        double delta = target - prev;
        if (delta > maxStep)
            delta = maxStep;
        else if (delta < -maxStep)
            delta = -maxStep;
        return clamp(prev + delta, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v))
            return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
