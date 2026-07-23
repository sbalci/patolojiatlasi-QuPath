# Navigation-research upgrade — design (rename + magnification + annotations + decision + metrics)

- **Date:** 2026-07-22
- **Repo:** `patolojiatlasi-QuPath`
- **Status:** Approved direction (user). Phased; each phase its own branch + SDD + review.
- **Motivation:** the literature review *Tracking Pathologists' Slide Navigation and Diagnostic
  Impact* (`brainstorming/…pdf`) validates our viewport-logging approach and identifies gaps: it
  frames navigation as **x, y, m (magnification)**, shows the **strongest accuracy correlates are
  zoom metrics** (avg zoom, zoom variance, "magnification percentage", scanning rate), uses
  **expert annotations as ground-truth ROIs**, ties navigation to the **diagnostic decision**
  ("diagnostic impact"), and warns consistency ≠ pixel overlap (evaluate coverage + attention +
  decision). This upgrade closes those gaps.

## Terminology (Phase 0 — rename, do first)

Rename the user-facing feature from **"Kör kayıt"** (blind recording) to **"Gezinme kaydı"**
(navigation recording) — friendlier, and matches the literature's "navigation tracking" framing.
- Menu item `FocusHeatmap.buildMenu`: "Kör kayıt (araştırma) — …" → **"Gezinme kaydı (araştırma) —
  sessizce kaydeder, ısı haritası gösterilmez"**.
- Flag action `AtlasExtension`: "Mevcut projeyi araştırma projesi yap (kör kayıt)…" →
  "…(gezinme kaydı)…".
- Consent text (`AtlasExtension.onProjectChanged`): "…araştırma amaçlı kör odak kaydı…" →
  "…araştırma amaçlı gezinme kaydı…".
- Builder checkbox (`ProjectBuilderDialog`): "Araştırma projesi — kör odak kaydı (blinded)" →
  "Araştırma projesi — gezinme kaydı (blinded)".
