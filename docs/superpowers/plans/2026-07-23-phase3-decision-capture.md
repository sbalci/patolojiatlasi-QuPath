# Phase 3 — Per-case decision capture + navigation↔accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture each reader's per-slide diagnostic decision (free-text diagnosis + optional 1–5 confidence + decision-latency ms) into the anonymized blinded fragment, and add an offline analysis that joins hand-graded decisions to the navigation metrics and reports navigation↔accuracy correlations.

**Architecture:** Recorder side (Java/JavaFX, `qupath-extension-atlas`): a `Decision` held per slide-session, entered via a menu action or a deferred auto-prompt when leaving a slide, written additively into the existing schema/5 blinded fragment. Analysis side (Python + R, numeric parity): a `decisions.csv` (raw fields + optional reference answer + a blank `correct` column for offline hand-grading), plus a `--graded` second pass that computes `nav_accuracy.csv` + a gated summary section. Source of truth: `docs/superpowers/specs/2026-07-22-navigation-research-upgrade-design.md` (the "Phase 3 — refined design" section).

**Tech Stack:** Java 21, JavaFX (hand-rolled `Stage` dialogs — no `Dialog`/fxtras), Gson; Python 3 (numpy/scipy, stdlib csv/json), R (jsonlite, base stats). Gradle build. No Java unit-test harness in this repo — Java tasks gate on `gradlew` compile + review; analysis tasks gate on the existing `selftest.py`/`selftest.R` harness (TDD).

## Global Constraints

Copied verbatim from the spec; every task's requirements include these:

- **Data-only, anonymized.** Blinded fragments are JSON only (never a PNG); no username; `slideKey` sha256 for non-http; `date` date-only; `decisionMs` is **relative to `blindedSlideStartMs`** (decision latency), never absolute wall-clock. The `decision` object carries only `diagnosis`/`confidence`/`decisionMs`.
- **Schema stays `atlas-focus-contribution/5`.** `decision` is a purely additive fragment-level field. Do NOT bump to /6 (would make older analysis silently drop new fragments). Analysis adds no `SCHEMAS` entry; a `get_decision` accessor defaults gracefully for /1–/5 fragments lacking the field.
- **Best-effort / no-throw** on every recorder path (never throw into the sampling `Timeline`, a checkpoint, or a shutdown write). One retained `FocusHeatmap` instance.
- **`showAndWait()` is illegal inside the `Timeline` tick.** The leave-prompt MUST be deferred via `Platform.runLater`; fragment-building for the deferred save reads an explicit `BlindedSnapshot`, not instance fields.
- **Hand-grading only.** The analysis NEVER string-matches a diagnosis to a key. `--key` is display-only (reference answer beside the reader's); `--graded` supplies hand-grades; join on the stable `(slideKey, sessionId)`, never the display label.
- **Python↔R numeric parity** (manual verification on a shared fixture; no mechanical cross-toolkit assert exists). Parity-safe stats only: point-biserial = plain Pearson; no p-values/CIs/Wilcoxon. Guards: correlation blank unless `n ≥ 5` and both variables have `var > 0`.
- **Blank-not-crash** analysis discipline: undefined cells are blank (`_sanitize_nan` / `write.csv(na="")`), never `"nan"`, never a raised exception that aborts the run.
- **Turkish forms** for user-facing strings; `String.format` is not used in analysis, but any Java numeric formatting pins `Locale.US` (existing convention). Menu labels/dialog text in Turkish.
- **Live QuPath smoke test of both capture paths required before merge** (runtime-only failure modes).

---

## File Structure

**Recorder (Java):**
- `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java` — MODIFY: `Decision` record, `BlindedSnapshot` record, `currentDecision`/`pendingDecisionSave`/`decisionPromptedSlides`/`decisionPromptOnLeave` fields, `currentSnapshot()`, parameterized `buildBlindedJson`/`writeBlindedFragmentSync`, `recordDecision`/`getCurrentDecision`/`promptDecisionInteractive`, `shouldPromptDecisionOnLeave`/`deferredDecisionPromptAndSave`, `tick()` guard, `switchTo` leave branch, menu item, resets. Schema/5 javadoc note.
- `src/main/java/com/patolojiatlasi/qupath/focus/DecisionDialog.java` — CREATE: hand-rolled `Stage` form; `static DecisionInput show(QuPathGUI, String preDiagnosis, Integer preConfidence)`.
- `src/main/java/com/patolojiatlasi/qupath/research/BlindedResearch.java` — MODIFY: `static boolean decisionPromptOnLeave(File projectDir)` sidecar reader (default true).
- `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` — MODIFY: pass `decisionPromptOnLeave` into `FocusHeatmap` when starting blinded recording.

**Analysis (Python):**
- `analysis/python/blinded_focus/io.py` — MODIFY: `get_decision`, `load_answer_key`, `load_graded`.
- `analysis/python/blinded_focus/analyze.py` — MODIFY: decision row fields, `decisions.csv` writer, `--key`/`--graded` params, `nav_accuracy.csv` + correlation summary.
- `analysis/python/selftest.py` — MODIFY: synthetic decisions + graded CSV + within-toolkit asserts.

**Analysis (R):**
- `analysis/R/blinded_focus.R` — MODIFY: mirror of io+analyze changes at corresponding locations.
- `analysis/R/run_analysis.R` — MODIFY: `--key`/`--graded` CLI branches.
- `analysis/R/selftest.R` — MODIFY: mirror of selftest.py additions.

**Docs:**
- `SHARING.md`, `SHARING.tr.md`, `analysis/python/README.md`, `analysis/R/README.md` — MODIFY.

---

## Task 1: Recorder core — decision state + snapshot-parameterized save path

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java`

**Interfaces:**
- Produces (used by Tasks 2/3): `void recordDecision(String diagnosis, Integer confidence)`; `String getCurrentDecisionDiagnosis()`; `Integer getCurrentDecisionConfidence()`; `boolean isBlindedRecording()`; private `record Decision(String diagnosis, Integer confidence, long decisionMs)`; private `record BlindedSnapshot(...)`; `void setDecisionPromptOnLeave(boolean)`.
- Produces (fragment contract, used by Tasks 4/5): a `"decision"` JSON object `{ "diagnosis": <string>, "confidence": <int|null>, "decisionMs": <int> }`, omitted when no decision.

**Context:** This task adds decision *state* + refactors the save path so a later-arriving (deferred) decision can be written into the correct slide's fragment. It adds NO UI and NO prompt yet (Tasks 2/3). Read the existing `buildBlindedJson` (~964-991), `saveBlindedSync` (~916-930), `checkpointBlinded` (~534-543), `flushOnShutdown` (~573-590), `switchTo` (~642-687), `startBlinded` (~354-404), `stopBlinded` (~406-455), and the per-slide fields (~139-164, ~244-251) first.

- [ ] **Step 1: Add the `Decision` and `BlindedSnapshot` nested types.** Near the other private nested types in `FocusHeatmap.java`, add:

```java
/** One reader's per-slide diagnostic decision. decisionMs is relative to blindedSlideStartMs
 *  (decision latency), never absolute wall-clock — anonymization-safe like path timestamps. */
private record Decision(String diagnosis, Integer confidence, long decisionMs) {
    /** Fragment JSON shape: {diagnosis, confidence(nullable), decisionMs}. */
    Map<String, Object> toMap() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("diagnosis", diagnosis);
        d.put("confidence", confidence);   // Gson writes JSON null when absent; analysis treats null/absent as blank
        d.put("decisionMs", decisionMs);
        return d;
    }
}

