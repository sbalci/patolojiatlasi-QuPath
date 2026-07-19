package com.patolojiatlasi.qupath;

/** Opens an atlas case in a second viewer beside the user's own slide, at matched magnification. */
public final class BenchReference {

    private BenchReference() {}

    /**
     * Downsample for the reference viewer so it shows the same µm/px on screen as your viewer:
     * {@code yourDs * mppYours / mppRef}. {@link Double#NaN} if any input is non-positive (unknown
     * calibration → caller skips matching).
     */
    public static double matchedDownsample(double yourDs, double mppYours, double mppRef) {
        if (yourDs <= 0 || mppYours <= 0 || mppRef <= 0)
            return Double.NaN;
        return yourDs * mppYours / mppRef;
    }
}