- Docs: README.md, SHARING.md, analysis/README.md — every user-facing "kör kayıt"/"kör odak kaydı".
- **Internal code names stay** (`blinded*`, `atlas-research`, schema `atlas-focus-contribution/*`,
  `atlas-focus/` folder, zip name) — renaming those breaks data/schema compat for no user benefit.
  Only the Turkish UI/doc strings change. (The audit's docs pass enumerates every occurrence.)

## Phase 1 — magnification + finer heatmaps + navigation metrics (analysis-heavy)

**Recorder (small):**
- Store the **downsample (zoom)** per scanpath point. Current point `[tRelMs, cx, cy, w, h]` →
  `[tRelMs, cx, cy, w, h, dsMilli]` where `dsMilli = round(viewer.getDownsampleFactor()*1000)`
  (integer, keep JSON compact). `w`/`h` already encode zoom, but an explicit ds is exact and matches
  the x,y,m literature. Schema bump `atlas-focus-contribution/3` → `/4` (additive; older /3 fragments
  have 5-element points → analysis treats missing ds as derived `imageWidth/w`).
- Raise `GRID_MAX` 256 → **512** (cheap; ≤ ~1 MB grid) so the in-extension heatmap is less coarse.
  (Fine detail still comes from the scanpath, below.)

**Analysis (Python + R, additive — the real value):**
- **Scanpath-rasterized heatmaps at chosen resolution.** Build the dwell heatmap FROM the scanpath
  (exact viewport rect per point × dwell Δt) at a user-set grid size (default 512, or from `--res`),
  independent of the recorded grid — so **40×+ navigation is faithfully resolved**. Fall back to the
  recorded grid for /2 fragments.
- **Magnification-split analysis.** Bin points by magnification (from ds/`getMagnification`); produce
  per-bin heatmaps + "attention at each magnification" — answers "at what scale did they inspect
  region X?" (Chakraborty).
- **Zoom-metric family** (per session): `avgZoom`, `zoomVariance`, `magnificationPercentage`
  (fraction of consecutive same-or-increasing-zoom transitions — Ghezloo's accuracy correlate),
  `scanningRatePxPerMin` (pan distance at ~fixed zoom — Drew's accuracy correlate), `drillingRate`
  (zoom-change events/min), `zoomRange`. Add to `metrics.csv`.
- **Path descriptors** (Roa-Peña): `pathVelocityPxPerSec` (median), `linearity` (net displacement /
  total path length), `searchFocusRatio` (fraction of time below a dwell/zoom "focus" threshold).
- **Consistency at multiple levels** (Xu/Nan): keep the pixel similarity matrix but ADD
  `coincidenceLevel` (fraction of high-dwell cells shared by ≥2 readers — Roa-Peña's ~70.5%) and
  `regionCoveragePct` per reader vs a reference/consensus.

## Phase 2 — annotation capture + comparison (#5)

**Recorder:** on each blinded save (checkpoint/switch/stop/shutdown), export the slide's current
annotations as **anonymized GeoJSON** into the fragment (a new `"annotations"` field =
FeatureCollection) via the existing `quiz/QuizGeometry.toGeoJson`/`GsonTools`. Include each
annotation's `name`/`pathClass` and any `description`/comment text. Anonymized like everything else
(no username; geometry + class + comment only). Reset per slide with the map. Schema stays `/4`
(additive field). Reuse `hierarchy.getAnnotationObjects()`; read on the FX thread at save time.

**Analysis:** per (slide, session): `nAnnotations`, `annotatedAreaPx`; **annotation-vs-navigation**
(did dwell concentrate inside their own annotations? — dwell-in-annotation / total); **cross-user
annotation agreement** (pairwise IoU of annotated regions + a coincidence level); **annotation vs
expert reference** (the `--roi`/`--reference` path already exists — now compare a reader's
*annotations* to the reference too). New `annotations_<slug>.csv`.

## Phase 3 — per-case decision capture + navigation↔accuracy (diagnostic impact)

**Recorder / UI:** a lightweight **"Bu slayt için tanı/karar gir…"** action (menu + optional prompt
when leaving a slide) recording per (slide, session): `diagnosis` (free text), optional `confidence`
(1–5), `decisionMs` (timestamp). Reuse the quiz's small form pattern (`QuizAuthorWindow`-style). Store
as an anonymized `"decision"` object in the fragment (schema `/4` additive). Natural "cover task"
(the paper's recommended way to reduce reactivity) — the reader is doing a real diagnostic task.

**Analysis:** join decisions with an optional **answer key** (`--key key.csv`: slideKey → correct dx)
→ per-reader **accuracy**, then correlate the Phase-1 navigation metrics with accuracy
(scanning-rate/zoom/coverage vs correct-vs-incorrect) — the paper's core "navigation ↔ diagnostic
accuracy" analysis. New `decisions.csv` + a correlation summary.

## Cross-cutting invariants (unchanged)

- Data-only (JSON only, no PNG for blinded maps); anonymized (no username, sha256 non-http slideKey,
  date-only; annotations/decisions carry no identity). All new writes go through `buildBlindedJson`
  only. Best-effort/no-throw. One retained `FocusHeatmap` instance. Python↔R numeric parity + shared
  output contract. Stdlib for `tools/`, full deps for `analysis/`.

## Phasing / order

0. **Rename** (Gezinme kaydı) + **audit fixes** (from the running re-eval) — quick, do first.
1. **Turkish `SHARING.tr.md`** (mirrors SHARING.md; can land alongside Phase 0).
2. **Phase 1** (magnification + finer heatmaps + nav metrics).
3. **Phase 2** (annotations).
4. **Phase 3** (decision + accuracy).

Each phase: brief plan → SDD build (implementer + review) → merge. User drives push.

---

# Phase 3 — refined design (2026-07-23, map-grounded + user-approved)

Phases 0–2 are merged. This section supersedes the one-paragraph Phase 3 sketch above with the
decisions locked with the user (2026-07-23) and grounded in a 4-way code-mapping pass + an advisor
review that caught a runtime-fatal flaw in the first cut. **Read this section, not the sketch, when
planning/implementing Phase 3.**

## User-approved decisions

1. **Grading model = free text + hand-grade** (no picklist, no synonym auto-matching). The reader types
   a free-text diagnosis; the analysis never string-matches a diagnosis to a key. This deliberately
   erases the Turkish İ/ı case-folding hazard entirely.
2. **Capture trigger = menu action + auto-prompt on leaving a slide** (when no decision is recorded yet),
   the auto-prompt configurable off via the project sidecar.

## Recorder (Java) — `FocusHeatmap.java`, `DecisionDialog.java` (new), `AtlasExtension.java`, `research/BlindedResearch.java`

### Decision data + storage
- New immutable holder `Decision(String diagnosis, Integer confidence, long decisionMs)` (confidence
  nullable = "not given"). `decisionMs` is **relative to `blindedSlideStartMs`** (= decision latency),
  never an absolute wall-clock — matching how `path` timestamps and `date` are anonymization-safe.
- One `private volatile Decision currentDecision;` field (single volatile ref ⇒ atomic, no torn read from
  the off-FX shutdown hook). Reset to `null` at the **two** existing per-slide reset points:
  `startBlinded()` (~383) and `switchTo()`'s blinded branch (~674) — mirroring `blindedPath`/`blindedTicks`.
- `recordDecision(String diagnosis, Integer confidence)` stamps `decisionMs = now − blindedSlideStartMs`
  and sets `currentDecision` (FX thread only). `getCurrentDecision()` accessor for dialog pre-fill.

### Schema — **stays `atlas-focus-contribution/5`** (do NOT bump to /6)
`decision` is a purely **additive**, fragment-level field on schema/5 (like `annotations` semantically,
but no schema-number change). Rationale (advisor): bumping to /6 would make an **older analysis checkout**
(allowlist /1–/5) **silently drop every fragment** a new recorder writes — including decision-less ones —
because `load_fragments` skips non-allowlisted schemas without warning; researcher-JAR vs
coordinator-analysis version skew is realistic and silent data loss is the worst failure. Staying /5 keeps
old-analysis-reads-new-fragments and matches io.py's "schema = field-shape, degrade gracefully" design.
Add a doc note to the schema/5 javadoc that it MAY carry an optional `decision` object (added 2026-07);
keep the number 5. On the analysis side, no `SCHEMAS` allowlist change; a `get_decision` accessor defaults
gracefully to blank for fragments/schemas without the field.

### `decision` fragment field (written by `buildBlindedJson`)
```json
"decision": { "diagnosis": "<free text>", "confidence": <1-5 or null>, "decisionMs": <int, rel to slide start> }
```
Omitted entirely (no key) when `currentDecision == null`. A plain-value object → built directly as a small
`Map`/`JsonObject` (no Gson toJson-string+reparse dance; that's only needed for nested QuPath geometry).

### Save-path refactor (REQUIRED — enables the deferred leave-prompt)
The leave-prompt cannot call `showAndWait()` synchronously inside `switchTo`: `tick()` runs inside a
JavaFX `Timeline` KeyFrame (FocusHeatmap.java:326), and `showAndWait()` during an animation pulse throws
`IllegalStateException`. The prompt must be **deferred via `Platform.runLater`**, which means the deferred
continuation builds slide-A's fragment *after* the viewer has already switched to B — so fragment-building
must read an explicit snapshot, not instance fields:
- Introduce immutable `BlindedSnapshot { String uri; FocusMap map; List<int[]> path; boolean
  pathTruncated; Double baseMagnification; JsonElement annotations; long slideStartMs; }`.
- `currentSnapshot()` builds it **eagerly** from instance fields (copy `blindedPath`; compute `baseMag`;
  call `buildAnnotationsFeatureCollection()` now — all while still on the current slide).
- Change `buildBlindedJson(String uri, FocusMap map)` → `buildBlindedJson(BlindedSnapshot snap, Decision
  decision)` reading only the snapshot + decision. Add `writeBlindedFragmentSync(BlindedSnapshot,
  Decision)` = the file-writing half of `saveBlindedSync`.
- Update all three existing synchronous callers to pass `currentSnapshot(), currentDecision`:
  `checkpointBlinded()` (~538), `saveBlindedSync()` (~918, now delegates to `writeBlindedFragmentSync`),
  `flushOnShutdown()` (~577 — `currentSnapshot()` reads off-FX with the same torn-read tradeoff already
  documented there; `currentDecision` is volatile ⇒ safe).

### Leave-prompt flow (deferred)
- `switchTo()` blinded-save branch: if `shouldPromptDecisionOnLeave()` → stash `snap = currentSnapshot()`
  + `leavingUri`; set `pendingDecisionSave = true`; `Platform.runLater(() ->
  deferredDecisionPromptAndSave(snap, leavingUri))`; **do not** save A synchronously here. Else → the
  existing synchronous `writeBlindedFragmentSync(currentSnapshot(), currentDecision); deleteCheckpoint();`.
- `shouldPromptDecisionOnLeave()` = `decisionPromptOnLeave` (sidecar flag, default true) **and**
  `currentDecision == null` **and** `currentUri ∉ decisionPromptedSlides` **and**
  `currentMap.getTotalWeight() >= MIN_DWELL_FOR_PROMPT_MS` (a short-glance gate to limit reactivity/nuisance)
  **and** `!pendingDecisionSave` (rapid A→B→C: while a prompt is pending, later switches save normally).
- `deferredDecisionPromptAndSave(snap, leavingUri)` (runs between pulses ⇒ `showAndWait` legal): show
  `DecisionDialog` (WINDOW_MODAL owned by the main stage ⇒ blocks the viewer, so no re-switch while open);
  if a decision entered → `decisionMs = now − snap.slideStartMs`, build `Decision`; else →
  `decisionPromptedSlides.add(leavingUri)` (declined; don't nag on revisit). Then
  `writeBlindedFragmentSync(snap, decision)`; `deleteCheckpoint()`; `finally { pendingDecisionSave = false; }`.
- `tick()` early-returns while `pendingDecisionSave` (serializes; B doesn't accumulate until A is saved).
- **Last slide before stop/project-close gets no auto-prompt** (stop isn't a switch; a modal during
  project-close is unsafe) — the reader uses the menu for the final slide. Document this limitation.

### Menu action + dialog
- `DecisionDialog` (new class) copies the repo's hand-rolled-`Stage` house style (like
  `QuizAuthorWindow.QuestionDialog`/`CitationDialog`): `Stage` + `Modality.WINDOW_MODAL` + owner + VBox/HBox
  + a `TextArea` (diagnosis) + a 1–5 confidence row (5 `RadioButton`s in one `ToggleGroup`, plus an
  unselected = "not given" default) + plain OK/Cancel `Button`s + a static `show(qupath, existingOrNull)`
  returning a `DecisionInput(String diagnosis, Integer confidence)` or `null`. No `Dialog`/`ButtonType`/
  fxtras (none used in this repo). Diagnosis required (non-blank) to OK; confidence optional.
- Menu item **"Bu slayt için tanı/karar gir…"** inside `FocusHeatmap.buildMenu()`'s submenu (it's part of
  the blinded session), **enabled only while blinded** (mirror the existing `setDisable` pattern in
  `startBlinded`/`stopBlinded`). Routes to `focusHeatmap.promptDecisionInteractive()`, which (FX thread,
  between pulses) shows `DecisionDialog` pre-filled from `getCurrentDecision()` and calls `recordDecision`.
- `BlindedResearch.decisionPromptOnLeave(projectDir)` reads the sidecar (`"decisionPromptOnLeave"`, default
  **true**); `AtlasExtension` passes it into `FocusHeatmap` when starting blinded recording.

## Analysis (Python + R) — `io.py`/`blinded_focus.R`, `analyze.py`, `selftest.*`, `run_analysis.R`

Scope = Group A (decisions.csv) + Group B (correlation). Groups C1–C6 (case-metadata, expertise strata,
GLMM/GEE/clogit, navigation-efficiency) are **out of scope** — blocked on inputs that don't exist yet;
documented as future work only.

### Accessors (mirror the `get_annotations`/`load_labels` templates)
- `get_decision(fragment)` → the `decision` dict if shape-valid (`isinstance dict`, has `diagnosis`), else a
  fresh empty default; graceful for /1–/5 fragments lacking the field.
- `load_answer_key(csv_path)` → `{slideKey → correctDx}` (near-verbatim `load_labels`, header sniff
  `slidekey|slide_key|slide`). **Display-only** — provides the reference answer beside the reader's; NEVER
  auto-marks correct.
- `load_graded(csv_path)` → `{(slideKey, sessionId) → correct(0/1)}` — the hand-graded input for the
  correlation. Join key is the **stable `sessionId`**, not the display label (advisor: a different
  `--labels` between passes must not misalign grades).

### `decisions.csv` (Group A) — one row per (slide, session), written once after the outer loop (like
`metrics.csv`, not per-slide)
Columns: `slide` (= slideKey), `sessionId` (stable join key), `session` (human label), `diagnosis`,
`confidence`, `confidenceScaled` (`(confidence−1)/4` or blank), `decisionMs`, `decisionLatencyMs`
(= `decisionMs`; **caveat:** ≈ `durationMs` when captured via the leave-prompt — documented, not a bug),
`correctDx` (from `--key`, blank if none), `correct` (from `--graded` join, else **blank** for offline
hand-grading). Always written (no gate) when any fragment carries a decision; skipped with a stderr
`warning:` if there are zero decisions anywhere. Blank-not-crash discipline throughout (`_sanitize_nan`).

### Correlation (Group B) → `nav_accuracy.csv` (tidy, one row per nav metric) + a gated
`## Navigation ↔ diagnostic accuracy` section in `summary.md`
Computed only when graded decisions exist (`--graded` supplied and ≥1 joined `correct`); else the section
is omitted and `nav_accuracy.csv` not written. Parity-safe stats only:
- **Point-biserial r = plain Pearson** (numpy `corrcoef`/`cor(method="pearson")`; NOT `pointbiserialr`)
  between `correct` (0/1) and each nav metric: primary `avgZoom, zoomVariance, magnificationPercentage,
  scanningRatePxPerMin, drillingRatePerMin` (Ghezloo/Drew correlates); secondary `coveragePct,
  dwellInAnnotationPct, enrichmentRatio, searchFocusRatio, linearity, pathVelocityPxPerSec, entropy,
  transitionEntropy`. **Hard guard:** blank unless `n ≥ 5` and `var(correct)>0` and `var(navMetric)>0`.
- **Group mean/median comparison** per metric (`meanCorrect, meanIncorrect, medianCorrect,
  medianIncorrect, meanDiff`) — the recommended primary readout at tiny n. `meanDiff`/`medianDiff` require
  `n ≥ 2` in each group; per-group means soft-guarded (printed with n, flag "n<5 → descriptive only").
- **accuracyRate** per session and per slide (soft guard, printed with n).
- **Confidence–accuracy:** `calibrationGap = mean(confidenceScaled) − proportionCorrect`, `brierScore =
  mean((confidenceScaled − correct)²)` (soft), and `confidenceAccuracyR` = Pearson(confidenceScaled,
  correct) (hard-guarded like B2).
- **No p-values / CIs / Wilcoxon** (cross-language parity + not meaningful at pilot n). Report r + n only.
- One honest-limits paragraph in the summary header (viewport-only null-result precedent; cohort-composition
  dependence; pilot-scale caveats).

### CLI
Add `--key key.csv` and `--graded graded.csv` next to `--labels` in both `analyze.py`'s argparse and
`run_analysis.R`'s `.parse_args`; thread `key_csv=/graded_csv=` through `analyze()`.

### Parity discipline
Cross-language numeric parity is a **manual** verification (no mechanical cross-toolkit assert exists). The
implementer MUST run both toolkits on one shared fixture (incl. synthetic decisions + a graded CSV) and
diff `decisions.csv`/`nav_accuracy.csv` numerically (typed compare, not byte-diff — `TRUE/True`,
`40/40.0`). Add within-toolkit assertions to both `selftest.py`/`selftest.R` (decisions row count/columns;
`correct`-driven point-biserial on a fixture where a nav metric is deliberately separable by outcome).

## Docs
- `SHARING.md` + `SHARING.tr.md`: a decision-note **PHI warning** (same class as the annotation-note one)
  and a coordinator **two-pass hand-grading workflow** (run → fill `correct` in `decisions.csv` → re-run
  with `--graded`), plus `--key` (display) vs `--graded` (grades) explanation and the last-slide/menu note.
- `analysis/python/README.md` + `analysis/R/README.md`: `decisions.csv`/`nav_accuracy.csv` columns,
  `--key`/`--graded`, the guard policy, the `decisionLatencyMs ≈ durationMs` caveat, the honest-limits note.
- Update this spec's Phase 3 status and CLAUDE-adjacent notes as needed.

## Merge gate (advisor)
Phase 3's failure modes (showAndWait context, modality scope, re-entrancy, deferred-save ordering) are
**runtime-only** — invisible to `gradlew build` + code review. A **live QuPath smoke test of BOTH capture
paths (menu + leave-prompt)** is required before merge. This is the user's action (GUI); do not merge until
confirmed.

## Task breakdown (SDD)
1. **Recorder core** — `Decision` holder, `currentDecision` field + resets, schema/5 doc note,
   `BlindedSnapshot` + `currentSnapshot()` + parameterized `buildBlindedJson`/`writeBlindedFragmentSync`
   (update all 3 callers), `recordDecision`/`getCurrentDecision`. (No UI, no prompt yet.) Gate: `gradlew build`.
2. **`DecisionDialog`** — standalone dialog class (Stage house style), `show(...)` → `DecisionInput`/null.
   Gate: `gradlew build`.
3. **Menu + leave-prompt wiring** — menu item + `promptDecisionInteractive` (menu path), deferred
   `switchTo` leave-prompt (`BlindedSnapshot` stash + `Platform.runLater` + `pendingDecisionSave` +
   `decisionPromptedSlides` + min-dwell gate + `tick()` guard), `BlindedResearch.decisionPromptOnLeave` +
   `AtlasExtension` wiring. Gate: `gradlew build` + careful re-entrancy review.
4. **Python analysis** — `get_decision`/`load_answer_key`/`load_graded`, decision row fields, `decisions.csv`,
   `--key`/`--graded` CLI, correlation (`nav_accuracy.csv` + summary section) with guards; selftest asserts.
   Gate: `selftest.py` green.
5. **R analysis** — line-for-line mirror of Task 4 at the corresponding locations; selftest asserts;
   manual Python↔R parity diff on a shared fixture. Gate: `selftest.R` green + parity diff clean.
6. **Docs** — SHARING EN+TR, analysis READMEs, spec status. Gate: link/citation checks if applicable.

Final: whole-branch adversarial review (Workflow, multi-lens: privacy/anonymization, re-entrancy &
deferred-save ordering, analysis correctness, Python↔R parity, backward-compat /1–/5) → fix → **user live
smoke test** → merge (no-ff) → user drives push.
