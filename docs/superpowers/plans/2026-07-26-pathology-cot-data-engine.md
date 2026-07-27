# Pathology-CoT Data Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the atlas extension's recorded blinded navigation into Pathology-CoT
(Wang et al., *Nat. Biomed. Eng.* 2026) supervision — a clean-room "AI Session Recorder" that
discretizes a recorded `path[]` into `inspect`/`peek` ROIs, an analyzable `behaviors.json`, a
reviewable guided-tour draft, and a Pathology-CoT case-folder export (crops + `conversation.json`).

**Architecture:** New package `com.patolojiatlasi.qupath.pathologycot`. A **pure, unit-testable
discretizer** (no JavaFX/QuPath-GUI deps, like `focus/FocusMap`) → a **behaviors.json** serializer
(the analysis contract) → a thin **draft builder** that emits an existing `quiz.AtlasQuiz` of
`NARRATION` stops (reviewed in the existing tour author/runner — the human-in-the-loop step) → a
**QuPath-side exporter** that renders crops via `ImageServer.readRegion` + `ImageIO` and writes
`conversation.json`. Three actions under the existing **Araştırma** menu.

**Tech Stack:** Java 21, QuPath 0.6 API (`ImageServer.readRegion`, `RegionRequest`,
`ROIs.createRectangleROI`, `GsonTools`), Gson, JavaFX (menu + choosers only), JUnit 5.

## Global Constraints

- **Clean-room only.** The reference repo (github.com/zhihuanglab/Pathology-CoT) has **no LICENSE →
  all-rights-reserved**. Do NOT copy its Python. Implement from the paper's Methods (thresholds
  below are published facts). Docstrings may cite the paper; no code is ported.
- **All navigation coordinates are full-res SLIDE (image) pixels that already respect pan+zoom**
  (verified: viewport = `getDisplayedRegionShape().getBounds()`; mouse via
  `componentPointToImagePoint`). `Behavior` bboxes are slide pixels. Never treat them as screen px.
- **Discretizer thresholds (from paper Methods / Extended Data Fig. 1), as named constants:**
  `STATIC_INSPECT_MS=1000`, `PAN_INSPECT_MS=2000`, `IDLE_GAP_MS=60000`, `MERGE_IOU=0.8`,
  `MAG_BINS={5,10,20,40}`, `VIEW_PX=1024` (standard field / peek crop), `PEEK_DS_MAX=1.5`
  (downsample at/under this ⇒ native-resolution peek), `OVERVIEW_AREA_FRAC=0.60`,
  `STATIC_MOVE_FRAC=0.5` (center moves < this × min(w,h) between ticks ⇒ static, else pan).
- **Reuse, do not modify, the quiz model.** `quiz.AtlasQuiz`, `QuizQuestion`(+`Viewport`,
  `highlightGeoJson`, `NARRATION`), `AtlasQuizIO`, `QuizGeometry.toGeoJson`, `QuizAuthorWindow`,
  `QuizRunnerWindow` are used as-is. The per-ROI rationale is the stop's `explanation` (one field).
- **`String.format` pins `java.util.Locale.US`** everywhere (Turkish-locale JVM).
- **Data governance:** the case-folder export contains slide pixels + rationale + diagnosis (NOT
  anonymized, unlike blinded fragments). Export is only ever an explicit user action to a
  user-chosen folder, and writes a short `README.txt` stating it is consented, non-anonymized
  research output distinct from the blinded contributions.
- **QuPath floor 0.6.0** (do not touch `AtlasExtension.getQuPathVersion`).

## Reference: the recorded fragment JSON (`focus/FocusHeatmap`, schema `atlas-focus-contribution/5`)

```jsonc
{ "schema":"atlas-focus-contribution/5", "slideKey":"https://…/x.dzi" | "sha256:…",
  "sessionId":"<uuid>", "imageWidth":100000, "imageHeight":80000,
  "baseMagnification": 40.0 | null,            // objective power; true mag = baseMagnification/downsample
  "path": [ [tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY], … ],   // slide px; dsMilli = downsample*1000
  "decision": { "diagnosis":"…", "confidence":4, "decisionMs":…, "promptShownMs":… }   // may be absent
}
```
Older fragments: `/3` points are `[t,cx,cy,w,h]` (no zoom → treat downsample as unknown = skip
peek/mag), `/4` are `[t,cx,cy,w,h,dsMilli]`. Accept `/3`–`/5`; read `dsMilli` when present.

