# Guided Teaching Tour — design spec (2026-07-25)

- **Repo:** `qupath-extension-atlas` (`d:/patolojiatlasi-QuPath`). Backlog **#6** (`docs/superpowers/atlas-research-backlog.md`).
- **One-line:** author an ordered sequence of teaching **stops** — each a slide + captured viewport + narration **or** question + optional highlight — and let a learner step through them (Next/Prev), or take it as a self-check quiz.
- **Approach (user-chosen in brainstorming):** build this as an **additive extension of the existing Quiz system**, not a separate Tour feature — a stop is a `QuizQuestion`; a "tour" is an `AtlasQuiz` that contains narration stops + captured viewports + highlights. Reuses `AtlasQuiz`/`AtlasQuizIO`/`QuizQuestion`/`QuizSlide`/`QuizRevealOverlay`/the author + runner windows. **Existing quizzes keep working unchanged.**

## Decisions (from the brainstorming dialogue)
1. **Unified quiz/tour** on the Quiz model (no duplicate Tour system).
2. **Mixed stops:** some narrate, some ask a question — a walkthrough with checkpoints. Narration = a new `QuizType.NARRATION` (caption-only, no answer).
3. **Same slide reusable across stops** (the Quiz already allows this; the player keeps the slide open between consecutive same-slide stops — only re-applies the viewport/highlight).
4. **Per-stop:** an **author-captured viewport** (today the author never sets one) + an **optional highlight** region (any stop type).
5. **Slides: atlas + local.** Atlas DZI URLs stream anywhere (fully shareable); local-slide stops are for personal tours and **degrade gracefully** on a machine without the file.
6. **allowBack (quiz-level, author-set, default true):** when false the player is **forward-only** (Önceki/Prev disabled) — for assessments where the learner must not return to change an earlier answer.

## Data model (all additive — existing files/quizzes unaffected)
- **`QuizType`** — add **`NARRATION`** (5th value): a caption-only stop, no answer/grading.
- **`QuizQuestion`** — add **`highlightGeoJson`** (`String`, nullable): an optional per-stop highlight ROI shown as a read-only overlay (any stop type), captured from a selected annotation via the existing `QuizGeometry.toGeoJson`. The existing **`viewport`** field (`downsample`, `centerX`, `centerY`) is now **captured by the author** (it already exists and is applied by the runner). Per-stop text reuses **`prompt`** (the question, or — for `NARRATION` — the caption) + **`explanation`** (the reveal / teaching point).
- **`AtlasQuiz`** — add **`allowBack`** (`boolean`, default `true`). `title`/`description` already exist (reused as the tour title/intro).
- **`AtlasQuizIO`** — **backward-compatible versioning.** Today `validate()` requires `formatVersion == FORMAT_VERSION` exactly. Change: bump `FORMAT_VERSION` to **2**, and accept **`formatVersion ∈ {1, 2}`** on read (a v1 quiz reads fine; new tour-quizzes write v2). Validation additions: a `NARRATION` stop needs only a non-blank `prompt` (the caption) + `slideUrl` (no answer fields); `highlightGeoJson`/`viewport`/`allowBack` are optional (default `allowBack=true` when absent). Existing MCQ/FREETEXT/ANNOTATION/NAVIGATION validation is unchanged. Add unit tests for: v1-still-reads, NARRATION round-trip, highlightGeoJson round-trip, allowBack default-on-absent.

## Slide open — the one genuinely new piece of plumbing (atlas + local)
- **Capture** (`QuizSlide.currentSlideUrl(viewer)`) already returns the current slide's first URI for **both** atlas DZI URLs and local file URIs — reused as-is for "capture current slide/view".
- **Open** — `QuizSlide.openAsync` today **always** builds a `DziImageServer(URI.create(url))` (atlas-only). Add a dispatched opener `QuizSlide.openSlideAsync(qupath, uri, onDone, onError, stillWanted)`:
  - **Atlas DZI** (uri ends `.dzi`, or is the atlas http(s) DZI form) → the existing `DziImageServer` path.
  - **Local** (`file:` / any non-DZI uri) → build via QuPath's standard server (`ImageServerProvider.buildImageServer(uri, BufferedImage.class)` / `ImageServers`), off the FX thread, then `setImageData` on it — same async+`Platform.runLater`+`stillWanted` discipline as `openAsync`.
  - **Already open** — if `currentSlideUrl(viewer)` already equals the stop's uri, skip the reload (existing runner optimization; extends the "same slide across stops" smoothness).
  - **Graceful-missing** — a build/open failure (shared tour referencing a local slide the recipient lacks) routes to `onError` → the player shows the stop's narration + a "Slayt açılamadı: <title>" note and lets the learner continue. Never dead-ends.

