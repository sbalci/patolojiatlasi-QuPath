# blinded_focus — R analysis toolkit

Standalone R toolkit — the sibling of `analysis/python/` — that turns anonymised QuPath atlas
**blinded-focus** fragments (schema `atlas-focus-contribution/{1,2,3,4}`) into per-session metrics
(including a Phase-1 zoom/navigation metric family), publication figures, cross-user agreement,
reference/ROI comparison, and scanpath analysis.

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

`selftest.R` synthesizes 3 sessions on one slide (2 similar dwell grids + 1 different; mixed
schema/2 (no path) + schema/3 (5-element path, w-proxy zoom) + schema/4 (6-element path, varying
zoom + `baseMagnification`), one designated as `--reference`), runs the full `analyze()` pipeline
into a temp directory, and asserts the output contract described below (symmetric compare matrix
with a 1.0 diagonal, similar-pair CC > dissimilar-pair CC, high reference-vs-itself NSS/CC,
scanpath diagonal 1.0, Phase-1 zoom columns populated/blank as expected,
`magnificationPercentage` in `[0,1]`, a scanpath-rasterized fine heatmap PNG, valid PNGs, `.zip`
input support). Exits non-zero (`quit(status = 1)`) on any failure.

## Usage

```bash
Rscript run_analysis.R <input...> --out DIR \
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
- `--figures` — also render per-(slide, session) PNGs (heatmap, scanpath overlay,
  coverage-over-time, scanpath-rasterized fine heatmap, magnification-band heatmaps) under
  `<out>/<slide-slug>/`.
- `--res N` (default 512) — longest-side resolution for the scanpath-rasterized fine heatmap and
  the magnification-band heatmaps (aspect-preserving; see `.res_grid_dims`).
- `--magbands N` (default 3) — number of within-path zoom bands (terciles) for the
  magnification-split analysis (see `zoom_band_labels`).

Also usable as a library: `source("blinded_focus.R")` gives you `load_fragments`, `resample_nn`,
`cc`/`sim`/`kld`/`nss`/`auc_judd`/`iou`, `visited_sequence`/`levenshtein_sim`, `mean_pairwise_cc`/
`icc`, `raster_from_path`/`point_zoom`/the zoom-metric family/`zoom_band_labels`,
`coincidence_level`/`region_coverage_pct`, the `plot_*` ggplot2 builders, and the top-level
`analyze()` pipeline function directly.

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
| `metrics.csv` | One row per (slide, session): `slide,session,durationMs,sampleCount,coveragePct,entropy,comX,comY,peakDwell,nHotspots,pathPoints,pathLengthPx,nRevisits,transitionEntropy,avgZoom,zoomVariance,zoomRange,magnificationPercentage,scanningRatePxPerMin,drillingRatePerMin,pathVelocityPxPerSec,linearity,searchFocusRatio,baseMagnification,pathTruncated`. Path-derived columns (incl. all the zoom/navigation ones) are blank for sessions without a `path` (schema /1, /2). `baseMagnification`/`pathTruncated` are blank for schema /1, /2, /3 (which lack those fragment-level fields). |
| `compare_<slug>.csv` | Per slide, pairwise agreement: `sessionA,sessionB,cc,sim,iou,diffFromConsensus,coincidenceLevel,regionCoveragePct`. Tidy long format (one row per ordered pair, including the diagonal) — `pivot_wider(names_from = sessionB, values_from = cc)` in R (or `pivot()` in pandas) recovers the matrix. The diagonal row (`sessionA == sessionB`) additionally carries `diffFromConsensus = 1 - cc(session, consensus)` and `regionCoveragePct` (this session's % coverage of the consensus's above-threshold cells); off-diagonal rows leave those blank. `coincidenceLevel` (a slide-level, not per-session, statistic) is written on exactly one row per slide — the diagonal row of the first session in insertion order — all other rows leave it blank. |
| `consensus_<slug>.png` | Heatmap of the mean of each session's max-normalised, common-grid-resampled dwell map. |
| `reference_<slug>.csv` | Written when `--reference` and/or `--roi` is given. One row per session (including the reference itself, for a self-check): `session,nss,aucJudd,cc,iou,refCoveragePct,timeOnRefMs,timeOffRefMs`, ranked descending by `nss`. |
| `scanpath_<slug>.csv` | Written when at least one session on the slide has a `path` (schema /3 or /4). Pairwise `sessionA,sessionB,levenshteinSim,transitionEntropy` over visited-cell sequences (same tidy/diagonal-reuse convention as `compare_<slug>.csv`); sessions without a path are excluded entirely. |
| `magbands_<slug>.csv` | Written alongside `scanpath_<slug>.csv` (same path-session gating). Tidy `session,band,bandTimeMs,bandTimePct` — per-session dwell time (and % of that session's total path duration) in each of `--magbands` within-path zoom bands (band 0 = lowest zoom, highest index = highest zoom; see `zoom_band_labels`). |
| `summary.md` | Slide/session counts, per-slide mean pairwise CC + ICC(2,1) + coverage/duration spread + coincidence level + mean avgZoom/scanningRate/drillingRate/magnificationPercentage, and the reference ranking when applicable. |
| `<slug>/<session>_heatmap.png`, `_scanpath.png`, `_coverage.png`, `_scanpath_raster.png`, `_magband<N>.png` | With `--figures`: per-(slide, session) figures. `_heatmap` is at native recorded-grid resolution; `_scanpath`/`_coverage`/`_scanpath_raster`/`_magband<N>` are only written for sessions with a `path` (schema /3 or /4). `_scanpath_raster` is the scanpath-rasterized fine heatmap at `--res` resolution (independent of the recorded grid); `_magband<N>` is one heatmap per within-path zoom band that has at least one step (bands with zero steps are skipped, so a session may have fewer than `--magbands` band PNGs). |

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
  readers' own-max-normalised grids) and `regionCoveragePct` (this session's % coverage of the
  consensus's above-threshold cells).

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
- **`magnification_percentage`** — fraction of consecutive transitions with non-decreasing zoom:
  `|{i : zoom[i+1] >= zoom[i]}| / (n-1)`. Exact `>=`, no tolerance. `0.0` for fewer than 2 points.
- **`scanning_rate_px_per_min`** / **`drilling_rate_per_min`** — pan distance accumulated over
  "zoom-unchanged" steps, and count of "zoom-changed" steps, both normalized by the path's total
  duration (`(t[last]-t[first])/60000`, minutes).
- **`path_velocity_px_per_sec`** — median of per-step `distance/dt` (px/sec); steps with
  non-positive `dt` get velocity `0.0` rather than being dropped.
- **`linearity`** — net first->last displacement / total scanpath length. `0.0` for a degenerate
  (zero-length) path.
- **`search_focus_ratio`** — dt-weighted fraction of steps that are "focused": zoom >= the path's
  own median zoom, OR velocity <= the path's own median velocity.
- **`zoom_band_labels`** — bins steps into within-path quantile bands (terciles by default) via
  `stats::quantile(x, probs, type = 7)` (R's default -- numerically identical to numpy's default
  linear-interpolation method) + `findInterval(zooms, cuts)` (equivalent to numpy's
  `searchsorted(cuts, zooms, side="right")`).

## Parity with the Python toolkit

Both toolkits are pinned to the same formulas and the same output-file contract (file names, CSV
columns, tidy long-format with diagonal-row extras) so a single input directory analyzed by both
produces directly comparable numbers — verified end-to-end on identical fixed synthetic input
(same fragment JSONs fed to both CLIs): every numeric column in `metrics.csv`, `compare_<slug>.csv`,
`scanpath_<slug>.csv`, `magbands_<slug>.csv`, and `reference_<slug>.csv` matches to floating-point
tolerance once parsed as typed values. Small, intentional, harmless divergences:

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
  `/3`, `/4`, fixed-weight sample counts for schema `/1`.
- `path` (schema `/3`, `/4`) is the ordered, capped list of viewport samples (image-pixel center +
  visible extent, time relative to that slide's recording start). Schema/3 points are 5-element
  `[tRelMs, cx, cy, w, h]`; schema/4 points are 6-element `[tRelMs, cx, cy, w, h, dsMilli]` and
  schema/4 fragments also carry `baseMagnification` (the slide's objective power, or `NULL`/absent
  when unknown) and `pathTruncated` (the recorder's point cap was hit). Fragments without a `path`
  (schema `/1`, `/2`) still get full spatial/dwell analysis; only the scanpath-specific and
  zoom/navigation outputs are skipped for them.