/** Immutable snapshot of everything buildBlindedJson needs for one slide's fragment, captured
 *  eagerly while still on that slide — so a deferred (post-switch) save builds the correct slide's
 *  fragment without reading instance fields that have since moved to the next slide. */
private record BlindedSnapshot(String uri, FocusMap map, java.util.List<int[]> path,
        boolean pathTruncated, Double baseMagnification, JsonElement annotations, long slideStartMs) { }
```

- [ ] **Step 2: Add the decision instance fields.** Alongside the blinded per-slide fields (~139-164):

```java
/** The current slide-session's decision, or null if none entered yet. A single volatile ref ⇒
 *  atomic read from the off-FX shutdown hook (no torn read). Reset with the map at the two reset points. */
private volatile Decision currentDecision;
/** Guards the deferred leave-prompt: while true, tick() early-returns so no sampling/switch races
 *  the pending prompt+save of the slide just left. */
private boolean pendingDecisionSave;
/** Slides (by uri) whose leave-prompt the reader declined — don't nag again on revisit-and-leave. */
private final java.util.Set<String> decisionPromptedSlides = new java.util.HashSet<>();
/** When true (default), leaving a slide with no decision auto-prompts. Set from the sidecar. */
private boolean decisionPromptOnLeave = true;
```

Add a constant near the other blinded constants (e.g. near `CHECKPOINT_EVERY_TICKS`):

```java
/** Minimum accumulated dwell (ms) on a slide before its leave triggers a decision auto-prompt —
 *  a short-glance gate so rapidly flipping past slides doesn't nag (reactivity control). */
private static final long MIN_DWELL_FOR_PROMPT_MS = 3000;
```

- [ ] **Step 3: Reset `currentDecision` at the two per-slide reset points.** In `startBlinded()` (right where `blindedPath.clear()` etc. run, ~380-383) and in `switchTo()`'s `if (blindedRecording)` branch (right where `blindedPath.clear()` etc. run, ~672-674), add:

```java
currentDecision = null;
```

- [ ] **Step 4: Add `currentSnapshot()`.** A private method that eagerly captures the current slide's state:

```java
/** Build a BlindedSnapshot from the current instance state, eagerly (copies the path, computes
 *  baseMag, serializes annotations now) so it stays valid after switchTo moves to the next slide.
 *  Reads currentImageData for baseMag/annotations — same off-FX torn-read tradeoff as flushOnShutdown. */
private BlindedSnapshot currentSnapshot() {
    Double baseMag = null;
    try {
        double x = currentImageData.getServer().getMetadata().getMagnification();
        if (!Double.isNaN(x) && x > 0)
            baseMag = x;
    } catch (Exception ignored) { }
    return new BlindedSnapshot(currentUri, currentMap, new java.util.ArrayList<>(blindedPath),
            blindedPathCapped, baseMag, buildAnnotationsFeatureCollection(), blindedSlideStartMs);
}
```

- [ ] **Step 5: Reparameterize `buildBlindedJson`.** Replace `private String buildBlindedJson(String uri, FocusMap map)` with a version reading only a snapshot + decision. Keep the existing schema/2–/5 javadoc block; append a schema/5 note about the optional `decision` field (see Step 8). New body:

```java
private String buildBlindedJson(BlindedSnapshot snap, Decision decision) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schema", CONTRIBUTION_SCHEMA_BLINDED);   // stays atlas-focus-contribution/5
    m.put("slideKey", anonymizeSlideKey(slideKey(snap.uri())));
    m.put("sessionId", sessionId);
    m.put("imageWidth", snap.map().getImageWidth());
    m.put("imageHeight", snap.map().getImageHeight());
    m.put("gridWidth", snap.map().getGridWidth());
    m.put("gridHeight", snap.map().getGridHeight());
    m.put("sampleCount", snap.map().getSampleCount());
    m.put("weightUnit", "ms");
    m.put("durationMs", snap.map().getTotalWeight());
    m.put("date", java.time.LocalDate.now().toString());
    m.put("grid", snap.map().getGrid().clone());
    m.put("path", new java.util.ArrayList<>(snap.path()));
    m.put("pathTruncated", snap.pathTruncated());
    m.put("baseMagnification", snap.baseMagnification());
    m.put("annotations", snap.annotations());
    if (decision != null)
        m.put("decision", decision.toMap());   // schema/5 additive; omitted when no decision entered
    return new GsonBuilder().create().toJson(m);
}
```

- [ ] **Step 6: Add `writeBlindedFragmentSync` and reroute the synchronous save callers.** Replace `saveBlindedSync(String uri, FocusMap map)`'s body-producing part with a snapshot-taking wrapper. Concretely:

Add:
```java
/** Persist a finished blinded fragment (JSON only, never a PNG) synchronously from an explicit
 *  snapshot + decision. See saveBlindedSync's original javadoc for why synchronous. Best-effort. */
