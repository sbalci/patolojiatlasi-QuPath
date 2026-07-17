# Atlas Quiz (self-study) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** An in-QuPath self-study quiz for atlas slides: author questions (MCQ / free-text / annotation / navigation), save a portable quiz-pack JSON, and take it as a guided sequential run where the learner answers and reveals the answer to self-check.

**Architecture:** A UI-free versioned data model + IO (`AtlasQuiz`/`QuizQuestion`/`AtlasQuizIO`, unit-tested); a shared read-only slide opener (`QuizSlide`); a runner window and an author window; and (slice 2) a custom viewer overlay for revealing reference geometries. Reference answers use `viewer.getCustomOverlayLayers()` (not the annotation hierarchy), so nothing is saved into the learner's project.

**Tech Stack:** Java 21, JavaFX (provided by QuPath), QuPath 0.6 API (`qupath-core`, `qupath-gui-fx`, `compileOnly`), Gson, JUnit 5, Gradle.

## Global Constraints

- **QuPath API floor 0.6.0**; Java 21. **No new bundled dependencies** (QuPath + JavaFX + Gson stay `compileOnly`).
- **Build/test use `--offline`** (deps cached).
- **Reuse the repo's established patterns — read these files before writing UI:**
  - **Open a slide read-only into the viewer:** background daemon thread builds `new DziImageServer(uri)` + `new ImageData<>(server, imageType)`, then `Platform.runLater(() -> viewer.setImageData(imageData))` — mirror `AtlasBrowser.openSelected()` (no-project branch) and `AtlasProjectService.addCaseToProject` (image-type usage).
  - **Custom overlay:** `viewer.getCustomOverlayLayers().add(overlay)` / `.remove(overlay)` + `viewer.repaint()`, on the FX thread — mirror `focus/FocusHeatmap.java` (`refreshOverlay`/`removeOverlay`).
  - **Window/threading:** mirror `AtlasBrowser`/`ProjectBuilderDialog` (a `Stage`, background daemon threads for network, `Platform.runLater` for UI, an in-flight guard).
  - **JSON:** Gson (`new GsonBuilder().setPrettyPrinting().create()`); geometry↔GeoJSON via `qupath.lib.io.GsonTools.getInstance()`.
