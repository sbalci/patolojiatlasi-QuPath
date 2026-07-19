# Bench-Side Atlas Reference — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Open an atlas case in a second viewer beside the user's own slide at matched magnification (mpp-based), with independent pan by default + a full-sync toggle; launchable from the browser and a menu picker.

**Architecture:** `BenchReference` — a pure `matchedDownsample` helper (unit-tested) + orchestration that adds a new viewer via `ViewerManager.addColumn`, opens the atlas case into it on a background thread, matches magnification, and shows a small floating control window. A `ReferencePickerDialog`, a browser context-menu item, and a menu group are the launch points.

**Tech Stack:** Java 21, JavaFX (provided), QuPath 0.6 API (`compileOnly`), JUnit 5.

## Global Constraints
- QuPath 0.6.0 API only; Java 21; no new bundled deps. Build/test `--offline`. Package `com.patolojiatlasi.qupath`.
- Turkish user-facing labels; ASCII names.
- DZI open on a background daemon thread; viewer/grid/sync/UI mutation on the FX thread; `Platform.runLater` for the viewer hand-off (mirror `AtlasBrowser.openSelected`).
- Adding a NEW empty viewer (`addColumn`) never replaces existing viewer content → no unsaved-changes guard needed for the open; `close()` routes through `QuPathGUI.closeViewer` (prompts to save).

---

### Task 1: `BenchReference.matchedDownsample` pure helper + test

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/BenchReference.java` (helper only in this task)
- Test: `src/test/java/com/patolojiatlasi/qupath/BenchReferenceTest.java`

**Interface (later tasks rely on):** `static double matchedDownsample(double yourDs, double mppYours, double mppRef)` — returns `yourDs * mppYours / mppRef`, or `Double.NaN` if either mpp ≤ 0 (or yourDs ≤ 0).

- [ ] **Step 1: Failing test** — `BenchReferenceTest.java`:
```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchReferenceTest {

    @Test
    void sameMppKeepsYourDownsample() {
        assertEquals(4.0, BenchReference.matchedDownsample(4.0, 0.25, 0.25), 1e-9);
    }

    @Test
    void finerReferenceUsesSmallerDownsample() {
        // ref is twice as fine (0.125 vs 0.25 µm/px) → needs half the downsample to match on-screen µm/px
        assertEquals(2.0, BenchReference.matchedDownsample(4.0, 0.25, 0.5), 1e-9);
        assertEquals(8.0, BenchReference.matchedDownsample(4.0, 0.5, 0.25), 1e-9);
    }

    @Test
    void unknownMppReturnsNaN() {
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(4.0, 0.0, 0.25)));
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(4.0, 0.25, 0.0)));
        assertTrue(Double.isNaN(BenchReference.matchedDownsample(0.0, 0.25, 0.25)));
    }
}
```

- [ ] **Step 2:** Run → FAIL (class missing): `./gradlew --offline test --tests "com.patolojiatlasi.qupath.BenchReferenceTest"`
- [ ] **Step 3:** Create `BenchReference.java` with just:
```java
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
```
- [ ] **Step 4:** Run → PASS (3 tests). `./gradlew --offline compileJava` → SUCCESSFUL.
- [ ] **Step 5:** Commit: `git commit -m "feat(reference): BenchReference.matchedDownsample helper + tests"`

---

### Task 2: `BenchReference` orchestration + floating control window

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/BenchReference.java`

**Read for patterns:** `AtlasBrowser.openSelected()` (no-project branch: background daemon `Thread` builds `DziImageServer`+`ImageData`, viewer touched only in `Platform.runLater`); `RotationControl` (small floating single-instance `Stage` control window); `CaseCompare.backToSingle` (closing viewers via `QuPathGUI.closeViewer`).

**Verified API:** `qupath.getViewerManager()` → `addColumn(QuPathViewer)`, `getAllViewers() : ObservableList<QuPathViewer>`, `setActiveViewer(...)`, `setSynchronizeViewers(boolean)`; `QuPathGUI.getViewer()`, `closeViewer(QuPathViewer)`; `QuPathViewer.getDownsampleFactor()`, `setDownsampleFactor(double)`, `getImageData()`, `setImageData(...)`; `ImageData.getServer().getPixelCalibration()` → `hasPixelSizeMicrons()`, `getAveragedPixelSizeMicrons()`.

**Interface:** `public static void openBeside(QuPathGUI qupath, AtlasCase ref)` (the entry point for both launchers). Internally an instance holds `qupath`, the anchor viewer (`yourViewer`), the `refViewer`, and the control `Stage` (single instance — a `static` current-instance ref so re-opening reuses/replaces cleanly).

