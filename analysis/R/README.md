# blinded_focus — R analysis toolkit

Standalone R toolkit — the sibling of `analysis/python/` — that turns anonymised QuPath atlas
**blinded-focus** fragments (schema `atlas-focus-contribution/{1,2,3}`) into per-session metrics,
publication figures, cross-user agreement, reference/ROI comparison, and scanpath analysis.

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

`selftest.R` synthesizes 3 sessions on one slide (2 similar dwell grids + 1 different, mixed
schema/2 + schema/3, one designated as `--reference`), runs the full `analyze()` pipeline into a
temp directory, and asserts the output contract described below (symmetric compare matrix with a
1.0 diagonal, similar-pair CC > dissimilar-pair CC, high reference-vs-itself NSS/CC, scanpath
diagonal 1.0, valid PNGs, `.zip` input support). Exits non-zero (`quit(status = 1)`) on any
failure.

## Usage

```bash
Rscript run_analysis.R <input...> --out DIR \
    [--reference SESSIONID] [--roi roi.geojson] [--labels labels.csv] [--figures]
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
  coverage-over-time) under `<out>/<slide-slug>/`.

Also usable as a library: `source("blinded_focus.R")` gives you `load_fragments`, `resample_nn`,
`cc`/`sim`/`kld`/`nss`/`auc_judd`/`iou`, `visited_sequence`/`levenshtein_sim`, `mean_pairwise_cc`/
`icc`, the `plot_*` ggplot2 builders, and the top-level `analyze()` pipeline function directly.

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
| `metrics.csv` | One row per (slide, session): `slide,session,durationMs,sampleCount,coveragePct,entropy,comX,comY,peakDwell,nHotspots,pathPoints,pathLengthPx,nRevisits,transitionEntropy`. Path-derived columns are blank for sessions without a schema/3 `path`. |
| `compare_<slug>.csv` | Per slide, pairwise agreement: `sessionA,sessionB,cc,sim,iou,diffFromConsensus`. Tidy long format (one row per ordered pair, including the diagonal) — `pivot_wider(names_from = sessionB, values_from = cc)` in R (or `pivot()` in pandas) recovers the matrix. The diagonal row (`sessionA == sessionB`) additionally carries `diffFromConsensus = 1 - cc(session, consensus)`; off-diagonal rows leave that column blank. |
| `consensus_<slug>.png` | Heatmap of the mean of each session's max-normalised, common-grid-resampled dwell map. |
| `reference_<slug>.csv` | Written when `--reference` and/or `--roi` is given. One row per session (including the reference itself, for a self-check): `session,nss,aucJudd,cc,iou,refCoveragePct,timeOnRefMs,timeOffRefMs`, ranked descending by `nss`. |
| `scanpath_<slug>.csv` | Written when at least one session on the slide has a schema/3 `path`. Pairwise `sessionA,sessionB,levenshteinSim,transitionEntropy` over visited-cell sequences (same tidy/diagonal-reuse convention as `compare_<slug>.csv`); sessions without a path are excluded entirely. |
| `summary.md` | Slide/session counts, per-slide mean pairwise CC + ICC(2,1) + coverage/duration spread, and the reference ranking when applicable. |
| `<slug>/<session>_heatmap.png`, `_scanpath.png`, `_coverage.png` | With `--figures`: per-(slide, session) figures at native grid resolution. Scanpath/coverage figures are only written for sessions with a schema/3 `path`. |

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

## Parity with the Python toolkit

Both toolkits are pinned to the same formulas and the same output-file contract (file names, CSV
columns, tidy long-format with diagonal-row extras) so a single input directory analyzed by both
produces directly comparable numbers. Two small, intentional, harmless divergences:

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

## Data model recap

- `slideKey` identifies a slide (already anonymised upstream — `sha256:`-hashed for non-http
  sources); `sessionId` identifies one recording sitting ("user" in this toolkit's vocabulary —
  one person across multiple sittings appears as multiple sessionIds; a coordinator maps identity
  to a real name out-of-band, e.g. via `--labels`).
- `grid` is a row-major `gridWidth x gridHeight` array — milliseconds of dwell for schema `/2`
  and `/3`, fixed-weight sample counts for schema `/1`.
- `path` (schema `/3` only) is the ordered, capped list of `[tRelMs, cx, cy, w, h]` viewport
  samples (image-pixel center + visible extent, time relative to that slide's recording start).
  Fragments without a `path` (schema `/1`, `/2`, or pre-schema/3 recordings) still get full
  spatial/dwell analysis; only the scanpath-specific outputs are skipped for them.