private void writeBlindedFragmentSync(BlindedSnapshot snap, Decision decision) {
    try {
        final String json = buildBlindedJson(snap, decision);
        final String base = "focus-blinded__" + safe(anonymizeSlideKey(slideKey(snap.uri()))) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
        File dir = blindedDir;
        if (!dir.exists())
            dir.mkdirs();
        File file = new File(dir, base + ".json");
        java.nio.file.Files.writeString(file.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
        logger.info("Saved focus contribution to {}", file);
    } catch (Exception e) {
        logger.error("Failed to save blinded focus map: {}", e.getMessage(), e);
    }
}
```
Remove the old `saveBlindedSync(String uri, FocusMap map)`. Update its two call sites:
- `switchTo()` (~648-655): the synchronous branch becomes `writeBlindedFragmentSync(currentSnapshot(), currentDecision); deleteCheckpoint();` (the leave-prompt branch is added in Task 3 — for THIS task, keep it a plain synchronous save).
- `stopBlinded()` (~442-445 area, the `saveBlindedSync(currentUri, currentMap)` call): becomes `writeBlindedFragmentSync(currentSnapshot(), currentDecision);`.

Update `checkpointBlinded()` (~538): `final String json = buildBlindedJson(currentSnapshot(), currentDecision);`
Update `flushOnShutdown()` (~577): `String json = buildBlindedJson(currentSnapshot(), currentDecision);`

- [ ] **Step 7: Add the public decision API used by Tasks 2/3.**

```java
/** Record (or overwrite) the current slide's decision; stamps decisionMs relative to the slide's
 *  blinded-recording start. FX thread only (menu / deferred prompt). No-op if not blinded / no slide. */
void recordDecision(String diagnosis, Integer confidence) {
    if (!blindedRecording || currentMap == null)
        return;
    long ms = System.currentTimeMillis() - blindedSlideStartMs;
    currentDecision = new Decision(diagnosis, confidence, ms);
}

String getCurrentDecisionDiagnosis() { Decision d = currentDecision; return d == null ? null : d.diagnosis(); }
Integer getCurrentDecisionConfidence() { Decision d = currentDecision; return d == null ? null : d.confidence(); }
boolean isBlindedRecording() { return blindedRecording; }
void setDecisionPromptOnLeave(boolean v) { this.decisionPromptOnLeave = v; }
```

- [ ] **Step 8: Update the schema/5 javadoc note.** In `buildBlindedJson`'s javadoc block (~932-963), after the schema/5 paragraph, add one sentence:

> Schema/5 fragments MAY additionally carry an optional top-level `decision` object (`{diagnosis, confidence, decisionMs}`, decisionMs relative to slide-record start) when the reader recorded a per-slide diagnosis (added 2026-07). Purely additive; the schema number stays 5 so an older analysis reader still accepts the fragment (it ignores the unknown key). Omitted entirely when no decision was entered.

- [ ] **Step 9: Compile.**

Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon compileJava`
Expected: `BUILD SUCCESSFUL`. (No Java unit tests exist; correctness of the refactor is verified by the task reviewer — assert every prior `buildBlindedJson`/`saveBlindedSync` caller now passes a snapshot, that `currentDecision` is reset at exactly the two reset points, and that no behavior changed when no decision is present, i.e. the `decision` key is simply omitted.)

- [ ] **Step 10: Commit.**

```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java
git commit -m "feat(focus): decision state + snapshot-parameterized blinded save path"
```

---

## Task 2: `DecisionDialog` — the diagnosis/confidence entry form

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/focus/DecisionDialog.java`

**Interfaces:**
- Produces (used by Task 3): `public static DecisionInput show(QuPathGUI qupath, String preDiagnosis, Integer preConfidence)` returning the entered `DecisionInput(String diagnosis, Integer confidence)` or `null` if cancelled.

**Context:** Copy the repo's hand-rolled-`Stage` house style — model on `QuizAuthorWindow.QuestionDialog` (`quiz/QuizAuthorWindow.java:305-361`, `417-436`) and `CitationDialog`. NO `javafx.scene.control.Dialog`/`ButtonType`/`qupath.fx` (none are used in this repo). `showAndWait()` inside `show(...)` requires the FX thread — callers guarantee that (Task 3). This class only builds/shows the form and returns the values; it does not touch `FocusHeatmap` state.

- [ ] **Step 1: Write the class.**

```java
package com.patolojiatlasi.qupath.focus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

/**
 * Modal form for entering a per-slide diagnostic decision (free-text diagnosis + optional 1–5
 * confidence), following this repo's hand-rolled-Stage dialog convention (cf. QuizAuthorWindow's
 * QuestionDialog / CitationDialog) — no javafx Dialog/ButtonType, no fxtras. Diagnosis is required;
 * confidence is optional (no radio selected = "not given"). {@link #show} returns the entered
 * {@link DecisionInput} or {@code null} if cancelled. Must be called on the FX thread.
 */
public final class DecisionDialog {

    /** What the reader entered: free-text diagnosis + optional 1–5 confidence (null = not given). */
    public record DecisionInput(String diagnosis, Integer confidence) { }

    private DecisionDialog() { }

    public static DecisionInput show(QuPathGUI qupath, String preDiagnosis, Integer preConfidence) {
        Stage owner = qupath == null ? null : qupath.getStage();
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null)
            stage.initOwner(owner);
        stage.setTitle("Bu slayt için tanı/karar");

        TextArea diagnosisArea = new TextArea(preDiagnosis == null ? "" : preDiagnosis);
        diagnosisArea.setWrapText(true);
        diagnosisArea.setPromptText("Tanınız / kararınız (serbest metin)");
        diagnosisArea.setPrefRowCount(4);

        ToggleGroup confGroup = new ToggleGroup();
        HBox confBox = new HBox(6);
        confBox.setAlignment(Pos.CENTER_LEFT);
        RadioButton[] confButtons = new RadioButton[5];
        for (int i = 0; i < 5; i++) {
            RadioButton rb = new RadioButton(Integer.toString(i + 1));
            rb.setToggleGroup(confGroup);
            rb.setUserData(i + 1);
            confButtons[i] = rb;
            confBox.getChildren().add(rb);
        }
        if (preConfidence != null && preConfidence >= 1 && preConfidence <= 5)
            confButtons[preConfidence - 1].setSelected(true);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setWrapText(true);

        final DecisionInput[] result = new DecisionInput[1];
        Button okBtn = new Button("Tamam");
        okBtn.setOnAction(e -> {
            String dx = diagnosisArea.getText() == null ? "" : diagnosisArea.getText().trim();
            if (dx.isEmpty()) {
                errorLabel.setText("Tanı alanı boş olamaz.");
                return;
            }
            Integer conf = confGroup.getSelectedToggle() == null ? null
                    : (Integer) confGroup.getSelectedToggle().getUserData();
            result[0] = new DecisionInput(dx, conf);
            stage.close();
        });
        Button cancelBtn = new Button("İptal");
        cancelBtn.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, errorLabel, spacer, cancelBtn, okBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8,
                new Label("Tanı / karar:"), diagnosisArea,
                new Label("Güven (1–5, isteğe bağlı):"), confBox,
                actions);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 420, 320));
        stage.showAndWait();
        return result[0];
    }
}
```

- [ ] **Step 2: Compile.**

Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon compileJava`
Expected: `BUILD SUCCESSFUL`. (Reviewer verifies: diagnosis-required validation keeps the dialog open on empty; confidence maps unselected→null and radio i→i+1; no reference to `FocusHeatmap` state; `QuPathGUI` import resolves.)

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/DecisionDialog.java
git commit -m "feat(focus): DecisionDialog — per-slide diagnosis/confidence entry form"
```

---

## Task 3: Menu action + deferred leave-prompt wiring

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/research/BlindedResearch.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java`

