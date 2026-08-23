# QuPath Edu-Inspired Enhancements (C1–C5) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all five candidates from the QuPath Edu review
(`docs/superpowers/2026-08-23-qupath-edu-feature-review.md`): C1 annotation auto-scoring on
reveal, C2 hidden-answer browse mode, C3 per-case description pane, C4 Basit görünüm (simple
view), C5 standalone web tour player.

**Architecture:** C1 = a pure `QuizScoring` helper + a small `QuizRunnerWindow.revealAnswer`
integration. C2 = a new single-window `QuizBrowseWindow` + a new multi-ROI `QuizRegionsOverlay`,
reusing `AtlasQuizIO`/`QuizSlide`/`QuizGeometry`. C3 = optional description fields through
`AtlasCase`/`AtlasCatalog` + a richer `AtlasBrowser` preview. C4 = a `CheckMenuItem` toggling
visibility of QuPath's analysis menus via **public API only**. C5 = one self-contained HTML file
(OpenSeadragon CDN) + README section.

**Tech Stack:** Java 21, QuPath 0.6 public API (javap-verified: `QuPathGUI.getMenuBar()`,
`getMenu(String,boolean)`, `QuPathViewer.componentPointToImagePoint(double,double,Point2D,boolean)`,
`ROI.getGeometry()` → JTS `org.locationtech.jts.geom.Geometry`, `ROI.contains(double,double)`),
JavaFX, Gson, JUnit 5; OpenSeadragon (CDN) for C5.

## Global Constraints

- **Clean-room:** QuPath Edu's extension/server repos carry NO LICENSE → no code ported, ever.
  Feature ideas + published survey numbers (Yli-Hallila et al., *J Anat* 2025;246(5):846–856,
  doi:10.1111/joa.14172) are citable facts. Cite the paper in new-class Javadoc where a feature is
  inspired by it.
- **No clinical interpretation** anywhere: scores are measurements (IoU %, hit/miss), никогда
  diagnosis language. UI text Turkish; QuPath UI strings stay English.
- **`String.format` pins `java.util.Locale.US`** (Turkish-locale JVM renders %f with comma).
- **Do not modify** existing quiz classes except the minimal `QuizRunnerWindow.revealAnswer`
  integration in Task 1. No writable backend; everything local/portable.
- **Public API only** for C4 — no reflection into QuPath internals (that is QuPath Edu's fragile
  approach; explicitly rejected).
- Single-window pattern for new windows (static `stage`, focus-if-open — copy `AtlasBrowser`/
  `QuizRunnerWindow` style). All dialogs `initOwner`; FX-thread discipline as in existing code.
- **Paper's hit criterion for scoring:** hit ⇔ IoU > 0.3 **or** one geometry covers the other
  (Wang et al. Pathology-CoT criterion, already used in this repo's review docs; QuPath Edu's exam
  idea is the feature inspiration). Constant `HIT_IOU = 0.3`.

## Verified facts the tasks rely on

- Quiz geometry strings are **bare GeoJSON geometry** (no Feature wrapper):
  `{"type":"Polygon","coordinates":[[[10,20],[110,20],[110,70],[10,70],[10,20]]]}`; `"plane"` key
  only on non-default planes.
- `QuizRunnerWindow` fields for learner drawings: `annotationBaseline` (Set<PathObject>),
  `annotationBaselineViewer`, `annotationBaselineImageData`, `annotationBaselineValid` — captured
  in `afterSlideReady` for ANNOTATION/NAVIGATION.
- `AtlasCase` fields: `reponame, stainname, image, titleEN, titleTR, organEN, speciality, type,
  dziUrl, thumbUrl, mpp`; **no description field**. Bundled snapshot =
  `src/main/resources/catalog.json` (JSON, entries use keys `"dzi"`, `"thumb"`, optional `"mpp"`).
  Live YAML parser = `AtlasCatalog.parseList` (line-based, `WANTED` scalar set).
- `AtlasBrowser.buildStage()`: `SplitPane(tree, preview)` where
  `VBox preview = new VBox(6, thumbView, infoLabel)`; `updatePreview()` sets `infoLabel` text.