## File Structure

- `src/main/java/com/patolojiatlasi/qupath/pathologycot/Behavior.java` — the value type.
- `.../pathologycot/PathologyCotDiscretizer.java` — pure clean-room discretizer (Task 1).
- `.../pathologycot/CotFragment.java` — parsed fragment inputs (path/baseMag/decision/keys) (Task 2).
- `.../pathologycot/PathologyCotIO.java` — read fragment JSON → `CotFragment`; write `behaviors.json`
  (schema `pathology-cot-behaviors/1`); `SlideKeys` matching mirror (Task 2).
- `.../pathologycot/PathologyCotDraft.java` — `Behavior[]` → `AtlasQuiz` (Task 3).
- `.../pathologycot/PathologyCotExport.java` — reviewed `AtlasQuiz` + `ImageServer` → case folder
  (Task 4).
- `.../pathologycot/PathologyCotActions.java` — the three JavaFX menu handlers (Task 5).
- `AtlasExtension.java` — add the "Pathology-CoT" submenu (Task 5).
- Tests under `src/test/java/com/patolojiatlasi/qupath/pathologycot/`.

---

## Task 1: `Behavior` + pure discretizer

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/Behavior.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotDiscretizer.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotDiscretizerTest.java`

**Interfaces:**
- Produces: `Behavior` (record) and `List<Behavior> PathologyCotDiscretizer.discretize(List<int[]>
  path, Double baseMagnification, int imgW, int imgH)`. `Behavior` fields:
  `Type type` (`INSPECT`/`PEEK`), `String magBin` (`"5x"`/`"10x"`/`"20x"`/`"40x"` or `null` when
  baseMag unknown), `int x,y,w,h` (slide px), `long startMs,endMs,dwellMs`, `int centerX()`,
  `int centerY()`.

- [ ] **Step 1: Write `Behavior`.**

```java
package com.patolojiatlasi.qupath.pathologycot;

/** One discretized viewing action in slide-pixel coordinates. Clean-room implementation of the
 *  inspect/peek behaviour primitives from Wang et al., Pathology-CoT, Nat. Biomed. Eng. 2026. */