## Author — extend `QuizAuthorWindow` (its `QuestionDialog`)
Additive to the existing add/edit-question dialog:
- **"Bu görünümü yakala"** button — captures the active viewer's viewport into the stop's `Viewport` (`viewer.getDownsampleFactor()` + center-pixel), shown with a captured/uncaptured status label. Works for atlas + local slides.
- **`NARRATION` type** in the type `ChoiceBox` — renders only the caption (`prompt`) + optional highlight; hides the answer/geometry widgets.
- **Optional per-stop highlight** — a "Vurguyu seçili anotasyondan al" button (available for any type) captures the selected annotation's geometry into `highlightGeoJson` (reusing `QuizGeometry.toGeoJson`); clearable; status label.
- **"Geri gitmeye izin ver"** checkbox on the outer author window (quiz-level `allowBack`, default checked).
- Reused unchanged: reorder/edit/delete, bind-current-slide, title, description, save/load (`AtlasQuizIO`), the `Stage`+VBox house style.

## Play — unified player (user-confirmed 2026-07-25)
**Extend the existing `QuizRunnerWindow`** into the single player for both self-check and guided tour (user-confirmed). The runner already does slide-open + viewport-apply (`afterSlideReady`) + per-type render + reveal overlay + **Önceki/Sonraki** + **progress** — a guided tour is just a quiz that also contains `NARRATION` stops, captured viewports, and highlights, so a separate window would duplicate most of it. All changes below are **additive and guarded so existing self-check behavior stays byte-for-byte identical** for a quiz that has no narration/highlight/viewport and `allowBack=true`.

Additive changes to the runner:
- **`NARRATION` render case** — show `prompt` as the narration text + (on "Göster") `explanation`; no answer widgets; "Sonraki" advances. Existing types render exactly as today.
- **Highlight overlay** — when the current stop has `highlightGeoJson`, draw it via `QuizRevealOverlay` (already used for ANNOTATION/NAVIGATION reveals) with a show/hide toggle.
- **`allowBack`** — gate `prevBtn.setDisable(...)`: when the loaded quiz's `allowBack` is false, Önceki stays disabled (forward-only) for all stops.
- **Viewport** — already applied in `afterSlideReady`; now meaningful because the author captures it.
- **Local slides** — route slide opens through the new `openSlideAsync` dispatcher (graceful-missing).

## Menu (`AtlasExtension`, the "Sınav / Quiz" submenu)
- Keep **"Çöz…"** (self-check, quiz-framed: progress "Soru X / N") + **"Hazırla…"** (the extended author, now tour-capable). **Add "Rehberli tur oynat…"** — launches the same extended runner in **tour presentation** (progress "Durak X / N", narration-first framing). One player, two entry points differing only in presentation wording — no new play mechanism. (Exact Turkish label wording is easily adjusted later.)

## Error handling / invariants
- All slide opens are off-FX-thread + `Platform.runLater` + `stillWanted`-cancellable (reuse `QuizSlide`'s discipline); a stale in-flight open for a closed/advanced player is dropped.
- `QuPathViewer.setImageData` bypasses the unsaved-work prompt → reuse the runner's existing changed-work guard on slide switches.
- Graceful-missing local slide → caption + note, never a crash or dead-end.
- Additive-only to the on-disk format; v1 files always still read.

## Testing
- **`AtlasQuizIO`** unit tests (extend the existing suite): v1-reads-under-v2-reader, NARRATION round-trip + validation, highlightGeoJson round-trip, allowBack default-on-absent, viewport round-trip.
- **`QuizSlide.openSlideAsync`** dispatch: unit-test the atlas-vs-local URI classification (pure function) — the actual open is GUI/network.
- Windows (author capture, guided play, allowBack forward-only, graceful-missing) are GUI — **user smoke-tests** (per `feedback_user_tests_qupath`).

## Out of scope (v1)
- Timed auto-advance / kiosk presentation mode (manual Next/Prev only — trivial to add later).
- A relink UI for missing local slides (learner opens it manually if they have it; the viewport still applies once it's showing).
- Per-stop allowBack (quiz-level only).
