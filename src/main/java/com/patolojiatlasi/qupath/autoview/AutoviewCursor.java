package com.patolojiatlasi.qupath.autoview;

import java.util.List;

/**
 * The moving position within an {@link AutoviewPath.Plan}: where the sweep is, how to step it
 * forward, and how to re-seat it when the plan is rebuilt mid-sweep.
 * <p>
 * Pure — no QuPath, no JavaFX — so the whole cursor walk is unit-testable headless. It was split out
 * of {@code AutoviewSweep} precisely because this arithmetic is the most bug-prone part of the
 * feature and, living inside an FX class, had no test coverage at all.
 * <p>
 * The cursor reports what happened via {@link Event} rather than reaching into the sweep's pacing
 * state; the caller decides what a jump or a finish means.
 */
public final class AutoviewCursor {

    /** Endpoints closer than this (image px) count as the same point. */
    private static final double EPS = 1e-6;

    /** Loop guard: one {@link #advance} may never walk more legs than this. */
    private static final int MAX_LEGS_PER_ADVANCE = 100_000;

    /** What one {@link #advance} call did. */
    public enum Event {
        /** Glided normally; the position may or may not have changed (a point leg dwells in place). */
        MOVED,
        /**
         * Landed on the start of a leg reached by teleporting. The caller should treat this as a
         * discontinuity: reset any pacing carried across frames and re-probe here next frame.
         */
        JUMPED,
        /** The plan is exhausted; the position is the final leg's end. */
        FINISHED
    }

    private final double pointDwell;
    private final AutoviewPath.Axis axis;

    private AutoviewPath.Plan plan;
    private int[] fieldsBefore;
    private int legIndex;
    private double legT;
    private double x;
    private double y;
    private boolean finished;

    /**
     * @param pointDwell how much travel distance a zero-length (single-field) leg is held for, so a
     *                   lone kept field is actually seen instead of flashing past in one frame.
     *                   Normally one field along the travel axis.
     */
    public AutoviewCursor(AutoviewPath.Plan plan, double pointDwell, AutoviewPath.Axis axis) {
        this.pointDwell = pointDwell > 0 ? pointDwell : 0;
        this.axis = axis;
        adopt(plan);
        if (this.plan != null && !this.plan.isEmpty()) {
            AutoviewPath.Leg first = this.plan.legs().get(0);
            this.x = first.x0();
            this.y = first.y0();
        }
    }

    private void adopt(AutoviewPath.Plan p) {
        this.plan = p;
        List<AutoviewPath.Leg> legs = p == null ? List.of() : p.legs();
        this.fieldsBefore = new int[legs.size() + 1];
        for (int i = 0; i < legs.size(); i++)
            fieldsBefore[i + 1] = fieldsBefore[i] + legs.get(i).fieldCount();
        this.legIndex = 0;
        this.legT = 0;
        this.finished = legs.isEmpty();
    }

    // --- stepping ------------------------------------------------------------

    /**
     * Move forward along the plan by {@code step} image pixels.
     * <p>
     * A step large enough to cross several legs is consumed across them, but the walk always stops
     * at the start of a leg that must be teleported to, so the caller sees every discontinuity.
     */
    public Event advance(double step) {
        if (plan == null || finished)
            return Event.FINISHED;

        List<AutoviewPath.Leg> legs = plan.legs();
        int guard = 0;
        while (true) {
            if (++guard > MAX_LEGS_PER_ADVANCE)
                return finish(legs);
            if (legIndex >= legs.size())
                return finish(legs);

            AutoviewPath.Leg leg = legs.get(legIndex);
            double len = leg.length();
            double effLen = effectiveLength(leg);

            if (effLen > 0) {
                double remaining = effLen - legT;
                if (step < remaining) {
                    legT += step;
                    if (len > EPS) {
                        double f = legT / effLen;
                        x = leg.x0() + (leg.x1() - leg.x0()) * f;
                        y = leg.y0() + (leg.y1() - leg.y0()) * f;
                    }
                    return Event.MOVED;
                }
                step -= remaining;
            }

            legIndex++;
            legT = 0;
            if (legIndex >= legs.size()) {
                x = leg.x1();
                y = leg.y1();
                finished = true;
                return Event.FINISHED;
            }

            AutoviewPath.Leg next = legs.get(legIndex);
            x = next.x0();
            y = next.y0();
            if (next.jumpToStart())
                return Event.JUMPED;
            if (step <= 0)
                return Event.MOVED;
        }
    }

    private Event finish(List<AutoviewPath.Leg> legs) {
        if (!legs.isEmpty()) {
            AutoviewPath.Leg last = legs.get(legs.size() - 1);
            x = last.x1();
            y = last.y1();
        }
        legIndex = legs.size();
        legT = 0;
        finished = true;
        return Event.FINISHED;
    }

    /**
     * The point {@code distance} ahead of the current position, clamped to the current leg.
     * <p>
     * Used to probe tile readiness slightly ahead of the cursor. Clamping is deliberate: the
     * lookahead is a fraction of one frame, so walking into later legs would add complexity without
     * changing the answer — and probing beyond the current viewport is exactly the mistake that
     * makes a readiness probe deadlock (see {@code AutoviewSweep.LOOKAHEAD_SEC}).
     */
    public double[] lookahead(double distance) {
        double[] here = { x, y };
        if (plan == null || finished || legIndex >= plan.legs().size())
            return here;
        AutoviewPath.Leg leg = plan.legs().get(legIndex);
        double len = leg.length();
        if (len <= EPS)
            return here;
        double f = Math.min(legT + distance, len) / len;
        return new double[] { leg.x0() + (leg.x1() - leg.x0()) * f, leg.y0() + (leg.y1() - leg.y0()) * f };
    }