- `QuizRevealOverlay` draws ONE ROI (`roi.getShape()`, magenta, stroke `2.5f*downsample`); overlays
  add/remove via `viewer.getCustomOverlayLayers()` + `repaint()`.
- Menu tree: `Extensions → Patoloji Atlası → … → Sınav / Quiz {Çöz…, Hazırla…, Rehberli tur oynat…}`;
  `Extensions → Araştırma → …`. Only `qupath.getMenu("Extensions", true)` is called today.

---

## Task 1: C1 — `QuizScoring` (pure) + reveal integration

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizScoring.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRunnerWindow.java` (revealAnswer
  ANNOTATION + NAVIGATION cases only)
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/QuizScoringTest.java`

**Interfaces:**
- Produces: `QuizScoring.Score` record `(double iou, boolean containment, boolean hit)` and
  `static Score score(Geometry learner, Geometry reference)`,
  `static Geometry unionOf(Collection<PathObject> objects)` (null if none usable),
  `static String formatScoreLine(Score s)` (Turkish, Locale.US numbers),
  `static final double HIT_IOU = 0.3`.

- [ ] **Step 1: failing test** (`QuizScoringTest`): build geometries via
  `ROIs.createRectangleROI(...).getGeometry()`.

```java
package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class QuizScoringTest {
    private static Geometry rect(double x, double y, double w, double h) {
        return ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()).getGeometry();
    }

    @Test void identicalIsPerfectHit() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(0,0,100,100));
        assertEquals(1.0, s.iou(), 1e-9);
        assertTrue(s.hit());
    }
    @Test void disjointIsMiss() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(500,500,100,100));
        assertEquals(0.0, s.iou(), 1e-9);
        assertFalse(s.hit());
    }
    @Test void smallInsideBigIsHitByContainmentDespiteLowIou() {
        QuizScoring.Score s = QuizScoring.score(rect(40,40,10,10), rect(0,0,100,100));
        assertTrue(s.iou() < QuizScoring.HIT_IOU);
        assertTrue(s.containment());
        assertTrue(s.hit());
    }
    @Test void partialOverlapAboveThresholdIsHit() {
        // overlap 50x100=5000; union 15000 -> IoU 1/3 > 0.3
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(50,0,100,100));
        assertTrue(s.iou() > QuizScoring.HIT_IOU);
        assertTrue(s.hit());
    }
    @Test void nullInputsAreSafe() {
        assertNull(QuizScoring.score(null, rect(0,0,10,10)));
        assertNull(QuizScoring.score(rect(0,0,10,10), null));
    }
    @Test void formatUsesUsLocaleDecimal() {
        QuizScoring.Score s = QuizScoring.score(rect(0,0,100,100), rect(50,0,100,100));
        String line = QuizScoring.formatScoreLine(s);
        assertTrue(line.contains("33.3"), line);   // never "33,3"
    }
}
```

- [ ] **Step 2: run → fails to compile.**
- [ ] **Step 3: implement `QuizScoring`.** Pure; JTS ops guarded (TopologyException → null score).