**Interfaces:**
- Consumes: Task 1's `recordDecision`/`getCurrentDecision*`/`isBlindedRecording`/`setDecisionPromptOnLeave`/`currentSnapshot`/`writeBlindedFragmentSync`/`Decision`/`BlindedSnapshot`/`pendingDecisionSave`/`decisionPromptedSlides`/`decisionPromptOnLeave`/`MIN_DWELL_FOR_PROMPT_MS`; Task 2's `DecisionDialog.show`.
- Consumes: `BlindedResearch.projectDir(...)`-style helper already used to read the sidecar (check the actual method used by `isBlindedProject`/`hasConsented`).

**Context:** Wires the two capture paths. The menu path runs on the FX thread between pulses (`showAndWait` legal). The leave path fires inside the `Timeline` tick → MUST defer via `Platform.runLater`. Read `FocusHeatmap.buildMenu()` (~257-291), the `setDisable` pattern in `startBlinded`/`stopBlinded` (~362-373, ~442-451), `tick()` (~459-525), `switchTo()` (~642-687), and `AtlasExtension.onProjectChanged` + the `Araştırma` menu (~151-158) first.

- [ ] **Step 1: Add the `tick()` guard.** At the very top of `tick()` (~459, before it reads the viewer), add:

```java
if (pendingDecisionSave)
    return;   // a deferred leave-prompt+save is in flight; don't sample/switch until it completes
```

- [ ] **Step 2: Add `promptDecisionInteractive` (menu path) + `shouldPromptDecisionOnLeave` + `deferredDecisionPromptAndSave` (leave path).**

```java
/** Menu path: show the decision dialog (pre-filled for edit) for the CURRENT slide and record the
 *  result. FX thread, between Timeline pulses ⇒ showAndWait is legal here. */
void promptDecisionInteractive() {
    if (!blindedRecording || currentMap == null) {
        qupath.lib.gui.dialogs.Dialogs.showInfoNotification("Karar kaydı",
                "Karar kaydı yalnızca gezinme kaydı açık ve bir slayt açıkken kullanılabilir.");
        return;
    }
    DecisionDialog.DecisionInput in = DecisionDialog.show(qupath,
            getCurrentDecisionDiagnosis(), getCurrentDecisionConfidence());
    if (in != null)
        recordDecision(in.diagnosis(), in.confidence());
}

/** Leave path gate: prompt on leaving the current slide only if enabled, no decision yet, not already
 *  declined this session, the reader actually dwelled (short-glance gate), and no prompt already pending. */
private boolean shouldPromptDecisionOnLeave() {
    return decisionPromptOnLeave
            && currentDecision == null
            && !pendingDecisionSave
            && currentUri != null
            && !decisionPromptedSlides.contains(currentUri)
            && currentMap != null
            && currentMap.getTotalWeight() >= MIN_DWELL_FOR_PROMPT_MS;
}

/** Deferred continuation of a leave-prompt: runs via Platform.runLater (between pulses ⇒ showAndWait
 *  legal). Prompts for the slide just left (state captured in `snap`), saves its fragment with the
 *  decision, then releases the pendingDecisionSave gate. Best-effort. */
private void deferredDecisionPromptAndSave(BlindedSnapshot snap, String leavingUri) {
    try {
        DecisionDialog.DecisionInput in = DecisionDialog.show(qupath, null, null);
        Decision decision = null;
        if (in != null) {
            long ms = System.currentTimeMillis() - snap.slideStartMs();
            decision = new Decision(in.diagnosis(), in.confidence(), ms);
        } else if (leavingUri != null) {
            decisionPromptedSlides.add(leavingUri);   // declined — don't nag on revisit
        }
        writeBlindedFragmentSync(snap, decision);
        deleteCheckpoint();
    } catch (Exception e) {
        logger.debug("Deferred decision prompt/save failed: {}", e.getMessage());
    } finally {
        pendingDecisionSave = false;
    }
}
```

Note: confirm the notification helper — the map says the repo uses `javafx.scene.control.Alert` directly (`QuizAuthorWindow.java:180-185`) and does NOT use `qupath.fx`. If `qupath.lib.gui.dialogs.Dialogs` is not already imported/used elsewhere in `FocusHeatmap.java`, use a plain `new Alert(Alert.AlertType.INFORMATION, "...").showAndWait()` instead (also FX-thread-legal here) rather than adding a new dialog dependency. The implementer must pick whichever matches what `FocusHeatmap.java` already imports.

- [ ] **Step 3: Wire the leave-prompt into `switchTo`.** Replace the Task-1 synchronous blinded-save branch in `switchTo()` with the prompt-or-save fork:

```java
if (blindedRecording && currentMap != null && !currentMap.isEmpty()) {
    if (shouldPromptDecisionOnLeave()) {
        BlindedSnapshot snap = currentSnapshot();   // capture the slide being left, before any reset
        String leavingUri = currentUri;
        pendingDecisionSave = true;                  // tick() early-returns until the deferred save runs
        javafx.application.Platform.runLater(() -> deferredDecisionPromptAndSave(snap, leavingUri));
    } else {
        writeBlindedFragmentSync(currentSnapshot(), currentDecision);
        deleteCheckpoint();
    }
}
```