public record Behavior(Type type, String magBin, int x, int y, int w, int h,
                       long startMs, long endMs, long dwellMs) {
    public enum Type { INSPECT, PEEK }
    public int centerX() { return x + w / 2; }
    public int centerY() { return y + h / 2; }
}
```

- [ ] **Step 2: Write the failing discretizer test** (asserts the primitives). Uses a helper to
  build path points `p(t,cx,cy,w,h,ds)` = `new int[]{t,cx,cy,w,h,ds,-1,-1}`.

```java
package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PathologyCotDiscretizerTest {
    private static int[] p(int t,int cx,int cy,int w,int h,int ds){return new int[]{t,cx,cy,w,h,ds,-1,-1};}

    @Test void staticDwellBecomesInspect() {
        // held ~static at 10x (baseMag 40 -> downsample 4 -> dsMilli 4000) for 1.5s
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(1, out.size());
        assertEquals(Behavior.Type.INSPECT, out.get(0).type());
        assertEquals("10x", out.get(0).magBin());
    }

    @Test void shortStaticIsDropped() {  // < STATIC_INSPECT_MS
        List<int[]> path = List.of(p(0,5000,5000,4096,4096,4000), p(250,5000,5000,4096,4096,4000));
        assertTrue(PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).isEmpty());
    }

    @Test void nativeZoomBecomesPeek() {   // downsample ~1 (dsMilli 1000) -> 40x peek
        List<int[]> path = List.of(p(0,7000,7000,1024,1024,1000), p(250,7000,7000,1024,1024,1000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000);
        assertEquals(1, out.size());
        assertEquals(Behavior.Type.PEEK, out.get(0).type());
        assertEquals("40x", out.get(0).magBin());
    }

    @Test void highIoUActionsMerge() {
        // two nearly-identical inspects separated by an idle gap → two actions that then merge
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        int base = 1500 + 70_000;   // > IDLE_GAP_MS: forces a distinct second action (IoU > 0.8)
        for (int t=base;t<=base+1500;t+=250) path.add(p(t, 5100,5100, 4096,4096, 4000));
        assertEquals(1, PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).size());
    }

    @Test void idleGapSplitsSegments() {
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        int base = 1500 + 70_000;  // > IDLE_GAP_MS
        for (int t=base;t<=base+1500;t+=250) path.add(p(t, 20000,20000, 4096,4096, 4000));
        assertEquals(2, PathologyCotDiscretizer.discretize(path, 40.0, 100000, 80000).size());
    }

    @Test void emptyAndSinglePointAreSafe() {
        assertTrue(PathologyCotDiscretizer.discretize(List.of(), 40.0, 100, 100).isEmpty());
        assertTrue(PathologyCotDiscretizer.discretize(List.of(p(0,1,1,2,2,4000)), 40.0, 100,100).isEmpty());
    }

    @Test void nullBaseMagLeavesMagBinNull() {
        List<int[]> path = new ArrayList<>();
        for (int t=0;t<=1500;t+=250) path.add(p(t, 5000,5000, 4096,4096, 4000));
        List<Behavior> out = PathologyCotDiscretizer.discretize(path, null, 100000, 80000);
        assertFalse(out.isEmpty());
        assertNull(out.get(0).magBin());
    }
}
```

- [ ] **Step 3: Run the test, verify it fails to compile** (`discretize` undefined).
  Run: `./gradlew --no-daemon test --tests "*PathologyCotDiscretizerTest"` → FAIL.

- [ ] **Step 4: Implement `PathologyCotDiscretizer`.** Pure static logic; the six stages of
  Extended Data Fig. 1. Complete code:

```java
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
    static final double STATIC_MOVE_FRAC   = 0.5;
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
        for (int i = 0; i < path.size() - 1; i++) {
            int[] a = path.get(i), b = path.get(i + 1);
            long dt = (long) b[0] - a[0];
            Mode mode = classify(a);
            boolean idle = dt > IDLE_GAP_MS;
            if (run == null || run.mode != mode || idle) {
                flush(run, baseMag, imgW, imgH, actions);
                run = new Run(); run.mode = mode;
            }
            run.add(a, idle ? 0 : dt);
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
        int x2 = Math.max(a.x()+a.w(), b.x()+b.w()), y2 = Math.max(a.y()+a.h(), b.y()+b.h());
        // keep the more specific (peek, else higher-mag) type/bin; combine time + dwell
        Behavior keep = specificity(a) >= specificity(b) ? a : b;
        return new Behavior(keep.type(), keep.magBin(), x, y, x2 - x, y2 - y,
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
                && small.x()+small.w() <= big.x()+big.w() && small.y()+small.h() <= big.y()+big.h();
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
```

- [ ] **Step 5: Run tests to green.** Run: `./gradlew --no-daemon test --tests
  "*PathologyCotDiscretizerTest"` → PASS. If `staticDwellBecomesInspect` expects exactly one action
  but the sweep/dwell edge cases produce extras, refine `flush`'s dwell accounting until the listed
  assertions hold (they encode the intended behaviour).

- [ ] **Step 6: Commit.** `git add … && git commit -m "feat(cot): clean-room Pathology-CoT
  discretizer (inspect/peek + 6-stage pipeline)"`

---

## Task 2: Fragment reader + `behaviors.json` (analysis contract) + slide-key match

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/CotFragment.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotIO.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotIOTest.java`

**Interfaces:**
- Consumes: `Behavior` (Task 1).
- Produces: `CotFragment readFragment(File)`; `void writeBehaviors(File out, CotFragment frag,
  List<Behavior> behaviors)`; `String anonymizedKeyForOpenSlide(String uri)` +
  `boolean slideMatches(CotFragment, String openUri)` (mirror of `FocusHeatmap`).

- [ ] **Step 1: Write `CotFragment`** — a parsed view of the fragment JSON.

```java
package com.patolojiatlasi.qupath.pathologycot;

import java.util.List;
import java.util.Map;

/** Parsed subset of a focus fragment (schema atlas-focus-contribution/3–5) needed to discretize. */
public record CotFragment(String slideKey, String sessionId, int imageWidth, int imageHeight,
                          Double baseMagnification, List<int[]> path, Map<String,Object> decision) {}
```

- [ ] **Step 2: Write the failing `PathologyCotIOTest`.** Round-trips a tiny fragment fixture and a
  behaviors write. (Write the fixture JSON to a JUnit `@TempDir`.)

```java
package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File; import java.nio.file.Files; import java.util.*;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.io.TempDir;

class PathologyCotIOTest {
    @TempDir File dir;

    private File writeFragment() throws Exception {
        String json = """
          {"schema":"atlas-focus-contribution/5","slideKey":"https://x/y.dzi","sessionId":"abcd1234",
           "imageWidth":100000,"imageHeight":80000,"baseMagnification":40.0,
           "path":[[0,5000,5000,4096,4096,4000,-1,-1],[250,5000,5000,4096,4096,4000,-1,-1],
                   [500,5000,5000,4096,4096,4000,-1,-1],[1500,5000,5000,4096,4096,4000,-1,-1]],
           "decision":{"diagnosis":"benign","confidence":4,"decisionMs":9000,"promptShownMs":8000}}""";
        File f = new File(dir, "frag.json"); Files.writeString(f.toPath(), json); return f;
    }

    @Test void readsFragmentFields() throws Exception {
        CotFragment fr = PathologyCotIO.readFragment(writeFragment());
        assertEquals("https://x/y.dzi", fr.slideKey());
        assertEquals(40.0, fr.baseMagnification());
        assertEquals(4, fr.path().size());
        assertEquals(6, fr.path().get(0).length >= 6 ? 6 : fr.path().get(0).length); // has zoom
        assertEquals("benign", fr.decision().get("diagnosis"));
    }

    @Test void writesBehaviorsJson() throws Exception {
        CotFragment fr = PathologyCotIO.readFragment(writeFragment());
        List<Behavior> b = PathologyCotDiscretizer.discretize(fr.path(), fr.baseMagnification(),
                fr.imageWidth(), fr.imageHeight());
        File out = new File(dir, "behaviors.json");
        PathologyCotIO.writeBehaviors(out, fr, b);
        String s = Files.readString(out.toPath());
        assertTrue(s.contains("\"schema\": \"pathology-cot-behaviors/1\""));
        assertTrue(s.contains("\"behaviors\""));
        assertTrue(s.contains("\"type\""));
    }

    @Test void slideMatchAnonymizesLikeRecorder() throws Exception {
        CotFragment http = PathologyCotIO.readFragment(writeFragment());
        assertTrue(PathologyCotIO.slideMatches(http, "https://x/y.dzi?mpp=0.25")); // query stripped
        assertFalse(PathologyCotIO.slideMatches(http, "https://x/OTHER.dzi"));
    }
}
```

- [ ] **Step 3: Run → fails to compile.**

- [ ] **Step 4: Implement `PathologyCotIO`.** Parse with Gson; write behaviors with pretty Gson.
  `SlideKeys` logic is a **verbatim mirror** of `FocusHeatmap.slideKey`/`anonymizeSlideKey` (kept in
  sync; documented). Complete code (elide obvious imports):

```java
package com.patolojiatlasi.qupath.pathologycot;

import com.google.gson.*;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.Files;
import java.security.MessageDigest; import java.util.*;

public final class PathologyCotIO {
    public static final String BEHAVIORS_SCHEMA = "pathology-cot-behaviors/1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private PathologyCotIO() {}

    public static CotFragment readFragment(File file) throws IOException {
        JsonObject o;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("Bozuk fragment JSON: " + e.getMessage(), e); }

        List<int[]> path = new ArrayList<>();
        if (o.has("path") && o.get("path").isJsonArray())
            for (JsonElement pe : o.getAsJsonArray("path")) {
                JsonArray pa = pe.getAsJsonArray();
                int[] pt = new int[pa.size()];
                for (int i = 0; i < pa.size(); i++) pt[i] = pa.get(i).getAsInt();
                if (pt.length >= 5) path.add(pt);
            }
        Double baseMag = o.has("baseMagnification") && !o.get("baseMagnification").isJsonNull()
                ? o.get("baseMagnification").getAsDouble() : null;
        Map<String,Object> decision = new LinkedHashMap<>();
        if (o.has("decision") && o.get("decision").isJsonObject())
            for (var e : o.getAsJsonObject("decision").entrySet())
                decision.put(e.getKey(), e.getValue().isJsonNull() ? null
                        : (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()
                           ? e.getValue().getAsString() : (Object) e.getValue()));
        return new CotFragment(str(o,"slideKey"), str(o,"sessionId"),
                intt(o,"imageWidth"), intt(o,"imageHeight"), baseMag, path, decision);
    }

    public static void writeBehaviors(File out, CotFragment f, List<Behavior> behaviors) throws IOException {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("schema", BEHAVIORS_SCHEMA);
        m.put("slideKey", f.slideKey()); m.put("sessionId", f.sessionId());
        m.put("imageWidth", f.imageWidth()); m.put("imageHeight", f.imageHeight());
        m.put("baseMagnification", f.baseMagnification());
        if (f.decision() != null && !f.decision().isEmpty()) m.put("decision", f.decision());
        List<Map<String,Object>> bs = new ArrayList<>();
        for (Behavior b : behaviors) {
            Map<String,Object> bm = new LinkedHashMap<>();
            bm.put("type", b.type().name().toLowerCase(Locale.ROOT));
            bm.put("magBin", b.magBin());
            bm.put("x", b.x()); bm.put("y", b.y()); bm.put("w", b.w()); bm.put("h", b.h());
            bm.put("startMs", b.startMs()); bm.put("endMs", b.endMs()); bm.put("dwellMs", b.dwellMs());
            bs.add(bm);
        }
        m.put("behaviors", bs);
        Files.writeString(out.toPath(), GSON.toJson(m), StandardCharsets.UTF_8);
    }

    // --- verbatim mirror of FocusHeatmap.slideKey / anonymizeSlideKey (keep in sync) ---
    static String slideKey(String uri) {
        if (uri == null || uri.isBlank()) return "unknown";
        int q = uri.indexOf('?'); return q >= 0 ? uri.substring(0, q) : uri;
    }
    public static String anonymize(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return key;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b));
            return "sha256:" + sb;
        } catch (Exception e) { return "sha256:unavailable"; }
    }
    public static String anonymizedKeyForOpenSlide(String uri) { return anonymize(slideKey(uri)); }
    public static boolean slideMatches(CotFragment f, String openUri) {
        return f.slideKey() != null && f.slideKey().equals(anonymizedKeyForOpenSlide(openUri));
    }

    private static String str(JsonObject o, String k){ return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null; }
    private static int intt(JsonObject o, String k){ return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0; }
}
```

- [ ] **Step 5: Run tests to green.** Fix the `decision` value coercion if the test's
  `getAsString`-style assertion needs primitives kept as `String`/`Number` (the fixture uses a
  string diagnosis + numeric confidence). Run `--tests "*PathologyCotIOTest"` → PASS.

- [ ] **Step 6: Commit** `feat(cot): fragment reader + behaviors.json (analysis contract) + slide-key match`.

---

## Task 3: Draft builder — `Behavior[]` → `AtlasQuiz`

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotDraft.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotDraftTest.java`

