# Guided Teaching Tour Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing Quiz into a unified quiz/tour — ordered stops that narrate *or* pose a question, each with an author-captured viewport + optional highlight, reusing the same slide across stops, playable as self-check ("Çöz") or a guided tour ("Rehberli tur"), with a quiz-level forward-only (`allowBack`) option; atlas + local slides with graceful-missing.

**Architecture:** Additive extension of `com.patolojiatlasi.qupath.quiz.*` — a "tour" is an `AtlasQuiz` containing `NARRATION` stops + captured viewports + highlights. One author (extended `QuizAuthorWindow`), one player (extended `QuizRunnerWindow`), backward-compatible Gson file format. **Every change is guarded so an existing quiz (no narration/highlight/viewport, `allowBack=true`) behaves byte-for-byte as today.**

**Tech Stack:** Java 21, JavaFX, Gson, JUnit (existing test suite under `src/test/java/com/patolojiatlasi/qupath/quiz/`). No Python/R.

## Global Constraints
- **Additive / no regression:** existing quiz files still open; existing self-check play is unchanged for quizzes lacking the new fields. New enum value + fields are optional.
- **Backward-compatible file format:** bump `AtlasQuizIO.FORMAT_VERSION` to 2 but **accept `formatVersion ∈ {1,2}`** on read (v1 files must still load).
- **Verified QuPath 0.6 APIs (javap-confirmed):** `QuPathViewer.getDownsampleFactor()/getCenterPixelX()/getCenterPixelY()/setDownsampleFactor(double,double,double)`; `qupath.lib.images.servers.ImageServers.buildServer(java.net.URI, String...) throws IOException`; `QuizType` = `MCQ, FREETEXT, ANNOTATION, NAVIGATION`.
- **Slide open discipline:** off-FX-thread build + `Platform.runLater` + `stillWanted` cancel (reuse `QuizSlide.openAsync`'s exact pattern). `setImageData` bypasses the unsaved-work prompt → reuse the runner's existing changed-work guard.
- **Graceful-missing:** a local slide that can't be built (shared tour, recipient lacks the file) routes to `onError` → the player shows the narration + a "Slayt açılamadı" note and lets the learner continue.
- **House style:** hand-rolled `Stage`+VBox dialogs (no `javafx.scene.control.Dialog`); Turkish UI strings; `String.format` unused here.
- **GUI is user-tested:** Java has no GUI test harness — GUI tasks gate on `gradlew build` + review; the unit-testable pieces (IO, URI classifier) are TDD. The user smoke-tests the windows.

---

## File Structure
- `src/main/java/.../quiz/QuizType.java` — MODIFY: add `NARRATION`.
- `src/main/java/.../quiz/QuizQuestion.java` — MODIFY: add `highlightGeoJson` field + getter/setter.
- `src/main/java/.../quiz/AtlasQuiz.java` — MODIFY: add `allowBack` (default `true`) + getter/setter.
- `src/main/java/.../quiz/AtlasQuizIO.java` — MODIFY: `FORMAT_VERSION=2`; accept `{1,2}`; validate `NARRATION`.
- `src/test/java/.../quiz/AtlasQuizIOTest.java` — MODIFY: v1-reads, NARRATION, highlight, allowBack default.
- `src/main/java/.../quiz/QuizSlide.java` — MODIFY: add `openSlideAsync(...)` dispatcher + `isAtlasDziUrl(...)`.
- `src/test/java/.../quiz/QuizSlideTest.java` — CREATE (or MODIFY if present): `isAtlasDziUrl` classifier tests.
- `src/main/java/.../quiz/QuizAuthorWindow.java` — MODIFY: capture-view, NARRATION type, highlight, allowBack checkbox.
- `src/main/java/.../quiz/QuizRunnerWindow.java` — MODIFY: NARRATION render, highlight overlay, allowBack gating, `openSlideAsync` routing, tour framing.
- `src/main/java/.../qupath/AtlasExtension.java` — MODIFY: add "Rehberli tur oynat…" menu item.

---

## Task 1: Data model — NARRATION type + highlightGeoJson + allowBack

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizType.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizQuestion.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuiz.java`

**Interfaces produced (used by later tasks):** `QuizType.NARRATION`; `QuizQuestion.getHighlightGeoJson()/setHighlightGeoJson(String)`; `AtlasQuiz.isAllowBack()/setAllowBack(boolean)`.

- [ ] **Step 1: Add `NARRATION` to `QuizType`.**
```java
/** The kinds of stop a quiz/tour supports. NARRATION is a caption-only stop (no question). */
public enum QuizType {
    MCQ, FREETEXT, ANNOTATION, NAVIGATION, NARRATION
}
```

- [ ] **Step 2: Add `highlightGeoJson` to `QuizQuestion`.** After the `targetGeometryGeoJson` field + accessors, add:
```java
    private String highlightGeoJson;            // optional per-stop highlight overlay (any type)
```
and (near the other getters/setters):
```java
    public String getHighlightGeoJson() {
        return highlightGeoJson;
    }

    public void setHighlightGeoJson(String highlightGeoJson) {
        this.highlightGeoJson = highlightGeoJson;
    }
```

- [ ] **Step 3: Add `allowBack` to `AtlasQuiz`.** After the `description` field, add:
```java
    private boolean allowBack = true;   // quiz-level: false = forward-only (Önceki disabled)
```
and:
```java
    public boolean isAllowBack() {
        return allowBack;
    }

    public void setAllowBack(boolean allowBack) {
        this.allowBack = allowBack;
    }
```
(Gson uses the no-arg constructor, so a v1 file without `allowBack` keeps the `true` initializer.)

- [ ] **Step 4: Compile.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon compileJava`
Expected: `BUILD SUCCESSFUL`. (Note: `QuizAuthorWindow`/`QuizRunnerWindow` `switch (type)` statements over `QuizType` may now warn/err on the unhandled `NARRATION` case — if the build fails on an exhaustive switch, add a temporary `case NARRATION -> {}` / `default` to each switch; Tasks 4–5 fill in real behavior. Prefer `default -> {}` where a switch is a statement, so the build passes without pre-empting later tasks.)

- [ ] **Step 5: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/QuizType.java \
        src/main/java/com/patolojiatlasi/qupath/quiz/QuizQuestion.java \
        src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuiz.java
git commit -m "feat(quiz): NARRATION type + per-stop highlightGeoJson + quiz-level allowBack"
```

---

## Task 2: `AtlasQuizIO` — backward-compatible versioning + NARRATION validation

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIO.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIOTest.java`

**Interfaces:** Consumes Task 1's model. Produces: an `AtlasQuizIO` that writes `formatVersion=2`, reads `{1,2}`, and validates `NARRATION` (prompt+slideUrl only).

- [ ] **Step 1: Write failing tests.** Add to `AtlasQuizIOTest` (match the existing test style — likely `@TempDir` + write/read round-trips):
```java
    @Test
    void readsExistingV1File() throws Exception {
        // A minimal v1 quiz JSON (formatVersion=1, one MCQ) must still load under the v2 reader.
        String v1 = "{\"formatVersion\":1,\"title\":\"t\",\"description\":\"\",\"questions\":["
                + "{\"type\":\"MCQ\",\"slideUrl\":\"https://x/y.dzi\",\"prompt\":\"p\","
                + "\"options\":[\"a\",\"b\"],\"correctIndex\":0}]}";
        File f = File.createTempFile("quiz", ".json");
        java.nio.file.Files.writeString(f.toPath(), v1);
        AtlasQuiz q = AtlasQuizIO.read(f);
        assertEquals(1, q.getQuestions().size());
        assertTrue(q.isAllowBack(), "absent allowBack must default true");
    }

    @Test
    void narrationRoundTripsAndValidates() throws Exception {
        AtlasQuiz q = new AtlasQuiz();
        q.setTitle("tour");
        q.setAllowBack(false);
        QuizQuestion s = new QuizQuestion();
        s.setType(QuizType.NARRATION);
        s.setSlideUrl("https://x/y.dzi");
        s.setPrompt("Bu bölgeye bakın");     // caption
        s.setHighlightGeoJson("{\"type\":\"Feature\"}");
        QuizQuestion.Viewport vp = new QuizQuestion.Viewport();
        vp.downsample = 4.0; vp.centerX = 100; vp.centerY = 200;
        s.setViewport(vp);
        q.getQuestions().add(s);
        File f = File.createTempFile("tour", ".json");
        AtlasQuizIO.write(q, f);
        AtlasQuiz back = AtlasQuizIO.read(f);            // must not throw (NARRATION needs no answer fields)
        QuizQuestion r = back.getQuestions().get(0);
        assertEquals(QuizType.NARRATION, r.getType());
        assertEquals("{\"type\":\"Feature\"}", r.getHighlightGeoJson());
        assertEquals(4.0, r.getViewport().downsample);
        assertFalse(back.isAllowBack());
        assertEquals(AtlasQuizIO.FORMAT_VERSION, readFormatVersion(f)); // written as 2 (see helper below)
    }

    private static int readFormatVersion(File f) throws Exception {
        return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f.toPath()))
                .getAsJsonObject().get("formatVersion").getAsInt();
    }
```

- [ ] **Step 2: Run the tests — verify they fail.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon test --tests "*AtlasQuizIOTest*"`
Expected: FAIL — `narrationRoundTripsAndValidates` throws in `read` (unsupported version 2, or NARRATION unhandled in `validate`'s switch); `readsExistingV1File` fails the exact-version check.

- [ ] **Step 3: Bump the version + accept a range.** In `AtlasQuizIO`:
```java
    /** Current on-disk format written by this build. Readers accept MIN_FORMAT_VERSION..FORMAT_VERSION. */
    public static final int FORMAT_VERSION = 2;
    public static final int MIN_FORMAT_VERSION = 1;
```
In `validate`, replace the exact-version check:
```java
        int v = quiz.getFormatVersion();
        if (v < MIN_FORMAT_VERSION || v > FORMAT_VERSION)
            throw new IOException("Unsupported quiz format version " + v
                    + " (this extension reads versions " + MIN_FORMAT_VERSION + ".." + FORMAT_VERSION + ")");
```

- [ ] **Step 4: Handle `NARRATION` in `validate`'s per-type switch.** Add a case (NARRATION needs no answer/geometry fields — the generic non-blank `slideUrl` + `prompt`[=caption] checks above the switch already apply):
```java
                case NARRATION -> {
                    // caption-only stop: prompt (the caption) + slideUrl already validated above; nothing extra.
                }
```

- [ ] **Step 5: Run the tests — verify they pass.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon test --tests "*AtlasQuizIOTest*"`
Expected: PASS (all existing + the 2 new tests).

- [ ] **Step 6: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIO.java \
        src/test/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIOTest.java
git commit -m "feat(quiz): AtlasQuizIO v2 with backward-compatible read + NARRATION validation"
```

---

## Task 3: `QuizSlide` — atlas/local dispatched open + URI classifier

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizSlide.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/QuizSlideTest.java` (create if absent)

**Interfaces:** Produces `QuizSlide.openSlideAsync(QuPathGUI, String, Runnable, Consumer<Exception>, BooleanSupplier)` (dispatches atlas DZI vs local) and `static boolean isAtlasDziUrl(String)`. Used by Task 5 (runner).

- [ ] **Step 1: Write failing classifier tests.** Create `QuizSlideTest` (or add to it):
```java
package com.patolojiatlasi.qupath.quiz;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QuizSlideTest {
    @Test void dziUrlsClassifyAtlas() {
        assertTrue(QuizSlide.isAtlasDziUrl("https://images.patolojiatlasi.com/x/y.dzi"));
        assertTrue(QuizSlide.isAtlasDziUrl("https://h/x/y.dzi?mpp=0.25"));      // query stripped
        assertTrue(QuizSlide.isAtlasDziUrl("HTTPS://H/Y.DZI"));                 // case-insensitive
    }
    @Test void localAndOtherUrisClassifyNonAtlas() {
        assertFalse(QuizSlide.isAtlasDziUrl("file:/C:/slides/case1.svs"));
        assertFalse(QuizSlide.isAtlasDziUrl("file:///home/u/case1.ndpi"));
        assertFalse(QuizSlide.isAtlasDziUrl(null));
        assertFalse(QuizSlide.isAtlasDziUrl(""));
        assertFalse(QuizSlide.isAtlasDziUrl("https://h/x/y.tiff"));
    }
}
```
Run: `./gradlew --no-daemon test --tests "*QuizSlideTest*"` → FAIL (`isAtlasDziUrl` undefined).

- [ ] **Step 2: Add the classifier.** In `QuizSlide`:
```java
    /** True iff {@code uri} is an atlas Deep-Zoom descriptor (ends in {@code .dzi}, query stripped,
     *  case-insensitive) — the only shape {@link #openAsync}'s {@link DziImageServer} path handles.
     *  Everything else (local {@code file:} slides, other formats) opens via {@link #openSlideAsync}'s
     *  standard-server path. */
    public static boolean isAtlasDziUrl(String uri) {
        if (uri == null || uri.isBlank())
            return false;
        String s = uri;
        int q = s.indexOf('?');
        if (q >= 0)
            s = s.substring(0, q);
        return s.toLowerCase(Locale.ROOT).endsWith(".dzi");
    }
```

- [ ] **Step 3: Add the dispatched opener.** In `QuizSlide` (imports: `qupath.lib.images.servers.ImageServers`):
```java
    /**
     * Open {@code uri} read-only into the active viewer, dispatching by kind: an atlas {@code .dzi}
     * URL streams via {@link #openAsync} (unchanged); any other URI (a local {@code file:} slide,
     * etc.) is built with QuPath's standard {@link ImageServers#buildServer(java.net.URI, String...)}
     * off the FX thread, then applied inside {@link Platform#runLater}. Same {@code onDone}/{@code
     * onError}/{@code stillWanted} contract as {@link #openAsync} — a local slide that can't be built
     * (e.g. a shared tour whose local file the recipient lacks) reports via {@code onError}, so the
     * caller can degrade gracefully instead of dead-ending.
     */
    public static void openSlideAsync(QuPathGUI qupath, String uri, Runnable onDone,
            Consumer<Exception> onError, BooleanSupplier stillWanted) {
        if (isAtlasDziUrl(uri)) {
            openAsync(qupath, uri, onDone, onError, stillWanted);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ImageServer<BufferedImage> server = ImageServers.buildServer(URI.create(uri));
                ImageData<BufferedImage> imageData = new ImageData<>(server, ImageData.ImageType.OTHER);
                Platform.runLater(() -> {
                    if (stillWanted != null && !stillWanted.getAsBoolean())
                        return;
                    try {
                        qupath.getViewer().setImageData(imageData);
                        onDone.run();
                    } catch (Exception ex) {
                        logger.error("Failed to display local slide {}: {}", uri, ex.getMessage(), ex);
                        onError.accept(ex);
                    }
                });
            } catch (Exception ex) {
                logger.error("Failed to open local slide {}: {}", uri, ex.getMessage(), ex);
                Platform.runLater(() -> onError.accept(ex));
            }
        }, "atlas-tour-open-local");
        t.setDaemon(true);
        t.start();
    }
```

- [ ] **Step 4: Run tests + compile.**
Run: `./gradlew --no-daemon test --tests "*QuizSlideTest*" && ./gradlew --no-daemon compileJava`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/QuizSlide.java \
        src/test/java/com/patolojiatlasi/qupath/quiz/QuizSlideTest.java
git commit -m "feat(quiz): QuizSlide.openSlideAsync dispatches atlas DZI vs local slide + isAtlasDziUrl"
```

---

## Task 4: Author — capture-view + NARRATION + highlight + allowBack

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizAuthorWindow.java`

**Interfaces:** Consumes Task 1's model. Read the existing `QuestionDialog` (its `typeChoice` `ChoiceBox<QuizType>`, `rebuildTypeSpecific()`, `bindCurrentSlide()`, `captureReference()`, `onOk()`, the `boundSlideUrl`/`boundSlideTitle` fields) + the outer `QuizAuthorWindow` (title/description fields, the questions `ListView`, save/load) before editing.

- [ ] **Step 1: Add `NARRATION` to the type picker.** In `QuestionDialog`, add `QuizType.NARRATION` to the `typeChoice` `ChoiceBox` items list. In `rebuildTypeSpecific()`, add a `NARRATION` branch that shows **no** answer/geometry widgets (the `promptArea` already serves as the caption; the shared explanation + the new highlight row below are enough).

- [ ] **Step 2: Add a "capture current view" control (all types).** In the dialog layout, add a row:
```java
        Label viewportStatus = new Label("(Görünüm yakalanmadı)");
        Button captureViewBtn = new Button("Bu görünümü yakala");
        captureViewBtn.setOnAction(e -> {
            var viewer = qupath.getViewer();
            if (viewer == null || viewer.getImageData() == null) {
                errorLabel.setText("Açık bir slayt yok — önce QuPath'te bir slayt açın.");
                return;
            }
            QuizQuestion.Viewport vp = new QuizQuestion.Viewport();
            vp.downsample = viewer.getDownsampleFactor();
            vp.centerX = viewer.getCenterPixelX();
            vp.centerY = viewer.getCenterPixelY();
            capturedViewport = vp;                       // new dialog field, seeded from `existing` on edit
            errorLabel.setText("");
            viewportStatus.setText(String.format(java.util.Locale.US,
                    "Görünüm: %.1fx, (%.0f, %.0f)", vp.downsample, vp.centerX, vp.centerY));
        });
        HBox viewportRow = new HBox(6, captureViewBtn, viewportStatus);
        viewportRow.setAlignment(Pos.CENTER_LEFT);
```
Add a `private QuizQuestion.Viewport capturedViewport;` field to `QuestionDialog`, initialized from `existing.getViewport()` when editing. Include `viewportRow` in the dialog's root VBox. In `onOk()`, `result.setViewport(capturedViewport);` (null if never captured — matches today's behavior).