- **Self-study saves nothing.** Reference answers are shown via custom overlay layers or transient UI only — never added to the annotation hierarchy, never saved.
- **User-facing labels are Turkish** (matching the extension's newer items, e.g. "Görüntüyü döndür…"). Class/field/file names are ASCII English.
- **Menu:** register quiz items in `AtlasExtension.installExtension`.
- Package for all new classes: `com.patolojiatlasi.qupath.quiz`.

---

## SLICE 1 — Foundation + text types (MCQ, free-text), end to end

### Task 1: Quiz data model + versioned IO (`AtlasQuiz`, `QuizQuestion`, `AtlasQuizIO`) + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizType.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizQuestion.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuiz.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIO.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/AtlasQuizIOTest.java`

**Interfaces (later tasks rely on these):**
- `enum QuizType { MCQ, FREETEXT, ANNOTATION, NAVIGATION }`
- `QuizQuestion` — mutable DTO (private fields + getters/setters) with: `String id, QuizType type, String slideUrl, String slideTitle, String prompt, explanation; Viewport viewport; List<String> options; Integer correctIndex; String modelAnswer, instruction, referenceGeometryGeoJson, targetGeometryGeoJson;` and nested `static class Viewport { double downsample, centerX, centerY; }`.
- `AtlasQuiz` — `int formatVersion; String title, description; List<QuizQuestion> questions;` + getters/setters; ctor sets `formatVersion=AtlasQuizIO.FORMAT_VERSION`, `questions=new ArrayList<>()`.
- `AtlasQuizIO` — `static final int FORMAT_VERSION = 1; static void write(AtlasQuiz, File) throws IOException; static AtlasQuiz read(File) throws IOException; static void validate(AtlasQuiz) throws IOException` (package-private, tested).

- [ ] **Step 1: Write the failing test**

Create `AtlasQuizIOTest.java`:

```java
package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

class AtlasQuizIOTest {

    private static QuizQuestion mcq() {
        QuizQuestion q = new QuizQuestion();
        q.setId("q1");
        q.setType(QuizType.MCQ);
        q.setSlideUrl("https://images.patolojiatlasi.com/case1/HE.dzi?mpp=0.26");
        q.setSlideTitle("Case 1 HE");
        q.setPrompt("What is shown?");
        q.setExplanation("Because reasons.");
        q.setOptions(List.of("Adenocarcinoma", "Normal", "Lymphoma"));
        q.setCorrectIndex(0);
        return q;
    }

    private static QuizQuestion freetext() {
        QuizQuestion q = new QuizQuestion();
        q.setId("q2");
        q.setType(QuizType.FREETEXT);
        q.setSlideUrl("https://images.patolojiatlasi.com/case2/HE.dzi");
        q.setSlideTitle("Case 2 HE");
        q.setPrompt("Describe the lesion.");
        q.setExplanation("Model description here.");
        q.setModelAnswer("A well-differentiated tumour.");
        return q;
    }

    private static File tempFile() throws IOException {
        File f = File.createTempFile("atlas-quiz", ".json");
        f.deleteOnExit();
        return f;
    }

    @Test
    void roundTripPreservesContent() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setTitle("GI quiz");
        quiz.setDescription("desc");
        quiz.getQuestions().add(mcq());
        quiz.getQuestions().add(freetext());

        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        assertTrue(Files.size(f.toPath()) > 0);

        AtlasQuiz back = AtlasQuizIO.read(f);
        assertEquals("GI quiz", back.getTitle());
        assertEquals(2, back.getQuestions().size());
        QuizQuestion q0 = back.getQuestions().get(0);
        assertEquals(QuizType.MCQ, q0.getType());
        assertEquals(3, q0.getOptions().size());
        assertEquals(0, q0.getCorrectIndex());
        assertEquals(QuizType.FREETEXT, back.getQuestions().get(1).getType());
    }

    @Test
    void rejectsUnknownFormatVersion() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setFormatVersion(999);
        quiz.getQuestions().add(mcq());
        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        assertThrows(IOException.class, () -> AtlasQuizIO.read(f));
    }

    @Test
    void rejectsMcqWithBadCorrectIndex() {
        AtlasQuiz quiz = new AtlasQuiz();
        QuizQuestion q = mcq();
        q.setCorrectIndex(5); // out of range (3 options)
        quiz.getQuestions().add(q);
        assertThrows(IOException.class, () -> AtlasQuizIO.validate(quiz));
    }

    @Test
    void rejectsMcqWithTooFewOptions() {
        AtlasQuiz quiz = new AtlasQuiz();
        QuizQuestion q = mcq();
        q.setOptions(List.of("only one"));
        quiz.getQuestions().add(q);
        assertThrows(IOException.class, () -> AtlasQuizIO.validate(quiz));
    }

    @Test
    void acceptsValidQuiz() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.getQuestions().add(mcq());
        quiz.getQuestions().add(freetext());
        AtlasQuizIO.validate(quiz); // must not throw
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.quiz.AtlasQuizIOTest"`
Expected: FAIL — classes don't exist (compile error).

- [ ] **Step 3: Write the model + IO**

`QuizType.java`:
```java
package com.patolojiatlasi.qupath.quiz;

/** The kinds of self-check question a quiz supports. */
public enum QuizType {
    MCQ, FREETEXT, ANNOTATION, NAVIGATION
}
```

`QuizQuestion.java` — mutable DTO. Write private fields for every interface field above, a no-arg constructor, and public getters/setters for each. Include the nested `Viewport`. (Gson populates fields by reflection on read; the author sets them via setters.) Example shape:
```java
package com.patolojiatlasi.qupath.quiz;

import java.util.List;

/** One self-check question. Type-specific fields are null when not applicable to {@link #type}. */
public class QuizQuestion {

    private String id;
    private QuizType type;
    private String slideUrl;
    private String slideTitle;
    private String prompt;
    private String explanation;
    private Viewport viewport;

    private List<String> options;      // MCQ
    private Integer correctIndex;      // MCQ
    private String modelAnswer;        // FREETEXT
    private String instruction;        // ANNOTATION
    private String referenceGeometryGeoJson; // ANNOTATION
    private String targetGeometryGeoJson;    // NAVIGATION

    /** Where the learner should start on the slide (full-resolution pixels). */
    public static class Viewport {
        public double downsample;
        public double centerX;
        public double centerY;
    }

    // no-arg ctor (Gson) + getId/setId ... for every field above.
}
```
(Write out all getters/setters — do not abbreviate.)

`AtlasQuiz.java`:
```java
package com.patolojiatlasi.qupath.quiz;

import java.util.ArrayList;
import java.util.List;

/** A portable quiz: metadata + an ordered list of questions. Serialized as one JSON file. */
public class AtlasQuiz {

    private int formatVersion = AtlasQuizIO.FORMAT_VERSION;
    private String title = "";
    private String description = "";
    private List<QuizQuestion> questions = new ArrayList<>();

    public int getFormatVersion() { return formatVersion; }
    public void setFormatVersion(int v) { this.formatVersion = v; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t == null ? "" : t; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }
    public List<QuizQuestion> getQuestions() {
        if (questions == null) questions = new ArrayList<>();
        return questions;
    }
    public void setQuestions(List<QuizQuestion> q) { this.questions = q; }
}
```

`AtlasQuizIO.java`:
```java
package com.patolojiatlasi.qupath.quiz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/** Read/write/validate a quiz-pack JSON file. UI-free and unit-tested. */
public final class AtlasQuizIO {

    /** Current on-disk format. Bump only with a matching reader change. */
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AtlasQuizIO() {}

    public static void write(AtlasQuiz quiz, File file) throws IOException {
        String json = GSON.toJson(quiz);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    public static AtlasQuiz read(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        AtlasQuiz quiz;
        try {
            quiz = GSON.fromJson(json, AtlasQuiz.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Not a valid quiz file: " + e.getMessage(), e);
        }
        if (quiz == null)
            throw new IOException("Empty or invalid quiz file: " + file);
        validate(quiz);
        return quiz;
    }

    /** Throw IOException on any structural problem. Package-private for tests. */
    static void validate(AtlasQuiz quiz) throws IOException {
        if (quiz.getFormatVersion() != FORMAT_VERSION)
            throw new IOException("Unsupported quiz format version " + quiz.getFormatVersion()
                    + " (this extension reads version " + FORMAT_VERSION + ")");
        if (quiz.getQuestions().isEmpty())
            throw new IOException("Quiz has no questions");
        int i = 0;
        for (QuizQuestion q : quiz.getQuestions()) {
            i++;
            if (q.getType() == null)
                throw new IOException("Question " + i + " has no type");
            if (isBlank(q.getSlideUrl()))
                throw new IOException("Question " + i + " has no slideUrl");
            if (isBlank(q.getPrompt()))
                throw new IOException("Question " + i + " has no prompt");
            switch (q.getType()) {
                case MCQ -> {
                    if (q.getOptions() == null || q.getOptions().size() < 2)
                        throw new IOException("Question " + i + " (MCQ) needs at least 2 options");
                    Integer ci = q.getCorrectIndex();
                    if (ci == null || ci < 0 || ci >= q.getOptions().size())
                        throw new IOException("Question " + i + " (MCQ) has an out-of-range correctIndex");
                }
                case FREETEXT -> {
                    if (q.getModelAnswer() == null)
                        throw new IOException("Question " + i + " (free-text) has no modelAnswer");
                }
                case ANNOTATION -> {
                    if (isBlank(q.getReferenceGeometryGeoJson()))
                        throw new IOException("Question " + i + " (annotation) has no reference geometry");
                }
                case NAVIGATION -> {
                    if (isBlank(q.getTargetGeometryGeoJson()))
                        throw new IOException("Question " + i + " (navigation) has no target geometry");
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.quiz.AtlasQuizIOTest"`
Expected: PASS (5 tests). Then `./gradlew --offline compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/quiz/ src/test/java/com/patolojiatlasi/qupath/quiz/
git commit -m "feat(quiz): versioned quiz-pack data model + IO"
```

---

### Task 2: `QuizSlide` — open a slide URL read-only into the viewer

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizSlide.java`

**Interfaces (runner/author rely on this):**
- `static void openAsync(QuPathGUI qupath, String slideUrl, Runnable onDone, Consumer<Exception> onError)` — on a background daemon thread build `new DziImageServer(URI.create(slideUrl))` + `new ImageData<>(server, inferType(slideUrl))`, then `Platform.runLater` to `qupath.getViewer().setImageData(imageData)` and call `onDone`; on failure `Platform.runLater(() -> onError.accept(ex))`.
- `static ImageData.ImageType inferType(String slideUrl)` — reuse the atlas stain heuristic. **Refactor:** extract `AtlasCase`'s stain→type logic into a shared static (e.g. `AtlasCase.imageTypeForImageName(String)` used by both `getImageType()` and here) rather than duplicating it. Keep `AtlasCase.getImageType()` behavior identical (verify its unit tests still pass).
- `static String currentSlideUrl(QuPathViewer viewer)` — the first URI of the viewer's current server as a string (`server.getURIs()`), or "" — used by the author to bind a question to the open slide, and by the runner to decide whether to reload.

**Behavior:** read `AtlasBrowser.openSelected()`'s no-project branch and `AtlasProjectService.addCaseToProject` for the exact `DziImageServer`/`ImageData` usage. Do the network build off the FX thread; touch the viewer only inside `Platform.runLater`.

- [ ] **Step 1:** Implement `QuizSlide` per the interface + the shared-heuristic refactor in `AtlasCase`.
- [ ] **Step 2:** `./gradlew --offline compileJava` → BUILD SUCCESSFUL; `./gradlew --offline test` → all prior tests still PASS (confirms the `AtlasCase` refactor didn't regress).
- [ ] **Step 3: Commit** `git commit -m "feat(quiz): QuizSlide read-only opener + shared image-type heuristic"`

**Manual verification (record):** not runnable here (needs QuPath); the runner (Task 3) exercises it.

---

### Task 3: `QuizRunnerWindow` — guided sequential run (MCQ + free-text)

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRunnerWindow.java`

**Interface:** `static void show(QuPathGUI qupath)` — single window (focus if already open), mirroring `AtlasBrowser.show`.

**Layout (a `Stage`, Turkish labels):**
- Top: **"Sınav yükle…"** (load pack via `FileChooser`), then the quiz title + **"Soru i / N"** progress.
- Center: the current question's `prompt`, then a type-specific input:
  - **MCQ:** a `ToggleGroup` of `RadioButton`s from `options`.
  - **FREETEXT:** a `TextArea` for the learner's note.
- An answer/explanation area (hidden until Reveal): for MCQ, mark the correct option (e.g. bold/✓ and, if the learner picked wrong, note theirs); for free-text, show `modelAnswer`. Always show `explanation`.
- Bottom: **"Önceki"** / **"Göster"** (Reveal) / **"Sonraki"**.

**Behavior:**
- **Load:** `AtlasQuizIO.read(file)`; on `IOException` show an `Alert` with the message; else start at question 1.
- **Show question i:** if `q.getSlideUrl()` differs from `QuizSlide.currentSlideUrl(viewer)`, `QuizSlide.openAsync(...)` (show a progress indicator; disable Prev/Next/Reveal until `onDone`); if it's the same slide, don't reopen. Reset the reveal area to hidden and clear the input. (Slice 2 will apply `viewport` here.)
- **Reveal:** reveal the answer area for the current type. Idempotent.
- **Next/Prev:** clamp to `[1, N]`; moving advances the question and (via the slide check) reopens only when the slide changes.
- Guard overlapping opens with an in-flight boolean (FX-thread only), like `ProjectBuilderDialog`.
- All UI on the FX thread; only `QuizSlide`'s network runs off-thread.

- [ ] **Step 1:** Implement `QuizRunnerWindow` (MCQ + free-text only; leave a clear `// slice 2: ANNOTATION/NAVIGATION input + reveal + viewport nav` seam).
- [ ] **Step 2:** `./gradlew --offline compileJava` → BUILD SUCCESSFUL; `./gradlew --offline test` → PASS (no new tests; UI).
- [ ] **Step 3: Commit** `git commit -m "feat(quiz): guided sequential runner (MCQ + free-text)"`

**Manual verification (record):** needs QuPath — deferred to the end-of-slice manual check.

---

### Task 4: `QuizAuthorWindow` — author MCQ + free-text and save a pack

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizAuthorWindow.java`

**Interface:** `static void show(QuPathGUI qupath)`.

**Layout (a `Stage`, Turkish labels):**
- Top: **"Yeni"** / **"Aç…"** (load an existing pack to edit) / **"Kaydet…"**; editable quiz **title** + **description** fields.
- Center: a `ListView<QuizQuestion>` (showing "i. [TYPE] prompt…") with **"Ekle"** / **"Düzenle"** / **"Sil"** / **"Yukarı"** / **"Aşağı"**.
- **Add/Edit** opens a sub-dialog: choose `QuizType` (slice 1: MCQ, FREETEXT only), enter `prompt`, `explanation`; MCQ → a small editable option list + a "correct" chooser; FREETEXT → `modelAnswer`. **Bind slide:** a **"Geçerli slayta bağla"** button captures `QuizSlide.currentSlideUrl(viewer)` as `slideUrl` and the viewer's image name as `slideTitle` (show them; refuse to save the question if no slide is open).

**Behavior:**
- Build an `AtlasQuiz` in memory; **"Kaydet…"** → `FileChooser` → `AtlasQuizIO.write(quiz, file)` (validation errors shown in an `Alert`).
- **"Aç…"** → `AtlasQuizIO.read` into the editor (so a pack can be revised).
- All FX thread.

- [ ] **Step 1:** Implement `QuizAuthorWindow` (MCQ + free-text; leave a `// slice 2: geometry types + draw-reference capture` seam).
- [ ] **Step 2:** `./gradlew --offline compileJava` → BUILD SUCCESSFUL; `./gradlew --offline test` → PASS.
- [ ] **Step 3: Commit** `git commit -m "feat(quiz): in-QuPath authoring (MCQ + free-text)"`

---

### Task 5: Menu wiring + README (slice 1)

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java`
- Modify: `README.md`

- [ ] **Step 1:** In `installExtension`, after the focus-heatmap line, add:
```java
            // Self-study quiz: take or author a quiz on atlas slides.
            MenuItem quizTakeItem = new MenuItem("Sınav/quiz çöz…");
            quizTakeItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizRunnerWindow.show(qupath));
            MenuItem quizAuthorItem = new MenuItem("Sınav/quiz hazırla…");
            quizAuthorItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizAuthorWindow.show(qupath));
            menu.getItems().addAll(quizTakeItem, quizAuthorItem);