- [ ] **Step 4: Add the menu item.** In `FocusHeatmap.buildMenu()` (~287-290), create and add:

```java
MenuItem decisionItem = new MenuItem("Bu slayt için tanı/karar gir…");
decisionItem.setOnAction(e -> promptDecisionInteractive());
```
Add it to the submenu's `getItems().addAll(...)` (after the blinded toggle). Follow the existing enable/disable convention: the item is only meaningful while blinded. Add `decisionItem.setDisable(!blindedRecording);` at creation, and (mirroring the existing `if (X != null) X.setDisable(...)` pattern for `clearItem`/`saveItem`) toggle it in `startBlinded()` (enable: `decisionItem.setDisable(false)`) and `stopBlinded()` (disable: `decisionItem.setDisable(true)`). Store `decisionItem` as an instance field like the other menu items it sits beside, so start/stop can reach it.

- [ ] **Step 5: Add the sidecar reader in `BlindedResearch`.** Following the existing sidecar-reading methods (`isBlindedProject`, `hasConsented` — read the file/JSON the same way they do):

```java
/** Read the optional "decisionPromptOnLeave" flag from the project sidecar (default true). Controls
 *  whether leaving a slide with no decision auto-prompts. Best-effort: true on any read/parse failure. */
public static boolean decisionPromptOnLeave(File projectDir) {
    try {
        // mirror the exact read used by hasConsented(): load atlas-research.json, get the boolean,
        // default true when the key is absent.
        // ... (implementer: copy hasConsented's file-load + JsonObject access, key "decisionPromptOnLeave",
        //      return obj.has("decisionPromptOnLeave") ? obj.get("decisionPromptOnLeave").getAsBoolean() : true)
    } catch (Exception e) {
        return true;
    }
    return true;
}
```
The implementer must read `BlindedResearch.java` and mirror `hasConsented`'s exact JSON-loading mechanics; the only logic is "default true when absent or on error."

- [ ] **Step 6: Wire it in `AtlasExtension`.** Where `onProjectChanged` starts blinded recording (after it confirms the project is a blinded research project and before/around the `focusHeatmap.startBlinded(...)` call, ~the same place it reads `hasConsented`), add:

```java
focusHeatmap.setDecisionPromptOnLeave(BlindedResearch.decisionPromptOnLeave(projectDir));
```
Use the same `projectDir` value already computed there for the other `BlindedResearch` calls.

- [ ] **Step 7: Compile.**

Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Reviewer focus (spec-critical, no test harness).** The task reviewer must specifically verify: (a) the leave-prompt NEVER calls `showAndWait` synchronously inside `switchTo`/`tick` — only via `Platform.runLater`; (b) `pendingDecisionSave` is set before the `runLater` and cleared in the `finally`, and `tick()` early-returns on it; (c) `shouldPromptDecisionOnLeave` guards prevent nagging (min-dwell, prompted-set, already-decided, already-pending); (d) the deferred save writes the slide-that-was-left's fragment from the captured `snap`, not the new slide's; (e) the menu item is disabled when not blinded and the menu path records into the current slide; (f) mouse/no global capture and data-only invariants are untouched; (g) `decisionPromptOnLeave` defaults true and is read via the same sidecar mechanics as `hasConsented`.

- [ ] **Step 9: Commit.**

```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java \
        src/main/java/com/patolojiatlasi/qupath/research/BlindedResearch.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java
git commit -m "feat(focus): decision menu action + deferred leave-prompt (Platform.runLater)"
```

---

## Task 4: Python analysis — decisions.csv + navigation↔accuracy correlation

**Files:**
- Modify: `analysis/python/blinded_focus/io.py`
- Modify: `analysis/python/blinded_focus/analyze.py`
- Test: `analysis/python/selftest.py`

**Interfaces:**
- Produces (used by Task 5 as the parity target): `decisions.csv` columns `["slide","sessionId","session","diagnosis","confidence","confidenceScaled","decisionMs","decisionLatencyMs","correctDx","correct"]`; `nav_accuracy.csv` columns `["metric","n","pointBiserialR","meanCorrect","meanIncorrect","medianCorrect","medianIncorrect","meanDiff"]`; a `## Navigation ↔ diagnostic accuracy` summary section; `--key`/`--graded` CLI.

**Context:** Read the analysis map (`p3-map-analysis.md`) sections 1–5 and the existing `analyze.py` loop (`372-498`), `_write_csv` (`308-324`), the `metrics.csv` write (`775-786`), `_write_summary` (`791-839`), and argparse (`846-883`). Key override vs. the map: **do NOT auto-compute `correct` from diagnosis string-matching** — `correct` comes only from `--graded`. TDD via `selftest.py`.

- [ ] **Step 1: Write failing selftest assertions for `decisions.csv`.** In `selftest.py`'s fixture builder (`build_fragments`, ~236-265), give two of the four sessions a `decision` object; e.g. session `s1` (`{"diagnosis": "tumor", "confidence": 4}`) and `s2` (`{"diagnosis": "benign", "confidence": 2}`), each with a `decisionMs` (e.g. 5000). Leave `s3`/`s4` without a decision. Add a new selftest function `check_decisions(out_dir)` asserting: `decisions.csv` exists; has one row per (slide, session); the `s1` row has `diagnosis=="tumor"`, `confidence=="4"`, `confidenceScaled=="0.75"`, non-empty `decisionMs`/`decisionLatencyMs`; the `s3` row has blank `diagnosis`/`confidence`/`correct`; every row's `correct` is blank when no `--graded` was passed. Wire `check_decisions` into `main()`.

Run: `PYTHONIOENCODING=utf-8 python analysis/python/selftest.py`
Expected: FAIL (no `decisions.csv`, no decision fields).

- [ ] **Step 2: Add `get_decision`, `load_answer_key`, `load_graded` to `io.py`.**

