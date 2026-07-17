# Compare a case's stains — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** From an open atlas slide, open all stains of the same case into QuPath's native synchronized multi-viewer grid, via one menu item (+ a "back to single view" item).

**Architecture:** A `CaseCompare` class with pure, unit-tested helpers (`siblingStains`, `gridFor`) and an orchestration method that matches the open slide to its case, builds the native grid, opens each stain into its own viewer on background threads, and turns on synchronization.

**Tech Stack:** Java 21, JavaFX (provided), QuPath 0.6 API (`compileOnly`), JUnit 5.

## Global Constraints
- QuPath 0.6.0 API only; Java 21; no new bundled deps. Build/test `--offline`.
- Turkish user-facing labels; ASCII class/method names.
- Reuse the atlas open pattern (background daemon thread builds `DziImageServer`+`ImageData`, viewer touched only in `Platform.runLater`) — mirror `AtlasBrowser.openSelected` no-project branch.
- All viewer/grid/sync mutation on the FX thread.
- Package: `com.patolojiatlasi.qupath`.

---

### Task 1: `CaseCompare` pure helpers + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/CaseCompare.java` (helpers only in this task)
- Test: `src/test/java/com/patolojiatlasi/qupath/CaseCompareTest.java`

**Interfaces (Task 2 relies on):**
- `static List<AtlasCase> siblingStains(List<AtlasCase> catalog, String openDziUrl)`
- `static int[] gridFor(int n)` → `{rows, cols}`
- `static String stripQuery(String url)` (package-private helper)

- [ ] **Step 1: Failing test** — `CaseCompareTest.java`:
```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CaseCompareTest {

    private static AtlasCase c(String repo, String stain, String dziUrl) {
        return new AtlasCase(repo, stain, stain, stain + " title", "", "Colon", "GI",
                "published", dziUrl, "");
    }

    private static final List<AtlasCase> CATALOG = List.of(
            c("caseA", "HE",  "https://x/caseA/HE.dzi"),
            c("caseA", "CD3", "https://x/caseA/CD3.dzi"),
            c("caseA", "CD8", "https://x/caseA/CD8.dzi"),
            c("caseB", "HE",  "https://x/caseB/HE.dzi"));

    @Test
    void groupsByReponameWithOpenSlideFirst() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseA/CD3.dzi");
        assertEquals(3, r.size());
        assertEquals("https://x/caseA/CD3.dzi", r.get(0).getDziUrl(), "open slide must be first");
        assertTrue(r.stream().allMatch(a -> a.getReponame().equals("caseA")));
    }

    @Test
    void ignoresMppQueryWhenMatching() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseA/HE.dzi?mpp=0.26");
        assertEquals(3, r.size());
        assertEquals("https://x/caseA/HE.dzi", r.get(0).getDziUrl());
    }

    @Test
    void notInCatalogReturnsEmpty() {
        assertTrue(CaseCompare.siblingStains(CATALOG, "https://x/unknown/HE.dzi").isEmpty());
        assertTrue(CaseCompare.siblingStains(CATALOG, "").isEmpty());
    }

    @Test
    void singleStainCaseReturnsJustIt() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseB/HE.dzi");
        assertEquals(1, r.size());
    }

    @Test
    void gridForMapsAndCapsAtSix() {
        assertArrayEquals(new int[]{1, 1}, CaseCompare.gridFor(1));
        assertArrayEquals(new int[]{1, 2}, CaseCompare.gridFor(2));
        assertArrayEquals(new int[]{1, 3}, CaseCompare.gridFor(3));
        assertArrayEquals(new int[]{2, 2}, CaseCompare.gridFor(4));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(5));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(6));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(9)); // capped
        assertArrayEquals(new int[]{1, 1}, CaseCompare.gridFor(0)); // floor
    }
}
```

- [ ] **Step 2:** Run → FAIL (class missing): `./gradlew --offline test --tests "com.patolojiatlasi.qupath.CaseCompareTest"`