```
- [ ] **Step 2:** README — add a "## Quiz (self-study)" section: author via **Sınav/quiz hazırla…** (add MCQ/free-text questions bound to the open slide, save a pack), take via **Sınav/quiz çöz…** (load a pack, answer, Reveal to self-check); note it's self-study (nothing scored/saved) and the pack is one portable file.
- [ ] **Step 3:** `./gradlew --offline build` → BUILD SUCCESSFUL (full jar).
- [ ] **Step 4: Commit** `git commit -m "feat(quiz): menu entries + README (slice 1)"`

**End-of-slice-1 manual verification (record honestly):** in a running QuPath — open an atlas slide, author a 2-question pack (one MCQ, one free-text) bound to it, save; then take the pack: the slide opens, MCQ reveal marks the correct option, free-text reveal shows the model answer, Next/Prev works, nothing is saved to any project.

---

## SLICE 2 — Geometry types (annotation, guided-navigation)

### Task 6: ROI ↔ GeoJSON serialization + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizGeometry.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/quiz/QuizGeometryTest.java`

**Interface:**
- `static String toGeoJson(ROI roi)` — serialize a ROI to a GeoJSON string via `GsonTools.getInstance()` (wrap the ROI in a `PathObject` if that is what serializes cleanly — verify which of `roi` / `PathObjects.createAnnotationObject(roi)` GsonTools handles).
- `static ROI fromGeoJson(String geoJson, ImagePlane plane)` — parse back to a ROI.

