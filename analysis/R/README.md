# blinded_focus — R analysis toolkit

Standalone R toolkit — the sibling of `analysis/python/` — that turns anonymised QuPath atlas
**blinded-focus** fragments (schema `atlas-focus-contribution/{1,2,3,4,5}`) into per-session
metrics (including a Phase-1 zoom/navigation metric family and a Phase-2 annotation/cursor metric
family), publication figures, cross-user agreement, reference/ROI comparison, and scanpath
analysis.

This is **not** part of the QuPath extension and does not import from it, nor from
`tools/aggregate-focus.py` or `analysis/python/` — it only shares the fragment JSON shape and the
metric formulas by convention (deliberately pinned to match, so the two toolkits are directly
comparable — see "Parity with the Python toolkit" below).

## Install

```bash
Rscript requirements.R
```

Checks for (and installs if missing) `jsonlite`, `dplyr`, `tidyr`, `ggplot2`, `irr`, `proxy`. R
4.4+ recommended.

## Verify

```bash
Rscript selftest.R
```

`selftest.R` synthesizes 4 sessions on one slide, one per accepted schema: schema/5 (8-element
path with cursor `mouseX`/`mouseY` + varying zoom + `baseMagnification`, plus an `annotations`
FeatureCollection overlapping its own dwell center), schema/4 (6-element path, varying zoom, no
mouse, plus the *same* annotation rectangle and a deliberately bouncing path for a non-trivial
re-entry count), schema/3 (5-element path, w-proxy zoom, no annotations), and schema/2 (no path,
no annotations). One session is designated as `--reference`. Runs the full `analyze()` pipeline
into a temp directory, and asserts the output contract described below (symmetric compare matrix
with a 1.0 diagonal, similar-pair CC > dissimilar-pair CC, high reference-vs-itself NSS/CC,
scanpath diagonal 1.0, Phase-1 zoom columns populated/blank as expected, `magnificationPercentage`
in `[0,1]`, a scanpath-rasterized fine heatmap PNG, valid PNGs, `.zip` input support, Phase-2
annotation/cursor columns populated/blank as expected, a symmetric annotation IoU matrix with a
1.0 diagonal for the annotated sessions, and direct regression asserts for the two literature-
review bug fixes — `coincidence_level`'s visited-footprint denominator and
`magnification_percentage`'s strict-increase tie fix). Exits non-zero (`quit(status = 1)`) on any
failure.

## Usage

```bash
Rscript run_analysis.R <input...> --out DIR \
    [--reference SESSIONID] [--roi roi.geojson] [--labels labels.csv] [--key key.csv] \
    [--graded graded.csv] [--figures] [--res 512] [--magbands 3] \
    [--magband-scheme canonical|tercile]
```

- `<input...>` — one or more fragment JSON files, directories (recursively globbed for
  `*.json`), and/or `.zip` archives (as sent by a participant), in any mix.
- `--reference SESSIONID` — treat this session's dwell grid (thresholded at `0.1 * max`) as the
  attended-map ground truth; every other session on the same slide is scored against it.
- `--roi roi.geojson` — a QuPath-exported GeoJSON polygon (image-pixel coordinates); rasterized
  onto each slide's grid (cell-center point-in-polygon, even-odd rule, holes supported) and used
  as the reference mask instead of (or alongside) `--reference`.
- `--labels labels.csv` — a `sessionId,label` CSV (optional header row) mapping anonymous
  `sessionId`s to human-readable labels for the output files. Identity mapping stays out-of-band
  — the fragment data itself carries no participant identity.
- `--figures` — also render per-(slide, session) PNGs (heatmap, scanpath overlay,
  coverage-over-time, scanpath-rasterized fine heatmap, magnification-band heatmaps) under
  `<out>/<slide-slug>/`.
- `--res N` (default 512) — longest-side resolution for the scanpath-rasterized fine heatmap and
  the magnification-band heatmaps (aspect-preserving; see `.res_grid_dims`).
- `--magbands N` (default 3) — number of within-path zoom bands (terciles) for the
  **tercile-fallback** magnification-split analysis (see `zoom_band_labels`). Ignored for sessions
  using the canonical scheme (always 7 fixed bands — see `--magband-scheme` below).
- `--magband-scheme canonical|tercile` (default `canonical`, Tier 2 B3) — band scheme for
  `magbands_<slug>.csv`. `canonical` (default) uses fixed true-objective-magnification bands
  (`[1,2,4,10,20,40]`x cut points, 7 labeled bands) for every session whose `baseMagnification` +
  per-point `dsMilli` are computable, auto-falling back to the `tercile` (within-path quantile)
  scheme per-session otherwise. `tercile` forces the old within-path quantile scheme for **every**
  session regardless of `baseMagnification` availability (pre-B3 behavior). Atlas DZI slides have
  a null `baseMagnification`, so their sessions always use `tercile` even under the default. See
  "Phase 3 enrichment" below for the full rationale.
- `--key key.csv` — a `slideKey,correctDx` CSV (optional header row). **Display-only**: populates
  `decisions.csv`'s `correctDx` column beside the reader's own `diagnosis` for a human to compare;
  never string-matched against `diagnosis` to derive `correct`.
- `--graded graded.csv` — a `slideKey,sessionId,correct` CSV (optional header row; `correct`
  parsed case-insensitively from `1`/`0`/`true`/`false`/`yes`/`no`/`correct`/`incorrect`). The
  **only** source of `decisions.csv`'s `correct` column (hand-graded, never auto-derived from
  comparing `diagnosis` to `correctDx`) and the switch that enables the navigation↔accuracy
  correlation (`nav_accuracy.csv` + the `summary.md` section). Joined by the stable
  `(slideKey, sessionId)` pair, never the display label.

Also usable as a library: `source("blinded_focus.R")` gives you `load_fragments`, `resample_nn`,
`cc`/`sim`/`kld`/`nss`/`auc_judd`/`iou`, `visited_sequence`/`levenshtein_sim`, `mean_pairwise_cc`/
`icc`, `raster_from_path`/`point_zoom`/the zoom-metric family/`zoom_band_labels`,
`coincidence_level`/`region_coverage_pct`, `get_annotations`/`rings_from_feature_collection`/
`rasterize_roi`/`annotations_area_px`, `dwell_in_mask_pct`/`enrichment_ratio`/
`annotation_reentry_count`, `has_mouse_data`/`cursor_over_slide_pct`/`mouse_viewport_coupling_px`,
`get_decision`/`load_answer_key`/`load_graded`, the `plot_*` ggplot2 builders, and the top-level
`analyze()` pipeline function directly. **Phase 3 enrichment** additions: `idle_step_mask`/
`idle_ms`/`active_span_ms`, `mean_abs_turn_angle_deg`/`turn_angle_entropy`,
`mouse_path_length_px`/`mouse_velocity_px_per_sec`, `active_fraction_pct`, `top_hotspots`/
`top_transitions`, `avg_zoom_log2_w`/`drilling_rate_octaves_per_min`, `true_magnification`/
`canonical_mag_band_labels`/`magband_labels_for_scheme`, `fixations_idt` + its summary helpers
(`n_fixations`/`mean_fixation_ms`/`median_fixation_ms`/`sd_fixation_ms`/`fixations_per_min`),
`mouse_raster_from_path`, `dtw_distance`, `mean_segment_linearity`, `js_divergence`,
`top_k_frac_mask`/`precision_recall_at_topk`, `visit_count_grid`/`visit_count_jaccard`, and
`annotated_area_union_px`.

