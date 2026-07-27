package com.patolojiatlasi.qupath.pathologycot;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean-room "AI Session Recorder" discretizer: turns a recorded viewport {@code path} (see
 * {@code focus.FocusHeatmap}, schema {@code atlas-focus-contribution/5}) into an ordered list of
 * {@link Behavior} inspect/peek actions with slide-pixel bounding boxes, following the six-stage
 * pipeline described in Wang et al., "Pathology-CoT", Nature Biomedical Engineering 2026
 * (Extended Data Fig. 1). Independently implemented from the published Methods — no code is ported.
 * Pure and GUI-free (mirrors {@code focus.FocusMap}) so it is unit-testable and headless-safe.
 */
public final class PathologyCotDiscretizer {

    static final long STATIC_INSPECT_MS = 1000;
    static final long PAN_INSPECT_MS    = 2000;   // reserved: PanInspect separation (see plan notes)
    static final long IDLE_GAP_MS       = 60_000;
    static final double MERGE_IOU       = 0.8;
    static final int   VIEW_PX          = 1024;
    static final double PEEK_DS_MAX     = 1.5;
    static final double OVERVIEW_AREA_FRAC = 0.60;
    static final int[] MAG_BINS = {5, 10, 20, 40};

    private PathologyCotDiscretizer() {}

    private enum Mode { STATIC, PEEK }

    /** A mutable run of consecutive same-mode ticks (before normalization). */
    private static final class Run {
        Mode mode; long startMs, endMs; long dwellMs;
        long sumCx, sumCy, sumW, sumH; int n; double sumDs; int dsN;
        void add(int[] pt, long dt) {
            long t = pt[0];
            if (n == 0) startMs = t;
            endMs = t; dwellMs += Math.max(0, dt);
            sumCx += pt[1]; sumCy += pt[2]; sumW += pt[3]; sumH += pt[4]; n++;
            if (pt.length > 5 && pt[5] > 0) { sumDs += pt[5] / 1000.0; dsN++; }
        }
        int cx() { return (int) (sumCx / n); }
        int cy() { return (int) (sumCy / n); }
        int w()  { return (int) (sumW / n); }
        int h()  { return (int) (sumH / n); }
        double ds() { return dsN > 0 ? sumDs / dsN : -1; }  // -1 = unknown zoom
    }

    public static List<Behavior> discretize(List<int[]> path, Double baseMag, int imgW, int imgH) {
        List<Behavior> actions = new ArrayList<>();
        if (path == null || path.size() < 2) return actions;

        // --- Stage 1: segment into STATIC / PAN / PEEK runs, split at idle gaps ---
        Run run = null;
        for (int i = 0; i < path.size(); i++) {
            int[] pt = path.get(i);
            Mode mode = classify(pt);
            boolean idle = i > 0 && (long) pt[0] - path.get(i - 1)[0] > IDLE_GAP_MS;
            if (run == null || run.mode != mode || idle) {
                flush(run, baseMag, imgW, imgH, actions);
                run = new Run(); run.mode = mode;
            }
            long dtNext = (i + 1 < path.size()) ? (long) path.get(i + 1)[0] - pt[0] : 0;
            run.add(pt, dtNext > IDLE_GAP_MS ? 0 : dtNext);
        }
        flush(run, baseMag, imgW, imgH, actions);

        // --- Stage 2: filter overview boxes by area (the < 5x half runs in flush, where the raw
        //     downsample is still available; a binned magBin can't distinguish a 2x survey from 5x) ---
        long imgArea = (long) imgW * imgH;
        actions.removeIf(x -> tooBig(x, imgArea));

        // --- Stage 3: merge high-IoU actions to a fixpoint ---
        mergeByIoU(actions);
        // --- Stage 4: prune a larger action that fully contains a smaller, higher-mag one ---
        pruneContaining(actions);
        // --- Stage 6: normalize each bbox to its magnification's standard field of view ---
        List<Behavior> normalized = new ArrayList<>();
        for (Behavior x : actions) normalized.add(normalize(x, baseMag, imgW, imgH));
        normalized.sort((p, q) -> Long.compare(p.startMs(), q.startMs()));
        return normalized;
    }

    /** STATIC (any non-native-resolution viewing → candidate inspect) vs PEEK (native-res look).
     *  PanInspect separation is deferred (plan notes): a moving viewport is captured as STATIC and
     *  survives only if its accumulated dwell exceeds STATIC_INSPECT_MS. */
    private static Mode classify(int[] a) {
        double ds = a.length > 5 && a[5] > 0 ? a[5] / 1000.0 : -1;
        return (ds > 0 && ds <= PEEK_DS_MAX) ? Mode.PEEK : Mode.STATIC;
    }