- [ ] **Step 3: Add an optional per-stop highlight (all types).** Add a row parallel to `captureReference()`'s "Referansı slayttan al", but storing into a new `capturedHighlightGeoJson` field:
```java
        Label highlightStatus = new Label("(Vurgu yok)");
        Button highlightBtn = new Button("Vurguyu seçili anotasyondan al");
        highlightBtn.setOnAction(e -> {
            ROI roi = selectedRoiOnCurrentSlide();     // reuse captureReference()'s selection logic (extract a helper)
            if (roi == null) {
                errorLabel.setText("Önce slayt üzerinde bir anotasyon seçin.");
                return;
            }
            capturedHighlightGeoJson = QuizGeometry.toGeoJson(roi);
            highlightStatus.setText("Vurgu alındı.");
        });
        Button clearHighlightBtn = new Button("Vurguyu temizle");
        clearHighlightBtn.setOnAction(e -> { capturedHighlightGeoJson = null; highlightStatus.setText("(Vurgu yok)"); });
        HBox highlightRow = new HBox(6, highlightBtn, clearHighlightBtn, highlightStatus);
```
Add `private String capturedHighlightGeoJson;` seeded from `existing.getHighlightGeoJson()`. In `onOk()`, `result.setHighlightGeoJson(capturedHighlightGeoJson);`. Extract the annotation-selection lookup from `captureReference()` into a reusable `selectedRoiOnCurrentSlide()` (so both the reference-capture and highlight-capture share it — DRY; do not duplicate the guard that the selection is on the bound slide).

