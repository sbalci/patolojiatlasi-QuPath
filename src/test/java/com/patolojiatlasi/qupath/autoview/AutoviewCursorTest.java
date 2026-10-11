package com.patolojiatlasi.qupath.autoview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.patolojiatlasi.qupath.autoview.AutoviewCursor.Event;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Axis;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Corner;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Plan;

class AutoviewCursorTest {

    // 300x200 extent, 100x100 fields, no overlap -> a 3-wide x 2-tall field grid on the
    // 50/150/250 x 50/150 lattice. Same fixture as AutoviewPathTest, so the two read together.
    private static final double EW = 300, EH = 200, F = 100;

    private static Plan plan(Axis axis, Corner corner, AutoviewPath.FieldFilter filter) {
        return AutoviewPath.build(0, 0, EW, EH, F, F, 0, axis, corner, filter);
    }

    private static AutoviewCursor cursor(Plan p, Axis axis) {
        return new AutoviewCursor(p, F, axis);
    }

    @Test
    void startsAtTheFirstLegStart() {
        AutoviewCursor c = cursor(plan(Axis.ROWS, Corner.TOP_LEFT, null), Axis.ROWS);
        assertEquals(50.0, c.getX(), 1e-9);
        assertEquals(50.0, c.getY(), 1e-9);
        assertEquals(0, c.fieldsVisited());
        assertFalse(c.isFinished());
    }

    @Test
    void walkingTheWholePlanEndsAtTheLastLegEndWithEveryFieldCounted() {
        Plan p = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(p, Axis.ROWS);

        Event last = Event.MOVED;
        for (int i = 0; i < 10_000 && last != Event.FINISHED; i++)
            last = c.advance(1.0);

        assertEquals(Event.FINISHED, last);
        assertTrue(c.isFinished());
        assertEquals(50.0, c.getX(), 1e-9, "row 1 travels right-to-left, so it ends at x=50");
        assertEquals(150.0, c.getY(), 1e-9);
        assertEquals(p.fieldCount(), c.fieldsVisited());
        assertEquals(1.0, c.progress(), 1e-9);
    }

    @Test
    void fieldIndexNeverDecreasesUnderRandomStepSizes() {
        Plan p = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(p, Axis.ROWS);
        Random rnd = new Random(20261011L);

        int previous = 0;
        double previousProgress = 0;
        for (int i = 0; i < 5000 && !c.isFinished(); i++) {
            c.advance(rnd.nextDouble() * 40.0);
            int now = c.fieldsVisited();
            assertTrue(now >= previous, "fieldsVisited went backwards: " + previous + " -> " + now);
            assertTrue(c.progress() >= previousProgress - 1e-12, "progress went backwards");
            assertTrue(now <= p.fieldCount(), "fieldsVisited overshot the plan total");
            previous = now;
            previousProgress = c.progress();
        }
        assertTrue(c.isFinished(), "random walk should still terminate");
        assertEquals(p.fieldCount(), c.fieldsVisited());
    }

    @Test
    void oneHugeStepStopsAtAJumpRatherThanTeleportingThroughIt() {
        // One row of three fields with the middle one dropped -> leg, [gap], leg.
        Plan p = AutoviewPath.build(0, 0, 300, 100, F, F, 0, Axis.ROWS, Corner.TOP_LEFT,
                (x, y, w, h) -> Math.abs(x - 150) > 1e-9);
        assertEquals(2, p.legs().size());

        AutoviewCursor c = cursor(p, Axis.ROWS);
        // A step far larger than the whole plan must still halt at the discontinuity.
        Event e = c.advance(100_000);
        assertEquals(Event.JUMPED, e);
        assertEquals(250.0, c.getX(), 1e-9, "should be parked at the second run's start");
        assertFalse(c.isFinished());
    }

