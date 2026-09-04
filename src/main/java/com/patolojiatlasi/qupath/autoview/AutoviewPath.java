package com.patolojiatlasi.qupath.autoview;

import java.util.ArrayList;
import java.util.List;

/**
 * Serpentine (boustrophedon) sweep geometry, in image pixels.
 * <p>
 * Pure: no QuPath, no JavaFX, no AWT — so it is unit-testable headless, following the convention
 * already used by {@code focus.FocusMap}, {@code pathologycot.PathologyCotDiscretizer} and
 * {@code quiz.QuizScoring} (pure kernel + thin FX driver).
 * <p>
 * The output is a chain of straight {@link Leg}s. Consecutive legs normally join end-to-start, so
 * the runtime glides through the whole plan continuously — including the perpendicular "drop to the
 * next line" connector, which is what makes the motion read as a serpentine rather than as a series
 * of jumps. A leg flagged {@link Leg#jumpToStart()} is instead reached by teleporting: that happens
 * for the very first leg, and wherever a {@link FieldFilter} removed a run of fields in between.
 */
public final class AutoviewPath {

    /** Which way the sweep travels, and therefore how the lines stack. */
    public enum Axis {
        /** Travel along x; lines stack along y. */
        ROWS,
        /** Travel along y; lines stack along x. */
        COLUMNS
    }

    /** Which corner the sweep starts from. Combined with {@link Axis} this gives all 8 variants. */
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Overlap is clamped here; at 1.0 the step would be zero and the plan would never advance. */
    private static final double MAX_OVERLAP = 0.9;

    /** Endpoints closer than this (image px) count as the same point. */
    private static final double EPS = 1e-6;

    /**
     * One straight glide segment, in image pixels.
     *
     * @param line        serpentine line index in <em>visit</em> order (0 = the line containing the
     *                    start corner). Stable across a re-filtered rebuild, because filtering only
     *                    removes fields and never changes how many lines there are or their order —
     *                    which is what lets the runtime resume mid-sweep once the tissue mask lands.
     * @param jumpToStart {@code true} means teleport to {@code (x0, y0)} rather than gliding to it:
     *                    the first leg, or a gap left by dropped fields.
     * @param fieldCount  how many kept field centres this leg covers. Connector legs cover none
     *                    (their endpoint field belongs to the following travel leg), so summing
     *                    {@code fieldCount} over the plan counts every kept field exactly once.
     */
    public record Leg(int line, double x0, double y0, double x1, double y1,
                      boolean jumpToStart, int fieldCount) {

        /** Glided length of this leg itself, in image pixels (excludes any teleport to reach it). */
        public double length() {
            return Math.hypot(x1 - x0, y1 - y0);
        }
    }

    /**
     * A complete sweep.
     *
     * @param fieldCount  total kept fields, for the "alan i / N" readout
     * @param totalLength total glided distance in image pixels (teleports excluded), for progress
     */
    public record Plan(List<Leg> legs, int fieldCount, double totalLength) {

        public boolean isEmpty() {
            return legs.isEmpty();
        }
    }

    /** Per-field predicate; fields that fail are dropped from the plan. */
    @FunctionalInterface
    public interface FieldFilter {
        boolean keep(double centerX, double centerY, double fieldW, double fieldH);
    }

    private AutoviewPath() {}

    /**
     * Build a serpentine plan covering the given extent.
     *
     * @param fieldW          viewport width in image pixels (screen width × downsample)
     * @param fieldH          viewport height in image pixels
     * @param overlapFraction fraction shared with the neighbouring field/line, clamped to [0, 0.9]
     * @param filter          may be {@code null} to keep every field
     */
    public static Plan build(double extentX, double extentY, double extentW, double extentH,
                             double fieldW, double fieldH, double overlapFraction,
                             Axis axis, Corner corner, FieldFilter filter) {

        List<double[]> visit = visitOrder(extentX, extentY, extentW, extentH,
                fieldW, fieldH, overlapFraction, axis, corner);
        int n = visit.size();
        if (n == 0)
            return new Plan(List.of(), 0, 0);

        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            double[] f = visit.get(i);
            keep[i] = filter == null || filter.keep(f[0], f[1], fieldW, fieldH);
        }

        List<Leg> legs = new ArrayList<>();
        int fields = 0;
        double length = 0;

        // Index of the last kept field emitted so far. A new run is "adjacent" to it — and so may be
        // glided into rather than teleported to — exactly when no field was dropped in between.
        int prevKept = -1;
        boolean hasPrev = false;