```java
package com.patolojiatlasi.qupath.quiz;

import java.util.Collection;
import java.util.Locale;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.objects.PathObject;

/**
 * Measurement-only scoring of a learner's drawn geometry against a stop's reference geometry.
 * Hit criterion: IoU > {@link #HIT_IOU} OR either geometry covers the other (the same inclusive
 * criterion used by the Pathology-CoT exporter's behaviour matching). Inspired by the examination
 * proof-of-concept described (but not shipped) in Yli-Hallila et al., J Anat 2025;246(5):846-856,
 * doi:10.1111/joa.14172 (QuPath Edu). Clean-room: no code from that project is used.
 */
public final class QuizScoring {

    public static final double HIT_IOU = 0.3;

    public record Score(double iou, boolean containment, boolean hit) {}

    private QuizScoring() {}

    /** IoU + containment + hit for two JTS geometries; null when either is null/empty/invalid. */
    public static Score score(Geometry learner, Geometry reference) {
        if (learner == null || reference == null || learner.isEmpty() || reference.isEmpty())
            return null;
        try {
            double inter = learner.intersection(reference).getArea();
            double union = learner.union(reference).getArea();
            double iou = union <= 0 ? 0.0 : inter / union;
            boolean containment = learner.covers(reference) || reference.covers(learner);
            return new Score(iou, containment, iou > HIT_IOU || containment);
        } catch (Exception ex) {          // TopologyException etc. -> no score rather than a crash
            return null;
        }
    }

    /** Union of the annotation ROI geometries of {@code objects}; null when none usable. */
    public static Geometry unionOf(Collection<PathObject> objects) {
        Geometry union = null;
        if (objects == null) return null;
        for (PathObject po : objects) {
            try {
                if (po == null || po.getROI() == null) continue;
                Geometry g = po.getROI().getGeometry();
                if (g == null || g.isEmpty()) continue;
                union = union == null ? g : union.union(g);
            } catch (Exception ignore) { /* skip unusable geometry */ }
        }
        return union;
    }

    /** Turkish, measurement-only score line; Locale.US numerals. */
    public static String formatScoreLine(Score s) {
        if (s == null) return "";
        String pct = String.format(Locale.US, "%.1f", s.iou() * 100.0);
        if (s.hit()) {
            String why = s.containment() && !(s.iou() > HIT_IOU)
                    ? "kapsama" : "IoU > " + String.format(Locale.US, "%.1f", HIT_IOU);
            return "Çiziminiz: IoU %" + pct + " — isabet (" + why + ").";
        }
        return "Çiziminiz: IoU %" + pct + " — isabet yok (eşik IoU " 
                + String.format(Locale.US, "%.1f", HIT_IOU) + " ya da kapsama).";
    }
}
```

- [ ] **Step 4: integrate into `QuizRunnerWindow.revealAnswer`.**
  Add a private helper and call it from BOTH cases:

```java
/** Learner's transient annotations (drawn since afterSlideReady's baseline), or empty set. */
private java.util.Set<PathObject> learnerDrawnAnnotations() {
    if (!annotationBaselineValid || annotationBaselineViewer == null)
        return java.util.Set.of();
    ImageData<BufferedImage> d = annotationBaselineViewer.getImageData();
    if (d == null || d != annotationBaselineImageData)
        return java.util.Set.of();
    java.util.Set<PathObject> drawn = new HashSet<>(d.getHierarchy().getAnnotationObjects());
    drawn.removeAll(annotationBaseline);
    return drawn;
}
```
  - **ANNOTATION case:** after `showRevealOverlay(roi)` set the label to the existing text PLUS
    a score suffix: compute `Geometry learner = QuizScoring.unionOf(learnerDrawnAnnotations());`
    if learner == null → append `"\nÇizim yapılmadı — karşılaştırma yok."`; else
    `QuizScoring.Score s = QuizScoring.score(learner, roi.getGeometry());` and append
    `"\n" + QuizScoring.formatScoreLine(s)` (skip the suffix entirely when `s == null`).
  - **NAVIGATION case:** BEFORE the recenter (`viewer.setDownsampleFactor(...)`), capture the
    current viewport as a geometry:
    `java.awt.Rectangle vb = viewer.getDisplayedRegionShape().getBounds();`
    `Geometry viewport = ROIs.createRectangleROI(vb.getX(), vb.getY(), vb.getWidth(), vb.getHeight(), ImagePlane.getDefaultPlane()).getGeometry();`
    then score viewport vs `roi.getGeometry()`; append to the label:
    hit → `"\nHedef bölge görüş alanınızdaydı (IoU %…)."` / miss → `"\nHedef bölge görüş alanınızda değildi (IoU %…)."`
    (build both via `String.format(Locale.US, "%.1f", …)`; guard viewer/shape null → no suffix).
  Keep everything else identical (existing overlays, texts as prefixes, NARRATION/MCQ/FREETEXT
  untouched).

