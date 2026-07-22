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
