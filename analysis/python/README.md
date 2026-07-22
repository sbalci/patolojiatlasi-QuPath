# blinded_focus — Python analysis toolkit

Standalone Python toolkit that turns anonymised QuPath atlas **blinded-focus** fragments
(schema `atlas-focus-contribution/{1,2,3,4,5}`) into per-session metrics (including a Phase-1
zoom/navigation metric family and a Phase-2 annotation/cursor metric family), publication
figures, cross-user agreement, reference/ROI comparison, and scanpath analysis.

This is **not** part of the QuPath extension and does not import from it, nor from
`tools/aggregate-focus.py` — it only shares the fragment JSON shape and the general heatmap idea
by convention.

## Install

```bash
python3 -m venv .venv
# Windows:
.venv\Scripts\python.exe -m pip install -r requirements.txt
# macOS/Linux:
.venv/bin/python -m pip install -r requirements.txt
```

Requires numpy, pandas, matplotlib, and scipy (see `requirements.txt`).

## Verify

```bash
# Windows:
.venv\Scripts\python.exe selftest.py
# macOS/Linux:
.venv/bin/python selftest.py
```

`selftest.py` synthesizes 4 sessions on one slide, one per accepted schema: schema/5 (8-element
path with cursor `mouseX`/`mouseY` + varying zoom + `baseMagnification`, plus an `annotations`
FeatureCollection overlapping its own dwell center), schema/4 (6-element path, varying zoom, no
mouse, plus the *same* annotation rectangle and a deliberately bouncing path for a non-trivial
re-entry count), schema/3 (5-element path, w-proxy zoom, no annotations), and schema/2 (no path,
no annotations). One session is designated as `--reference`. Runs the full `analyze` pipeline
into a temp directory, and asserts the output contract described below (symmetric compare matrix
with a 1.0 diagonal, similar-pair CC > dissimilar-pair CC, high reference-vs-itself NSS/CC,
scanpath diagonal 1.0, Phase-1 zoom columns populated/blank as expected, `magnificationPercentage`
in `[0,1]`, a scanpath-rasterized fine heatmap PNG, valid PNGs, `.zip` input support, Phase-2
annotation/cursor columns populated/blank as expected, a symmetric annotation IoU matrix with a
1.0 diagonal for the annotated sessions, and direct regression asserts for the two literature-
review bug fixes — `coincidence_level`'s visited-footprint denominator and
`magnification_percentage`'s strict-increase tie fix). Exits non-zero on any failure.

## Usage

```bash
python -m blinded_focus.analyze <input...> --out DIR \
    [--reference SESSIONID] [--roi roi.geojson] [--labels labels.csv] [--figures] \
    [--res 512] [--magbands 3]
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
- `--figures` — also render per-(slide, session) PNGs (heatmap, scanpath overlay, coverage-over-
  time, scanpath-rasterized fine heatmap, magnification-band heatmaps) under
  `<out>/<slide-slug>/`.
- `--res N` (default 512) — longest-side resolution for the scanpath-rasterized fine heatmap and
  the magnification-band heatmaps (aspect-preserving; see `analyze._res_grid_dims`).
- `--magbands N` (default 3) — number of within-path zoom bands (terciles) for the
  magnification-split analysis (see `metrics.zoom_band_labels`).

Also importable as a library: `from blinded_focus import io, metrics, figures, analyze`.

### Example

```bash
python -m blinded_focus.analyze ~/focus-contributions --out results \
    --reference expert-session-uuid --labels participants.csv --figures
```

## Output files

| File | Contents |
|---|---|
| `metrics.csv` | One row per (slide, session): `slide,session,durationMs,sampleCount,coveragePct,entropy,comX,comY,peakDwell,nHotspots,pathPoints,pathLengthPx,nRevisits,transitionEntropy,avgZoom,zoomVariance,zoomRange,magnificationPercentage,scanningRatePxPerMin,drillingRatePerMin,pathVelocityPxPerSec,linearity,searchFocusRatio,baseMagnification,pathTruncated,nAnnotations,annotatedAreaPx,dwellInAnnotationPct,annotationReentryCount,enrichmentRatio,cursorOverSlidePct,mouseViewportCouplingPx`. Path-derived columns (incl. all the zoom/navigation ones, plus `annotationReentryCount`/`cursorOverSlidePct`/`mouseViewportCouplingPx`) are blank for sessions without a `path` (schema /1, /2). `baseMagnification`/`pathTruncated` are blank for schema /1, /2, /3 (which lack those fragment-level fields). `nAnnotations`/`annotatedAreaPx`/`dwellInAnnotationPct` need only the grid + a session's own `annotations` — populated (0/0.0) for every session, including path-less ones. `enrichmentRatio` is blank whenever its annotated/non-annotated split is degenerate (no annotations, a fully-annotated slide, or zero non-annotated dwell). `cursorOverSlidePct`/`mouseViewportCouplingPx` are blank unless the session's `path` carries schema/5 8-element points (`mouseX`/`mouseY`). |
| `compare_<slug>.csv` | Per slide, pairwise agreement: `sessionA,sessionB,cc,sim,iou,diffFromConsensus,coincidenceLevel,regionCoveragePct`. Tidy long format (one row per ordered pair, including the diagonal) rather than a 2D matrix — `pivot(index="sessionA", columns="sessionB")` in pandas (or `pivot_wider` in R) recovers the matrix. The diagonal row (`sessionA == sessionB`) additionally carries `diffFromConsensus = 1 - cc(session, consensus)` and `regionCoveragePct` (this session's % coverage of the consensus's above-threshold cells); off-diagonal rows leave those blank. `coincidenceLevel` (a slide-level, not per-session, statistic) is written on exactly one row per slide — the diagonal row of the first session in insertion order — all other rows leave it blank. |
| `consensus_<slug>.png` | Heatmap of the mean of each session's max-normalised, common-grid-resampled dwell map. |
| `reference_<slug>.csv` | Written when `--reference` and/or `--roi` is given. One row per session (including the reference itself, for a self-check): `session,nss,aucJudd,cc,iou,refCoveragePct,timeOnRefMs,timeOffRefMs`, ranked descending by `nss`. |
| `scanpath_<slug>.csv` | Written when at least one session on the slide has a `path` (schema /3+). Pairwise `sessionA,sessionB,levenshteinSim,transitionEntropy` over visited-cell sequences (same tidy/diagonal-reuse convention as `compare_<slug>.csv`); sessions without a path are excluded entirely. |
| `magbands_<slug>.csv` | Written alongside `scanpath_<slug>.csv` (same path-session gating). Tidy `session,band,bandTimeMs,bandTimePct` — per-session dwell time (and % of that session's total path duration) in each of `--magbands` within-path zoom bands (band 0 = lowest zoom, highest index = highest zoom; see `metrics.zoom_band_labels`). |
| `annotations_<slug>.csv` | Written when at least one session on the slide has drawn at least one annotation (schema/4+ `annotations`). Pairwise `sessionA,sessionB,iou,coincidenceLevel` over each session's own rasterized annotated region (tidy long format, same diagonal-reuse convention as `compare_<slug>.csv`) — `iou` is 0.0 (not 1.0) on the self-diagonal for a session with no annotations at all (empty-mask self-comparison; same convention `metrics.iou` uses elsewhere), and 1.0 for a session whose own (non-empty) annotated region is compared to itself. `coincidenceLevel` is written once per slide (the diagonal row of the first session), using the same fixed visited-footprint denominator as the dwell-grid `coincidenceLevel`. |
| `summary.md` | Slide/session counts, per-slide mean pairwise CC + ICC(2,1) + coverage/duration spread + coincidence level + mean avgZoom/scanningRate/drillingRate/magnificationPercentage + mean dwellInAnnotationPct/annotation coincidence level + mean cursorOverSlidePct, and the reference ranking when applicable. |
| `<slug>/<session>_heatmap.png`, `_scanpath.png`, `_coverage.png`, `_scanpath_raster.png`, `_magband<N>.png` | With `--figures`: per-(slide, session) figures. `_heatmap` is at native recorded-grid resolution; `_scanpath`/`_coverage`/`_scanpath_raster`/`_magband<N>` are only written for sessions with a `path` (schema /3+). `_scanpath_raster` is the scanpath-rasterized fine heatmap at `--res` resolution (independent of the recorded grid); `_magband<N>` is one heatmap per within-path zoom band that has at least one step (bands with zero steps are skipped, so a session may have fewer than `--magbands` band PNGs). |

`<slug>` is a filesystem-safe hash-suffixed slug of the `slideKey` (see `blinded_focus.io.slug`);
`<session>` likewise slugs the `sessionId` (or a resolved label).

## Metric formulas

Implemented in `blinded_focus/metrics.py`, one function per metric, each with a docstring citing
its formula. Grids are always resampled to a common `(tw, th)` via nearest-neighbour
(`resample_nn`) before any cross-session/cross-grid metric — this is the load-bearing rule that
keeps numbers comparable across sessions that recorded at different grid resolutions.

- **CC** — Pearson correlation of the two flattened grids.
- **SIM** — histogram intersection: `sum(min(a/sum(a), b/sum(b)))`.
- **KLD** — `sum(P*log((P+eps)/(Q+eps)))`, `P=ref/sum(ref)`, `Q=pred/sum(pred)`.
- **NSS** — mean, over attended-mask cells, of the globally z-scored saliency map.
- **AUC-Judd** — standard ROC-AUC (mask cells = positives, all others = negatives), computed
  exactly via the Mann-Whitney rank-sum identity (equivalent to the trapezoidal ROC integral).
- **IoU** — Jaccard index of `{grid > thresh*max(grid)}` regions (default `thresh=0.1`).
- **Coverage / entropy / center-of-mass / hotspot count** — per-session spatial-spread summaries.
- **Scanpath** — path points are mapped to grid cells (`visited_sequence`, run-length-deduped),
  compared via a normalized Levenshtein similarity (`1 - edit_distance/max(len,len,1)`) and
  summarized via consecutive-transition Shannon entropy, path length (px), and revisit count.
- **Inter-observer** — mean pairwise CC, and ICC(2,1) (two-way random-effects, absolute
  agreement, single-rater; Shrout & Fleiss 1979 / McGraw & Wong 1996) via the documented
  two-way-ANOVA formula, with cells as rows and sessions as columns.
- **Cross-session consistency** — `coincidenceLevel` (fraction of cells above-threshold in ≥2
  readers' own-max-normalised grids, normalized to the **visited footprint** — cells
  above-threshold in ≥1 reader, *not* the whole grid; Roa-Peña reports ~70.5% with this style of
  rule) and `regionCoveragePct` (this session's % coverage of the consensus's above-threshold
  cells). **Bug fix (2026-07):** `coincidenceLevel` used to normalize by the whole grid instead of
  the visited footprint, silently under-reporting coincidence on any partially-explored slide —
  see `docs/superpowers/navtrack-lit-review-improvements.md` §0.A and `metrics.coincidence_level`'s
  docstring for the exact before/after.

### Phase 1 — scanpath raster + zoom/navigation metric family

Motivated by the navigation-tracking literature review cited in the project's design doc, whose
strongest diagnostic-accuracy correlates are **zoom metrics**. All formulas below are in
`blinded_focus/metrics.py`, one function per metric with a docstring pinning the exact edge-case
behavior (an R port must match these, not just the "happy path" formula):

- **`raster_from_path(path, img_w, img_h, gw, gh)`** — rebuilds a `gh x gw` dwell-ms grid directly
  from the scanpath (independent of the recorded `grid` resolution): each step's `Δt` is
  attributed to the viewport rectangle of its first point, clamped to the image and spread evenly
  across the covered cells. This is the "trustworthy" heatmap for very-zoomed-in (40×+) navigation.
  `None` for a 0/1-point path.
- **`point_zoom(point, base_mag, img_w)`** — magnification for one scanpath point, in fallback
  order: (1) true `base_mag / (dsMilli/1000)` when both are known (schema/4); (2) a unitless
  `1000/dsMilli` "zoom level" when only `dsMilli` is known; (3) a width-proxy `img_w/w` for
  schema/3 (5-element) points. Higher always means "more zoomed in" in every branch.
- **`avgZoom` / `zoomVariance` / `zoomRange`** — mean / sample variance (`ddof=1`, matches R's
  `var()`) / range of `point_zoom` over every path point. `0.0` (never NaN/NA) for a path with
  fewer than 2 points.
- **`magnificationPercentage`** — fraction of consecutive transitions with strictly increasing
  zoom (zoom-IN only): `|{i : zoom[i+1] > zoom[i]}| / (n-1)`. Exact `>`, no tolerance (`point_zoom`
  is deterministic on integer-quantized inputs, so a held zoom level yields bit-identical floats).
  `0.0` for fewer than 2 points. **Bug fix (2026-07):** this used to count held-zoom ties too
  (`>=`); Ghezloo's definition requires a strict increase — see
  `docs/superpowers/navtrack-lit-review-improvements.md` §0.B and
  `metrics.magnification_percentage`'s docstring for the exact before/after.
- **`scanningRatePxPerMin`** / **`drillingRatePerMin`** — pan distance accumulated over
  "zoom-unchanged" steps (exact equality, same determinism note), and count of "zoom-changed"
  steps, both normalized by the path's **total duration** (`(t[-1]-t[0])/60000`, minutes) — the
  design doc does not pin this denominator; total-session-time was the chosen convention (see
  code docstring).
- **`pathVelocityPxPerSec`** — median of per-step `distance/Δt` (px/sec); steps with non-positive
  `Δt` get velocity `0.0` rather than being dropped.
- **`linearity`** — net first→last displacement / total scanpath length. `0.0` for a degenerate
  (zero-length) path.
- **`searchFocusRatio`** — Δt-weighted fraction of steps that are "focused": zoom ≥ the path's own
  median zoom, OR velocity ≤ the path's own median velocity (both thresholds session-relative, not
  fixed absolute cutoffs).
- **`zoom_band_labels`** — bins path steps into `n_bands` (terciles by default) using
  `numpy.quantile` (linear interpolation — numerically identical to R's `quantile(type=7)`) cut
  points, assigned via `numpy.searchsorted(..., side="right")` (equivalent to R's
  `findInterval()`). Powers the magnification-split heatmaps/CSV.

These formulas are pinned exactly (see the project's design doc) so that the parallel R toolkit
under `analysis/R/` reproduces the same numbers on the same input.

### Phase 2 — annotation + cursor metric family

Motivated by the same literature review's ROI-based and partial-attention metrics, once ground-
truth-style reader annotations (schema/4+) and cursor position (schema/5) are available. All
formulas are in `blinded_focus/metrics.py`; the GeoJSON-to-grid rasterization they consume
(`rasterize_roi`) lives in `blinded_focus/analyze.py` and is the *same* rasterizer already used
for the `--roi` CLI flag — annotation metrics never parse GeoJSON themselves, only an
already-rasterized boolean cell mask.

- **`nAnnotations` / `annotatedAreaPx`** — feature count and total polygon area (image px²) of a
  session's own `annotations` FeatureCollection, via the shoelace formula (exterior ring area
  minus hole-ring areas, both by absolute value so ring winding order doesn't matter). Grid-only
  (no `path` needed) — populated (0 / 0.0) for every session, including path-less ones.
- **`dwellInAnnotationPct`** (Ghezloo's "ROI time percentage", generalized to a reader's own
  annotated region) — `100 * sum(grid[mask]) / sum(grid)`. `0.0` if total dwell is 0 or the mask
  has no annotated cells.
- **`enrichmentRatio`** (Nan 2025 Nat Commun) — `mean(grid[mask]) / mean(grid[~mask])`. Blank
  (`NaN`) if the mask has no annotated cells, no non-annotated cells, or zero non-annotated mean.
- **`annotationReentryCount`** (Brunyé 2017's re-entry rate) — maps the scanpath to grid cells
  (`visited_sequence`, run-length-deduped), looks up the annotation mask at each visited cell, and
  counts the number of maximal `True` ("inside") runs in that boolean sequence minus 1 (the first
  visit is an entry, not a *re*-entry): `max(0, n_visits - 1)`. Blank without a `path` at all;
  `0` if there's a path but no annotation, or the path never enters the region.
- **`cursorOverSlidePct`** / **`mouseViewportCouplingPx`** (a partial-attention proxy after
  Raghunath, schema/5 8-element points only: `[..., dsMilli, mouseX, mouseY]`) — percentage of
  path points where the cursor was over the slide (`mouseX/mouseY != (-1,-1)`), and the median
  Euclidean distance (image px) between the cursor and the viewport center over on-slide points
  only. Both blank for any fragment whose path doesn't carry 8-element points (schema </5).
- **Cross-user annotation agreement** (`annotations_<slug>.csv`) — pairwise IoU of each session's
  own rasterized annotated region (resampled to the slide's common `(tw, th)` grid, same as the
  dwell-based `compare_<slug>.csv`), plus a slide-level `coincidenceLevel` reusing the same
  (fixed, visited-footprint) `coincidence_level` function.

## Data model recap

- `slideKey` identifies a slide (already anonymised upstream — `sha256:`-hashed for non-http
  sources); `sessionId` identifies one recording sitting ("user" in this toolkit's vocabulary —
  one person across multiple sittings appears as multiple sessionIds; a coordinator maps identity
  to a real name out-of-band, e.g. via `--labels`).
- `grid` is a row-major `gridWidth x gridHeight` array — milliseconds of dwell for schema `/2`,
  `/3`, `/4`, `/5`, fixed-weight sample counts for schema `/1`.
- `path` (schema `/3`+) is the ordered, capped list of viewport samples (image-pixel center +
  visible extent, time relative to that slide's recording start). `/3` points are 5-element
  `[tRelMs, cx, cy, w, h]`; `/4` points are 6-element `[tRelMs, cx, cy, w, h, dsMilli]`
  (`dsMilli` = downsample × 1000); `/5` points are 8-element
  `[tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY]` — purely additive over `/4` (`mouseX`/`mouseY`
  are the cursor's position in the same image-pixel space as `cx`/`cy`, or the sentinel `-1, -1`
  when the cursor was off the slide viewer). `/4`+ fragments also carry a fragment-level
  `baseMagnification` (number, or absent/`null` if unknown), `pathTruncated` (bool), and
  `annotations` (a GeoJSON `FeatureCollection` snapshot of the reader's own slide annotations —
  geometry in image px, plus each feature's `properties`, which may include `name`,
  `classification.name`, and `metadata.ANNOTATION_DESCRIPTION`; defaults to an empty
  FeatureCollection via `blinded_focus.io.get_annotations` when absent or malformed). Fragments
  without a `path` (schema `/1`, `/2`) still get full spatial/dwell analysis; only the scanpath-,
  zoom/navigation-, and path-dependent annotation/cursor outputs are skipped (left blank in
  `metrics.csv`) for them.