- [ ] **Step 5:** `./gradlew --no-daemon test --tests "*QuizScoringTest"` → PASS, then full
  `test` once. **Step 6: Commit** `feat(quiz): measurement-only auto-scoring on reveal (IoU + containment)`.

---

## Task 2: C3 — optional case descriptions + richer browser preview

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasCase.java` (add nullable
  `descriptionTR`, `descriptionEN` + `getDescription()`)
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasCatalog.java` (bundled-JSON + YAML parse
  of the new optional keys)
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` (`updatePreview` + preview VBox)
- Test: extend the existing catalog test class (find `AtlasCatalogTest`/similar under
  `src/test/java/com/patolojiatlasi/qupath/`; if none tests `parseList`, create
  `AtlasCatalogDescriptionTest.java`)

**Interfaces:**
- Produces: `AtlasCase.getDescription()` → TR-first, EN fallback, else `""` (never null).

- [ ] **Step 1: failing test** — YAML with `descriptionTR:`/`descriptionEN:` scalar lines parses
  into the case; absent keys → `getDescription().isEmpty()`; bundled-style JSON entry with
  `"descriptionTR"` round-trips. (Single-line scalars only — the hand-rolled parser is line-based;
  document that limitation in the parser comment.)

```java
@Test void parsesOptionalDescriptionScalars() {
    String yaml = """
        - stainname: demo-HE
          reponame: demo
          titleEN: Demo case
          descriptionTR: Kısa Türkçe açıklama.
          descriptionEN: Short English description.
          url: https://images.patolojiatlasi.com/demo/HE.html
        """;
    var cases = AtlasCatalog.parseList(yaml);
    assertEquals(1, cases.size());
    assertEquals("Kısa Türkçe açıklama.", cases.get(0).getDescription());
}
@Test void missingDescriptionYieldsEmptyString() { /* same YAML minus description lines -> "" */ }
```
  (Adapt the construction to `parseList`'s actual signature/return; assertions as above.)

- [ ] **Step 2: implement.** `AtlasCase`: two new nullable fields + constructor plumbing (follow
  how `mpp` is optionally carried) + `getDescription()` (`descriptionTR` non-blank → it, else
  `descriptionEN` non-blank → it, else `""`). `AtlasCatalog`: add `"descriptionTR"`,
  `"descriptionEN"` to the YAML `WANTED` set + map into the case; read the same optional keys from
  bundled `catalog.json` entries (Gson path). Do NOT add the fields to the bundled
  `catalog.json` data file itself (upstream list.yaml doesn't have them yet — this is
  forward-compatible plumbing that lights up when the catalog adds them).
- [ ] **Step 3: browser preview.** In `AtlasBrowser`:
  - `updatePreview()` — extend the info text with `Speciality:` (when non-blank) and
    `mpp: <µm/px>` (when known; `String.format(Locale.US, "%.4f", …)`).
  - Add a `descriptionLabel` (wrapped, styled slightly muted) under `infoLabel` inside a
    `ScrollPane` so long descriptions scroll: replace `VBox preview = new VBox(6, thumbView, infoLabel)`
    with thumb + info + scrollable description; `descriptionLabel` gets
    `c.getDescription()`, and is hidden (`setVisible/setManaged(false)`) when empty — layout
    unchanged for today's catalog.
- [ ] **Step 4:** focused test green, full `test` green. **Step 5: Commit**
  `feat(atlas): optional per-case descriptions (catalog-fed) + richer preview pane`.

---

## Task 3: C4 — Basit görünüm (simple view toggle)

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/SimpleViewMode.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (one `CheckMenuItem`)

**Interfaces:**
- Produces: `SimpleViewMode.apply(QuPathGUI, boolean enabled)` — idempotent, restores exactly the
  prior visibility.

- [ ] **Step 1: implement `SimpleViewMode`.** Public-API only:

```java
package com.patolojiatlasi.qupath;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.control.Menu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