    // --- re-seating onto a rebuilt plan -------------------------------------

    /**
     * Re-seat onto a rebuilt plan (one built from the same extent and geometry but with a
     * {@link AutoviewPath.FieldFilter} now applied) without ever moving backwards along the sweep.
     * <p>
     * This is what lets the tissue mask land mid-sweep. Filtering only removes fields — it never
     * reorders them, changes the line count, or changes a line's travel direction (see
     * {@link AutoviewPath#lineTravelsForward}) — so the current {@code (line, travel coordinate)}
     * pair still locates the cursor in the rebuilt plan. The first leg of the current line that has
     * not already been travelled past is taken: resumed in place when it spans the current
     * coordinate, otherwise entered at its start. Failing that, the first leg of a later line.
     * <p>
     * Naively taking "the first leg whose line is at or after the current one" would rewind:
     * connector legs carry the line they lead <em>into</em>, so that leg is usually the connector
     * from the previous line, and the sweep would re-travel the line it is partway through.
     *
     * @param corner the start corner the plan was built with, needed to know each line's direction
     * @return the event describing where the cursor ended up
     */
    public Event resumeOn(AutoviewPath.Plan rebuilt, AutoviewPath.Corner corner) {
        if (rebuilt == null || rebuilt.isEmpty()) {
            finished = true;
            return Event.FINISHED;
        }

        int line = currentLine();
        double travel = AutoviewPath.travelCoordinate(x, y, axis);
        boolean forward = line >= 0 && AutoviewPath.lineTravelsForward(line, axis, corner);

        List<AutoviewPath.Leg> legs = rebuilt.legs();
        if (line < 0) {
            adopt(rebuilt);
            seat(0, 0);
            return legs.get(0).jumpToStart() ? Event.JUMPED : Event.MOVED;
        }

        for (int i = 0; i < legs.size(); i++) {
            AutoviewPath.Leg leg = legs.get(i);
            if (leg.line() < line)
                continue;
            if (leg.line() > line) {
                adopt(rebuilt);
                seat(i, 0);
                return Event.JUMPED;
            }
            // Connector legs carry no fields; their following travel leg is the real candidate.
            if (leg.fieldCount() == 0)
                continue;

            double start = AutoviewPath.travelCoordinate(leg.x0(), leg.y0(), axis);
            double end = AutoviewPath.travelCoordinate(leg.x1(), leg.y1(), axis);
            boolean alreadyPast = forward ? end < travel - EPS : end > travel + EPS;
            if (alreadyPast)
                continue;

            boolean spansUs = forward
                    ? travel >= start - EPS && travel <= end + EPS
                    : travel <= start + EPS && travel >= end - EPS;
            adopt(rebuilt);
            if (spansUs && leg.length() > EPS) {
                seat(i, Math.min(Math.abs(travel - start), leg.length()));
                return Event.MOVED;
            }
            seat(i, 0);
            return Event.JUMPED;
        }

        // Everything still ahead was filtered away.
        adopt(rebuilt);
        return finish(legs);
    }

    private void seat(int index, double t) {
        this.legIndex = index;
        this.legT = t;
        this.finished = false;
        AutoviewPath.Leg leg = plan.legs().get(index);
        double len = leg.length();
        if (len > EPS) {
            double f = Math.min(t / len, 1.0);
            x = leg.x0() + (leg.x1() - leg.x0()) * f;
            y = leg.y0() + (leg.y1() - leg.y0()) * f;
        } else {
            x = leg.x0();
            y = leg.y0();
        }
    }

    // --- state ---------------------------------------------------------------

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public boolean isFinished() {
        return finished;
    }

    public AutoviewPath.Plan getPlan() {
        return plan;
    }

    /** The serpentine line the cursor is on, or -1 once the plan is exhausted. */
    public int currentLine() {
        if (plan == null || legIndex >= plan.legs().size())
            return -1;
        return plan.legs().get(legIndex).line();
    }

    /** Kept fields visited so far — monotone within a plan, and never above the plan's total. */
    public int fieldsVisited() {
        if (plan == null || plan.fieldCount() <= 0)
            return 0;
        if (finished)
            return plan.fieldCount();
        int done = fieldsBefore[Math.min(legIndex, fieldsBefore.length - 1)];
        if (legIndex < plan.legs().size()) {
            AutoviewPath.Leg leg = plan.legs().get(legIndex);
            double effLen = effectiveLength(leg);
            if (effLen > 0)
                done += (int) Math.floor(Math.min(legT / effLen, 1.0) * leg.fieldCount());
        }
        return Math.min(done, plan.fieldCount());
    }

    /** Fraction of the plan's fields visited, 0..1. */
    public double progress() {
        if (plan == null || plan.fieldCount() <= 0)
            return 0;
        return fieldsVisited() / (double) plan.fieldCount();
    }

    /**
     * How much distance a leg absorbs. A single-field leg has zero geometric length, so it is given
     * {@link #pointDwell} instead; a zero-length connector absorbs nothing and is stepped straight
     * over.
     */
    private double effectiveLength(AutoviewPath.Leg leg) {
        double len = leg.length();
        if (len > EPS)
            return len;
        return leg.fieldCount() > 0 ? pointDwell : 0;
    }
}
