# Blinded Temporal Focus Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record which slide regions a viewer looked at and for how long, WITHOUT ever showing them a heatmap (unbiased), with a project-default "hidden tracking on" mode for easy studies.

**Architecture:** A pure temporal kernel in `FocusMap` (weighted deposit + a tested idle/Δt function); a blinded recording state in `FocusHeatmap` (no visuals, idle-paused, ms-weighted, raw-JSON save); a `BlindedResearch` sidecar-flag helper; and an `AtlasExtension` project-open hook (one shared `FocusHeatmap` instance) with a one-time consent notice + a builder checkbox.

**Tech Stack:** Java 21, JavaFX, QuPath 0.6 API (`compileOnly`), Gson (present), JUnit 5, Gradle (offline).

## Global Constraints

- Java 21; QuPath 0.6 only; **NO new dependencies**.
- Turkish participant-facing labels; menu-path strings English.
- **Blinding = data-only:** blinded recording shows NO overlay/window/status, and its save writes **raw JSON only, never a PNG** (the PNG render is tuned for sample-counts, would saturate on ms grids, and a rendered heatmap on disk is itself "visualization" the data-only decision rules out).
- **Idle validity:** dwell accrues only while the QuPath window is focused AND a slide is open; the per-tick Δt is clamped to a cap (suspend/resume guard). This accounting MUST be a **pure, unit-tested** function — it is the feature's core research-validity claim.
- **One `FocusHeatmap` controller:** the auto-start hook and the menu must drive the SAME instance (today `AtlasExtension` builds a throwaway `new FocusHeatmap(qupath).buildMenu()` — refactor to a field). Two instances = two Timelines + broken mutual-exclusion.
- **`durationMs` is a separate accumulator** (`FocusMap.getTotalWeight()`), not re-derived elsewhere.
- The **visible** heatmap's behavior/look is unchanged — only blinded recording is ms-weighted.
- Anonymized/opt-in/no-PHI (reuse the existing `slideKey`/`sessionId` machinery); upload stays gated off.

---