/**
 * "Basit görünüm": hides QuPath's analysis-focused top-level menus (Analyze, Classify, Automate,
 * TMA — whichever exist) so learners see a reading-focused UI, and restores them on toggle-off.
 * Public-API only ({@link QuPathGUI#getMenu(String, boolean)} + Menu.setVisible) — deliberately
 * NOT the reflection-into-internals approach QuPath Edu uses for its Study mode (Yli-Hallila et
 * al., J Anat 2025, doi:10.1111/joa.14172), so it cannot break on private-API changes.
 * Session-only (no persistence); state kept per-JVM in this class.
 */
public final class SimpleViewMode {
    private static final Logger logger = LoggerFactory.getLogger(SimpleViewMode.class);
    private static final String[] HIDE_MENUS = {"Analyze", "Classify", "Automate", "TMA"};
    private static final Map<Menu, Boolean> priorVisibility = new LinkedHashMap<>();

    private SimpleViewMode() {}

    /** Enable/disable simple view. Idempotent; FX thread. */
    public static void apply(QuPathGUI qupath, boolean enabled) {
        if (qupath == null) return;
        if (enabled) {
            if (!priorVisibility.isEmpty()) return;             // already applied
            for (String name : HIDE_MENUS) {
                try {
                    Menu m = qupath.getMenu(name, false);       // null when absent in this QuPath
                    if (m != null) {
                        priorVisibility.put(m, m.isVisible());
                        m.setVisible(false);
                    }
                } catch (Exception ex) {
                    logger.debug("Basit görünüm: '{}' menüsü gizlenemedi: {}", name, ex.getMessage());
                }
            }
        } else {
            priorVisibility.forEach((m, was) -> {
                try { m.setVisible(was); } catch (Exception ignore) {}
            });
            priorVisibility.clear();
        }
    }
}
```
- [ ] **Step 2: wire the menu item.** In `AtlasExtension.installExtension`, add to the
  `Patoloji Atlası` menu (after `Katalog kapsamı ve QC…`, before the separator):
  `CheckMenuItem simpleView = new CheckMenuItem("Basit görünüm (analiz menülerini gizle)");`
  `simpleView.setOnAction(e -> SimpleViewMode.apply(qupath, simpleView.isSelected()));`
- [ ] **Step 3:** `./gradlew --no-daemon clean build` green (GUI toggle is user-smoke-tested).
  **Step 4: Commit** `feat(atlas): Basit görünüm — hide analysis menus via public API`.

---

## Task 4: C2 — hidden-answer browse mode (`QuizBrowseWindow` + `QuizRegionsOverlay`)

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRegionsOverlay.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizBrowseWindow.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (one menu item under
  `Sınav / Quiz`)
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/QuizBrowseWindowTest.java` (pure helpers only)

**Interfaces:**
- Consumes: `AtlasQuizIO.read`, `QuizGeometry.fromGeoJson`, `QuizSlide.openSlideAsync/currentSlideUrl`,
  `QuizQuestion` getters, `Behavior`-free (no CoT dep).
- Produces: `QuizBrowseWindow.show(QuPathGUI)`; static pure helpers
  `stopRegionRoi(QuizQuestion)` (highlight → reference → target → null, parsed defensively) and
  `pickStop(List<ROI> rois, double x, double y)` → index of the SMALLEST-area ROI containing
  (x,y), or −1.

- [ ] **Step 1: failing pure-helper test.**