        int i = 0;
        while (i < n) {
            if (!keep[i]) {
                i++;
                continue;
            }
            // A travel leg is a maximal run of kept fields within one line. It ends at a dropped
            // field or at a line change; the line change becomes a perpendicular connector leg.
            int start = i;
            int line = (int) visit.get(i)[2];
            while (i + 1 < n && keep[i + 1] && (int) visit.get(i + 1)[2] == line)
                i++;
            int end = i;
            i++;

            double[] a = visit.get(start);
            double[] b = visit.get(end);
            boolean adjacent = hasPrev && prevKept == start - 1;

            if (adjacent) {
                double[] p = visit.get(prevKept);
                if (Math.abs(p[0] - a[0]) > EPS || Math.abs(p[1] - a[1]) > EPS) {
                    Leg connector = new Leg(line, p[0], p[1], a[0], a[1], false, 0);
                    legs.add(connector);
                    length += connector.length();
                }
            }

            Leg leg = new Leg(line, a[0], a[1], b[0], b[1], !adjacent, end - start + 1);
            legs.add(leg);
            length += leg.length();
            fields += leg.fieldCount();

            prevKept = end;
            hasPrev = true;
        }

        return new Plan(List.copyOf(legs), fields, length);
    }

    /**
     * Every field centre in serpentine visit order, unfiltered, as {@code {x, y, lineIndex}}.
     * {@code lineIndex} counts in visit order, so line 0 always contains the start corner.
     * <p>
     * This is the single source of truth for ordering — {@link #build} groups its output into legs —
     * and it is the seam the 8-variant test asserts against.
     */
    static List<double[]> visitOrder(double extentX, double extentY, double extentW, double extentH,
                                     double fieldW, double fieldH, double overlapFraction,
                                     Axis axis, Corner corner) {

        if (!(extentW > 0) || !(extentH > 0) || !(fieldW > 0) || !(fieldH > 0))
            return List.of();

        double overlap = clamp(overlapFraction, 0, MAX_OVERLAP);
        boolean rows = axis == Axis.ROWS;

        // Split the extent into a "stacking" axis (across the lines) and a "travel" axis (along one).
        double[] lines = rows
                ? centres(extentY, extentH, fieldH, overlap)
                : centres(extentX, extentW, fieldW, overlap);
        double[] travel = rows
                ? centres(extentX, extentW, fieldW, overlap)
                : centres(extentY, extentH, fieldH, overlap);
        if (lines.length == 0 || travel.length == 0)
            return List.of();

        // Corner decomposes into two independent booleans. For ROWS a "low" line is the top one and
        // "forward" travel is left-to-right; for COLUMNS a "low" line is the left one and "forward"
        // travel is top-to-bottom. All 8 (Axis, Corner) variants fall out of this pair.
        boolean firstLineLow = rows
                ? (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT)
                : (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT);
        boolean firstForward = rows
                ? (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT)
                : (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT);

        List<double[]> out = new ArrayList<>(lines.length * travel.length);
        for (int li = 0; li < lines.length; li++) {
            double lineCoord = lines[firstLineLow ? li : lines.length - 1 - li];
            boolean forward = (li % 2 == 0) == firstForward;
            for (int k = 0; k < travel.length; k++) {
                double t = travel[forward ? k : travel.length - 1 - k];
                out.add(new double[] { rows ? t : lineCoord, rows ? lineCoord : t, li });
            }
        }
        return out;
    }

    /**
     * Evenly spaced field centres along one axis. The first and last centres sit flush with the
     * extent edges (half a field in), so the sweep never overshoots the extent and never leaves a
     * sliver of it unvisited — the spacing absorbs the non-integer remainder instead.
     * <p>
     * Package-visible as a seam for the tests.
     */
    static double[] centres(double min, double span, double field, double overlapFraction) {
        if (!(span > 0) || !(field > 0))
            return new double[0];
        if (span <= field)
            return new double[] { min + span / 2.0 };

        double step = field * (1.0 - overlapFraction);
        if (!(step > 0))
            step = field;
        int n = (int) Math.ceil((span - field) / step) + 1;
        if (n < 2)
            n = 2;

        double actual = (span - field) / (n - 1);
        double[] out = new double[n];
        for (int i = 0; i < n; i++)
            out[i] = min + field / 2.0 + i * actual;
        out[n - 1] = min + span - field / 2.0;   // exact, rather than accumulated FP drift
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v))
            return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