```python
def _empty_decision():
    return {}


def get_decision(fragment):
    """Return a fragment's ``decision`` object (dict with at least ``diagnosis``) or an empty dict
    when absent/malformed — so /1–/5 fragments without the field degrade to blank, never crash."""
    dec = fragment.get("decision")
    if isinstance(dec, dict) and isinstance(dec.get("diagnosis"), str):
        return dec
    return _empty_decision()


def load_answer_key(csv_path):
    """Load a ``slideKey,correctDx`` CSV (optional header) into ``{slideKey: correctDx}`` — the
    DISPLAY-ONLY reference answer (never used to auto-grade). ``{}`` for a falsy path."""
    if not csv_path:
        return {}
    with open(csv_path, newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))
    start = 1 if rows and rows[0][0].strip().lower() in ("slidekey", "slide_key", "slide") else 0
    key = {}
    for row in rows[start:]:
        if len(row) >= 2 and row[0].strip():
            key[row[0].strip()] = row[1].strip()
    return key


def load_graded(csv_path):
    """Load a ``slideKey,sessionId,correct`` CSV (optional header) into ``{(slideKey, sessionId):
    correct}`` where correct is 1/0 (parsed from 1/0/true/false/yes/no). ``{}`` for a falsy path.
    Join key is the stable sessionId, never the display label. Rows with an unparseable ``correct``
    are skipped."""
    if not csv_path:
        return {}
    with open(csv_path, newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))
    start = 1 if rows and rows[0][0].strip().lower() in ("slidekey", "slide_key", "slide") else 0
    graded = {}
    for row in rows[start:]:
        if len(row) >= 3 and row[0].strip() and row[1].strip():
            v = row[2].strip().lower()
            if v in ("1", "true", "yes", "correct"):
                graded[(row[0].strip(), row[1].strip())] = 1
            elif v in ("0", "false", "no", "incorrect"):
                graded[(row[0].strip(), row[1].strip())] = 0
    return graded
```

- [ ] **Step 3: Build `decisions.csv` in `analyze.py`.** Add `key_csv=None, graded_csv=None` to `analyze()`'s signature (parallel to `labels_csv`). After the existing loaders (`~359-360`) add `answer_key = bf_io.load_answer_key(key_csv)` and `graded = bf_io.load_graded(graded_csv)`. Initialize `decision_rows = []` before the outer loop. Inside the inner per-session loop (right after `row` is appended, ~498), compute:

```python
dec = bf_io.get_decision(f)
diagnosis = dec.get("diagnosis", "")
confidence = dec.get("confidence")
conf_scaled = "" if not isinstance(confidence, (int, float)) else (float(confidence) - 1.0) / 4.0
decision_ms = dec.get("decisionMs", "")
sid_stable = f.get("sessionId") or ""
correct_dx = answer_key.get(slide_key, "")
graded_val = graded.get((slide_key, sid_stable), "")
decision_rows.append({
    "slide": slide_key,
    "sessionId": sid_stable,
    "session": _session_label(f, labels),
    "diagnosis": diagnosis,
    "confidence": confidence if confidence is not None else "",
    "confidenceScaled": conf_scaled,
    "decisionMs": decision_ms,
    "decisionLatencyMs": decision_ms,   # == decisionMs (rel to slide start); ≈ durationMs for leave-prompt captures
    "correctDx": correct_dx,
    "correct": graded_val,              # blank unless --graded supplied; NEVER auto-derived from diagnosis
})
```

After the outer loop, next to the `metrics.csv` write (~775), write `decisions.csv` if any decision exists:

```python
if any(r["diagnosis"] != "" for r in decision_rows):
    _write_csv(os.path.join(out_dir, "decisions.csv"), decision_rows,
               ["slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
                "decisionMs", "decisionLatencyMs", "correctDx", "correct"])
else:
    print("warning: no decisions found in any fragment; decisions.csv not written", file=sys.stderr)
```

- [ ] **Step 4: Add `--key`/`--graded` to argparse + pass-through.** After `--labels` (~863) add both `ap.add_argument("--key", ...)` and `ap.add_argument("--graded", ...)`; pass `key_csv=args.key, graded_csv=args.graded` into the `analyze(...)` call.

- [ ] **Step 5: Run the decisions selftest.**

Run: `PYTHONIOENCODING=utf-8 python analysis/python/selftest.py`
Expected: PASS (decisions assertions now hold).

- [ ] **Step 6: Write failing selftest assertions for the correlation.** Extend `build_fragments` so a graded CSV can make the correlation computable: ensure ≥5 (slide, session) rows carry a decision + a nav metric, and construct the fixture so one nav metric (e.g. `coveragePct`) is deliberately separable by outcome. Add `check_nav_accuracy(out_dir)` asserting: with a synthetic `graded.csv` passed via `graded_csv`, `nav_accuracy.csv` exists with the documented columns; a metric with `n<5` or zero variance has a **blank** `pointBiserialR`; the deliberately-separable metric has a non-blank `meanDiff` of the expected sign; and the `## Navigation ↔ diagnostic accuracy` section appears in `summary.md`. Also assert that WITHOUT `--graded`, `nav_accuracy.csv` is NOT written and the summary section is absent.

Run: `PYTHONIOENCODING=utf-8 python analysis/python/selftest.py`
Expected: FAIL.

- [ ] **Step 7: Implement the correlation.** Add helpers to `analyze.py` (parity-safe: point-biserial = plain Pearson; guards `n≥5` + `var>0`; no p-values):