**Interfaces:**
- Consumes: `Behavior` (T1), `CotFragment` (T2), `quiz.AtlasQuiz`/`QuizQuestion`/`QuizGeometry`.
- Produces: `AtlasQuiz buildDraft(List<Behavior>, String slideUrl, String slideTitle, Double
  baseMag, Map<String,Object> decision)`.

- [ ] **Step 1: Failing test** — asserts one NARRATION stop per behaviour, viewport downsample from
  the bin, blank explanation, non-blank highlight, and the decision surfaced in the description.

```java
package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.junit.jupiter.api.Test;
import com.patolojiatlasi.qupath.quiz.*;

class PathologyCotDraftTest {
    @Test void oneNarrationStopPerBehaviour() {
        List<Behavior> bs = List.of(
            new Behavior(Behavior.Type.INSPECT,"10x", 3000,3000, 4096,4096, 0,1500,1500),
            new Behavior(Behavior.Type.PEEK,"40x", 6500,6500, 1024,1024, 3000,3250,250));
        AtlasQuiz q = PathologyCotDraft.buildDraft(bs, "https://x/y.dzi", "y",
                40.0, Map.of("diagnosis","benign"));
        assertEquals(2, q.getQuestions().size());
        QuizQuestion q0 = q.getQuestions().get(0);
        assertEquals(QuizType.NARRATION, q0.getType());
        assertEquals("", q0.getExplanation());            // blank for manual rationale
        assertNotNull(q0.getViewport());
        assertEquals(4.0, q0.getViewport().downsample, 1e-9);  // 40/10
        assertNotNull(q0.getHighlightGeoJson());
        assertTrue(q.getDescription().contains("benign"));
        assertTrue(q.isAllowBack());
    }
}
```