- [ ] **Step 1:** Write `QuizGeometryTest` — build a rectangle ROI (`ROIs.createRectangleROI(10,20,100,50, ImagePlane.getDefaultPlane())`), `toGeoJson` then `fromGeoJson`, and assert the round-tripped ROI's bounds (`getBoundsX/Y/Width/Height`) match within a small epsilon. Run → FAIL (class missing).
- [ ] **Step 2:** Implement `QuizGeometry` using the concrete `GsonTools` API (verify signatures via `javap` on `qupath.lib.io.GsonTools` and `qupath.lib.objects.PathObjects`/`ROIs`/`GeometryTools` in the cached jars first). Run → PASS.
- [ ] **Step 3:** `./gradlew --offline test` → all PASS. **Commit** `git commit -m "feat(quiz): ROI<->GeoJSON serialization for reference answers"`

---

### Task 7: `QuizRevealOverlay` + runner reveal/nav for geometry types

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRevealOverlay.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizRunnerWindow.java`

**Interface / behavior:**
- `QuizRevealOverlay extends AbstractOverlay` (verify `paintOverlay` signature via `javap` on `qupath.lib.gui.viewer.overlays.AbstractOverlay`) — paints a given ROI's shape (`roi.getShape()`) in a distinct colour/stroke, downsample-aware. Alternatively, if a vector overlay is awkward, render the shape into a `BufferedImage` and use `BufferedImageOverlay` (mirror `FocusHeatmap.refreshOverlay`). Choose the simpler correct one and note why.
- Runner: fill the slice-2 seam.
  - **ANNOTATION** input: instruction label ("Cevabınızı slayt üzerine çizin"); learner draws with QuPath's normal tools. **Reveal:** `QuizGeometry.fromGeoJson` → add a `QuizRevealOverlay` via `viewer.getCustomOverlayLayers().add(...)` + `repaint()`. **Next/Prev:** remove the overlay AND clear the learner's transient annotations for this question (track annotations added since the question was shown, or clear the hierarchy's annotations — decide and document; self-study saves nothing).
  - **NAVIGATION** input: instruction ("İlgili bölgeye gidin"). **Reveal:** overlay the target geometry (same overlay) and/or `viewer.setDownsampleFactor(ds, cx, cy)` to it.
  - **Viewport:** when entering a question with a `viewport`, apply `viewer.setDownsampleFactor(viewport.downsample, viewport.centerX, viewport.centerY)` after the slide is loaded.
- All overlay/viewer calls on the FX thread; remove overlays on question change and on window close.

- [ ] **Step 1:** Implement overlay + runner reveal/nav. `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → PASS.
- [ ] **Step 2: Commit** `git commit -m "feat(quiz): reveal overlay + annotation/navigation taking"`