```python
def _pearson_guarded(xs, ys, min_n=5):
    """Plain Pearson r (point-biserial when one side is 0/1), or float('nan') if n<min_n or either
    side has zero variance. Parity-safe: numpy.corrcoef, statistic only, no p-value."""
    import numpy as np
    xs = np.asarray(xs, dtype=float); ys = np.asarray(ys, dtype=float)
    if len(xs) < min_n or np.var(xs) == 0.0 or np.var(ys) == 0.0:
        return float("nan")
    return float(np.corrcoef(xs, ys)[0, 1])


def _nav_accuracy_rows(metrics_rows, decision_rows):
    """Join decisions' `correct` (0/1) onto metrics by (slide, sessionId); per navigation column,
    compute guarded point-biserial r + group means/medians. Returns (rows, had_any_graded)."""
    import statistics
    NAV_COLS = ["avgZoom", "zoomVariance", "magnificationPercentage", "scanningRatePxPerMin",
                "drillingRatePerMin", "coveragePct", "dwellInAnnotationPct", "enrichmentRatio",
                "searchFocusRatio", "linearity", "pathVelocityPxPerSec", "entropy", "transitionEntropy"]
    correct_by = {(r["slide"], r["sessionId"]): r["correct"]
                  for r in decision_rows if r["correct"] in (0, 1)}
    metrics_by = {(r["slide"], r["session"]): r for r in metrics_rows}  # note: join below uses sessionId
    # metrics_rows key session by LABEL; decisions carry both. Re-key metrics by (slide, sessionId)
    # using decision_rows' session→sessionId map (same run, so 1:1).
    label_to_sid = {(r["slide"], r["session"]): r["sessionId"] for r in decision_rows}
    rows, had = [], bool(correct_by)
    for col in NAV_COLS:
        xs, ys = [], []
        for mr in metrics_rows:
            sid = label_to_sid.get((mr["slide"], mr["session"]))
            key = (mr["slide"], sid)
            if sid is None or key not in correct_by:
                continue
            v = mr.get(col, "")
            if v == "" or v is None:
                continue
            xs.append(float(v)); ys.append(correct_by[key])
        r = _pearson_guarded(ys, xs)   # r(correct, navmetric)
        corr_vals = [x for x, y in zip(xs, ys) if y == 1]
        incorr_vals = [x for x, y in zip(xs, ys) if y == 0]
        mean_c = statistics.mean(corr_vals) if corr_vals else float("nan")
        mean_i = statistics.mean(incorr_vals) if incorr_vals else float("nan")
        med_c = statistics.median(corr_vals) if corr_vals else float("nan")
        med_i = statistics.median(incorr_vals) if incorr_vals else float("nan")
        mean_diff = (mean_c - mean_i) if (len(corr_vals) >= 2 and len(incorr_vals) >= 2) else float("nan")
        rows.append({"metric": col, "n": len(xs), "pointBiserialR": r,
                     "meanCorrect": mean_c, "meanIncorrect": mean_i,
                     "medianCorrect": med_c, "medianIncorrect": med_i, "meanDiff": mean_diff})
    return rows, had
```

Write `nav_accuracy.csv` and extend the summary only when graded data exists. Next to the `decisions.csv` write:

```python
nav_rows, had_graded = _nav_accuracy_rows(metrics_rows, decision_rows)
if had_graded:
    _write_csv(os.path.join(out_dir, "nav_accuracy.csv"), nav_rows,
               ["metric", "n", "pointBiserialR", "meanCorrect", "meanIncorrect",
                "medianCorrect", "medianIncorrect", "meanDiff"])
```

Pass `decision_rows`, `nav_rows`, `had_graded` into `_write_summary` and add a gated section there:

```python
if had_graded:
    lines.append("## Navigation ↔ diagnostic accuracy")
    lines.append("")
    lines.append("> Pilot-scale caveats: viewport-only navigation has a null-result precedent; at "
                 "n=5–20 only r, its n, and group means/medians are defensible — no p-values/CIs. "
                 "Coincidence/accuracy numbers are cohort-composition-dependent.")
    lines.append("")
    # accuracy per session/slide
    graded_dec = [r for r in decision_rows if r["correct"] in (0, 1)]
    n_all = len(graded_dec)
    if n_all:
        acc = sum(r["correct"] for r in graded_dec) / n_all
        lines.append(f"- overall accuracy: {sum(r['correct'] for r in graded_dec)}/{n_all} = {_fmt(acc*100,1)}%"
                     + (" (n<5 → descriptive only)" if n_all < 5 else ""))
    for nr in nav_rows:
        lines.append(f"- {nr['metric']}: r={_fmt(nr['pointBiserialR'])} (n={nr['n']}), "
                     f"meanCorrect={_fmt(nr['meanCorrect'])}, meanIncorrect={_fmt(nr['meanIncorrect'])}, "
                     f"meanDiff={_fmt(nr['meanDiff'])}")
    lines.append("")
```

Also compute + print calibration (`calibrationGap`, `brierScore`) and `confidenceAccuracyR` over graded rows with non-blank `confidenceScaled` (soft-guarded / hard-guarded respectively), as summary lines. (Implementer: reuse `_pearson_guarded` for `confidenceAccuracyR`; `calibrationGap = mean(confScaled) − mean(correct)`, `brierScore = mean((confScaled − correct)²)`, both blank when n==0.)

- [ ] **Step 8: Run the full selftest.**

Run: `PYTHONIOENCODING=utf-8 python analysis/python/selftest.py`
Expected: PASS (all assertions incl. decisions + nav_accuracy).

- [ ] **Step 9: Commit.**

```bash
git add analysis/python/blinded_focus/io.py analysis/python/blinded_focus/analyze.py analysis/python/selftest.py
git commit -m "feat(analysis): Python — decisions.csv + --key/--graded + nav↔accuracy correlation"
```

---

## Task 5: R analysis — mirror of Task 4 with numeric parity

**Files:**
- Modify: `analysis/R/blinded_focus.R`
- Modify: `analysis/R/run_analysis.R`
- Test: `analysis/R/selftest.R`

**Interfaces:**
- Consumes Task 4's output contract (identical CSV columns + summary section text). Produces the numerically-identical R output.

**Context:** Line-for-line port of Task 4 at the structurally-corresponding R locations (analysis map §6.1 table). New functions go right after their Python analogs' R mirrors: `get_decision` after `get_annotations` (`blinded_focus.R:261-274`); `load_answer_key`/`load_graded` after `load_labels` (`blinded_focus.R:211-230`); decision-row fields in the `row <- list(...)` region and a `decision_rows` accumulator; `decisions.csv`/`nav_accuracy.csv` writes via `write_csv_tidy` next to the `metrics.csv` write (`blinded_focus.R:2125-2137`); the summary section in `write_summary` (`blinded_focus.R:2145-2194`); `--key`/`--graded` branches in `run_analysis.R`'s `.parse_args`. Honor the three documented intentional divergences (djb2 slug, `NA`→`""`, `TRUE/FALSE` vs `True/False`) — parity is compared on **typed** values, not byte-diff.

- [ ] **Step 1: Add failing R selftest assertions.** Mirror Task 4 Steps 1 & 6 in `selftest.R`'s `build_fragments` (`238-267`) and add `check_decisions`/`check_nav_accuracy` equivalents with the same synthetic decisions + graded CSV content and the same targeted asserts (`confidenceScaled == 0.75`; blank `correct` without graded; blank `pointBiserialR` at n<5/zero-var; expected `meanDiff` sign; summary section present only when graded).

