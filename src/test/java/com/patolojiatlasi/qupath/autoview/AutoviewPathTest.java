package com.patolojiatlasi.qupath.autoview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.patolojiatlasi.qupath.autoview.AutoviewPath.Axis;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Corner;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Leg;
import com.patolojiatlasi.qupath.autoview.AutoviewPath.Plan;

class AutoviewPathTest {

    // A 300x200 extent with 100x100 fields and no overlap gives exactly a 3-wide x 2-tall grid,
    // with field centres on the 50/150/250 x 50/150 lattice. Small enough to write every expected
    // visit order out by hand, which is what makes the 8-variant test meaningful.
    private static final double EX = 0, EY = 0, EW = 300, EH = 200, F = 100;

    private static String order(Axis axis, Corner corner) {
        return render(AutoviewPath.visitOrder(EX, EY, EW, EH, F, F, 0, axis, corner));
    }

    private static String render(List<double[]> fields) {
        StringBuilder sb = new StringBuilder();
        for (double[] f : fields) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append('(').append((int) f[0]).append(',').append((int) f[1]).append(')');
        }
        return sb.toString();
    }

    @Test
    void allEightVariantsVisitEveryFieldInTheRightOrder() {
        // ROWS: travel along x, drop a row, travel back.
        assertEquals("(50,50) (150,50) (250,50) (250,150) (150,150) (50,150)",
                order(Axis.ROWS, Corner.TOP_LEFT));
        assertEquals("(250,50) (150,50) (50,50) (50,150) (150,150) (250,150)",
                order(Axis.ROWS, Corner.TOP_RIGHT));
        assertEquals("(50,150) (150,150) (250,150) (250,50) (150,50) (50,50)",
                order(Axis.ROWS, Corner.BOTTOM_LEFT));
        assertEquals("(250,150) (150,150) (50,150) (50,50) (150,50) (250,50)",
                order(Axis.ROWS, Corner.BOTTOM_RIGHT));

        // COLUMNS: travel along y, step across a column, travel back.
        assertEquals("(50,50) (50,150) (150,150) (150,50) (250,50) (250,150)",
                order(Axis.COLUMNS, Corner.TOP_LEFT));
        assertEquals("(250,50) (250,150) (150,150) (150,50) (50,50) (50,150)",
                order(Axis.COLUMNS, Corner.TOP_RIGHT));
        assertEquals("(50,150) (50,50) (150,50) (150,150) (250,150) (250,50)",
                order(Axis.COLUMNS, Corner.BOTTOM_LEFT));
        assertEquals("(250,150) (250,50) (150,50) (150,150) (50,150) (50,50)",
                order(Axis.COLUMNS, Corner.BOTTOM_RIGHT));
    }

    @Test
    void everyVariantStartsAtItsNamedCornerAndCoversAllFields() {
        for (Axis axis : Axis.values()) {
            for (Corner corner : Corner.values()) {
                List<double[]> visit = AutoviewPath.visitOrder(EX, EY, EW, EH, F, F, 0, axis, corner);
                assertEquals(6, visit.size(), axis + "/" + corner + " should visit every field once");

                double expectX = (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) ? 50 : 250;
                double expectY = (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) ? 50 : 150;
                assertEquals(expectX, visit.get(0)[0], 1e-9, axis + "/" + corner + " start x");
                assertEquals(expectY, visit.get(0)[1], 1e-9, axis + "/" + corner + " start y");
            }
        }
    }

    @Test
    void unfilteredSweepIsOneContinuousChainAfterTheFirstLeg() {
        Plan plan = AutoviewPath.build(EX, EY, EW, EH, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, null);

        // row 0 travel, the perpendicular drop connector, row 1 travel
        assertEquals(3, plan.legs().size());
        assertEquals(6, plan.fieldCount());

        assertTrue(plan.legs().get(0).jumpToStart(), "the first leg is always reached by a jump");
        for (int i = 1; i < plan.legs().size(); i++)
            assertFalse(plan.legs().get(i).jumpToStart(),
                    "leg " + i + " should glide -- an unfiltered serpentine has no teleports after the start");

        // Legs must chain end-to-start, or the glide would visibly skip.
        for (int i = 1; i < plan.legs().size(); i++) {
            Leg prev = plan.legs().get(i - 1);
            Leg cur = plan.legs().get(i);
            assertEquals(prev.x1(), cur.x0(), 1e-9, "leg " + i + " x0 should continue from leg " + (i - 1));
            assertEquals(prev.y1(), cur.y0(), 1e-9, "leg " + i + " y0 should continue from leg " + (i - 1));
        }

        // 200 across row 0 + 100 down the connector + 200 back across row 1
        assertEquals(500.0, plan.totalLength(), 1e-9);
    }

    @Test
    void connectorLegCarriesNoFieldsSoFieldsAreCountedExactlyOnce() {
        Plan plan = AutoviewPath.build(EX, EY, EW, EH, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, null);
        Leg connector = plan.legs().get(1);
        assertEquals(0, connector.fieldCount());
        assertEquals(1, connector.line(), "the connector belongs to the line it leads into");

        int summed = 0;
        double length = 0;
        for (Leg leg : plan.legs()) {
            summed += leg.fieldCount();
            length += leg.length();
        }
        assertEquals(plan.fieldCount(), summed);
        assertEquals(plan.totalLength(), length, 1e-9);
    }

    @Test
    void serpentineAlternatesTravelDirectionEachLine() {
        Plan plan = AutoviewPath.build(EX, EY, EW, EH, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, null);
        Leg row0 = plan.legs().get(0);
        Leg row1 = plan.legs().get(2);
        assertTrue(row0.x1() > row0.x0(), "row 0 should travel left to right");
        assertTrue(row1.x1() < row1.x0(), "row 1 should travel back, right to left");
    }

    @Test
    void filterDropsFieldsAndSplitsLegsWithAJump() {
        // One row of 3 fields; reject the middle one.
        AutoviewPath.FieldFilter dropMiddle = (x, y, w, h) -> Math.abs(x - 150) > 1e-9;
        Plan plan = AutoviewPath.build(0, 0, 300, 100, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, dropMiddle);

        assertEquals(2, plan.legs().size());
        assertEquals(2, plan.fieldCount());
        assertTrue(plan.legs().get(0).jumpToStart());
        assertTrue(plan.legs().get(1).jumpToStart(), "crossing the dropped field must be a teleport, not a glide");
        assertEquals(50.0, plan.legs().get(0).x0(), 1e-9);
        assertEquals(250.0, plan.legs().get(1).x0(), 1e-9);

        // Both legs are single fields, so nothing is glided at all.
        assertEquals(0.0, plan.totalLength(), 1e-9);
    }

    @Test
    void filterRejectingEverythingYieldsAnEmptyPlan() {
        Plan plan = AutoviewPath.build(EX, EY, EW, EH, F, F, 0, Axis.ROWS, Corner.TOP_LEFT,
                (x, y, w, h) -> false);
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.fieldCount());
        assertEquals(0.0, plan.totalLength(), 1e-9);
    }

    @Test
    void lineIndexIsStableAcrossFilteredAndUnfilteredBuilds() {
        // Drop the whole of line 0. The surviving legs must still report line 1 -- the runtime
        // resumes a replanned sweep by matching `line`, so renumbering here would rewind the sweep.
        Plan filtered = AutoviewPath.build(EX, EY, EW, EH, F, F, 0, Axis.ROWS, Corner.TOP_LEFT,
                (x, y, w, h) -> y > 100);
        assertFalse(filtered.isEmpty());
        assertEquals(3, filtered.fieldCount());
        for (Leg leg : filtered.legs())
            assertEquals(1, leg.line(), "surviving legs keep their original line index");
        assertTrue(filtered.legs().get(0).jumpToStart());
    }

    @Test
    void overlapReducesTheStepAndAddsLines() {
        double[] none = AutoviewPath.centres(0, 300, 100, 0);
        double[] half = AutoviewPath.centres(0, 300, 100, 0.5);
        assertEquals(3, none.length);
        assertEquals(5, half.length);
        assertEquals(50.0, half[0], 1e-9);
        assertEquals(250.0, half[half.length - 1], 1e-9);
    }

    @Test
    void extentSmallerThanOneFieldYieldsASingleCentredField() {
        double[] c = AutoviewPath.centres(10, 40, 100, 0);
        assertEquals(1, c.length);
        assertEquals(30.0, c[0], 1e-9, "a too-small extent is covered by one field centred on it");
    }

    @Test
    void nonIntegerDivisionClampsTheLastLineFlushWithTheExtentEdge() {
        // 250 wide with 100-wide fields does not divide evenly; the spacing absorbs the remainder
        // rather than letting the sweep overshoot or leave a sliver unvisited.
        double[] c = AutoviewPath.centres(0, 250, 100, 0);
        assertEquals(3, c.length);
        assertEquals(50.0, c[0], 1e-9);
        assertEquals(125.0, c[1], 1e-9);
        assertEquals(200.0, c[2], 1e-9, "last centre sits exactly half a field inside the far edge");
    }

    @Test
    void degenerateExtentsProduceNothingRatherThanThrowing() {
        assertTrue(AutoviewPath.build(0, 0, 0, 0, F, F, 0, Axis.ROWS, Corner.TOP_LEFT, null).isEmpty());
        assertTrue(AutoviewPath.build(0, 0, 300, 200, 0, 0, 0, Axis.ROWS, Corner.TOP_LEFT, null).isEmpty());
        assertEquals(0, AutoviewPath.centres(0, -5, 100, 0).length);
    }

    @Test
    void extremeOverlapIsClampedSoThePlanStillAdvances() {
        // overlap 1.0 would make the step zero; it is clamped to 0.9 instead of hanging.
        Plan plan = AutoviewPath.build(EX, EY, EW, EH, F, F, 1.0, Axis.ROWS, Corner.TOP_LEFT, null);
        assertFalse(plan.isEmpty());
        assertTrue(plan.fieldCount() < 1000, "clamped overlap should still give a finite plan");
    }
}