**Behaviour:**
- `openBeside`: `QuPathViewer active = qupath.getViewer();`
  - if `active == null` → info alert, return.
  - if `active.getImageData() == null` → open `ref` into `active` (background, per the open pattern) and return (degenerate: no second viewer, no control window).
  - else: snapshot `Set<QuPathViewer> before = new HashSet<>(getAllViewers())`; `getViewerManager().addColumn(active)`; find `refViewer` = the viewer in `getAllViewers()` not in `before` (if none found — addColumn refused — info alert, return). Open `ref` into `refViewer` on a background thread; on `onDone` (FX thread): `matchMagnification(active, refViewer)`, `setActiveViewer(refViewer)` (optional), and show the control window bound to (`active`, `refViewer`). On open failure: log, info alert, and best-effort remove the empty `refViewer` via `closeViewer`.
- `matchMagnification(yourViewer, refViewer)`: read `yourDs`, `mppYours` (`yourViewer.getImageData().getServer().getPixelCalibration()`, guarded by `hasPixelSizeMicrons()` else 0), `mppRef` likewise; `double ds = matchedDownsample(yourDs, mppYours, mppRef)`; if `!Double.isNaN(ds)` → `refViewer.setDownsampleFactor(ds)` + status "Büyütme eşlendi"; else status "Kalibrasyon bilinmiyor — büyütme eşlenemedi". FX thread.
- **Control window** (a small non-modal `Stage`, single instance — focus if open; mirror `RotationControl`):
  - **Büyütmeyi eşle** button → `matchMagnification(yourViewer, refViewer)`.
  - **Kaydırmayı da eşle (tam senkron)** `CheckBox` → `getViewerManager().setSynchronizeViewers(checked)`.
  - **Referansı kapat** button → `setSynchronizeViewers(false)`; close `refViewer` via `qupath.closeViewer(refViewer)` (prompts to save); close the control window.
  - a status `Label`.
  - `setOnHidden` clears the static current-instance ref.

- [ ] **Step 1:** Implement the orchestration + control window in `BenchReference`.
- [ ] **Step 2:** `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → all prior tests still PASS (no new automated tests — viewer UI is manual).
- [ ] **Step 3:** Commit: `git commit -m "feat(reference): open beside + magnification match + Referans control window"`

**Manual verification (record):** open your own slide; call `openBeside` with an atlas case → a second viewer appears beside it showing the atlas slide at matched µm/px; "Büyütmeyi eşle" re-matches after zooming; the sync checkbox links/unlinks pan; "Referansı kapat" removes the reference viewer (prompting to save if the anchor had edits).

---

### Task 3: Launch points — picker dialog + browser action + menu + README

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/ReferencePickerDialog.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` (tree context menu)
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (Referans menu group)
- Modify: `README.md`

**`ReferencePickerDialog`:** `static void show(QuPathGUI qupath)` — a small modal window: a search `TextField` + a `ListView<AtlasCase>` populated from `AtlasCatalog.loadBundled()`, filtered live by title/organ/stain substring (reuse `AtlasCase.getTitle()/getOrganEN()/getImage()`), a cell that shows the title; an **"Yanında aç"** button (and double-click) → `BenchReference.openBeside(qupath, selected)` then close. Mirror `ProjectBuilderDialog`'s modal-Stage style.

**`AtlasBrowser`:** add a second item to the existing tree `ContextMenu` — **"Referans olarak yanında aç"** → `BenchReference.openBeside(qupath, getSelectedCase())` (guard null selection with the existing status-hint pattern).

**`AtlasExtension`:** add a **Referans** `Menu` group under "Patoloji Atlası" (after Karşılaştır) with one item **"Referans slayt aç…"** → `ReferencePickerDialog.show(qupath)`.

**README:** a short "Bench-side reference" note (open an atlas case beside your own slide at matched magnification; sync toggle; from the browser right-click or Patoloji Atlası → Referans).

- [ ] **Step 1:** Implement the picker, browser item, menu group, README.
- [ ] **Step 2:** `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → PASS; `./gradlew --offline build` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -m "feat(reference): browser action + menu picker + README"`

## Self-Review
- Spec coverage: matchedDownsample (T1, tested); openBeside/addColumn/background-open/match/control-window/close (T2); picker + browser + menu + README (T3). ✅
- Placeholders: T1 full code+tests; T2/T3 precise interface + behavior + verified API + named reuse anchors. ✅
- Types consistent: `matchedDownsample`, `openBeside`, `ReferencePickerDialog.show` used identically across tasks. ✅
- Data safety: adds a NEW viewer (no overwrite); close routes through `closeViewer` (save prompt) — no silent loss. ✅