```java
class QuizBrowseWindowTest {
    @Test void pickStopPrefersSmallestContainingRegion() {
        ROI big = ROIs.createRectangleROI(0,0,1000,1000, ImagePlane.getDefaultPlane());
        ROI small = ROIs.createRectangleROI(400,400,100,100, ImagePlane.getDefaultPlane());
        assertEquals(1, QuizBrowseWindow.pickStop(List.of(big, small), 450, 450));
        assertEquals(0, QuizBrowseWindow.pickStop(List.of(big, small), 10, 10));
        assertEquals(-1, QuizBrowseWindow.pickStop(List.of(big, small), 5000, 5000));
        assertEquals(-1, QuizBrowseWindow.pickStop(java.util.Arrays.asList(null, null), 10, 10)); // null-safe
    }
    @Test void stopRegionPrefersHighlightThenReferenceThenTarget() {
        QuizQuestion q = new QuizQuestion();
        assertNull(QuizBrowseWindow.stopRegionRoi(q));
        q.setTargetGeometryGeoJson(QuizGeometry.toGeoJson(ROIs.createRectangleROI(0,0,10,10, ImagePlane.getDefaultPlane())));
        assertNotNull(QuizBrowseWindow.stopRegionRoi(q));
        q.setHighlightGeoJson(QuizGeometry.toGeoJson(ROIs.createRectangleROI(50,50,10,10, ImagePlane.getDefaultPlane())));
        assertEquals(50.0, QuizBrowseWindow.stopRegionRoi(q).getBoundsX(), 1e-9); // highlight wins
    }
}
```

- [ ] **Step 2: `QuizRegionsOverlay`** — like `QuizRevealOverlay` but for a list, with a selected
  index and small index labels:

```java
/** Draws every stop region of the browse mode at once; the selected one thicker. One instance per
 *  viewer; ROIs may contain nulls (skipped). Inspired by QuPath Edu's always-visible annotation
 *  pins (Yli-Hallila et al., J Anat 2025); drawing follows QuizRevealOverlay's conventions. */
public class QuizRegionsOverlay extends AbstractOverlay {
    private final List<ROI> rois;
    private volatile int selectedIndex = -1;
    public QuizRegionsOverlay(OverlayOptions options, List<ROI> rois) { super(options); this.rois = rois; setOpacity(1.0); }
    public void setSelectedIndex(int i) { this.selectedIndex = i; }
    @Override public void paintOverlay(Graphics2D g2d, ImageRegion region, double downsample,
            ImageData<BufferedImage> imageData, boolean paintCompletely) {
        Graphics2D g = (Graphics2D) g2d.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < rois.size(); i++) {
                ROI roi = rois.get(i);
                if (roi == null) continue;
                boolean sel = i == selectedIndex;
                g.setStroke(new BasicStroke((float) ((sel ? 4.0 : 2.0) * downsample)));
                g.setColor(sel ? new Color(255, 0, 255) : new Color(0, 120, 215));
                try { g.draw(roi.getShape()); } catch (UnsupportedOperationException ignore) { continue; }
                // index label at the region's top-left, sized in image space
                g.setFont(g.getFont().deriveFont((float) (14.0 * downsample)));
                g.drawString(String.valueOf(i + 1),
                        (float) roi.getBoundsX(), (float) Math.max(roi.getBoundsY() - 4 * downsample, 12 * downsample));
            }
        } finally { g.dispose(); }
    }
}
```

- [ ] **Step 3: `QuizBrowseWindow`** — single-window; layout `BorderPane`:
  - **Top:** "Paket yükle…" button + title label + slide `ComboBox` (one item per distinct
    `slideUrl` in the pack, label = `slideTitle`); selecting a slide opens it via
    `QuizSlide.openSlideAsync` (same `stillWanted`/token + viewer-replace-confirm discipline as
    `QuizRunnerWindow.applySlide` — replicate the minimal guard, don't refactor the runner).
  - **Left/center:** `ListView<QuizQuestion>` of the current slide's stops (cell text:
    `"N. " + prompt`, wrapped) — prompts are visible by design; **answers stay hidden**.
  - **Right/bottom pane:** selected stop detail: prompt label; for MCQ a read-only list of
    options; a **"Cevabı göster"** button; hidden-until-reveal answer area showing — MCQ: "Doğru
    cevap: <option>"; FREETEXT: "Model cevap: …"; ANNOTATION/NAVIGATION: "Referans/hedef bölge
    gösteriliyor." — plus `explanation` (italic). Re-selecting another stop re-hides (fresh state
    per selection; no persistence).
  - **Slide interaction:** on slide ready, build `List<ROI> regions` via `stopRegionRoi(q)` per
    stop (index-aligned with the stop list; null for geometry-less stops), add ONE
    `QuizRegionsOverlay`; attach a `MOUSE_CLICKED` **event filter** to `viewer.getView()`
    (only act when `e.isStillSincePress()` — never consume, mirroring `FocusHeatmap`'s filter
    pattern), convert via `componentPointToImagePoint(e.getX(), e.getY(), null, true)` and
    `pickStop(regions, x, y)`; a hit selects that stop in the ListView (which flies via its
    viewport if present: `viewer.setDownsampleFactor(vp.downsample, vp.centerX, vp.centerY)`).
  - Selecting a stop calls `overlay.setSelectedIndex(i)` + `viewer.repaint()`; stops without
    geometry are list-only (clicking the list flies to their viewport when set).
  - **Cleanup:** `setOnHidden` removes the overlay + the mouse filter from whatever viewer they
    were attached to (pin the viewer reference at attach time, like `revealOverlayViewer`); also
    detach before re-attaching on slide change.
  - Javadoc cites QuPath Edu's hidden-answer annotations (their best-rated feature, 47.5/50,
    86.7 % preferred concealed-then-reveal; Yli-Hallila et al., J Anat 2025) as the inspiration,
    clean-room.