### Example

```bash
Rscript run_analysis.R ~/focus-contributions --out results \
    --reference expert-session-uuid --labels participants.csv --figures
```

## Output files

Identical file names and CSV columns to the Python toolkit (`analysis/python/`), so results from
both toolkits can be placed side by side and diffed directly:

| File | Contents |
|---|---|
| `metrics.csv` | One row per (slide, session): `slide,session,durationMs,sampleCount,coveragePct,entropy,comX,comY,peakDwell,nHotspots,pathPoints,pathLengthPx,nRevisits,transitionEntropy,avgZoom,zoomVariance,zoomRange,magnificationPercentage,scanningRatePxPerMin,drillingRatePerMin,pathVelocityPxPerSec,linearity,searchFocusRatio,baseMagnification,pathTruncated,nAnnotations,annotatedAreaPx,dwellInAnnotationPct,annotationReentryCount,enrichmentRatio,cursorOverSlidePct,mouseViewportCouplingPx,meanAbsTurnAngleDeg,turnAngleEntropy,mousePathLengthPx,mouseVelocityPxPerSec,activeFractionPct,idleMs,activeSpanMs,avgZoomLog2W,drillingRateOctavesPerMin,magnificationSource,nFixations,meanFixationMs,medianFixationMs,sdFixationMs,fixationsPerMin,mouseCoveragePct,mouseEntropy,meanSegmentLinearity,annotatedAreaUnionPx,visitCountJaccard` (the last 20 columns, from `meanAbsTurnAngleDeg` on, are the Tier 1–3 enrichment — see "Phase 3 enrichment" below for definitions/blank-conditions). Path-derived columns (incl. all the zoom/navigation ones, plus `annotationReentryCount`/`cursorOverSlidePct`/`mouseViewportCouplingPx`) are blank for sessions without a `path` (schema /1, /2). `baseMagnification`/`pathTruncated` are blank for schema /1, /2, /3 (which lack those fragment-level fields). `nAnnotations`/`annotatedAreaPx`/`dwellInAnnotationPct` need only the grid + a session's own `annotations` — populated (0/0.0) for every session, including path-less ones. `enrichmentRatio` is blank whenever its annotated/non-annotated split is degenerate (no annotations, a fully-annotated slide, or zero non-annotated dwell). `cursorOverSlidePct`/`mouseViewportCouplingPx` are blank unless the session's `path` carries schema/5 8-element points (`mouseX`/`mouseY`). **`scanningRatePxPerMin`/`drillingRatePerMin`/`pathVelocityPxPerSec`/`searchFocusRatio` now exclude idle (>60s Δt) steps from their computation (Tier 2 B1) — see "Phase 3 enrichment" below.** |
| `compare_<slug>.csv` | Per slide, pairwise agreement: `sessionA,sessionB,cc,sim,iou,diffFromConsensus,coincidenceLevel,regionCoveragePct,jsDivergence`. Tidy long format (one row per ordered pair, including the diagonal) — `pivot_wider(names_from = sessionB, values_from = cc)` in R (or `pivot()` in pandas) recovers the matrix. The diagonal row (`sessionA == sessionB`) additionally carries `diffFromConsensus = 1 - cc(session, consensus)` and `regionCoveragePct` (this session's % coverage of the consensus's above-threshold cells); off-diagonal rows leave those blank. `coincidenceLevel` (a slide-level, not per-session, statistic) is written on exactly one row per slide — the diagonal row of the first session in insertion order — all other rows leave it blank. `jsDivergence` (Tier 3 C6, appended) is a genuine **pairwise** quantity computed on every row (not diagonal-only) — always exactly `0.0` on the diagonal; see "Phase 3 enrichment" below. |
| `consensus_<slug>.png` | Heatmap of the mean of each session's max-normalised, common-grid-resampled dwell map. |
| `reference_<slug>.csv` | Written when `--reference` and/or `--roi` is given. One row per session (including the reference itself, for a self-check): `session,nss,aucJudd,cc,iou,refCoveragePct,timeOnRefMs,timeOffRefMs,precisionAtTopK,recall`, ranked descending by `nss`. `precisionAtTopK`/`recall` (Tier 3 C6, appended) compare the session's own top-10%-dwell cells to the SAME reference mask this row already scores against — see "Phase 3 enrichment" below. |
| `scanpath_<slug>.csv` | Written when at least one session on the slide has a `path` (schema /3+). Pairwise `sessionA,sessionB,levenshteinSim,transitionEntropy,dtwDistance` over visited-cell sequences (same tidy/diagonal-reuse convention as `compare_<slug>.csv`); sessions without a path are excluded entirely. `dtwDistance` (Tier 3 C3, appended) is Dynamic Time Warping between the two sessions' raw (not grid-cell) viewport-center paths — always `0.0` on the diagonal; see "Phase 3 enrichment" below. |
| `magbands_<slug>.csv` | Written alongside `scanpath_<slug>.csv` (same path-session gating). Tidy `session,band,bandTimeMs,bandTimePct,bandScheme`. **As of Tier 2 B3, the default scheme is `canonical`** — 7 fixed true-objective-magnification bands (`<1x,1-2x,2-4x,4-10x,10-20x,20-40x,>=40x`) for any session with a computable `baseMagnification`+`dsMilli`; every other session (or every session under `--magband-scheme tercile`) falls back to the pre-B3 tercile scheme sized by `--magbands` (default 3). `bandScheme` (`"canonical"`/`"tercile"`) records which scheme each session's rows actually used. **`bandTimeMs`/`bandTimePct` now exclude idle (>60s Δt) steps (Tier 2 B1)** — band *assignment* is unaffected by idle exclusion, only the per-band time SUM. See "Phase 3 enrichment" below for the full rationale. |
| `annotations_<slug>.csv` | Written when at least one session on the slide has drawn at least one annotation (schema/4+ `annotations`). Pairwise `sessionA,sessionB,iou,coincidenceLevel` over each session's own rasterized annotated region (tidy long format, same diagonal-reuse convention as `compare_<slug>.csv`) — `iou` is 0.0 (not 1.0) on the self-diagonal for a session with no annotations at all (empty-mask self-comparison; same convention `iou()` uses elsewhere), and 1.0 for a session whose own (non-empty) annotated region is compared to itself. `coincidenceLevel` is written once per slide (the diagonal row of the first session), using the same fixed visited-footprint denominator as the dwell-grid `coincidenceLevel`. |
| `mouse_<slug>.csv` | **New (Tier 3 C2).** Written when at least one session on the slide carries schema/5 mouse data at all (regardless of whether its mouse grid ends up non-empty). Pairwise `sessionA,sessionB,cc,iou,coincidenceLevel` over each session's own point-based mouse-dwell grid (`mouse_raster_from_path`, resampled to the slide's common grid) — identical tidy/diagonal-reuse convention to `annotations_<slug>.csv`; a session without mouse data contributes an all-zero grid rather than being excluded. See "Phase 3 enrichment" below. |
| `hotspots_<slug>.csv` | **New (Tier 1 A5).** Written for every slide (every fragment always carries a grid). Top-5 dwell cells per session, at that session's own native grid resolution: `session,rank,cellRow,cellCol,centerImageX,centerImageY,dwellMs,dwellFrac`. Ties broken deterministically (value descending, then flat row-major index ascending — see `top_hotspots`). |
| `transitions_<slug>.csv` | **New (Tier 1 A5).** Written when at least one session on the slide has a `path`. Top-15 directed cell→cell transitions per session: `session,fromCell,toCell,count`. Ties broken deterministically (count descending, then `(fromCell,toCell)` ascending — see `top_transitions`). |
| `fixations_<slug>.csv` | **New (Tier 3 C1).** Written when at least one session on the slide has a `path` AND at least one I-DT fixation was found anywhere on that slide. One row per fixation, per session: `session,idx,startMs,durationMs,centerImageX,centerImageY,nPoints`. **These are a deterministic dispersion-threshold (I-DT) proxy, not true eye-tracking fixations** — see "Phase 3 enrichment" below. |
| `consensus_count_<slug>.csv` | **New (Tier 3 C6).** Written whenever a slide has >=2 sessions (possibly with zero data rows). Per-cell reader count above the hotspot threshold, on the slide's common resampled grid: `cellRow,cellCol,nReaders`. Cells with `nReaders == 0` are omitted. This is the spatial map that the single-scalar `coincidenceLevel` statistic collapses — a per-cell view of the same "how many readers agreed here" question. |
| `decisions.csv` | Written when at least one fragment anywhere carries a hand-entered `decision` (else skipped with a stderr warning and no file). One row per (slide, session): `slide,sessionId,session,diagnosis,confidence,confidenceScaled,decisionMs,decisionLatencyMs,correctDx,correct,promptShownMs,responseLatencyMs`. `sessionId` is the stable join key (`session` is only the human display label). `confidenceScaled = (confidence-1)/4`, blank if `confidence` isn't numeric. `decisionLatencyMs` always equals `decisionMs` (both relative to the slide's recording start, never a wall clock) — **permanently** "time from slide open to submit", not a placeholder; **for a decision captured via the leave-prompt it reflects the reader's whole time on the slide** (comparable to `metrics.csv`'s `durationMs`), not a short dialog-fill duration. `correctDx` comes from `--key` (display-only, blank without it); `correct` comes **only** from `--graded` (blank otherwise) — never derived by comparing `diagnosis` to `correctDx`. `promptShownMs`/`responseLatencyMs` (Tier 3 C5, appended) are the true once-prompted deliberation pair — see "Phase 3 enrichment" below for the `decisionLatencyMs` vs `responseLatencyMs` distinction; both blank for a fragment recorded before the recorder gained `promptShownMs`. |
| `nav_accuracy.csv` | Written only when `--graded` supplies at least one graded decision. One row per navigation metric — `avgZoom,zoomVariance,magnificationPercentage,scanningRatePxPerMin,drillingRatePerMin,coveragePct,dwellInAnnotationPct,enrichmentRatio,searchFocusRatio,linearity,pathVelocityPxPerSec,entropy,transitionEntropy,durationMs,cursorOverSlidePct,mouseViewportCouplingPx` (the last 3, Tier 1 A1, close the "recorded-but-uncorrelated" gap the audit identified) plus one further row for `decisionLatencyMs` (Tier 1 A1, sourced directly from `decisions.csv`'s rows, not from `metrics.csv`) — joined to `correct` by the stable `(slide, sessionId)` pair: `metric,n,pointBiserialR,meanCorrect,meanIncorrect,medianCorrect,medianIncorrect,meanDiff`. `pointBiserialR` (plain Pearson `stats::cor(method="pearson")`, not a point-biserial-specific function) is blank unless `n >= 5` and both `correct` and the metric have non-zero variance. `meanCorrect`/`meanIncorrect`/`medianCorrect`/`medianIncorrect` are reported once that group alone has `>= 1` value; `meanDiff` additionally needs `>= 2` values in **both** groups. No p-values or confidence intervals at this pilot scale. |
| `summary.md` | Slide/session counts, per-slide mean pairwise CC + ICC(2,1) + coverage/duration spread + coincidence level + mean avgZoom/scanningRate/drillingRate/magnificationPercentage + mean dwellInAnnotationPct/annotation coincidence level + mean cursorOverSlidePct, and the reference ranking when applicable — plus, only when `--graded` supplies at least one graded decision, a "Navigation ↔ diagnostic accuracy" section (overall accuracy, per-metric r/n/group means from `nav_accuracy.csv`, a confidence-calibration summary `calibrationGap`/`brierScore`/`confidenceAccuracyR`, and a pilot-scale honest-limits note). **New (Tier 2 B4):** when any path-carrying session exists, a magnification-source caveat line reporting how many sessions used `"true"` vs `"proxy-downsample"` magnification (see `magnificationSource` below) — pooled avgZoom-family stats should not mix the two. |
| `<slug>/<session>_heatmap.png`, `_scanpath.png`, `_coverage.png`, `_scanpath_raster.png`, `_magband<N>.png`, `_mousemap.png` | With `--figures`: per-(slide, session) figures. `_heatmap` is at native recorded-grid resolution; `_scanpath`/`_coverage`/`_scanpath_raster`/`_magband<N>` are only written for sessions with a `path` (schema /3+). `_scanpath_raster` is the scanpath-rasterized fine heatmap at `--res` resolution (independent of the recorded grid); `_magband<N>` is one heatmap per zoom band that has at least one step (bands with zero steps are skipped — under the default canonical scheme this may be up to 7 PNGs, under tercile up to `--magbands`). `_mousemap.png` (**new, Tier 3 C2**) is a heatmap of the point-based mouse-dwell grid at `--res` resolution, written only for sessions carrying schema/5 mouse data; not part of the numeric-parity contract. |
| `overlay_<slug>_scanpaths.png` | **New (Tier 1 A6).** With `--figures`, one PNG per slide (at `<out>` root, not under `<slug>/`) when at least one session has a `path`: every path-carrying session's viewport-center path overlaid on one shared axis, distinct color per session, alpha≈0.5, start/end markers, legend. Not part of the numeric-parity contract (existence/valid-PNG only). |

`<slug>` is a filesystem-safe hash-suffixed slug of the `slideKey` (see `blinded_focus.R`'s
`slug()`); `<session>` likewise slugs the `sessionId` (or a resolved label).

## Metric formulas

Implemented in `blinded_focus.R`, one function per metric, each with a roxygen-style docstring
citing its formula. Grids are always resampled to a common `(tw, th)` via nearest-neighbour
(`resample_nn`) before any cross-session/cross-grid metric — this is the load-bearing rule that
keeps numbers comparable across sessions that recorded at different grid resolutions.

- **CC** — Pearson correlation of the two flattened grids (`stats::cor`).
- **SIM** — histogram intersection: `sum(min(a/sum(a), b/sum(b)))`.
- **KLD** — `sum(P*log((P+eps)/(Q+eps)))`, `P=ref/sum(ref)`, `Q=pred/sum(pred)`.
- **NSS** — mean, over attended-mask cells, of the globally z-scored saliency map (population std,
  matching numpy's `ddof=0` default, not R's default sample `sd()`).
- **AUC-Judd** — standard ROC-AUC (mask cells = positives, all others = negatives), computed
  exactly via the Mann-Whitney rank-sum identity (`stats::rank(ties.method = "average")`,
  matching `scipy.stats.rankdata`'s default).
- **IoU** — Jaccard index of `{grid > thresh*max(grid)}` regions (default `thresh=0.1`).
- **Coverage / entropy / center-of-mass / hotspot count** — per-session spatial-spread summaries;
  `nHotspots` is a manual 4-connected BFS flood-fill (matching `scipy.ndimage.label`'s default
  cross-shaped structuring element — base R has no connected-components primitive).
- **Scanpath** — path points are mapped to grid cells (`visited_sequence`, run-length-deduped),
  compared via a normalized Levenshtein similarity (`1 - edit_distance/max(len,len,1)`, a manual
  DP over integer tokens — NOT `utils::adist`, which is character-wise and would not reproduce
  token-level edit distance for multi-digit cell indices) and summarized via consecutive-transition
  Shannon entropy, path length (px), and revisit count.
- **Inter-observer** — mean pairwise CC, and ICC(2,1) via `irr::icc(model="twoway",
  type="agreement", unit="single")` (two-way random-effects, absolute agreement, single-rater;
  Shrout & Fleiss 1979 / McGraw & Wong 1996), with cells as rows and sessions as columns. Verified
  numerically identical to the Python toolkit's manual two-way-ANOVA formula on synthetic data.
- **Cross-session consistency** — `coincidenceLevel` (fraction of cells above-threshold in >=2
  readers' own-max-normalised grids, normalized to the **visited footprint** — cells
  above-threshold in >=1 reader, *not* the whole grid; Roa-Peña reports ~70.5% with this style of
  rule) and `regionCoveragePct` (this session's % coverage of the consensus's above-threshold
  cells). **Bug fix (2026-07):** `coincidenceLevel` used to normalize by the whole grid instead of
  the visited footprint, silently under-reporting coincidence on any partially-explored slide —
  see `docs/superpowers/navtrack-lit-review-improvements.md` §0.A and `coincidence_level`'s
  docstring for the exact before/after.

### Phase 1 — scanpath raster + zoom/navigation metric family

Motivated by the navigation-tracking literature review cited in the project's design doc, whose
strongest diagnostic-accuracy correlates are **zoom metrics**. Every function below is a direct
numeric port of its `blinded_focus/metrics.py` counterpart — same edge-case behavior (blank-vs-0.0,
`ddof`, quantile method), not just the happy-path formula:

- **`raster_from_path(path, img_w, img_h, gw, gh, step_mask=NULL)`** — rebuilds a `gh x gw`
  dwell-ms grid directly from the scanpath (independent of the recorded `grid` resolution): each
  step's `dt` is attributed to the viewport rectangle of its first point, clamped to the image and
  spread evenly across the covered cells. `NULL` for a 0/1-point path.
- **`point_zoom(point, base_mag, img_w)`** — magnification for one scanpath point, in fallback
  order: (1) true `base_mag / (dsMilli/1000)` when both are known (schema/4); (2) a unitless
  `1000/dsMilli` "zoom level" when only `dsMilli` is known; (3) a width-proxy `img_w/w` for
  schema/3 (5-element) points.
- **`avg_zoom` / `zoom_variance` / `zoom_range`** — mean / sample variance (`stats::var()`'s
  default `n-1`, matching numpy's `ddof=1`) / range of `point_zoom` over every path point. `0.0`
  (never `NA`) for a path with fewer than 2 points.
- **`magnification_percentage`** — fraction of consecutive transitions with strictly increasing
  zoom (zoom-IN only): `|{i : zoom[i+1] > zoom[i]}| / (n-1)`. Exact `>`, no tolerance. `0.0` for
  fewer than 2 points. **Bug fix (2026-07):** this used to count held-zoom ties too (`>=`);
  Ghezloo's definition requires a strict increase — see
  `docs/superpowers/navtrack-lit-review-improvements.md` §0.B and
  `magnification_percentage`'s docstring for the exact before/after.
- **`scanning_rate_px_per_min`** / **`drilling_rate_per_min`** — pan distance accumulated over
  "zoom-unchanged" steps, and count of "zoom-changed" steps, both normalized by the path's
  **ACTIVE duration** (`active_span_ms/60000`, minutes — Tier 2 B1: total duration minus idle
  (>60s dt) time; see "Phase 3 enrichment" below). For a path with no idle gap this is numerically
  identical to the original `(t[last]-t[first])/60000` total-duration denominator.
- **`path_velocity_px_per_sec`** — median of per-step `distance/dt` (px/sec); steps with
  non-positive `dt` get velocity `0.0` rather than being dropped. **Tier 2 B1:** idle (>60s dt)
  steps are excluded from the median entirely (not counted as near-zero speed) — identical to the
  pre-B1 value for a path with no idle gap.
- **`linearity`** — net first->last displacement / total scanpath length. `0.0` for a degenerate
  (zero-length) path.
- **`search_focus_ratio`** — dt-weighted fraction of steps that are "focused": zoom >= the path's
  own median zoom, OR velocity <= the path's own median velocity. **Tier 2 B1:** both the median
  thresholds and the dt-weighted sum are computed over ACTIVE (non-idle) steps only — an idle step
  is dropped as if it never existed.
- **`zoom_band_labels`** — bins steps into within-path quantile bands (terciles by default) via
  `stats::quantile(x, probs, type = 7)` (R's default -- numerically identical to numpy's default
  linear-interpolation method) + `findInterval(zooms, cuts)` (equivalent to numpy's
  `searchsorted(cuts, zooms, side="right")`). **As of Tier 2 B3 this is only the FALLBACK scheme**
  — `magbands_<slug>.csv` defaults to a canonical fixed-cut-point scheme instead whenever true
  magnification is computable; see "Phase 3 enrichment" below.
- **`raster_from_path`** — **Tier 2 B1:** a step flagged idle is always excluded from the raster
  (contributes no dwell-weight at any resolution), regardless of any `step_mask` passed in
  (unaffected for a path with no idle step).

### Phase 2 — annotation + cursor metric family

Motivated by the same literature review's ROI-based and partial-attention metrics, once ground-
truth-style reader annotations (schema/4+) and cursor position (schema/5) are available. All
formulas are in `blinded_focus.R`; the GeoJSON-to-grid rasterization they consume (`rasterize_roi`)
is the *same* rasterizer already used for the `--roi` CLI flag — annotation metrics never parse
GeoJSON themselves, only an already-rasterized logical cell mask.

- **`nAnnotations` / `annotatedAreaPx`** — feature count and total polygon area (image px²) of a
  session's own `annotations` FeatureCollection, via the shoelace formula (exterior ring area
  minus hole-ring areas, both by absolute value so ring winding order doesn't matter). Grid-only
  (no `path` needed) — populated (0 / 0.0) for every session, including path-less ones.
- **`dwell_in_mask_pct`** (Ghezloo's "ROI time percentage", generalized to a reader's own
  annotated region) — `100 * sum(grid[mask]) / sum(grid)`. `0.0` if total dwell is 0 or the mask
  has no annotated cells.
- **`enrichment_ratio`** (Nan 2025 Nat Commun) — `mean(grid[mask]) / mean(grid[!mask])`. Blank
  (`NaN`) if the mask has no annotated cells, no non-annotated cells, or zero non-annotated mean.
- **`annotation_reentry_count`** (Brunyé 2017's re-entry rate) — maps the scanpath to grid cells
  (`visited_sequence`, run-length-deduped, 0-based indices — hence `mask[idx + 1]` for R's 1-based
  vectors), looks up the annotation mask at each visited cell, and counts the number of maximal
  `TRUE` ("inside") runs in that logical sequence minus 1 (the first visit is an entry, not a
  *re*-entry): `max(0, n_visits - 1)`. Blank without a `path` at all; `0` if there's a path but no
  annotation, or the path never enters the region.
- **`cursor_over_slide_pct`** / **`mouse_viewport_coupling_px`** (a partial-attention proxy after
  Raghunath, schema/5 8-element points only: `[..., dsMilli, mouseX, mouseY]`) — percentage of
  path points where the cursor was over the slide (`mouseX/mouseY != (-1,-1)`), and the median
  Euclidean distance (image px) between the cursor and the viewport center over on-slide points
  only. Both blank for any fragment whose path doesn't carry 8-element points (schema </5).
- **Cross-user annotation agreement** (`annotations_<slug>.csv`) — pairwise IoU of each session's
  own rasterized annotated region (resampled to the slide's common `(tw, th)` grid, same as the
  dwell-based `compare_<slug>.csv`), plus a slide-level `coincidenceLevel` reusing the same
  (fixed, visited-footprint) `coincidence_level` function.
- **`get_annotations(fragment)`** — normalizes a fragment's `annotations` field (parsed with
  `simplifyVector = TRUE`, so its shape can vary with feature count/consistency) into the same
  fully-nested list shape `load_roi_rings` gets from a `simplifyVector = FALSE` file parse, by
  round-tripping it through `toJSON(digits = 15)` -> `fromJSON(simplifyVector = FALSE)`. Defaults
  to an empty FeatureCollection when the field is absent or malformed. This same normalization
  also incidentally fixed a **latent bug** in `.extract_rings` (`ring[, 1:2]` threw "incorrect
  number of dimensions" on a `simplifyVector = FALSE`-parsed ring, which is a plain nested list,
  not a matrix) — the fix (`.ring_to_matrix`, handling matrix/data.frame/list-of-points rings
  uniformly) also repairs the pre-existing `--roi` CLI path, not just the new annotation path.

### Phase 3 — decision capture + navigation↔diagnostic-accuracy

Motivated by the same literature review's "diagnostic impact" framing: does navigation behavior
predict diagnostic accuracy? A reader's per-slide diagnosis is captured in QuPath as free text
(menu action **"Bu slayt için tanı/karar gir…"**, plus an optional leave-a-slide auto-prompt) and
rides inside the fragment as an additive `decision` object (`diagnosis`, `confidence` 1–5 or
`null`, `decisionMs` relative to the slide's recording start). This **does not** introduce a new
file type or schema number — decisions still ship inside the existing `atlas-focus-contribution/5`
fragment, and `get_decision(fragment)` defaults to an empty list for any schema/1–5 fragment
recorded without one.

- **Grading is deliberately two-pass and hand-done, never automatic.** A free-text diagnosis is
  never string-matched (not even case/synonym-normalized) against an answer key — auto-matching
  free text would silently misgrade valid alternate phrasings, and for Turkish text specifically
  would reintroduce the İ/ı case-folding hazard this design was chosen to avoid entirely.
  `load_answer_key` (`--key`) supplies only a **display-only** reference (`correctDx`);
  `load_graded` (`--graded`) is the **only** source of the hand-graded `correct` (0/1) column.
- **`decisions.csv`** — one row per (slide, session), written once after the outer loop (like
  `metrics.csv`, not per-slide): `slide, sessionId, session, diagnosis, confidence,
  confidenceScaled, decisionMs, decisionLatencyMs, correctDx, correct, promptShownMs,
  responseLatencyMs` (the last two appended by Tier 3 C5, 2026-07-23). `confidenceScaled =
  (confidence-1)/4` (blank if `confidence` isn't numeric). `decisionLatencyMs` is **permanently**
  identical to `decisionMs` — "time from slide open to submit" — this is no longer a placeholder
  awaiting a future divergence: the recorder now also stamps `promptShownMs` (when the decision
  dialog was shown, relative to slide-record start), so `responseLatencyMs = decisionMs -
  promptShownMs` is the true once-prompted deliberation time, appended as its **own** column
  rather than replacing `decisionLatencyMs`. **Caveat:** for a decision captured via the
  leave-prompt path, `decisionLatencyMs` is on the same scale as the slide's whole `durationMs`
  (the reader had the entire slide-viewing time available before the prompt fired), not a short
  reaction-time measurement — use `responseLatencyMs` for that. `promptShownMs`/
  `responseLatencyMs` are blank for a fragment recorded before the recorder gained this field, and
  `responseLatencyMs` is additionally blank unless BOTH `decisionMs` and `promptShownMs` are
  numeric. Written only when at least one fragment anywhere carries a decision; otherwise skipped
  with a stderr warning and no file.
- **`nav_accuracy.csv`** — written only when `--graded` supplies at least one graded decision. One
  row per navigation metric in `NAV_ACCURACY_COLS` (`avgZoom, zoomVariance,
  magnificationPercentage, scanningRatePxPerMin, drillingRatePerMin, coveragePct,
  dwellInAnnotationPct, enrichmentRatio, searchFocusRatio, linearity, pathVelocityPxPerSec,
  entropy, transitionEntropy, durationMs, cursorOverSlidePct, mouseViewportCouplingPx` — all
  already present as `metrics.csv` columns; the last 3 are Tier 1 A1, closing the
  "recorded-but-uncorrelated" gap the audit identified), plus one further row for
  `decisionLatencyMs` (Tier 1 A1, sourced directly from the `decisions.csv` rows rather than
  `metrics.csv`, since `decisionLatencyMs` lives on the decision entry, not the metrics row),
  joined to `correct` by the stable `(slide, sessionId)` pair (never the display `session` label,
  so relabeling sessions with a different `--labels` between the two passes can't misalign
  grades): `metric, n, pointBiserialR, meanCorrect, meanIncorrect, medianCorrect,
  medianIncorrect, meanDiff`.
- **Guard policy** — no statistic here is ever reported as numerically unstable/undefined:
  - `pointBiserialR` — plain Pearson r (`stats::cor(method = "pearson")`, deliberately **not** a
    point-biserial-specific function — same numeric identity for a 0/1 vs continuous pair, and the
    exact numeric match `.pearson_guarded`'s Python counterpart (`numpy.corrcoef`) is verified
    against) between `correct` and the metric. Blank (`NaN`) unless `n >= 5` **and** both `correct`
    and the metric have non-zero variance (`.pearson_guarded`).
  - `meanCorrect`/`meanIncorrect`/`medianCorrect`/`medianIncorrect` — reported whenever that group
    alone has `>= 1` value (soft guard; the recommended primary readout at tiny n — still eyeball
    the `n` column).
  - `meanDiff` — additionally needs `>= 2` values in **both** groups.
  - The confidence-calibration trio printed in `summary.md` (`calibrationGap =
    mean(confidenceScaled) - mean(correct)`, `brierScore = mean((confidenceScaled-correct)^2)`,
    both soft-guarded over graded rows with a numeric `confidenceScaled`; `confidenceAccuracyR`
    hard-guarded exactly like `pointBiserialR`).
  - **No p-values or confidence intervals anywhere** — deliberate, both for Python↔R parity and
    because pilot-scale `n` (typically 5–20 sessions per workshop) doesn't support them; only `r`
    and `n` are reported.
- **Honest-limits note** (also printed verbatim in `summary.md`'s accuracy section): viewport-only
  navigation tracking has a **null-result precedent** in the literature this feature is modeled
  on — a correlation found here should not be over-read as proof that zoom/scan behavior drives
  accuracy. Coincidence/accuracy numbers are **cohort-composition-dependent** (a different mix of
  easy/hard cases, or of expert/trainee readers, changes the numbers). Everything here is
  **pilot-scale** — report `r` and `n`, nothing stronger.

## Phase 3 enrichment (Tier 1–3, `docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md`)

A follow-up enrichment pass on top of Phase 1–3 above, driven by
`docs/superpowers/phase3-data-dimension-audit.md`'s 5-dimension gap analysis. Grouped by
dimension; every function lives in `blinded_focus.R` unless noted, with a roxygen-style docstring
pinning the exact formula + edge-case behavior, and is a direct numeric port of its
`blinded_focus/metrics.py` counterpart (verified against the same synthetic fixture — see "Parity
with the Python toolkit" below).

**Read this whole section before comparing enrichment-era output to older output** — two of these
changes alter the value of columns that already existed before this pass (flagged **CHANGES**
below: B1 idle exclusion, B3 canonical magnification bands); everything else is purely additive
(new columns/files/flags, no existing value changes).

### Temporal

- **`idleMs`** (`metrics.csv`, path-only) — sum of step dt over steps flagged "idle" (dt >
  `IDLE_GAP_MS` = 60,000 ms, Ghezloo's >60s-frozen-viewport rule). `0.0` when the path has no
  >60s gap (not blank) — a session with no idle time correctly reports zero idle time.
- **`activeSpanMs`** (`metrics.csv`, path-only) — wall-clock span (`tRel_last - tRel_first`) minus
  `idleMs`; the new denominator (in minutes) for `scanningRatePxPerMin`/`drillingRatePerMin`/
  `drillingRateOctavesPerMin`/`fixationsPerMin`. `0.0` for a <2-point path.
- **B1 idle exclusion (CHANGES existing numbers for idle-containing sessions).**
  `scanningRatePxPerMin`, `drillingRatePerMin`, `pathVelocityPxPerSec`, `searchFocusRatio`,
  `raster_from_path`'s per-step dwell weight, and `magbands_<slug>.csv`'s `bandTimeMs`/
  `bandTimePct` now **exclude** any step whose dt exceeds `IDLE_GAP_MS` (60s) entirely — not just
  cap its contribution. **Numerically identical to the pre-enrichment value for any session with
  no >60s gap** (the exclusion set is empty); different for a session where the reader stepped
  away from the mouse/keyboard for over a minute. Band *assignment* in `magbands_<slug>.csv` is
  unaffected by idle exclusion — only the per-band time SUM.
- **`activeFractionPct`** (`metrics.csv`, path-only, Tier 1 A4) — `100 * durationMs /
  (tRel_last - tRel_first)`: the fragment's recorded dwell duration as a % of the scanpath's
  wall-clock span (Mello-Thoms engagement confound). **Not clamped at 100%** — a value above 100%
  is a real signal from the recorder's dwell-weight accounting, not hidden. Blank if the path has
  <2 points, `duration_ms` is missing/non-numeric, or the wall-clock span is <= 0.
- **`nFixations` / `meanFixationMs` / `medianFixationMs` / `sdFixationMs` / `fixationsPerMin`**
  (`metrics.csv`, path-only, Tier 3 C1) — see "Trajectory" below for the fixation algorithm.
  `fixationsPerMin` uses the same idle-excluded `activeSpanMs` denominator as the rate metrics
  above, and (final-review fix) the numerator is now idle-consistent too: an idle-flagged step
  (`dt > IDLE_GAP_MS`) is a hard fixation-window boundary, so `nFixations`/the other summary
  stats never bridge a >60s away-gap into one artificially long fixation — see the "Fixation
  extraction (I-DT)" entry below for the full idle-boundary rule. All 5 blank if the path has <2
  points; `nFixations`/`meanFixationMs`/`medianFixationMs`/`sdFixationMs` additionally need >=1
  (or >=2 for `sdFixationMs`) fixations actually found — a genuinely-zero-fixations path (e.g.
  shorter than `MIN_FIXATION_MS`=250ms total) reports `nFixations=0`/`fixationsPerMin=0.0`
  (well-defined zero) but blank mean/median/sd (undefined over zero fixations).
- **`decisionLatencyMs` vs `responseLatencyMs`** (`decisions.csv`, Tier 3 C5) —
  `decisionLatencyMs` = time from slide-open to submit (**permanently** == `decisionMs`; for a
  leave-prompt capture this is essentially the whole slide-viewing `durationMs`, not a reaction
  time). `responseLatencyMs` = `decisionMs - promptShownMs` = the true once-prompted deliberation
  time, now real because the recorder stamps `promptShownMs` when the decision dialog is shown.
  Blank unless both are numeric (older fragments predating the recorder change have no
  `promptShownMs` at all).

### Spatial

- **`hotspots_<slug>.csv`** (Tier 1 A5) — surfaces the already-implemented `top_hotspots()`: top-5
  dwell cells per session at native grid resolution. Written for every slide (a grid always
  exists). Deterministic tie-break (value desc, flat index asc).
- **`transitions_<slug>.csv`** (Tier 1 A5) — surfaces `top_transitions()`: top-15 directed
  cell→cell transitions per session. Written only for path-carrying sessions.
- **`jsDivergence`** (`compare_<slug>.csv`, Tier 3 C6) — Jensen-Shannon divergence (base-2,
  symmetric, bounded `[0,1]`), via a zero-safe KL convention distinct from the pre-existing `kld`'s
  additive-EPS smoothing. Exactly `0.0` for two identical grids (incl. both-all-zero). A genuine
  pairwise quantity (unlike `diffFromConsensus`/`coincidenceLevel`), computed on every row.
- **`precisionAtTopK` / `recall`** (`reference_<slug>.csv`, Tier 3 C6) — the reader's top-10%
  highest-dwell cells (tie-inclusive: every cell at or above the 10th-percentile cutoff VALUE, so
  the set can exceed 10% of cells when there are ties) vs the same reference/ROI mask this file
  already scores against. `precisionAtTopK = |topK INTERSECT roi| / |topK|` (low = "wasted
  attention"); `recall = |topK INTERSECT roi| / |roi|` (low = "missed target"). Both blank if the
  reference mask or the top-K set is empty.
- **`visitCountJaccard`** (`metrics.csv`, path-only, Tier 3 C6) — Jaccard similarity between the
  top-5 dwell-TIME hotspots and the top-5 visit-COUNT hotspots (a +1-per-cell-**entry**, not
  per-tick, grid) at the session's own native resolution — disagreement flags "one long dwell" vs
  "many brief revisits" navigation styles. Blank without a path, or if either hotspot set is empty
  (only possible for a degenerate <2-cell grid).
- **`annotatedAreaUnionPx`** (`metrics.csv`, always populated, Tier 3 C6) — area (image px²) of the
  UNIONED rasterized annotation mask — the overlap-correct companion to the existing sum-based
  `annotatedAreaPx`. **Caveat:** it is the rasterized-mask union (each grid cell contributes its
  full cell-area footprint), NOT the exact vector polygon-union area — for grid-aligned annotations
  it is `<=` the sum-based `annotatedAreaPx` (no double-counting of overlap), but a sub-cell
  annotation can round its footprint UP to a whole cell, so `annotatedAreaUnionPx <=
  annotatedAreaPx` is **not a hard invariant**. `0.0` for no annotations.
- **`consensus_count_<slug>.csv`** (Tier 3 C6) — per-cell reader count above the hotspot threshold
  (the same threshold `nHotspots` uses), on the slide's common grid: `cellRow,cellCol,nReaders`.
  Written whenever a slide has >=2 sessions (possibly zero data rows if no cell clears the
  threshold). The spatial map the single-scalar `coincidenceLevel` collapses.

### Zoom / magnification

- **`avgZoomLog2W`** (`metrics.csv`, path-only, Tier 2 B2, **added alongside** the untouched
  `avgZoom`) — dt-weighted (idle-excluded) mean of `log2(magnification)` — Drew's fidelity-weighted
  zoom formulation. Blank if the path has <2 points or every step is idle (0/0 weighted mean is
  undefined, unlike `avgZoom`'s `0.0`-for-empty convention).
- **`drillingRateOctavesPerMin`** (`metrics.csv`, path-only, Tier 2 B2, **added alongside** the
  untouched `drillingRatePerMin`) — sum(|Δlog2(zoom)|) over active steps, per active minute — a
  continuous zoom-change-magnitude complement to the discrete drilling event count. Blank if <2
  points or non-positive active duration.
- **`magnificationSource`** (`metrics.csv`, path-only, Tier 2 B4) — `"true"` when
  `baseMagnification` is present (real objective-power magnification) or `"proxy-downsample"` when
  it's null (a scale-relative fallback). **Atlas DZI slides have a null `baseMagnification` and
  therefore always report `"proxy-downsample"`.** Pooled `avgZoom`/`zoomVariance`/`zoomRange`/
  `avgZoomLog2W` statistics across a mix of `"true"` and `"proxy-downsample"` sessions are not
  directly comparable — `summary.md` prints a caveat line whenever both are present.
- **B3 canonical magnification bands (CHANGES `magbands_<slug>.csv` for true-magnification
  sessions).** Default `--magband-scheme canonical` assigns 7 fixed bands (cut points
  `[1,2,4,10,20,40]`x) to any session with computable `baseMagnification`+`dsMilli`, replacing the
  old always-tercile scheme for those sessions. Auto-falls back to tercile per-session when true
  magnification isn't computable. `bandScheme` column records which scheme was actually used.
  **Atlas DZI slides (null `baseMagnification`) always fall back to `tercile` and are unaffected.**
  `--magband-scheme tercile` forces the old behavior for every session.

### Trajectory (movement)

- **`meanAbsTurnAngleDeg` / `turnAngleEntropy`** (`metrics.csv`, path-only, Tier 1 A2) — heading
  `theta_i = atan2(dy,dx)` per segment; turn `phi_i = wrapToPi(theta_{i+1}-theta_i)` at each
  interior point. `meanAbsTurnAngleDeg` = mean `|phi_i|` in degrees. `turnAngleEntropy` = Shannon
  entropy (bits) of `phi` binned into 8 equal bins over `(-180°,180°]`, normalized to `[0,1]` by
  `log2(8)` (0 = perfectly directional, 1 = turns spread evenly). Both blank if the path has <3
  points (a turn needs two consecutive segments).
- **`dtwDistance`** (`scanpath_<slug>.csv`, Tier 3 C3) — Dynamic Time Warping (standard DP,
  deliberately **not** Fréchet distance) between two sessions' z-normalized (per-axis, per-session,
  sample-sd) viewport-center `(cx,cy)` sequences — a resolution-independent complement to the
  grid-cell `levenshteinSim`. Raw accumulated cost, **not** length-normalized (so it is
  length-dependent as well as similarity-dependent — documented, not a bug). Always exactly `0.0`
  on the diagonal. Blank if either session lacks a path.
- **`meanSegmentLinearity`** (`metrics.csv`, path-only, Tier 3 C4) — mean `linearity` over
  sub-paths split at the session's own top-5 dwell-hotspot cells (consecutive same-cell hits
  collapse to one boundary per distinct hotspot *visit*, not per raw sample — a 2026-07-23 dedup
  fix; see the function's docstring for the exact before/after). Complements the whole-path
  `linearity` (Roa-Peña: whole-path ~0.41 vs between-hotspot segment ~0.8 — transit segments are
  straighter than the whole meandering path). **The spec's ROI-entry-boundary variant is
  intentionally NOT implemented** — only the uniform hotspot-based segmentation is. Blank if fewer
  than 2 hotspot cells exist at all, the path has <2 points, or fewer than 2 distinct-hotspot
  boundaries are found (zero segments).
- **Fixation extraction (I-DT)** (Tier 3 C1) — `fixations_idt()`: a deterministic
  dispersion-threshold (Salvucci & Goldberg 2000) detector over the ordered viewport centers —
  deliberately **not** a clustering library (whose cluster assignment isn't guaranteed identical
  across languages/library versions, which would break Python↔R parity). Dispersion threshold =
  `0.25 x` the visible viewport width **at the window's first point** (fixed once, never
  recomputed as the window expands — avoids a circular threshold); minimum fixation duration =
  250ms. **Idle-gap hard boundary (final-review fix):** a step flagged idle by `idle_step_mask`
  (Tier 2 B1, `dt > IDLE_GAP_MS` = 60s) is a hard window boundary a fixation may never bridge —
  the path is split into maximal idle-free "runs" and each run is fed through the (otherwise
  unmodified) I-DT loop independently, so a >60s away-gap with a near-stationary viewport either
  side no longer gets silently bridged into one artificially long fixation. This keeps
  `nFixations`/`meanFixationMs`/`medianFixationMs`/`sdFixationMs` consistent with
  `fixationsPerMin`'s idle-excluded `activeSpanMs` denominator (both numerator and denominator are
  now idle-aware). A session with no >60s gap is numerically unchanged (the split is a no-op).
  **These are I-DT viewport-jump proxies, not true eye-tracking fixations** — label them as such
  in any downstream write-up. Per-fixation rows in `fixations_<slug>.csv` (see "Spatial"/
  output-files table above); summary stats in `metrics.csv` (see "Temporal" above). Directional
  metrics (turn-angle, transitions) stay tick-based and are untouched by this — fixations are an
  added lens, not a rebase.

### Mouse / cursor

- **`mousePathLengthPx` / `mouseVelocityPxPerSec`** (`metrics.csv`, schema/5 only, Tier 1 A3) —
  sum / median-of-rate of Euclidean distance between consecutive **on-slide** cursor points
  (sentinel-aware: a segment touching the off-viewer sentinel `(-1,-1)` at either end is skipped
  whole, never bridged). Blank if the path doesn't carry schema/5 mouse data at all, or has zero
  valid on-slide segments.
- **`mouseCoveragePct` / `mouseEntropy`** (`metrics.csv`, schema/5 only, Tier 3 C2) —
  coverage/entropy of a point-based mouse-dwell grid (`mouse_raster_from_path`): analogous to
  `raster_from_path` but deposits a step's whole dt into the single cell containing the cursor,
  not spread across a viewport rectangle. Idle (>60s) steps and off-slide segments are excluded
  (same rules as B1/A3 above). Blank if there's no mouse data or zero on-slide points anywhere;
  `0.0` (not blank) is a legitimate result when there's >=1 on-slide point but zero valid
  consecutive on-slide pairs.
- **`mouse_<slug>.csv`** (Tier 3 C2) — cross-reader mouse-dwell agreement: pairwise `cc`/`iou` +
  a slide-level `coincidenceLevel` (same diagonal-reuse convention as `compare_<slug>.csv`/
  `annotations_<slug>.csv`), of each session's own point-based mouse-dwell grid resampled to the
  slide's common grid. Written whenever at least one session carries schema/5 mouse data at all.
  **No mouse ICC is computed** (only `cc`/`iou`/`coincidenceLevel`) — see "Explicitly deferred"
  below.
- **`_mousemap.png`** (`--figures`, Tier 3 C2) — heatmap of the point-based mouse-dwell grid at
  `--res` resolution, written per session carrying schema/5 mouse data. Not part of the
  numeric-parity contract.
- **Mouse metrics and fixations are schema/5-only** — every mouse-kinematics/mouse-dwell/
  cross-reader-mouse column above requires 8-element schema/5 path points (`mouseX`/`mouseY`);
  older fragments (schema /1–/4, or /5 fragments the recorder wrote without cursor tracking) leave
  every one of these columns blank, never `0.0`/`NA`-as-a-real-value.

### Explicitly deferred (not built in this enrichment pass)

From the audit's Tier 3 list, three items were intentionally left out of scope:

- **Mouse ICC** — `mouse_<slug>.csv` reuses `cc`/`iou`/`coincidenceLevel` for cross-reader mouse
  agreement but does not compute an ICC(2,1) analog for the mouse-dwell grid.
- **ROI-entry segment-linearity variant** — `mean_segment_linearity` only implements the uniform
  top-hotspot-based segmentation; splitting sub-paths at annotation-ROI-entry boundaries instead
  (the spec's alternative) is noted in the code as a future option, not implemented.
- **Per-band spatial agreement** — the audit flagged extending cc/IoU/coincidence to a per-
  magnification-band basis; this did not make it into the Tier 2/3 spec and is not implemented.

(Fréchet distance was **not** deferred — DTW was the deliberate design choice over it for C3, per
the spec; see "Trajectory" above.)

## Parity with the Python toolkit

Both toolkits are pinned to the same formulas and the same output-file contract (file names, CSV
columns, tidy long-format with diagonal-row extras) so a single input directory analyzed by both
produces directly comparable numbers — verified end-to-end on identical fixed synthetic input
(same fragment JSONs fed to both CLIs, spanning schema/2-/5 including annotations and mouse data):
every numeric column in `metrics.csv`, `compare_<slug>.csv`, `scanpath_<slug>.csv`,
`magbands_<slug>.csv`, `reference_<slug>.csv`, and `annotations_<slug>.csv` — including all 7
Phase-2 columns and the two literature-review bug fixes — matches to floating-point tolerance once
parsed as typed values. The Tier 1–3 enrichment columns/files above ("Phase 3 enrichment") were
verified the same way, task by task, against a shared synthetic fixture — see
`.superpowers/sdd/t1-report.md` through `t6-report.md` for the per-task parity diffs. Small,
intentional, harmless divergences:

- **Slug hashing.** The Python toolkit suffixes slugs with a SHA1 prefix; this R toolkit uses a
  djb2-style rolling hash computed in `double` arithmetic instead of hand-rolling SHA1 (R's
  `bitwAnd`/`bitwShiftL` operate on signed 32-bit `integer` and silently overflow to `NA` well
  before a real SHA1's `0xFFFFFFFF` masks would apply). Slugs are filenames, not compared
  byte-for-byte across toolkits, so this doesn't affect output parity.
- **Undefined-metric CSV cells.** Where a metric is genuinely undefined (e.g. `aucJudd` when a
  reference mask is degenerate), the Python toolkit writes the literal `nan`; this toolkit writes
  a blank cell (`NA`/`NaN` and the diagonal-only blank convention share the same `na=""` CSV
  writer). Both indicate "not computable"; neither should occur on realistic non-degenerate input
  (including the shipped `selftest.R`/`selftest.py` data).
- **Boolean/whole-number text rendering.** `pathTruncated` (`TRUE`/`FALSE` here vs Python's
  `True`/`False`) and whole-number `baseMagnification` (`40` here vs Python's `40.0`) render as
  different literal text in the two toolkits' CSVs — a byte-diff would flag these as mismatches
  even though the underlying values agree. A parity check must parse-and-compare typed values,
  not byte-diff the CSV text.

## Data model recap

- `slideKey` identifies a slide (already anonymised upstream — `sha256:`-hashed for non-http
  sources); `sessionId` identifies one recording sitting ("user" in this toolkit's vocabulary —
  one person across multiple sittings appears as multiple sessionIds; a coordinator maps identity
  to a real name out-of-band, e.g. via `--labels`).
- `grid` is a row-major `gridWidth x gridHeight` array — milliseconds of dwell for schema `/2`,
  `/3`, `/4`, `/5`, fixed-weight sample counts for schema `/1`.
- `path` (schema `/3`+) is the ordered, capped list of viewport samples (image-pixel center +
  visible extent, time relative to that slide's recording start). `/3` points are 5-element
  `[tRelMs, cx, cy, w, h]`; `/4` points are 6-element `[tRelMs, cx, cy, w, h, dsMilli]` (`dsMilli` =
  downsample × 1000); `/5` points are 8-element `[tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY]` —
  purely additive over `/4` (`mouseX`/`mouseY` are the cursor's position in the same image-pixel
  space as `cx`/`cy`, or the sentinel `-1, -1` when the cursor was off the slide viewer). `/4`+
  fragments also carry a fragment-level `baseMagnification` (number, or absent/`NULL` if unknown),
  `pathTruncated` (bool), and `annotations` (a GeoJSON `FeatureCollection` snapshot of the reader's
  own slide annotations — geometry in image px, plus each feature's `properties`, which may
  include `name`, `classification.name`, and `metadata.ANNOTATION_DESCRIPTION`; defaults to an
  empty FeatureCollection via `get_annotations` when absent or malformed), and (Phase 3) an
  optional `decision` object (`diagnosis` string, `confidence` 1–5 or `NULL`, `decisionMs` relative
  to the slide's recording start) — present only when the reader recorded one; `get_decision`
  defaults to an empty list otherwise. `decision` is a purely additive field on the existing schema
  (still `atlas-focus-contribution/5` — no new schema number, no new file type, no `SCHEMAS`
  allowlist change). Fragments without a `path` (schema `/1`, `/2`) still get full spatial/dwell
  analysis; only the scanpath-, zoom/navigation-, and path-dependent annotation/cursor outputs are
  skipped (left blank in `metrics.csv`) for them.