- [ ] **Step 3:** Implement the helpers in `CaseCompare.java`:
```java
package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opens all stains of one atlas case into QuPath's synchronized multi-viewer grid. */
public final class CaseCompare {

    private CaseCompare() {}

    /**
     * All stains of the case that owns {@code openDziUrl} (matched by DZI URL, ignoring any
     * {@code ?mpp=} query), with the open slide first, deduped, order stable. Empty if the URL
     * isn't a catalogue slide; a single-element list if its case has only that stain (or a blank
     * reponame, which can't be grouped).
     */
    public static List<AtlasCase> siblingStains(List<AtlasCase> catalog, String openDziUrl) {
        if (catalog == null || openDziUrl == null || openDziUrl.isBlank())
            return List.of();
        String openBase = stripQuery(openDziUrl);
        AtlasCase open = null;
        for (AtlasCase a : catalog) {
            if (stripQuery(a.getDziUrl()).equals(openBase)) {
                open = a;
                break;
            }
        }
        if (open == null)
            return List.of();
        String repo = open.getReponame();
        if (repo == null || repo.isBlank())
            return List.of(open);
        List<AtlasCase> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        result.add(open);
        seen.add(stripQuery(open.getDziUrl()));
        for (AtlasCase a : catalog) {
            if (!repo.equals(a.getReponame()))
                continue;
            if (seen.add(stripQuery(a.getDziUrl())))
                result.add(a);
        }
        return result;
    }

    /** {rows, cols} for {@code n} panels: 1×1, 1×2, 1×3, 2×2, else 2×3; n clamped to [1,6]. */
    public static int[] gridFor(int n) {
        int c = Math.max(1, Math.min(6, n));
        return switch (c) {
            case 1 -> new int[]{1, 1};
            case 2 -> new int[]{1, 2};
            case 3 -> new int[]{1, 3};
            case 4 -> new int[]{2, 2};
            default -> new int[]{2, 3};
        };
    }

    static String stripQuery(String url) {
        if (url == null)
            return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
```

- [ ] **Step 4:** Run → PASS (5 tests). `./gradlew --offline compileJava` → SUCCESSFUL.
- [ ] **Step 5:** Commit: `git commit -m "feat(compare): CaseCompare sibling-finding + grid-sizing helpers"`

---