- [ ] **Step 2: Run → fails.**
- [ ] **Step 3: Implement `PathologyCotDraft`.**

```java
package com.patolojiatlasi.qupath.pathologycot;

import com.patolojiatlasi.qupath.quiz.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.util.*;

/** Builds a reviewable guided-tour {@link AtlasQuiz} from discretized {@link Behavior}s. Each stop
 *  is a NARRATION with the ROI captured as a viewport + highlight box; the pathologist fills the
 *  per-ROI rationale (the stop's {@code explanation}) during review in the existing tour author. */
public final class PathologyCotDraft {
    private PathologyCotDraft() {}

    public static AtlasQuiz buildDraft(List<Behavior> behaviors, String slideUrl, String slideTitle,
                                       Double baseMag, Map<String,Object> decision) {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setTitle("Pathology-CoT taslağı — " + (slideTitle == null ? "" : slideTitle));
        String dx = decision == null ? null : Objects.toString(decision.get("diagnosis"), null);
        quiz.setDescription(dx == null || dx.isBlank()
                ? "Kaydedilen gezinmeden üretilen inceleme/yakın-bakış dizisi. Her durak için gerekçe ekleyin."
                : "Kaydedilen tanı/karar: " + dx + "\n\nHer durak için 'neden baktım / ne gördüm' gerekçesini ekleyin.");
        quiz.setAllowBack(true);
        List<QuizQuestion> qs = quiz.getQuestions();
        int i = 1;
        for (Behavior b : behaviors) {
            QuizQuestion q = new QuizQuestion();
            q.setId("cot" + System.nanoTime() + "_" + i);
            q.setType(QuizType.NARRATION);
            q.setSlideUrl(slideUrl); q.setSlideTitle(slideTitle);
            q.setPrompt(label(b, i));
            q.setExplanation("");   // manual: filled during review
            QuizQuestion.Viewport vp = new QuizQuestion.Viewport();
            vp.centerX = b.centerX(); vp.centerY = b.centerY();
            vp.downsample = downsampleFor(b, baseMag);
            q.setViewport(vp);
            ROI box = ROIs.createRectangleROI(b.x(), b.y(), b.w(), b.h(), ImagePlane.getDefaultPlane());
            q.setHighlightGeoJson(QuizGeometry.toGeoJson(box));
            qs.add(q); i++;
        }
        return quiz;
    }

    private static String label(Behavior b, int i) {
        String kind = b.type() == Behavior.Type.PEEK ? "Yakın bakış (peek)" : "İnceleme (inspect)";
        String mag = b.magBin() == null ? "" : b.magBin() + " ";
        return i + ". " + mag + kind;
    }
    /** downsample the runner should fly to: baseMag/bin (e.g. 10x on 40x → 4.0); fallback 1.0. */
    private static double downsampleFor(Behavior b, Double baseMag) {
        if (baseMag != null && b.magBin() != null) {
            try { return baseMag / Integer.parseInt(b.magBin().replace("x","")); } catch (Exception ignore) {}
        }
        return b.type() == Behavior.Type.PEEK ? 1.0 : 4.0;
    }
}
```