- [ ] **Step 4: Add the quiz-level `allowBack` checkbox.** On the outer `QuizAuthorWindow` (near the title/description fields):
```java
        CheckBox allowBackCheck = new CheckBox("Geri gitmeye izin ver");
        allowBackCheck.setSelected(quiz.isAllowBack());   // default true; reflects a loaded quiz
        allowBackCheck.selectedProperty().addListener((o, was, now) -> quiz.setAllowBack(now));
```
Include it in the window layout; ensure `save` persists it (it's on the `quiz` object already, so `AtlasQuizIO.write(quiz, …)` carries it).

- [ ] **Step 5: Relax the "needs a question" validation for NARRATION.** If `onOk()` (or a pre-save check) requires answer/geometry per type, ensure `NARRATION` only requires a non-blank prompt (caption) + a bound slide — no options/geometry. Mirror `AtlasQuizIO.validate`'s NARRATION handling.

- [ ] **Step 6: Compile.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Reviewer focus (no GUI test harness).** Verify: capture-view uses the javap-confirmed getters and stores into the stop's `Viewport`; NARRATION hides answer widgets and requires only prompt+slide; highlight capture reuses the shared selection helper (no duplicated guard) and is clearable; `allowBack` binds to `quiz` and persists; existing MCQ/FREETEXT/ANNOTATION/NAVIGATION authoring is unchanged.

- [ ] **Step 8: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/QuizAuthorWindow.java
git commit -m "feat(quiz): author — capture viewport, NARRATION stops, per-stop highlight, allowBack"
```

---

## Task 5: Player — NARRATION render + highlight + allowBack + local-slide routing + tour framing

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRunnerWindow.java`

**Interfaces:** Consumes Task 1 (model), Task 3 (`openSlideAsync`). Read `showQuestion(int)`, `buildInput(QuizQuestion)`, `applySlide(QuizQuestion)`, `afterSlideReady(QuizQuestion)`, `revealAnswer()`, `showRevealOverlay(ROI)`, the `prevBtn`/`nextBtn`/`progressLabel`/`promptLabel` fields, and the button-enable logic in `applySlide`'s onDone/onError before editing. Add a `boolean tourMode` set by a new static `show(qupath, tourMode)` overload (default existing `show(qupath)` → `tourMode=false`).

- [ ] **Step 1: Route slide opens through the dispatcher.** In `applySlide`, replace the `QuizSlide.openAsync(...)` call with `QuizSlide.openSlideAsync(...)` (same args) so local slides open too. Keep the existing "already open → skip reload" check (it compares `QuizSlide.currentSlideUrl(viewer)` to `q.getSlideUrl()` — unchanged, works for both). In the `onError` path, additionally set a visible "Slayt açılamadı: <slideTitle>" note in the prompt/status area and re-enable navigation so the learner can continue (graceful-missing).

- [ ] **Step 2: NARRATION render.** In `buildInput(q)`'s switch add:
```java
            case NARRATION -> {
                // Caption-only stop: promptLabel already shows q.getPrompt(); no answer widget.
            }
```
In `revealAnswer()`'s switch add:
```java
            case NARRATION -> revealAnswerLabel.setText("");   // no "correct answer" line; explanation shown below
```
(The shared `revealExplanationLabel.setText(q.getExplanation()…)` after the switch still shows the teaching point on "Göster".)

- [ ] **Step 3: Per-stop highlight overlay + toggle.** In `afterSlideReady(q)`, after the viewport is applied, if `q.getHighlightGeoJson()` is non-blank parse it (reuse `parseGeometrySafely`) and draw it via the existing `showRevealOverlay(roi)` path — but as a stop highlight independent of "Göster". Add a `CheckBox highlightToggle` ("Vurguyu göster", default selected, visible only when the stop has a highlight) that shows/removes the overlay. Ensure the highlight overlay is cleared on `leaveQuestion` (same lifecycle as the reveal overlay) so it doesn't leak to the next stop.

- [ ] **Step 4: `allowBack` gating.** Wherever `prevBtn`'s enabled state is set (initial `prevBtn.setDisable(true)` and in `applySlide`/`showQuestion`'s control-enable logic), gate it: `prevBtn.setDisable(currentIndex <= 1 || (quiz != null && !quiz.isAllowBack()));`. So when `allowBack` is false, Önceki is never enabled (forward-only), for both quiz and tour play.