### Task 2: Orchestration (open into synced grid) + menu wiring

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/CaseCompare.java` (add the orchestration methods)
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (two menu items)

**Read for patterns:** `AtlasBrowser.openSelected()` (no-project branch: `new DziImageServer(uri)` + `new ImageData<>(server, type)` on a background daemon thread, viewer touched in `Platform.runLater`); `AtlasCatalog.loadBundled()`; `AtlasCase.getImageType()`/`getDziURI()`.

**Verified API:** `QuPathGUI.getViewerManager()`; `ViewerManager.setGridSize(int nRows, int nCols)` (VERIFY arg order at impl via a quick check of QuPath source/behavior — the helper returns `{rows, cols}`), `resetGridSize()`, `setSynchronizeViewers(boolean)`, `getAllViewers() : ObservableList<QuPathViewer>`, `getActiveViewer()`; `QuPathViewer.setImageData(ImageData)`/`getImageData()`; server `getURIs()`; `ImageData.isChanged()`.

**Interface:**
- `public static void compareCurrentCase(QuPathGUI qupath)`
- `public static void backToSingle(QuPathGUI qupath)`

**Behaviour of `compareCurrentCase`:**
1. `QuPathViewer active = qupath.getViewer();` if null or `active.getImageData()==null` → info Alert "Bu bir atlas slaytı değil ya da katalogda bulunamadı." and return.
2. Read the open DZI URL: first URI of `active.getImageData().getServer().getURIs()` as string; if none → same info Alert, return.
3. `List<AtlasCase> stains = siblingStains(AtlasCatalog.loadBundled(), openUrl);`
   - empty → info "Bu bir atlas slaytı değil ya da katalogda bulunamadı."; return.
   - size 1 → info "Bu vakada karşılaştırılacak başka boya yok."; return.
4. If `active.getImageData().isChanged()` (guard via a `try/catch` returning false) → CONFIRMATION Alert "Karşılaştırma görünümü, görüntüleyicideki görüntülerin yerini alır; kaydedilmemiş değişiklikler kaybolabilir. Devam edilsin mi?" — Cancel → return.
5. Cap: if `stains.size() > 6`, keep the first 6 and remember the dropped count for the final status.
6. `int[] g = gridFor(stains.size());` → `qupath.getViewerManager().setGridSize(g[0], g[1]);` (FX thread).
7. `List<QuPathViewer> viewers = qupath.getViewerManager().getAllViewers();` — open `stains.get(i)` into `viewers.get(i)` for `i < min(stains.size(), viewers.size())`, each on a background daemon thread: build `new DziImageServer(stains.get(i).getDziURI())` + `new ImageData<>(server, stains.get(i).getImageType())`, then `Platform.runLater(() -> viewers.get(i).setImageData(imageData))`. On per-slide failure, log + continue (don't abort the whole compare); collect failures.
8. After dispatching opens, `qupath.getViewerManager().setSynchronizeViewers(true);` (FX thread). (Sync applies as each viewer loads.)
9. Optionally set the first viewer active (`setActiveViewer(viewers.get(0))`).

**Shared open helper:** factor the per-viewer open into `private static void openInto(QuPathViewer viewer, AtlasCase c, ...)` used by the loop (mirrors `QuizSlide.openAsync` but targets a *given* viewer). Do not reuse `QuizSlide` (it opens into the active viewer only).

**`backToSingle`:** `qupath.getViewerManager().setSynchronizeViewers(false); qupath.getViewerManager().resetGridSize();` (FX thread).

**Menu wiring (`AtlasExtension.installExtension`, after the quiz items):**
```java
            MenuItem compareItem = new MenuItem("Bu vakanın boyalarını karşılaştır…");
            compareItem.setOnAction(e -> com.patolojiatlasi.qupath.CaseCompare.compareCurrentCase(qupath));
            MenuItem singleItem = new MenuItem("Tek görünüme dön");
            singleItem.setOnAction(e -> com.patolojiatlasi.qupath.CaseCompare.backToSingle(qupath));
            menu.getItems().addAll(compareItem, singleItem);
```

- [ ] **Step 1:** Implement the orchestration + `openInto` helper + menu wiring.
- [ ] **Step 2:** `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → all prior tests still PASS (no new automated tests — multi-viewer UI is manual).
- [ ] **Step 3:** README — add a short "Compare a case's stains" note under the quiz/features section.
- [ ] **Step 4:** `./gradlew --offline build` → SUCCESSFUL. Commit: `git commit -m "feat(compare): open a case's stains into a synchronized grid + menu"`

**Manual verification (record):** open a multi-stain atlas case (e.g. lymphocytic-gastritis: HE/CD3/CD8/CD20), run "Bu vakanın boyalarını karşılaştır…" → the stains open in a grid, pan/zoom is linked; a single-stain slide shows the "no other stains" info; "Tek görünüme dön" collapses back.

## Self-Review
- Spec coverage: siblingStains+gridFor (T1, tested), matching/guards/grid/open/sync (T2), back-to-single (T2), menu (T2). ✅
- Placeholders: T1 full code+tests; T2 precise behavior + verified API + the one arg-order VERIFY note (setGridSize rows/cols) which is a deliberate impl-time check. ✅
- Types consistent: `siblingStains`, `gridFor`, `stripQuery`, `compareCurrentCase`, `backToSingle` used identically across tasks. ✅