---

### Task 8: Author geometry capture (draw reference → store)

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/quiz/QuizAuthorWindow.java`

**Behavior:** in Add/Edit, allow `ANNOTATION`/`NAVIGATION`. For these, a **"Referansı slayttan al"** button reads the currently-selected annotation on the open slide (`viewer.getImageData().getHierarchy().getSelectionModel().getSelectedObject()` → its ROI), serializes via `QuizGeometry.toGeoJson` into `referenceGeometryGeoJson` / `targetGeometryGeoJson`, and (optionally) captures the current viewport. Refuse to save the question if no annotation is selected. Also capture `instruction` for ANNOTATION.

- [ ] **Step 1:** Implement. `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → PASS.
- [ ] **Step 2: Commit** `git commit -m "feat(quiz): author annotation/navigation reference capture"`

---

### Task 9: README (slice 2) + full build

**Files:** Modify `README.md`.

- [ ] **Step 1:** Extend the quiz section: annotation ("mark/outline, then Reveal overlays the reference to compare") and navigation ("find the region, Reveal shows it"); note no auto-grading (visual self-compare).
- [ ] **Step 2:** `./gradlew --offline build` → BUILD SUCCESSFUL. **Commit** `git commit -m "docs(quiz): README for annotation/navigation (slice 2)"`

