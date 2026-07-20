# Related-Content Navigator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A companion window showing the open atlas slide's related content (same-case stains + same-category cases) as thumbnail filmstrips, auto-following the active viewer, with one guarded click to swap the viewer to a related slide.

**Architecture:** A pure, unit-tested list-builder (`RelatedContent`) reusing `CaseCompare.siblingStains` + `CoverageStats.stainBucket`; a JavaFX companion `Stage` (`RelatedContentNavigator`) with an `imageDataProperty` auto-follow listener, single-instance guard, background-loading thumbnails, and a dirty-checked click-to-swap that reuses two `CaseCompare` helpers.

**Tech Stack:** Java 21, JavaFX, QuPath 0.6 API (`compileOnly`), JUnit 5, Gradle (offline).

## Global Constraints

- Java 21; QuPath 0.6 API only; **NO new dependencies**.
- Turkish participant-facing labels; QuPath menu-path strings stay English.
- **The data-loss trap:** swapping the active viewer via `setImageData` bypasses QuPath's save prompt. The click-to-swap MUST dirty-check the outgoing image (`CaseCompare.isChangedSafe`) and confirm before swapping. `CaseCompare.openInto` is intentionally unguarded (its own caller guards) — reusing it bare without the guard is a save-protection REGRESSION.
- **Off-FX-thread for network/build; on-FX-thread for UI.** Thumbnails use JavaFX background-loading `Image` (no manual threads). `openInto` already builds the server off-thread + `setImageData` in `Platform.runLater`.
- **Single instance:** never open a second navigator (a second `imageDataProperty` listener). Mirror `BenchReference`'s refuse-second-open.
- **Listener lifecycle:** remove the `imageDataProperty` listener + clear the static instance on window close, or it leaks and keeps firing.
- Reuse (don't reimplement): `AtlasExtension.resolveOpenCase`, `CaseCompare.siblingStains`/`stripQuery`, `AtlasCatalog.loadBundled`, `CoverageStats.stainBucket`, the `AtlasBrowser` thumbnail idiom.

---

### Task 1: `RelatedContent` pure core + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/RelatedContent.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/RelatedContentTest.java`

**Interfaces:**
- Consumes: `CaseCompare.siblingStains(List<AtlasCase>, String)`, `CaseCompare.stripQuery(String)`, `CoverageStats.stainBucket(String, String)`, `CoverageStats.StainBucket`, `AtlasCase.getDziUrl/getReponame/getCategory/getImage/getStainname`.
- Produces:
  - `static List<AtlasCase> otherStains(List<AtlasCase> catalog, AtlasCase open)`
  - `static List<AtlasCase> sameCategoryCases(List<AtlasCase> catalog, AtlasCase open)`

- [ ] **Step 1: Write `RelatedContentTest` (failing)**

```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelatedContentTest {

    // reponame, stainname, image, titleEN, titleTR, organEN, speciality, type, dziUrl, thumbUrl, mpp
    private static AtlasCase mk(String repo, String image, String organ, String dzi) {
        return new AtlasCase(repo, image, image, "T " + repo, "", organ, "", "published", dzi, "", 0.0);
    }

    @Test
    void otherStainsDropsTheOpenSlide() {
        AtlasCase he  = mk("repoA", "HE",  "Colon", "https://x/repoA/HE.dzi");
        AtlasCase cd3 = mk("repoA", "CD3", "Colon", "https://x/repoA/CD3.dzi");
        AtlasCase cd20= mk("repoA", "CD20","Colon", "https://x/repoA/CD20.dzi");
        AtlasCase other = mk("repoB", "HE", "Colon", "https://x/repoB/HE.dzi");
        List<AtlasCase> cat = List.of(he, cd3, cd20, other);
        List<AtlasCase> res = RelatedContent.otherStains(cat, he);
        assertEquals(2, res.size());                         // CD3, CD20 — not HE (the open one)
        assertTrue(res.stream().noneMatch(c -> c.getDziUrl().equals(he.getDziUrl())));
        assertTrue(res.stream().anyMatch(c -> c.getImage().equals("CD3")));
    }

    @Test
    void otherStainsEmptyForSingleStainCase() {
        AtlasCase only = mk("solo", "HE", "Colon", "https://x/solo/HE.dzi");
        assertEquals(0, RelatedContent.otherStains(List.of(only), only).size());
    }

    @Test
    void sameCategoryReturnsOneRepresentativePerOtherCase() {
        AtlasCase openHe = mk("repoA", "HE", "Colon", "https://x/repoA/HE.dzi");     // Gastrointestinal
        AtlasCase bHe  = mk("repoB", "HE",  "Colon", "https://x/repoB/HE.dzi");
        AtlasCase bCd3 = mk("repoB", "CD3", "Colon", "https://x/repoB/CD3.dzi");
        AtlasCase cCd  = mk("repoC", "CD20","Colon", "https://x/repoC/CD20.dzi");    // no HE -> first stain
        AtlasCase breast = mk("repoD", "HE", "Breast", "https://x/repoD/HE.dzi");    // different category
        List<AtlasCase> cat = List.of(openHe, bHe, bCd3, cCd, breast);
        List<AtlasCase> res = RelatedContent.sameCategoryCases(cat, openHe);
        // repoB (represented by its HE) + repoC (represented by CD20). Not repoA (self), not repoD (other cat).
        assertEquals(2, res.size());
        assertTrue(res.stream().noneMatch(c -> c.getReponame().equals("repoA")));
        assertTrue(res.stream().noneMatch(c -> c.getReponame().equals("repoD")));
        AtlasCase repB = res.stream().filter(c -> c.getReponame().equals("repoB")).findFirst().orElseThrow();
        assertEquals("HE", repB.getImage());                 // prefers the H&E stain
        AtlasCase repC = res.stream().filter(c -> c.getReponame().equals("repoC")).findFirst().orElseThrow();
        assertEquals("CD20", repC.getImage());               // no HE -> first (only) stain
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline test --tests RelatedContentTest` → FAIL (class missing).

- [ ] **Step 3: Implement `RelatedContent`**

```java
package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.patolojiatlasi.qupath.CoverageStats.StainBucket;

/** Pure related-slide list building for the navigator. No UI, no network. */
final class RelatedContent {

    private RelatedContent() {}

    /** The open case's OTHER stains: {@code siblingStains} returns the open slide first; drop it. */
    static List<AtlasCase> otherStains(List<AtlasCase> catalog, AtlasCase open) {
        if (catalog == null || open == null)
            return List.of();
        List<AtlasCase> siblings = CaseCompare.siblingStains(catalog, open.getDziUrl());
        if (siblings.size() <= 1)
            return List.of();
        return List.copyOf(siblings.subList(1, siblings.size()));
    }

    /**
     * One representative {@link AtlasCase} per OTHER case (distinct {@code reponame}) sharing the
     * open case's {@link AtlasCase#getCategory()} — excluding the open case's own reponame. Stable
     * by first catalogue appearance; representative = the case's H&E stain if present, else its
     * first stain in catalogue order.
     */
    static List<AtlasCase> sameCategoryCases(List<AtlasCase> catalog, AtlasCase open) {
        if (catalog == null || open == null)
            return List.of();
        String category = open.getCategory();
        String openRepo = open.getReponame();
        // Group same-category, other-case slides by reponame, preserving first-seen order.
        Map<String, List<AtlasCase>> byRepo = new LinkedHashMap<>();
        for (AtlasCase c : catalog) {
            if (!category.equals(c.getCategory()))
                continue;
            String repo = c.getReponame();
            if (repo == null || repo.isBlank() || repo.equals(openRepo))
                continue;
            byRepo.computeIfAbsent(repo, k -> new ArrayList<>()).add(c);
        }
        List<AtlasCase> result = new ArrayList<>();
        for (List<AtlasCase> stains : byRepo.values())
            result.add(representative(stains));
        return result;
    }

    /** The H&E stain of a case if present (via {@link CoverageStats#stainBucket}), else the first. */
    private static AtlasCase representative(List<AtlasCase> stains) {
        for (AtlasCase c : stains)
            if (CoverageStats.stainBucket(c.getImage(), c.getStainname()) == StainBucket.HE)
                return c;
        return stains.get(0);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --offline test --tests RelatedContentTest` → PASS (3 tests). Then `./gradlew --offline build` → SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/RelatedContent.java \
        src/test/java/com/patolojiatlasi/qupath/RelatedContentTest.java
git commit -m "feat(related): RelatedContent pure list-builder + tests"
```

---

### Task 2: `RelatedContentNavigator` UI + widen `CaseCompare` helpers + menu + README

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/CaseCompare.java` (widen 2 helpers to package-private)
- Create: `src/main/java/com/patolojiatlasi/qupath/RelatedContentNavigator.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (one menu item)
- Modify: `README.md`

**Interfaces:**
- Consumes: `RelatedContent.otherStains/sameCategoryCases`, `AtlasExtension.resolveOpenCase`, `AtlasCatalog.loadBundled`, `CaseCompare.openInto` (widened), `CaseCompare.isChangedSafe` (widened), `qupath.imageDataProperty()`, `qupath.getViewer()`, `qupath.getStage()`, `AtlasCase.getThumbUrl/getTitle/getStainname/getImage/getDziURI`.
- Produces: `static void show(QuPathGUI qupath)`.

**Pattern references (read before writing):** `BenchReference.java` (single-instance static + refuse-second-open + teardown on close), `RotationControl.java` (`qupath.viewerProperty().addListener(...)` — mirror for `imageDataProperty`), `AtlasBrowser.java:258-259` (the `new Image(url, 220, 0, true, true, true)` background-loading thumbnail idiom + its open path), `CaseCompare.java` (`openInto`, `isChangedSafe`, `compareCurrentCase`'s guard usage), `CoverageDashboard.java` (a recent companion `Stage` with `.initOwner` + close handling).

- [ ] **Step 1: Widen the two `CaseCompare` helpers**

Change ONLY the access modifiers (no body change):
- `private static void openInto(QuPathViewer viewer, AtlasCase c)` → `static void openInto(...)`.
- `private static boolean isChangedSafe(ImageData<BufferedImage> d)` → `static boolean isChangedSafe(...)`.
Confirm both compile and existing tests still pass: `./gradlew --offline test`.

- [ ] **Step 2: Implement `RelatedContentNavigator`**

`public static void show(QuPathGUI qupath)`:
1. **Single-instance:** a `private static RelatedContentNavigator instance;`. If `instance != null && instance.stage.isShowing()` → `instance.stage.toFront(); return;`. Otherwise construct + assign `instance`.
2. **Stage:** non-modal, resizable, title "İlgili içerik", `.initOwner(qupath.getStage())` when non-null (null-guarded, as `CoverageDashboard`/`CitationDialog` do). Root = a `VBox`: a header `Label` (current case title), then Section 1 "Bu vakanın diğer boyaları" (a `ScrollPane` wrapping an `HBox` strip), then Section 2 "Aynı kategoriden vakalar" (same), then a "Yenile" `Button` → `refresh()`.
3. **Auto-follow:** create `ChangeListener<ImageData<BufferedImage>> imageListener = (obs, oldD, newD) -> Platform.runLater(this::refresh);` and `qupath.imageDataProperty().addListener(imageListener);` (keep a field ref for removal). Call `refresh()` once immediately.
4. **Teardown on close:** `stage.setOnHidden(e -> { qupath.imageDataProperty().removeListener(imageListener); if (instance == this) instance = null; });` (also fine to set `onCloseRequest`; `onHidden` covers both the X and programmatic close).
5. **`refresh()`** (must run on FX thread — the listener wraps it in `Platform.runLater`; the initial call is already on FX):
   ```java
   AtlasCase open = AtlasExtension.resolveOpenCase(qupath);
   if (open == null) { headerLabel.setText("İlgili içerik için bir atlas slaytı açın."); clear both strips; return; }
   headerLabel.setText(open.getTitle());
   List<AtlasCase> catalog = AtlasCatalog.loadBundled();
   fillStrip(stainsStrip, RelatedContent.otherStains(catalog, open), /*captionIsStain=*/true);
   fillStrip(caseStrip,   RelatedContent.sameCategoryCases(catalog, open), /*captionIsStain=*/false);
   ```
   `loadBundled()` is offline/cheap (called on the FX thread throughout this codebase — `AtlasBrowser`, `CoverageDashboard` do the same). If a strip's list is empty, put a single "—" label in it.
6. **`fillStrip(HBox strip, List<AtlasCase> items, boolean captionIsStain)`:** clear + for each `AtlasCase c` build a tile: a `VBox`(ImageView, captionLabel) wrapped in a `Button` (or a clickable VBox). ImageView: if `!c.getThumbUrl().isBlank()` → `new Image(c.getThumbUrl(), THUMB_W, 0, true, true, true)` (background loading), `setFitWidth(THUMB_W)`, `setPreserveRatio(true)`; else no image (text tile). Caption = `captionIsStain ? stainLabel(c) : c.getTitle()`. Tooltip = `c.getTitle()`. On click → `swapTo(c)`.
7. **`swapTo(AtlasCase target)`** (the guarded action, FX thread):
   ```java
   QuPathViewer viewer = qupath.getViewer();
   if (viewer == null) return;
   ImageData<BufferedImage> outgoing = viewer.getImageData();
   if (outgoing != null && CaseCompare.isChangedSafe(outgoing) && !confirmSwap()) return;
   CaseCompare.openInto(viewer, target);   // off-thread build + Platform.runLater setImageData
   ```
   `confirmSwap()` → `Dialogs.showConfirm("İlgili içerik", "Açık slaytta kaydedilmemiş değişiklikler var. Başka bir slaytla değiştirilsin mi?")` (match the repo's `Dialogs`/`fx.dialogs` usage — check `CaseCompare.confirmReplace` for the exact API/import). After the swap, `imageDataProperty` fires → `refresh()` rebuilds for the new case automatically.
8. `THUMB_W` ≈ 160. All `Alert`/`Dialogs` owner-safe. No `String.format` needed; if any is added, pin `Locale.US`.

- [ ] **Step 3: Wire the menu in `AtlasExtension`**

```java
MenuItem relatedItem = new MenuItem("İlgili içerik…");
relatedItem.setOnAction(e -> RelatedContentNavigator.show(qupath));
```
Add it to the Patoloji Atlası menu near the browser / Case-Compare items (NOT under Atıf), matching the existing add-order style.

- [ ] **Step 4: README**

Add a "Related-content navigator" subsection: Extensions → Patoloji Atlası → "İlgili içerik…" opens a companion window that, for the open atlas slide, shows the same case's other stains and other cases in the same category as thumbnail strips; it auto-follows the active viewer; click a thumbnail to swap the viewer to that slide (you're asked to confirm if the current slide has unsaved changes).

- [ ] **Step 5: Build + commit**

Run: `./gradlew --offline build` → BUILD SUCCESSFUL (existing tests + `RelatedContentTest` pass; no new automated tests for the JavaFX navigator, per the repo's pure-logic-tested / dialogs-manual boundary).
```bash
git add src/main/java/com/patolojiatlasi/qupath/CaseCompare.java \
        src/main/java/com/patolojiatlasi/qupath/RelatedContentNavigator.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java README.md
git commit -m "feat(related): navigator window + auto-follow + guarded swap + menu + README"
```

---

## Self-Review (author)

- **Spec coverage:** §3 pure core → Task 1. §4 navigator (single-instance, auto-follow listener + teardown, thumbnails, guarded swap) → Task 2. §5 widen `openInto`+`isChangedSafe` → Task 2 Step 1. §6 menu → Task 2 Step 3. §7 tests → Task 1. ✅
- **Type consistency:** `otherStains`/`sameCategoryCases` signatures identical across plan + tests; `CaseCompare.openInto(QuPathViewer, AtlasCase)` + `isChangedSafe(ImageData<BufferedImage>)` match the verified source; `imageDataProperty()` returns `ReadOnlyObjectProperty<ImageData<BufferedImage>>` (javap-verified) so the `ChangeListener<ImageData<BufferedImage>>` type is correct.
- **Placeholder scan:** none — full code for the pure core + tests; Task 2 gives exact strings, the consumed signatures, the guarded-swap sequence verbatim, and named pattern references (repo convention for JavaFX dialogs).
- **Trap coverage:** the swap guard (`isChangedSafe` + `confirmSwap` before `openInto`) is the load-bearing safety step, called out in Global Constraints AND Task 2 Step 2.7 with the exact sequence. Listener removal on close is Step 2.4. Single-instance is Step 2.1.