- [ ] **Step 4: menu item.** `Sınav / Quiz` submenu, after "Rehberli tur oynat…":
  `MenuItem browseItem = new MenuItem("Serbest inceleme (gizli cevaplar)…");`
  `browseItem.setOnAction(e -> QuizBrowseWindow.show(qupath));`
- [ ] **Step 5:** pure tests green; full `test` + `clean build` green. **Step 6: Commit**
  `feat(quiz): hidden-answer browse mode (clickable stop regions, reveal on demand)`.

---

## Task 5: C5 — standalone web tour player

**Files:**
- Create: `web-tour-player/tour-player.html` (self-contained; OpenSeadragon via CDN)
- Create: `web-tour-player/README.md` (deployment + CORS notes + license note)
- Modify: `README.md` (one pointer line in the Quiz section)

**Interfaces:** consumes the quiz-pack JSON contract verbatim:
`{formatVersion:1|2, title, description, allowBack, questions:[{type, slideUrl, slideTitle, prompt,
explanation, viewport:{downsample,centerX,centerY}, options[], correctIndex, modelAnswer,
instruction, referenceGeometryGeoJson, targetGeometryGeoJson, highlightGeoJson}]}` — geometry
values are STRINGS containing bare GeoJSON geometry (`{"type":"Polygon","coordinates":[...]}`).

- [ ] **Step 1: implement `tour-player.html`.** Single file, no build step. Requirements:
  - **Load:** `?tour=<url>` (fetch) OR a file `<input type=file>` + drag-drop (FileReader) — the
    drag-drop path works from `file://` for local preview. Validate `questions` non-empty.
  - **Viewer:** OpenSeadragon from CDN
    (`https://cdn.jsdelivr.net/npm/openseadragon@4/build/openseadragon/openseadragon.min.js`),
    `tileSources` = the stop's `slideUrl` **with any query string stripped** (a `?mpp=` suffix
    breaks OSD's `_files` tile-path derivation). Reopen only when the slide changes between stops.
  - **Stops UI:** header `Durak N / M` + tour title; intro panel (title + description) before stop
    1; Önceki/Sonraki buttons — Önceki disabled when `allowBack === false` or at stop 1; prompt
    shown; **"Göster"** reveals: MCQ → correct option (radios rendered, learner pick marked
    right/wrong), FREETEXT → `modelAnswer` + a free textarea, ANNOTATION/NAVIGATION → overlay the
    reference/target bbox, NARRATION → `explanation` only. Explanation always shown on reveal
    (italic). Turkish labels matching the desktop runner ("Göster", "Önceki", "Sonraki",
    "Doğru cevap: …", "Model cevap: …").
  - **Viewport fly-to:** after the OSD `open` event, for a stop with `viewport`:
    ```js
    const item = viewer.world.getItemAt(0);
    const widthPx = vp.downsample * viewer.container.clientWidth;   // QuPath: downsample = image px per screen px
    const rect = new OpenSeadragon.Rect(vp.centerX - widthPx/2,
        vp.centerY - (vp.downsample * viewer.container.clientHeight)/2,
        widthPx, vp.downsample * viewer.container.clientHeight);
    viewer.viewport.fitBounds(item.imageToViewportRectangle(rect), false);
    ```
  - **Geometry overlays:** parse the geometry STRING (`JSON.parse`), compute the coordinate bbox
    recursively over `coordinates`, and `viewer.addOverlay` a positioned outline `div` via
    `item.imageToViewportRectangle(bboxRect)`. Highlight (if present) auto-shows per stop with a
    toggle checkbox ("Vurguyu göster"); reveal overlays cleared on stop change.
  - Minimal embedded CSS (system font stack, light background, no external CSS), responsive
    (`viewer` flex-grows; panel fixed-width, stacks on narrow screens).
  - Header comment in the HTML: purpose, the quiz-pack contract, clean-room note + citation
    (Yli-Hallila et al. 2025 web client as prior art — Zlib-licensed, no code copied), and that
    packs come from the QuPath extension's author window.