- [ ] **Step 5: Tour presentation framing.** Add the `tourMode` flag + `show(QuPathGUI, boolean)` overload (existing `show(qupath)` delegates with `false`). In `showQuestion`, set the progress label per mode: `progressLabel.setText((tourMode ? "Durak " : "Soru ") + currentIndex + " / " + n);`. (Everything else identical — the tour is the same player, just framed.)

- [ ] **Step 6: Compile.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Reviewer focus.** Verify: existing self-check play is UNCHANGED for a quiz with no NARRATION/highlight, `allowBack=true`, `tourMode=false` (progress "Soru", Önceki enabled at index>1, opens via the dispatcher which routes DZI URLs to the unchanged `openAsync`); NARRATION shows caption + explanation, no answer; highlight overlay draws + toggles + is cleared on leave; `allowBack=false` disables Önceki everywhere; graceful-missing shows the note + lets the learner continue; the highlight/reveal overlays don't leak across stops.

- [ ] **Step 8: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/QuizRunnerWindow.java
git commit -m "feat(quiz): player — NARRATION, per-stop highlight, allowBack, local slides, tour framing"
```

---

## Task 6: Menu — "Rehberli tur oynat…"

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java`

**Interfaces:** Consumes Task 5's `QuizRunnerWindow.show(qupath, true)`.