    @Test
    void aLoneFieldIsHeldForOneFieldOfTravelInsteadOfFlashingPast() {
        Plan p = AutoviewPath.build(0, 0, 100, 100, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, null);
        assertEquals(1, p.fieldCount());
        assertEquals(0.0, p.legs().get(0).length(), 1e-9, "a single field is a zero-length leg");

        AutoviewCursor c = cursor(p, Axis.ROWS);
        // Just under one field of travel: still dwelling, not finished.
        assertEquals(Event.MOVED, c.advance(F - 1));
        assertFalse(c.isFinished(), "a lone field must absorb a field's worth of travel");
        // The remainder completes it.
        assertEquals(Event.FINISHED, c.advance(2));
        assertTrue(c.isFinished());
    }

    @Test
    void lookaheadStaysOnTheCurrentLegAndNeverRunsPastItsEnd() {
        AutoviewCursor c = cursor(plan(Axis.ROWS, Corner.TOP_LEFT, null), Axis.ROWS);
        double[] near = c.lookahead(10);
        assertEquals(60.0, near[0], 1e-9);
        assertEquals(50.0, near[1], 1e-9);

        // Row 0 spans x 50..250; a huge lookahead clamps to its end, not into row 1.
        double[] far = c.lookahead(100_000);
        assertEquals(250.0, far[0], 1e-9);
        assertEquals(50.0, far[1], 1e-9);
    }

    // --- re-seating ----------------------------------------------------------

    @Test
    void resumeMidLineDoesNotRewindAlongTravel() {
        // This is the regression guard for the replan-rewinds bug: taking "the first leg whose line
        // is >= the current line" lands on the CONNECTOR into this line, which starts back at the
        // previous line's end, and the sweep re-travels a line it was partway through.
        Plan full = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.ROWS);

        // Walk into row 1 (which travels right-to-left from x=250 to x=50).
        while (c.currentLine() != 1 && !c.isFinished())
            c.advance(5);
        while (c.getX() > 150 && !c.isFinished())
            c.advance(5);
        assertEquals(1, c.currentLine());
        double xBefore = c.getX();
        assertTrue(xBefore <= 150.0 + 1e-9, "should be at or past the middle of row 1");