- [ ] **Step 2: CORS check (evidence for README).** From Bash:
    `curl -sI -H "Origin: https://www.patolojiatlasi.com" "https://images.patolojiatlasi.com/myxoidliposarcoma/HE.dzi" | grep -i "access-control\|HTTP/"`
    Record the result in `web-tour-player/README.md`: if `Access-Control-Allow-Origin` is present,
    any-origin deploys work; if absent, the player must be deployed on the SAME host as the DZIs
    (e.g. under `images.patolojiatlasi.com`) or CORS must be enabled. State whichever was observed.
- [ ] **Step 3: `web-tour-player/README.md`** — what it is, how to deploy (copy the single file
  to the website; open `tour-player.html?tour=<pack-url>`; or open locally + drag-drop a pack),
  the CORS finding, limitations (bbox-outline rendering of polygon geometries; requires the DZI
  host reachable), and the citation/prior-art note.
- [ ] **Step 4: sanity check** — validate the HTML's JS syntax headlessly:
    `node --check` is unavailable for inline HTML, so extract the script block to a temp `.js` and
    run `node --check <tmp>.js` **if node exists** (`node -v`); if node is absent, review-only.
    `./gradlew` untouched by this task.
- [ ] **Step 5: Commit** `feat(web): standalone tour player (OpenSeadragon) for quiz/tour packs`.

---

## Verification (whole feature)
1. `./gradlew --no-daemon clean build test` green (new tests: QuizScoringTest,
   catalog-description test, QuizBrowseWindowTest pure helpers).
2. Final whole-branch review (most capable model), incl. cross-checks: scoring only ever appends
   to reveal text (no behavior change when nothing drawn); browse-mode overlay/filter cannot leak
   after window close (same lifecycle bar as the tour's highlight overlay); Basit görünüm restores
   exact prior menu visibility; the web player consumes the v1/v2 pack contract exactly as
   `AtlasQuizIO` writes it.
3. **User GUI smoke (surfaced once, user-run):** (a) draw an annotation on an ANNOTATION stop →
   Göster shows IoU line; navigate near/away from a NAVIGATION target → hit/miss line; (b) browse
   mode: load a pack, click regions on-slide + list entries, reveal answers, close window → no
   leftover overlay; (c) Basit görünüm on/off → Analyze/Classify/Automate/TMA menus disappear and
   fully restore; (d) browser: open tour-player.html, drag a pack JSON, step through stops on the
   live atlas DZI; (e) description pane appears once a catalog entry carries descriptionTR.

## Notes for the executor
- Sequential tasks only (several touch `AtlasExtension.java`).
- javap-verified: `QuPathGUI.getMenuBar()/getMenu(String,boolean)`, `ROI.getGeometry()`,
  `ROI.contains(double,double)`, `componentPointToImagePoint(double,double,Point2D,boolean)`.
- Branch: `feat/qupath-edu-enhancements` off `a0e8e97`. User drives push; merge decision at the end.
