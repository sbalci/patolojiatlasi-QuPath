# Atlas Quiz (self-study self-check) — design

- **Date:** 2026-07-17
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/atlas-quiz`
- **Status:** Approved design (brainstormed 2026-07-16), ready for planning
- **Author:** Serdar Balcı (with Claude Code)

## 1. Background

The Patoloji Atlası extension streams atlas whole-slide images into QuPath and (as of
2026-07) lets a user curate a **project** from selected slides (the curated-project builder),
with pixel-size calibration + image-type on open, a focus (dwell) heatmap, and a view-rotation
control. This feature adds an **exam/quiz layer** on top: a learner works through per-slide
questions and self-checks against a revealed answer, so the extension can be used for courses,
seminars, and exams. It is the second of the two features planned when the builder was designed.

**Reconciliation with the current codebase (all verified in the tree on `master`):**
- Slides are opened by building `new DziImageServer(uri)` and wrapping in
  `new ImageData<>(server, imageType)`; pixel calibration rides in the URI as `?mpp=` (see
  `AtlasProjectService.addCaseToProject` and `AtlasCase.getDziURI()`/`getImageType()`). The quiz
  **runner reuses this open path** to load slides read-only into the viewer.
- Custom viewer overlays are added via `viewer.getCustomOverlayLayers().add(overlay)` /
  `.remove(overlay)` + `viewer.repaint()`, on the JavaFX thread — this is a layer **separate from
  the annotation hierarchy** (see `focus/FocusHeatmap.java`). The quiz **reveal overlay uses this
  same mechanism**, so a revealed reference answer can never be saved into the learner's project.
- Menu items are registered in `AtlasExtension.installExtension` (newer items use Turkish labels:
  "Görüntüyü döndür…", "Odak ısı haritası"). The quiz adds items there.
- JSON is handled with Gson (`new GsonBuilder().create()`); geometry ↔ GeoJSON uses
  `qupath.lib.io.GsonTools.getInstance()`.

## 2. Decisions (settled in brainstorming)

| Question | Decision |
|----------|----------|
| Answer flow | **Self-study / self-check** — the learner reveals the answer to check themselves; nothing is collected or scored. Client-side, offline. |
| Question types | **All four:** MCQ, free-text, annotation-task, guided-navigation. |
| Authoring | **In-QuPath authoring** — the instructor opens a slide and adds questions via a form; for geometry types draws the reference on the slide. |
| Storage / distribution | **Portable quiz-pack JSON** (`formatVersion`-stamped) referencing slides by DZI URL; emailed as one file, loaded by the learner. |
| Taking UX | **Guided sequential run** — a panel steps through questions in order, auto-opening each slide and (geometry types) navigating to the region. |

## 3. Goals

- Author a quiz in QuPath (all four question types) and save it as a portable pack file.
- Take a quiz as a guided sequential run: per question, open the slide, answer (pick / type /
  draw / navigate), **Reveal** to self-compare, **Next**.
- Reveal a reference answer (text or geometry) **without** it entering the learner's saved
  annotations.
- Be **self-contained**: quiz-pack file + the extension is enough — no curated-project folder
  required (the runner opens each slide from its DZI URL).

## 4. Non-goals (explicit)

- **No grading / answer capture / submission** — self-study only. **No auto-grading** of
  annotation answers; Reveal is a pure visual self-compare (consistent with the extension
  producing measurements, not clinical verdicts).
- No backend / multi-user / results database.
- No instructor-led or free-browse modes (guided sequential run only).
- No editing of a running learner attempt's saved state (self-study saves nothing).

## 5. The quiz-pack format (`AtlasQuiz`, versioned JSON)

A single JSON file. Top level:
- `formatVersion` (int, currently `1`) — the runner refuses/upgrades unknown versions rather than
  mis-parsing.
- `title`, `description` (strings).
- `questions` — ordered array of `QuizQuestion`.

Each `QuizQuestion`:
- `id` (string, stable), `type` (`MCQ` | `FREETEXT` | `ANNOTATION` | `NAVIGATION`).
- `slideUrl` (string) — the DZI URL, **including any `?mpp=`** captured at authoring time, so the
  runner opens the slide with the same calibration; `slideTitle` (string, human label).
- `prompt` (string), `explanation` (string, shown on reveal).
- `viewport` (optional) — `{ downsample, centerX, centerY }` in full-resolution pixels, where the
  learner should start (also the fallback "region" for navigation).
- Type-specific:
  - **MCQ:** `options` (string[]), `correctIndex` (int).
  - **FREETEXT:** `modelAnswer` (string).
  - **ANNOTATION:** `instruction` (string), `referenceGeometryGeoJson` (string; a GeoJSON
    FeatureCollection/Geometry serialized via `GsonTools`).
  - **NAVIGATION:** `targetGeometryGeoJson` (string; GeoJSON of the target region).

Slice split: MCQ + FREETEXT fields ship in **slice 1**; ANNOTATION + NAVIGATION geometry fields
in **slice 2**. The format holds all four from the start (slice 1 just never populates the
geometry fields).

## 6. Components

- **`AtlasQuiz` / `QuizQuestion`** — plain data model (records/POJOs). No QuPath UI deps.
- **`AtlasQuizIO`** *(UI-free, unit-tested)* — `read(File) : AtlasQuiz` and `write(AtlasQuiz, File)`
  via Gson; validates `formatVersion`, required fields per type, and `correctIndex` bounds; throws a
  clear `IOException` on malformed packs. Geometry fields are opaque strings to `AtlasQuizIO`
  (parsed to ROIs only in the runner/author via `GsonTools`), keeping IO pure and testable.
- **`QuizSlide`** *(small helper)* — opens a `slideUrl` read-only into a viewer: build
  `DziImageServer(URI)`, wrap in `ImageData` with an inferred image type, hand to the viewer.
  Mirrors the existing open path; used by both author and runner. (Image type: reuse the
  `AtlasCase.getImageType` heuristic via a shared static, or `UNSET` — a quiz viewer doesn't need
  a perfect type; calibration comes from `?mpp=` in the URL.)
- **`QuizRunnerWindow`** — the guided sequential run (see §7).
- **`QuizAuthorWindow`** — in-QuPath authoring (see §8).
- **`QuizRevealOverlay`** *(slice 2)* — a custom `AbstractOverlay` (or `BufferedImageOverlay`)
  added to `viewer.getCustomOverlayLayers()` that paints a reference geometry distinctly; removed
  on Next. Never enters the hierarchy.
- **Menu** — two items in `AtlasExtension.installExtension` (Turkish, matching the newer items):
  **"Sınav/quiz çöz…"** (take) and **"Sınav/quiz hazırla…"** (author).

## 7. Taking flow + per-question lifecycle (the important part)

`QuizRunnerWindow`: **Load pack…** → shows "Soru i / N", the prompt, a type-specific input, and
**Göster (Reveal)** / **Sonraki (Next)** / **Önceki (Prev)** + progress.

For each question, on entering it:
1. If `slideUrl` differs from the currently-loaded slide, open it read-only via `QuizSlide`;
   **if the next question shares the slide, do not reload**.
2. Apply `viewport` (slice 2 navigation uses `viewer.setDownsampleFactor(downsample, centerX,
   centerY)`).
3. Show the input: MCQ radios / free-text area / (slice 2) "Cevabınızı slayt üzerine çizin"
   prompt / "Bölgeye gidin" prompt.
4. **Reveal:** MCQ highlights the correct option; free-text shows `modelAnswer`; (slice 2)
   annotation/navigation add the reference geometry as a `QuizRevealOverlay`. Always show
   `explanation`.
5. **Next / Prev:** remove any reveal overlay **and** clear the learner's transient answer
   annotations created for this question, then move. The reference never touches saved annotations;
   self-study **saves nothing**.

## 8. Authoring flow

`QuizAuthorWindow`: **New / Load pack…**, edit `title`/`description`, and a question list with
**Add / Edit / Remove / Move up / Move down**. Adding a question:
- Pick type; fill prompt/explanation and type-specific fields (MCQ options + correct; free-text
  model answer).
- **Slide binding:** the question binds to the **currently-open slide** — capture its server URI
  (which carries `?mpp=` if opened from the atlas) as `slideUrl` and its name as `slideTitle`.
  Optionally capture the current viewport as `viewport`.
- **(slice 2) geometry capture:** for annotation/navigation, the instructor draws the reference
  annotation on the slide; on save, its ROI is serialized to `referenceGeometryGeoJson` /
  `targetGeometryGeoJson` via `GsonTools`, then the drawn annotation is removed (it lives in the
  pack, not the project).
- **Save pack…** writes the JSON via `AtlasQuizIO`.

## 9. Slice decomposition (one spec, plan sequences two slices)

- **Slice 1 — foundation + text types:** `AtlasQuiz`/`QuizQuestion` model, `AtlasQuizIO` (+ unit
  tests), `QuizSlide`, menu wiring, `QuizAuthorWindow` + `QuizRunnerWindow` **shells with MCQ +
  free-text** end to end (author in QuPath → save pack → take it). No overlays, no viewer
  navigation, no geometry serialization — zero risky APIs.
- **Slice 2 — geometry types:** add ANNOTATION + NAVIGATION — geometry capture in the author,
  GeoJSON (de)serialization, `QuizRevealOverlay`, and viewport navigation in the runner.

## 10. Threading & safety

- All viewer/overlay/UI access on the JavaFX thread (mirrors `FocusHeatmap`). Slide opening does
  network I/O (DZI descriptor) — do it on a background daemon thread with a progress indicator and
  `Platform.runLater` for the viewer hand-off, mirroring `AtlasBrowser.openSelected`.
- Windows are non-modal (author/runner are working tools, not blocking dialogs); guard against
  overlapping slide-opens within a window with a simple in-flight flag.

## 11. Testing

Same honest split as the builder:
- **Automated (JUnit):** `AtlasQuizIO` + the model — round-trip write/read, `formatVersion`
  handling (reject unknown), per-type validation (missing fields, `correctIndex` out of range),
  and (slice 2) a GeoJSON round-trip of a reference geometry through `GsonTools` to a ROI and back.
  No network, no JavaFX.
- **Manual (stated, not faked):** authoring, the runner, the reveal overlay, and viewer
  navigation need a running QuPath — verified by authoring a small pack, saving it, and taking it.

## 12. Files (anticipated)

- `src/main/java/com/patolojiatlasi/qupath/quiz/AtlasQuiz.java` — **new** (model)
- `.../quiz/QuizQuestion.java` — **new** (model)
- `.../quiz/AtlasQuizIO.java` — **new** (IO, tested)
- `.../quiz/QuizSlide.java` — **new** (open helper)
- `.../quiz/QuizRunnerWindow.java` — **new**
- `.../quiz/QuizAuthorWindow.java` — **new**
- `.../quiz/QuizRevealOverlay.java` — **new** (slice 2)
- `AtlasExtension.java` — **changed** (two menu items)
- `src/test/java/.../quiz/AtlasQuizIOTest.java` (+ model tests) — **new**
- `README.md` — **changed** (quiz workflow)

## 13. Verified / to-verify APIs

- **Verified present (0.6.0):** `viewer.getCustomOverlayLayers().add/remove`, `BufferedImageOverlay`,
  `AbstractOverlay`, `viewer.setDownsampleFactor(double, double, double)`, `GsonTools.getInstance()`,
  `GeometryTools`, `new ImageData<>(server, ImageType)`, `DziImageServer(URI)`.
- **To verify at plan/impl time:** exact `AbstractOverlay.paintOverlay(...)` signature for
  `QuizRevealOverlay`; ROI→GeoJSON→ROI concretely (`GsonTools` serializing a `PathObject`/ROI, or
  `ROI.getGeometry()` + a GeoJSON feature); how the author reads the currently-open server URI +
  current viewport from `QuPathViewer`.

## 14. Open questions

None blocking. The deferred "set-list export" from the builder is effectively subsumed: a
quiz-pack *is* a shareable, slide-referencing course artifact.