- [ ] **Step 4: Run → green. Step 5: Commit** `feat(cot): draft builder (behaviors → reviewable tour)`.

---

## Task 4: Exporter — reviewed `AtlasQuiz` + slide → Pathology-CoT case folder

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotExport.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotExportTest.java`

**Interfaces:**
- Consumes: `quiz.AtlasQuiz`, `qupath.lib.images.servers.ImageServer<BufferedImage>`.
- Produces: `void export(AtlasQuiz reviewed, ImageServer<BufferedImage> server, File outDir)`; plus a
  package-visible pure helper `List<Map<String,Object>> buildConversation(AtlasQuiz, int thumbW,
  int thumbH, double thumbDs)` so the JSON structure is unit-testable without image I/O.

- [ ] **Step 1: Failing test** for `buildConversation` — assert the turn roles and that the inspect
  token is in thumbnail coords (bbox scaled by `1/thumbDs`), and `<conclusion>` present.

```java
package com.patolojiatlasi.qupath.pathologycot;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.junit.jupiter.api.Test; import com.patolojiatlasi.qupath.quiz.*;
import qupath.lib.regions.ImagePlane; import qupath.lib.roi.ROIs;

class PathologyCotExportTest {
    @Test void conversationHasTurnsAndThumbnailCoords() {
        AtlasQuiz q = new AtlasQuiz(); q.setTitle("t");
        q.setDescription("Kaydedilen tanı/karar: benign");
        QuizQuestion s = new QuizQuestion(); s.setType(QuizType.NARRATION);
        s.setPrompt("1. 10x İnceleme (inspect)"); s.setExplanation("Reaktif germinal merkez.");
        QuizQuestion.Viewport vp = new QuizQuestion.Viewport(); vp.downsample = 4; vp.centerX=5000; vp.centerY=5000;
        s.setViewport(vp);
        s.setHighlightGeoJson(QuizGeometry.toGeoJson(
                ROIs.createRectangleROI(4000,4000,2048,2048, ImagePlane.getDefaultPlane())));
        q.getQuestions().add(s);
        var turns = PathologyCotExport.buildConversation(q, 1000, 800, 100.0); // thumbDs=100 → /100
        assertTrue(turns.stream().anyMatch(t -> "user".equals(t.get("role"))));
        assertTrue(turns.stream().anyMatch(t -> "assistant".equals(t.get("role"))));
        String all = turns.toString();
        assertTrue(all.contains("<inspect>[40,40,60,60]</inspect>")); // 4000/100..6048/100 → 40..60 (rounded)
        assertTrue(all.contains("<conclusion>"));
        assertTrue(all.contains("Reaktif germinal merkez"));
    }
}
```

- [ ] **Step 2: Run → fails.**
- [ ] **Step 3: Implement `PathologyCotExport`.** `export(...)` derives a thumbnail downsample
  (`max(imgW,imgH)/1024.0`), writes `thumbnail.jpeg`, per NARRATION stop recovers the ROI from
  `highlightGeoJson` (via `QuizGeometry.fromGeoJson`) + magnification from `viewport.downsample`,
  classifies inspect vs peek (`downsample <= PEEK_DS_MAX` → peek → `cyto_box_N.jpeg`, else
  `box_N.jpeg`), crops with `server.readRegion(RegionRequest.createInstance(server.getPath(),
  downsample, x, y, w, h))`, `ImageIO.write(img,"jpg",file)`, then writes `conversation.json`
  (`buildConversation`) + `README.txt` (governance line). Key structure of `buildConversation`
  (pure): a `user` turn (task text + `"thumbnail.jpeg"`), an `assistant` turn listing planned
  actions (`<inspect>[x0,y0,x1,y1]`/`<peek>[…]` in **thumbnail px** = ROI bbox × `1/thumbDs`,
  rounded), per-stop `system`(image ref)+`assistant`(the `explanation` = region interpretation)
  turns, and a final `assistant` turn with `<conclusion>…</conclusion>` from the quiz description's
  recorded diagnosis. Write JSON with the same pretty Gson as `PathologyCotIO`. Verify the exact
  0.6 `readRegion` overload/`getPath()` via javap if compilation complains (0.6 replaced
  `readBufferedImage` with `readRegion`). Pin `Locale.US` in any numeric `String.format`.

- [ ] **Step 4: Run the pure test to green** (no real image I/O in the test).
- [ ] **Step 5: Commit** `feat(cot): Pathology-CoT case-folder export (crops + conversation.json)`.

---

## Task 5: Menu wiring + fragment/slide selection

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/pathologycot/PathologyCotActions.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (add the submenu)

**Interfaces:**
- Consumes: everything above + `QuPathGUI`, `quiz.QuizAuthorWindow`, `quiz.QuizSlide`,
  `AtlasQuizIO`.

- [ ] **Step 1:** Implement `PathologyCotActions` with three static handlers taking `QuPathGUI`:
  1. `draftFromRecording(qupath)` — require an open slide (`qupath.getViewer().getImageData()`),
     `FileChooser` a fragment `*.json` (initial dir = `<projectDir>/atlas-focus` when a project is
     open), `PathologyCotIO.readFragment`, **warn (Alert, continue)** if
     `!slideMatches(frag, QuizSlide.currentSlideUrl(viewer))`, `discretize`, then `FileChooser`
     save-as → write **both** `behaviors.json` (sibling, fixed name `<draft>.behaviors.json`) and
     the draft tour via `AtlasQuizIO.write(PathologyCotDraft.buildDraft(...), file)`, then
     `QuizAuthorWindow.show(qupath)` and offer to load it. If `behaviors` is empty, inform and stop.
  2. `reviewDraft(qupath)` — just `QuizAuthorWindow.show(qupath)` (author already loads packs); a
     thin convenience.
  3. `exportDataset(qupath)` — require an open slide, `FileChooser` a reviewed `*.json`
     (`AtlasQuizIO.read`), `DirectoryChooser` a target, then
     `PathologyCotExport.export(quiz, server, chosenDir)` on a background thread with a completion
     Alert (mirror `FocusHeatmap`'s async-save + `Platform.runLater` discipline; never block the FX
     thread on crops). All dialogs `initOwner` the main stage; all failures → `Alert`, never a crash.
- [ ] **Step 2:** In `AtlasExtension.installExtension`, where the `"Araştırma"` menu is assembled
  (~line 154), add a `Menu "Pathology-CoT"` with three `MenuItem`s wired to the handlers, after the
  focus/blinded items + a `SeparatorMenuItem`. Labels: "Gezinme kaydından CoT taslağı oluştur…",
  "CoT taslağını gözden geçir…", "Pathology-CoT veri kümesi olarak dışa aktar…".
- [ ] **Step 3:** `./gradlew --no-daemon clean build` → green (no unit test for the FX wiring; it is
  GUI-smoke-tested by the user). **Step 4: Commit** `feat(cot): Araştırma → Pathology-CoT menu`.

---

## Verification
1. `./gradlew --no-daemon clean build test` green (discretizer, IO, draft, export-structure tests).
2. **User GUI smoke** (surfaced once, per `feedback_user_tests_qupath`): record a short blinded
   session with a decision → "CoT taslağı oluştur" → confirm `behaviors.json` + draft written and
   the draft opens as a tour (fly-to + ROI box per stop) → edit two rationales, delete one stop,
   save → "dışa aktar" → confirm the case folder has `thumbnail.jpeg`, `box_*/cyto_box_*.jpeg`, a
   well-formed `conversation.json` (inspect/peek boxes aligned to the thumbnail, `<conclusion>` =
   recorded diagnosis), and `README.txt`.

## Notes for the executor
- Do not modify the quiz package or `FocusHeatmap`. If `readRegion`/`getPath` signatures differ in
  the pinned 0.6 API, javap-verify and adjust (see memory `reference_groovy_review_verified_apis`).
- The `flush()` STATIC-vs-PAN dwell nuance in Task 1 is intentionally conservative (single
  threshold); the tests encode the required outcomes. If a reviewer wants true PanInspect (2 s)
  separation, add a spatial-spread measure to `Run` and gate the two thresholds on it — but keep
  the listed tests green.
- Branch `feat/pathology-cot-data-engine` off `master` (`3bcf68c`); master is ~14 ahead of origin,
  unpushed (user drives push).