Run: `Rscript analysis/R/selftest.R`
Expected: FAIL.

- [ ] **Step 2: Port the io accessors.** Add `get_decision`, `load_answer_key`, `load_graded` to `blinded_focus.R` mirroring the Python (Step 2 of Task 4): same empty-default-on-malformed behavior; `load_graded` keyed by the `(slideKey, sessionId)` pair (use a named-list/env keyed by `paste(slideKey, sessionId, sep="")` or a 2-col lookup); same 1/0/true/false parsing.

- [ ] **Step 3: Port the decisions rows + `decisions.csv`.** Add `key_csv=NULL, graded_csv=NULL` to `analyze <- function(...)`; load `answer_key`/`graded`; accumulate `decision_rows`; build each row with the SAME column order/values as Python (R `NA` where Python uses `""`); write `decisions.csv` via `write_csv_tidy` under the same gate.

- [ ] **Step 4: Port `--key`/`--graded` CLI.** Add `--key`/`--graded` branches to `run_analysis.R`'s `.parse_args`, the `NULL` initializers, the returned list, and the `analyze(...)` call.

- [ ] **Step 5: Port the correlation.** Add `.pearson_guarded` (`stats::cor(method="pearson")` with the same `n>=5` + `var>0` guard returning `NA`) and `.nav_accuracy_rows`; write `nav_accuracy.csv` and the gated summary section with identical text/format. Calibration + `confidenceAccuracyR` likewise.

- [ ] **Step 6: Run the R selftest.**

Run: `Rscript analysis/R/selftest.R`
Expected: `OK: all selftest assertions passed`.

- [ ] **Step 7: Manual Python↔R parity diff.** Write ONE shared fixture directory (identical fragment JSONs incl. decisions) + one shared `graded.csv`, run both CLIs into two out-dirs, and typed-compare `decisions.csv` + `nav_accuracy.csv` (parse numbers; `TRUE`↔`True`, `40`↔`40.0` normalized) to 1e-6. Record the diff result in the task report. (Reuse the approach documented in `analysis/R/README.md:223-232`.)

Run (example):
```bash
PYTHONIOENCODING=utf-8 python -m blinded_focus.analyze <fixture_dir> --out /tmp/py --key <key.csv> --graded <graded.csv>
Rscript analysis/R/run_analysis.R <fixture_dir> --out /tmp/r --key <key.csv> --graded <graded.csv>
```
Expected: every numeric cell matches to 1e-6 (typed compare).

- [ ] **Step 8: Commit.**

```bash
git add analysis/R/blinded_focus.R analysis/R/run_analysis.R analysis/R/selftest.R
git commit -m "feat(analysis): R — decisions.csv + --key/--graded + nav↔accuracy (parity with Python)"
```

---

## Task 6: Docs

**Files:**
- Modify: `SHARING.md`, `SHARING.tr.md`, `analysis/python/README.md`, `analysis/R/README.md`

**Context:** Document the new capture flow, the PHI caveat, the two-pass hand-grading workflow, and the CSV/CLI contract. Match the existing tone (participant-facing researcher guide + coordinator section).

- [ ] **Step 1: `SHARING.md` — researcher + coordinator.** In the researcher "read the slides" area, add one line: after finishing a slide you may be asked (or can use **Extensions ▸ Araştırma ▸ … ▸ Bu slayt için tanı/karar gir…**) to record a diagnosis + optional 1–5 confidence; **never type identifying text** (name/MRN/accession) into it (same PHI caveat as annotation notes). Note the last slide before quitting needs the menu action (no auto-prompt on stop). In the coordinator "Getting data back & analyzing it" section, add the **two-pass hand-grading workflow**: (1) run the analysis with `--key key.csv` (slideKey,correctDx — display only) to get `decisions.csv` with a blank `correct` column; (2) hand-fill `correct` (1/0) per row by comparing `diagnosis` vs `correctDx`; (3) re-run with `--graded decisions_graded.csv` (slide,sessionId,correct) to get `nav_accuracy.csv` + the correlation summary. State that `--key` never auto-grades and joins are on the stable `sessionId`.

- [ ] **Step 2: `SHARING.tr.md` — mirror Step 1 in Turkish.**

- [ ] **Step 3: `analysis/python/README.md` + `analysis/R/README.md`.** Document: `decisions.csv` columns (incl. the `decisionLatencyMs ≈ durationMs` caveat for leave-prompt captures); `nav_accuracy.csv` columns; `--key` (display-only) vs `--graded` (hand-grades, `(slideKey,sessionId)` join); the guard policy (point-biserial blank unless n≥5 & var>0; no p-values); the honest-limits note; and that decisions ride inside the same schema/5 fragment (no new file type, no allowlist change).

- [ ] **Step 4: Verify links/citations if the repo has a checker; otherwise re-read for accuracy.**

Run (if present): `pwsh ./tools/check-links.ps1` (this repo may not have it — skip if absent).
Expected: no broken links introduced.

- [ ] **Step 5: Commit.**

```bash
git add SHARING.md SHARING.tr.md analysis/python/README.md analysis/R/README.md
git commit -m "docs: Phase 3 decision capture — sharing guide + analysis README (EN/TR)"
```

---

## Self-Review notes (author)

- **Spec coverage:** recorder state (T1) · dialog (T2) · both capture paths + sidecar (T3) · Python decisions+correlation (T4) · R parity (T5) · docs (T6) — every spec bullet maps to a task.
- **Type consistency:** `DecisionInput(diagnosis, confidence)` (T2) ↔ consumed in T3; `Decision(diagnosis, confidence, decisionMs)` + `BlindedSnapshot(...)` defined T1 ↔ used T1/T3; `decisions.csv`/`nav_accuracy.csv` columns identical across T4/T5; `--key`/`--graded` identical across Python/R.
- **Known limitation (documented, not a gap):** the final slide before stop/project-close has no auto-prompt (menu only) — intentional (modal-during-close is unsafe).
- **Merge gate:** after the final adversarial review + fixes, the USER must live-smoke-test both capture paths in QuPath before merge (runtime-only failure modes). Do not merge without it.