    /** Turn a finished run into a candidate Behavior if it meets the dwell threshold. */
    private static void flush(Run r, Double baseMag, int imgW, int imgH, List<Behavior> out) {
        if (r == null || r.n == 0) return;
        // PEEK: any run at native resolution becomes a quick high-mag look (central VIEW_PX box).
        if (r.mode == Mode.PEEK) {
            int half = VIEW_PX / 2;
            out.add(new Behavior(Behavior.Type.PEEK, magBinOf(r.ds(), baseMag),
                    r.cx() - half, r.cy() - half, VIEW_PX, VIEW_PX, r.startMs, r.endMs, r.dwellMs));
            return;
        }
        if (r.dwellMs < STATIC_INSPECT_MS) return;   // too brief to be a deliberate inspect
        // Overview filter (part 1): drop very low-magnification survey views (< 5x true mag) when
        // the objective power is known; the area-based half of the filter runs in stage 2 below.
        double ds = r.ds();
        if (baseMag != null && ds > 0 && baseMag / ds < MAG_BINS[0]) return;
        out.add(new Behavior(Behavior.Type.INSPECT, magBinOf(ds, baseMag),
                r.cx() - r.w()/2, r.cy() - r.h()/2, r.w(), r.h(), r.startMs, r.endMs, r.dwellMs));
    }

    /** Nearest of {5,10,20,40}× from downsample + base objective; null when either is unknown. */
    static String magBinOf(double downsample, Double baseMag) {
        if (baseMag == null || downsample <= 0) return null;
        double mag = baseMag / downsample;
        int best = MAG_BINS[0]; double bestD = Math.abs(mag - MAG_BINS[0]);
        for (int m : MAG_BINS) { double d = Math.abs(mag - m); if (d < bestD) { bestD = d; best = m; } }
        return best + "x";
    }

    private static boolean tooBig(Behavior b, long imgArea) {
        return imgArea > 0 && (double) b.w() * b.h() > OVERVIEW_AREA_FRAC * imgArea;
    }

    static double iou(Behavior a, Behavior b) {
        long ix = Math.max(a.x(), b.x()), iy = Math.max(a.y(), b.y());
        long ax2 = Math.min((long)a.x()+a.w(), (long)b.x()+b.w());
        long ay2 = Math.min((long)a.y()+a.h(), (long)b.y()+b.h());
        long iw = ax2 - ix, ih = ay2 - iy;
        if (iw <= 0 || ih <= 0) return 0;
        double inter = (double) iw * ih;
        double uni = (double) a.w()*a.h() + (double) b.w()*b.h() - inter;
        return uni <= 0 ? 0 : inter / uni;
    }

    private static void mergeByIoU(List<Behavior> xs) {
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < xs.size(); i++)
                for (int j = i + 1; j < xs.size(); j++)
                    if (iou(xs.get(i), xs.get(j)) > MERGE_IOU) {
                        xs.set(i, union(xs.get(i), xs.get(j))); xs.remove(j);
                        merged = true; break outer;
                    }
        }
    }
    private static Behavior union(Behavior a, Behavior b) {
        int x = Math.min(a.x(), b.x()), y = Math.min(a.y(), b.y());
        long x2 = Math.max((long) a.x()+a.w(), (long) b.x()+b.w());
        long y2 = Math.max((long) a.y()+a.h(), (long) b.y()+b.h());
        // keep the more specific (peek, else higher-mag) type/bin; combine time + dwell
        Behavior keep = specificity(a) >= specificity(b) ? a : b;
        return new Behavior(keep.type(), keep.magBin(), x, y, (int) (x2 - x), (int) (y2 - y),
                Math.min(a.startMs(), b.startMs()), Math.max(a.endMs(), b.endMs()), a.dwellMs()+b.dwellMs());
    }
    private static int specificity(Behavior b) {  // higher = more specific
        int m = b.magBin() == null ? 0 : magIntSafe(b.magBin());
        return (b.type()==Behavior.Type.PEEK ? 1000 : 0) + m;
    }
    private static int magIntSafe(String bin) {
        try { return Integer.parseInt(bin.replace("x","")); } catch (Exception e) { return 0; }
    }

    private static void pruneContaining(List<Behavior> xs) {
        xs.removeIf(big -> xs.stream().anyMatch(small ->
                small != big && contains(big, small) && specificity(small) > specificity(big)));
    }
    private static boolean contains(Behavior big, Behavior small) {
        return small.x() >= big.x() && small.y() >= big.y()
                && (long) small.x()+small.w() <= (long) big.x()+big.w()
                && (long) small.y()+small.h() <= (long) big.y()+big.h();
    }

    /** Stage 6: resize bbox to the standard field of view for the mag bin, centered on the action.
     *  field_px = VIEW_PX * downsample, downsample = baseMag/bin (or the run's own ds when baseMag
     *  is null). Peeks keep their VIEW_PX box. Clamped to the image. */
    private static Behavior normalize(Behavior b, Double baseMag, int imgW, int imgH) {
        int field;
        if (b.type() == Behavior.Type.PEEK) field = VIEW_PX;
        else if (baseMag != null && b.magBin() != null)
            field = (int) Math.round(VIEW_PX * (baseMag / magIntSafe(b.magBin())));
        else field = Math.max(b.w(), b.h());   // unknown zoom: keep observed extent
        int half = field / 2;
        int cx = b.centerX(), cy = b.centerY();
        int x = clamp(cx - half, 0, Math.max(0, imgW - field));
        int y = clamp(cy - half, 0, Math.max(0, imgH - field));
        int w = Math.min(field, imgW), h = Math.min(field, imgH);
        return new Behavior(b.type(), b.magBin(), x, y, w, h, b.startMs(), b.endMs(), b.dwellMs());
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }
}