### Task 1: `FocusMap` temporal kernel (pure) + tests

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusMap.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/focus/FocusMapTemporalTest.java` (new; there may be an existing `FocusMapTest` — do not disturb it)

**Interfaces:**
- Produces:
  - `boolean deposit(double x, double y, double w, double h, double weight)` (weighted overload)
  - existing `boolean deposit(double x, double y, double w, double h)` now delegates `deposit(x,y,w,h,1.0)`
  - `double getTotalWeight()` — running sum of successfully-deposited weight (dwell-ms for a blinded map)
  - `static long activeDwellMs(long nowMs, long lastTickMs, boolean active, long capMs)` — the pure idle/Δt kernel

- [ ] **Step 1: Write `FocusMapTemporalTest` (failing)**

```java
package com.patolojiatlasi.qupath.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FocusMapTemporalTest {

    @Test
    void activeDwellMsClampsAndGuards() {
        assertEquals(0L, FocusMap.activeDwellMs(1000, 900, false, 500));   // inactive -> 0
        assertEquals(100L, FocusMap.activeDwellMs(1000, 900, true, 500));  // normal dt
        assertEquals(500L, FocusMap.activeDwellMs(2000, 900, true, 500));  // dt > cap -> cap
        assertEquals(0L, FocusMap.activeDwellMs(900, 1000, true, 500));    // backwards clock -> 0
        assertEquals(0L, FocusMap.activeDwellMs(1000, 1000, true, 500));   // zero dt -> 0
    }

    @Test
    void weightedDepositAccumulatesTotalWeight() {
        FocusMap m = new FocusMap(1000, 1000, 10);   // 10x10 grid
        assertTrue(m.deposit(0, 0, 1000, 1000, 250.0));   // whole image, 250 ms
        assertEquals(250.0, m.getTotalWeight(), 1e-6);
        m.deposit(0, 0, 1000, 1000, 250.0);
        assertEquals(500.0, m.getTotalWeight(), 1e-6);
        // grid sum equals total deposited weight (full weight spread over covered cells)
        double sum = 0;
        for (float v : m.getGrid()) sum += v;
        assertEquals(500.0, sum, 1e-3);
    }

    @Test
    void legacyDepositIsWeightOne() {
        FocusMap m = new FocusMap(1000, 1000, 10);
        m.deposit(0, 0, 1000, 1000);                 // legacy -> weight 1
        assertEquals(1.0, m.getTotalWeight(), 1e-6);
    }

    @Test
    void nonPositiveWeightIsNoOp() {
        FocusMap m = new FocusMap(1000, 1000, 10);
        assertFalse(m.deposit(0, 0, 1000, 1000, 0.0));
        assertFalse(m.deposit(0, 0, 1000, 1000, -5.0));
        assertEquals(0.0, m.getTotalWeight(), 1e-6);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline test --tests FocusMapTemporalTest` → FAIL (methods missing).

- [ ] **Step 3: Implement in `FocusMap`**

Add the field `private double totalWeight;` next to `sampleCount`. Refactor `deposit`:
```java
public boolean deposit(double x, double y, double w, double h) {
    return deposit(x, y, w, h, 1.0);
}

/** Deposit {@code weight} (e.g. dwell-milliseconds) spread over the covered cells. */
public boolean deposit(double x, double y, double w, double h, double weight) {
    if (weight <= 0 || w <= 0 || h <= 0)
        return false;
    if (x + w <= 0 || y + h <= 0 || x >= imageWidth || y >= imageHeight)
        return false;
    int c0 = clamp((int) Math.floor(x / imageWidth * gridW), 0, gridW - 1);
    int c1 = clamp((int) Math.floor((x + w) / imageWidth * gridW), 0, gridW - 1);
    int r0 = clamp((int) Math.floor(y / imageHeight * gridH), 0, gridH - 1);
    int r1 = clamp((int) Math.floor((y + h) / imageHeight * gridH), 0, gridH - 1);
    int n = (c1 - c0 + 1) * (r1 - r0 + 1);
    float per = (float) (weight / n);
    for (int r = r0; r <= r1; r++)
        for (int c = c0; c <= c1; c++)
            grid[r * gridW + c] += per;
    sampleCount++;
    totalWeight += weight;
    return true;
}

public double getTotalWeight() { return totalWeight; }

/** Pure idle/Δt kernel: elapsed ms to credit this tick. Inactive → 0; else dt clamped to [0, cap]. */
public static long activeDwellMs(long nowMs, long lastTickMs, boolean active, long capMs) {
    if (!active)
        return 0L;
    long dt = nowMs - lastTickMs;
    if (dt <= 0)
        return 0L;
    return Math.min(dt, capMs);
}
```
Also reset `totalWeight = 0` wherever `clear()` resets `sampleCount`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --offline test --tests FocusMapTemporalTest` → PASS. Then `./gradlew --offline build` → SUCCESSFUL (existing FocusMap tests still green — the legacy `deposit` is unchanged behavior).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusMap.java \
        src/test/java/com/patolojiatlasi/qupath/focus/FocusMapTemporalTest.java
git commit -m "feat(focus): FocusMap weighted deposit + tested idle/Δt kernel + totalWeight"
```

---

### Task 2: `FocusHeatmap` blinded recording mode + one-controller refactor

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java`

**Interfaces:**
- Consumes: `FocusMap.deposit(x,y,w,h,weight)`, `FocusMap.getTotalWeight()`, `FocusMap.activeDwellMs(...)`, `Stage.isFocused()`, `qupath.getViewer().getImageData()`.
- Produces on `FocusHeatmap`: `void startBlinded()`, `void stopBlinded()`, `boolean isBlinded()` (called by Task 3's hook). Keeps its existing `buildMenu()`.

**Pattern references (read first):** `FocusHeatmap.java` in full (its `overlayVisible`/`windowVisible`/`track`/`tick()`/`save`/`buildContributionJson`/`defaultDir`/menu), `FocusMap` (Task 1 additions), `AtlasExtension.java:62-67` (the throwaway `new FocusHeatmap(qupath).buildMenu()`).

- [ ] **Step 1: `AtlasExtension` — hold ONE `FocusHeatmap` instance**

Change `AtlasExtension.java:67` from `viewMenu.getItems().addAll(rotationItem, new FocusHeatmap(qupath).buildMenu());` to store the instance in a field:
```java
this.focusHeatmap = new FocusHeatmap(qupath);
viewMenu.getItems().addAll(rotationItem, focusHeatmap.buildMenu());
```
Add `private FocusHeatmap focusHeatmap;` as an `AtlasExtension` field. (Task 3's project hook will call `focusHeatmap.startBlinded()/stopBlinded()`.) Build to confirm no behavior change.

- [ ] **Step 2: `FocusHeatmap` — add the blinded state**

- Add `private boolean blindedRecording;`, `private long lastTickMs;`, and `private static final long DT_CAP_MS = 2L * SAMPLE_MS;`.
- `track = overlayVisible || windowVisible || blindedRecording` (update the existing `track` computation + the timer start/stop condition).
- `boolean isBlinded() { return blindedRecording; }`.
- `void startBlinded()`: if already blinded → return. Clear any visible overlay/window (call the existing hide paths) and DISABLE the overlay + window menu items (keep references to them from `buildMenu`); `currentMap`-reset for the active slide; `lastTickMs = System.currentTimeMillis()`; `blindedRecording = true`; ensure the timer runs. NO visual.
- `void stopBlinded()`: if not blinded → return; `blindedRecording = false`; save the blinded contribution (Step 4); re-enable the overlay/window menu items; stop the timer if nothing else needs it.
- Guard: while `blindedRecording`, the overlay/window toggle handlers must refuse to turn on (belt-and-suspenders beyond disabling them).

- [ ] **Step 3: `tick()` — blinded branch (uses the pure kernel)**

In `tick()`, when `blindedRecording`:
```java
long now = System.currentTimeMillis();
QuPathViewer v = qupath.getViewer();
boolean active = mainStageFocused() && v != null && v.getImageData() != null;
long ms = FocusMap.activeDwellMs(now, lastTickMs, active, DT_CAP_MS);
lastTickMs = now;
if (ms > 0 && currentMap != null) {
    Shape shape = v.getDisplayedRegionShape();
    Rectangle b = shape.getBounds();
    currentMap.deposit(b.getX(), b.getY(), b.getWidth(), b.getHeight(), ms);
}
// NO overlay/window refresh in the blinded path.
```
`mainStageFocused()` = `qupath.getStage() != null && qupath.getStage().isFocused()`. Keep the existing visible-mode branch untouched (still fixed-weight, still refreshes when visible). Handle slide-change while blinded exactly as the visible modes do (save the finished slide's map, start a fresh map for the new slide, reset `lastTickMs`).

- [ ] **Step 4: Blinded save — raw JSON only, temporal schema**

Add a blinded save that writes **only** a JSON (no PNG) to `defaultDir()/contributions/` reusing the anonymized payload, but:
- `schema = "atlas-focus-contribution/2"`, add `"weightUnit":"ms"` and `"durationMs": currentMap.getTotalWeight()`.
- Keep `slideKey`/`sessionId`/dims/`grid`/date. (The `grid` values are now ms.)
Do NOT call the PNG-writing path for blinded saves. (The visible-mode save is unchanged.)
**Aggregator compatibility:** add a one-line note in the code + update `tools/aggregate-focus.py` to accept schema/2 (read `grid` as ms when `weightUnit=="ms"`; it already normalizes per-contribution, so ms vs counts normalizes the same) — OR, if simpler, keep it additive so the existing reader still works. State which in the report.

- [ ] **Step 5: Menu toggle**

Add a `CheckMenuItem` (or start/stop pair) **"Kör kayıt (araştırma)"** to `buildMenu()` → `startBlinded()`/`stopBlinded()`. A short tooltip: records viewing silently for research; no heatmap is shown.

- [ ] **Step 6: Build + commit**

Run: `./gradlew --offline build` → SUCCESSFUL (Task 1 tests green; no new automated tests for the FX recorder — the tested kernel is in Task 1).
```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java \
        tools/aggregate-focus.py
git commit -m "feat(focus): blinded recording mode (data-only, idle-paused, ms grid) + one controller"
```

---

### Task 3: Blinded-research project — sidecar flag, auto-start hook, consent, builder

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/research/BlindedResearch.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/research/BlindedResearchTest.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (project-open hook)
- Modify: `src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java` (checkbox) + possibly `AtlasProjectService.java`
- Modify: `README.md`

**Interfaces:**
- Produces on `BlindedResearch` (static, fail-soft; file I/O like `AtlasCollectionIO`):
  - `static void writeFlag(java.io.File projectDir, boolean blinded)`
  - `static boolean isBlindedProject(qupath.lib.projects.Project<?> project)`
  - `static boolean hasConsented(qupath.lib.projects.Project<?> project)`
  - `static void markConsented(qupath.lib.projects.Project<?> project)`
  - `static java.io.File projectDir(qupath.lib.projects.Project<?> project)` — `project.getPath().getParent().toFile()` (null-safe → null)

- [ ] **Step 1: Write `BlindedResearchTest` (failing)**

Test with a `@TempDir` acting as the project dir (do NOT construct a real QuPath `Project` — test the file-level helpers via a package-private `writeFlag(File,...)` + a package-private reader that takes a `File`, so no `Project` mock is needed):
```java
package com.patolojiatlasi.qupath.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlindedResearchTest {
    @Test void flagRoundTrips(@TempDir File dir) {
        assertFalse(BlindedResearch.readBlinded(dir));       // no sidecar -> not blinded
        BlindedResearch.writeFlag(dir, true);
        assertTrue(BlindedResearch.readBlinded(dir));
    }
    @Test void consentRoundTrips(@TempDir File dir) {
        BlindedResearch.writeFlag(dir, true);
        assertFalse(BlindedResearch.readConsented(dir));
        BlindedResearch.markConsented(dir);
        assertTrue(BlindedResearch.readConsented(dir));
        assertTrue(BlindedResearch.readBlinded(dir));        // marking consent preserves the flag
    }
    @Test void corruptSidecarIsNotBlinded(@TempDir File dir) throws Exception {
        java.nio.file.Files.writeString(new File(dir, "atlas-research.json").toPath(), "{ bad");
        assertFalse(BlindedResearch.readBlinded(dir));        // fail-soft, no throw
    }
}
```
(The `Project`-taking overloads `isBlindedProject`/`hasConsented`/`markConsented` delegate to the `File`-taking `readBlinded`/`readConsented`/`writeFlag` via `projectDir(project)`; the `File` ones are what the tests exercise.)

- [ ] **Step 2: Run to verify it fails**, then implement `BlindedResearch` (Gson read/write of `{schema:"atlas-research/1", blindedTracking, consented}` at `<dir>/atlas-research.json`; fail-soft load → false; `markConsented` preserves `blindedTracking`). Run tests → PASS.

- [ ] **Step 3: Auto-start hook in `AtlasExtension`**

Register a `ChangeListener` on `qupath.projectProperty()` (mirror `RotationControl`'s `viewerProperty()` listener):
```java
qupath.projectProperty().addListener((obs, oldProj, newProj) -> onProjectChanged(oldProj, newProj));
```
`onProjectChanged`: if `oldProj` was blinded and recording → `focusHeatmap.stopBlinded()`. If `newProj != null && BlindedResearch.isBlindedProject(newProj)`:
- if `!BlindedResearch.hasConsented(newProj)` → show the one-time consent `Alert` (Turkish, §2.5 of the spec); on OK → `BlindedResearch.markConsented(newProj)` and `focusHeatmap.startBlinded()`; on cancel/decline → do NOT start (consent stays false → asked again next open, recording off).
- else (already consented) → `focusHeatmap.startBlinded()`.
Guard re-entrancy (don't start if already blinded). All on the FX thread (property fires on FX).

- [ ] **Step 4: Builder checkbox**

In `ProjectBuilderDialog`, add a `CheckBox` **"Araştırma projesi — kör odak kaydı (blinded)"**. When the project is created/opened, if checked → `BlindedResearch.writeFlag(projectDir, true)` (projectDir from the newly-built/added project). Wire minimally; do not disturb the existing build flow.

- [ ] **Step 5: README + build + commit**

README "Blinded research recording" subsection (what it records, that nothing is shown, the project-default-on + one-time consent, anonymized/local). Run `./gradlew --offline build` → SUCCESSFUL.
```bash
git add src/main/java/com/patolojiatlasi/qupath/research/BlindedResearch.java \
        src/test/java/com/patolojiatlasi/qupath/research/BlindedResearchTest.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java \
        src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java README.md
git commit -m "feat(focus): blinded-research project flag + auto-start hook + consent + builder"
```

---

## Self-Review (author)

- **Spec coverage:** §3 temporal kernel → Task 1 (pure + tested, incl. the `activeDwellMs` kernel the advisor gated on). §4 blinded mode (no visuals, idle-pause, ms grid, raw-JSON-only save, one controller) → Task 2. §5 sidecar/auto-start/consent/builder → Task 3. ✅
- **Advisor points:** #1 pure tested kernel → Task 1 `activeDwellMs`. #2 one controller → Task 2 Step 1 refactor. #3 raw-JSON-only blinded save → Task 2 Step 4. #4 `durationMs` = `getTotalWeight()` separate accumulator → Task 1. #5 consent-declined behavior → Task 3 Step 3. ✅
- **Type consistency:** `deposit(x,y,w,h,weight)`, `getTotalWeight`, `activeDwellMs` signatures match across Task 1 + Task 2 usage; `BlindedResearch` File-taking helpers match the tests; `startBlinded/stopBlinded/isBlinded` consumed by Task 3's hook are produced by Task 2.
- **No-regression:** the legacy `deposit(x,y,w,h)` and the visible overlay/window paths are unchanged; blinded is purely additive; the `AtlasExtension` refactor is behavior-preserving (same menu, now a retained instance).
- **Placeholder scan:** none — full code for Task 1's pure kernel + tests + `BlindedResearch` tests; Tasks 2/3 give exact state/handlers/strings + named pattern references (repo convention for FX).