- [ ] **Step 1: Add the tour-play menu item.** In the "Sınav / Quiz" submenu construction (where `quizTakeItem`/`quizAuthorItem` are created + added to `quizMenu`), add:
```java
            MenuItem tourPlayItem = new MenuItem("Rehberli tur oynat…");
            tourPlayItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizRunnerWindow.show(qupath, true));
            quizMenu.getItems().addAll(quizTakeItem, quizAuthorItem, tourPlayItem);
```
(Adjust the exact `getItems().addAll(...)` call to include `tourPlayItem` alongside the existing two.)

- [ ] **Step 2: Build.**
Run: `cd /d/patolojiatlasi-QuPath && ./gradlew --no-daemon build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit.**
```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java
git commit -m "feat(menu): 'Rehberli tur oynat…' launches the player in guided-tour mode"
```

---

## Self-Review (author)
- **Spec coverage:** NARRATION + highlight + viewport + allowBack (T1) · backward-compat IO + validation (T2) · atlas+local open + graceful-missing (T3, T5) · author capture/NARRATION/highlight/allowBack (T4) · player render/overlay/gating/framing (T5) · menu (T6). Every spec section maps to a task.
- **Type consistency:** `QuizType.NARRATION`, `QuizQuestion.getHighlightGeoJson`, `AtlasQuiz.isAllowBack`, `QuizSlide.openSlideAsync`/`isAtlasDziUrl`, `QuizRunnerWindow.show(qupath, boolean)` are defined in T1/T3/T5 and consumed consistently downstream.
- **APIs javap-verified:** viewer getters, `ImageServers.buildServer(URI)`, `QuizType` values — no unverified method references.
- **No-regression guardrail:** T5 Step 7 explicitly requires existing self-check play to be unchanged; the dispatcher routes DZI URLs to the untouched `openAsync`.
- **Merge gate:** after the final review, the USER smoke-tests the author (capture view, NARRATION, highlight, allowBack) + both play modes (self-check unchanged; guided tour with narration/highlight/forward-only; graceful-missing local slide) before merge — GUI-only behaviors can't be verified headless.