**End-of-slice-2 manual verification (record):** author an annotation question (draw a reference region, capture it) and a navigation question; take them — draw an answer, Reveal overlays the reference distinctly, Next clears both and never saves the reference into the project.

---

## Self-Review

**1. Spec coverage:** model+IO (T1), open helper (T2), runner MCQ/free-text (T3), author MCQ/free-text (T4), menu+README (T5) = slice 1; GeoJSON (T6), overlay+reveal/nav (T7), author capture (T8), README (T9) = slice 2. Self-study "saves nothing" enforced by overlay-layer reveal (T7) + transient-annotation clearing. Portable pack (T1). All four types covered. ✅

**2. Placeholder scan:** logic tasks (T1, T6) carry complete code + tests; UI tasks carry precise interface + layout + behavior + named pattern files to read (`AtlasBrowser`, `ProjectBuilderDialog`, `FocusHeatmap`) — legitimate for in-tree patterns, not vague placeholders. The `javap`-verify steps in T6/T7 are deliberate (the exact GeoJSON/overlay signatures must be confirmed against the jars, not written from memory).

**3. Type consistency:** `AtlasQuiz`/`QuizQuestion`/`QuizType`/`AtlasQuizIO.{read,write,validate,FORMAT_VERSION}`, `QuizSlide.{openAsync,inferType,currentSlideUrl}`, `QuizGeometry.{toGeoJson,fromGeoJson}`, `QuizRunnerWindow.show`, `QuizAuthorWindow.show`, `QuizRevealOverlay` — used consistently across tasks.

**4. Risk isolation:** every unverified API (GeoJSON serialization, `AbstractOverlay.paintOverlay`, viewport nav) is in slice 2, with an explicit `javap`-first step; slice 1 uses only APIs already proven in the repo.