        // A rebuilt plan identical in geometry (filter keeps everything).
        Plan rebuilt = plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> true);
        Event e = c.resumeOn(rebuilt, Corner.TOP_LEFT);

        assertEquals(Event.MOVED, e, "an in-place resume is not a teleport");
        assertEquals(1, c.currentLine(), "must stay on row 1");
        assertEquals(xBefore, c.getX(), 1e-6, "must resume at the same place, not rewind");
        assertEquals(150.0, c.getY(), 1e-9);
    }

    @Test
    void resumeSkipsAheadWhenTheRestOfTheCurrentLineWasFilteredAway() {
        Plan full = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.ROWS);
        // Part-way along row 0 (left-to-right).
        c.advance(60);
        assertEquals(0, c.currentLine());
        assertEquals(110.0, c.getX(), 1e-9);

        // Rebuild keeping only row 1.
        Plan rebuilt = plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> y > 100);
        Event e = c.resumeOn(rebuilt, Corner.TOP_LEFT);

        assertEquals(Event.JUMPED, e);
        assertEquals(1, c.currentLine(), "nothing of row 0 survived, so move on to row 1");
        assertEquals(150.0, c.getY(), 1e-9);
    }

    @Test
    void resumeJumpsForwardOverAHolePunchedInTheCurrentLine() {
        // The realistic tissue-mask case: the mask lands while we are mid-line and removes the very
        // field we are standing on, but later fields on that line survive. Row 1 of a TOP_LEFT ROWS
        // sweep travels right-to-left (250 -> 150 -> 50), so "forward" here means decreasing x.
        Plan full = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.ROWS);
        while (c.currentLine() != 1 && !c.isFinished())
            c.advance(5);
        // Glide to roughly the middle field of row 1.
        while (c.getX() > 150 && !c.isFinished())
            c.advance(5);
        assertEquals(1, c.currentLine());
        assertTrue(c.getX() <= 150.0 + 1e-9);

        // Rebuild dropping the x=150 column entirely; x=250 is behind us, x=50 is ahead.
        Plan rebuilt = plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> Math.abs(x - 150) > 1e-9);
        Event e = c.resumeOn(rebuilt, Corner.TOP_LEFT);

        assertEquals(Event.JUMPED, e, "the field under us is gone, so the next kept one is a jump");
        assertEquals(1, c.currentLine(), "must stay on row 1 -- x=50 is still ahead on it");
        assertEquals(50.0, c.getX(), 1e-9, "must jump FORWARD along travel, to x=50, not back to x=250");
        assertEquals(150.0, c.getY(), 1e-9);
    }

    @Test
    void resumeNeverMovesToAnEarlierLine() {
        Plan full = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.ROWS);
        while (c.currentLine() != 1 && !c.isFinished())
            c.advance(5);
        assertEquals(1, c.currentLine());

        // Rebuild keeping only row 0 -- strictly behind us. There is nothing left to sweep.
        Plan rebuilt = plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> y < 100);
        Event e = c.resumeOn(rebuilt, Corner.TOP_LEFT);

        assertEquals(Event.FINISHED, e, "a plan entirely behind the cursor is finished, not a rewind");
        assertTrue(c.isFinished());
    }

    @Test
    void resumeOnAnEmptyPlanFinishesRatherThanThrowing() {
        AutoviewCursor c = cursor(plan(Axis.ROWS, Corner.TOP_LEFT, null), Axis.ROWS);
        c.advance(30);
        assertEquals(Event.FINISHED, c.resumeOn(plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> false),
                Corner.TOP_LEFT));
        assertTrue(c.isFinished());
    }

    @Test
    void resumeWorksOnTheColumnsAxisToo() {
        Plan full = plan(Axis.COLUMNS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.COLUMNS);
        // Column 0 travels top-to-bottom at x=50, y 50..150.
        c.advance(40);
        assertEquals(0, c.currentLine());
        assertEquals(90.0, c.getY(), 1e-9);
        double yBefore = c.getY();

        Event e = c.resumeOn(plan(Axis.COLUMNS, Corner.TOP_LEFT, (x, y, w, h) -> true), Corner.TOP_LEFT);
        assertEquals(Event.MOVED, e);
        assertEquals(yBefore, c.getY(), 1e-6, "columns resume by y, not x");
        assertEquals(50.0, c.getX(), 1e-9);
    }

    @Test
    void backwardTravellingLineResumesCorrectly() {
        // Row 1 of a TOP_LEFT ROWS sweep travels right-to-left, so "ahead" means DECREASING x.
        // Getting the direction wrong here silently rewinds the sweep by a whole line.
        assertTrue(AutoviewPath.lineTravelsForward(0, Axis.ROWS, Corner.TOP_LEFT));
        assertFalse(AutoviewPath.lineTravelsForward(1, Axis.ROWS, Corner.TOP_LEFT));

        Plan full = plan(Axis.ROWS, Corner.TOP_LEFT, null);
        AutoviewCursor c = cursor(full, Axis.ROWS);
        while (c.currentLine() != 1 && !c.isFinished())
            c.advance(5);
        while (c.getX() > 100 && !c.isFinished())
            c.advance(5);
        double xBefore = c.getX();

        c.resumeOn(plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> true), Corner.TOP_LEFT);
        assertTrue(c.getX() <= xBefore + 1e-6,
                "on a backward line the cursor must not move to a larger x: " + xBefore + " -> " + c.getX());
    }

    @Test
    void lineDirectionAlternatesForEveryVariant() {
        for (Axis axis : Axis.values()) {
            for (Corner corner : Corner.values()) {
                boolean first = AutoviewPath.lineTravelsForward(0, axis, corner);
                assertEquals(!first, AutoviewPath.lineTravelsForward(1, axis, corner),
                        axis + "/" + corner + " must alternate");
                assertEquals(first, AutoviewPath.lineTravelsForward(2, axis, corner),
                        axis + "/" + corner + " must alternate with period 2");
            }
        }
    }

    @Test
    void emptyPlanIsFinishedImmediately() {
        AutoviewCursor c = cursor(plan(Axis.ROWS, Corner.TOP_LEFT, (x, y, w, h) -> false), Axis.ROWS);
        assertTrue(c.isFinished());
        assertEquals(Event.FINISHED, c.advance(10));
        assertEquals(0, c.fieldsVisited());
        assertEquals(0.0, c.progress(), 1e-9);
    }
}
