# Blinded evaluation tooling: scanpath + render + analysis — design

- **Date:** 2026-07-22
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/blinded-evaluation-tooling`
- **Status:** Approved design (evaluation tooling for the blinded focus recording feature), ready for planning

## 1. Goal

Turn recorded blinded-focus data into evaluation artifacts:
1. **Scanpath recording** (extension) — also log the ordered viewport path (time + center + extent per
   sample) so navigation *sequence* (not just accumulated dwell) can be analyzed.
2. **PNG generator** (offline) — a heatmap PNG per slide × per session/user, plus a scanpath overlay,
   plus per-slide consensus + difference maps.
3. **Analysis** (offline) — per-session metrics + **cross-user** agreement/consensus **and**
   **reference-vs-participant** comparison (spatial + temporal), including scanpath-sequence metrics.

Offline tools are the intended visualization path (the in-app blinding only forbids showing the map
*inside QuPath during recording*). Tools stay **pure-stdlib Python** (matching `aggregate-focus.py`;
portable, no pip). PNGs reuse the aggregator's hand-rolled colormap/writer.

## 2. Scanpath recording — extension (schema `/3`, additive)

`FocusHeatmap` already deposits per-tick dwell into the grid. Add, alongside it, an ordered path:
- A per-slide `List<int[]>` `blindedPath`; on each **active** blinded tick, append
  `[tRelMs, cx, cy, w, h]` — `tRelMs` = ms since this slide's blinded recording started (a
  `blindedSlideStartMs`), `cx,cy` = viewport center in **image pixels**, `w,h` = the visible
  region's extent in image pixels (from the same `getDisplayedRegionShape().getBounds()` used for
  the deposit). Integers (rounded) to keep it compact.
- **Cap** at `MAX_PATH_POINTS = 20000` (~83 min at 4/s); once reached, stop appending (log once).
  Bounds memory + file size.
- Reset `blindedPath` + `blindedSlideStartMs` on `startBlinded()` and on each fresh slide map in
  `switchTo`.
- Serialize into the blinded JSON: bump `CONTRIBUTION_SCHEMA` to `atlas-focus-contribution/3`; add
  `"path": [[t,cx,cy,w,h], ...]`. **Keep** the existing `grid`/`durationMs`/`weightUnit`/dims (the
  path is additive; the grid is still the direct spatial map and stays for backward-compat).
- Include the path in the checkpoint, the final fragment, AND the shutdown flush (build it in
  `buildBlindedJson`, which every blinded write already routes through — so no new write path and
  the data-only + anonymization guarantees are unchanged: the path is viewport coordinates + zoom +
  relative time, no identity; `slideKey` still `sha256:`-anonymized for non-http).
- **Aggregator:** `tools/aggregate-focus.py` accepts `/3` too (it reads `grid`; `path` is ignored
  there). Additive change to its accepted-schema set.

## 3. Shared loader — `tools/blinded_focus_common.py` (stdlib)

- `load_fragments(paths)` — accept files, directories, and **`.zip`** archives (the participant
  sends a zip). Recursively glob `*.json` in dirs; read `*.json` entries from any `.zip`. Keep only
  dicts whose `schema` is in `{/1,/2,/3}` with `slideKey` + `grid`. Return a list of fragment dicts,
  each tagged with its source name. (Reuse `aggregate-focus.py`'s filter idea.)
- `group_by_slide(fragments)` → `{slideKey: [fragment, ...]}`; `session_of(frag)` = `sessionId`.
- Grid helpers: `normalise(grid)` (÷max), `resample(grid, gw, gh, tw, th)` (nearest, from the
  aggregator), `coverage(grid)` (non-zero / total), `entropy(grid)` (Shannon of the normalized
  distribution — focused vs scattered), `center_of_mass(grid, gw, gh)`, `top_hotspots(grid, gw, gh,
  n)`. All pure Python.
- Similarity (pure): `cosine(a,b)`, `pearson(a,b)`, `iou_viewed(a,b, thresh)` (Jaccard of
  above-threshold cells) — grids resampled to a common size first.
- `heat_rgba`/`write_png` — imported/copied from the aggregator's colormap so PNGs match the
  existing look.
- Optional `--labels labels.csv` (`sessionId,label`) so output uses participant labels instead of
  raw UUIDs (coordinator maps identity out-of-band; the data itself stays anonymous).

## 4. `tools/render-blinded-focus.py` (stdlib)

`python3 tools/render-blinded-focus.py <input-dir-or-zip...> --out <dir> [--labels labels.csv] [--scale N]`
- Per fragment (slide × session): write `<out>/<slideSlug>/<sessionLabel>.png` — the grid via the
  heat colormap, upscaled `--scale` (default 6), with a small footer band drawn on the raster:
  `slide · session · durationMs · coverage%` (hand-rendered text is overkill — instead write a
  sidecar `<...>.txt`/embed in filename; keep the PNG pure heat + optional scanpath).
- **Scanpath overlay** (when `path` present, schema/3): draw the ordered path as line segments on
  the raster (simple integer line raster between consecutive centers, mapped image→grid→scaled
  px), color graded start→end (e.g. blue→red over time) so direction reads; mark the first point.
- **Per-slide consensus** `<out>/<slideSlug>/_consensus.png` — mean of per-session normalized grids
  (resampled to a common size), min-N guard (skip if < `--min-sessions`, default 1).
- **Per-slide difference** `<out>/<slideSlug>/_diff_<session>.png` — each session's normalized grid
  minus the consensus (diverging colormap), showing where that user over/under-attended.
- Selftest: `--selftest` generates synthetic fragments (a couple of sessions, one with a path) and
  asserts the expected PNGs are produced and are valid PNG bytes.

## 5. `tools/analyze-blinded-focus.py` (stdlib)

`python3 tools/analyze-blinded-focus.py <input...> --out <dir> [--reference <sessionId>] [--labels ...] [--roi roi.geojson]`
- **Per-session metrics → `<out>/metrics.csv`:** one row per (slideKey, session): durationMs,
  sampleCount, coverage%, entropy, centerOfMass(x,y), peakDwell, nHotspots, and (schema/3)
  pathPoints, pathLengthPx (sum of segment lengths), nRevisits (returns to a previously-dwelt cell).
- **Cross-user (per slide) → `<out>/compare_<slideSlug>.csv` + a consensus PNG:** the pairwise
  similarity matrix (cosine + IoU) across sessions; a mean/consensus grid; per-session
  difference-from-consensus score (1 − cosine); a coverage/time spread summary (min/median/max
  durationMs + coverage across users). Answers "do users look at the same regions?".
- **Reference-vs-participant (when `--reference <sessionId>` OR `--roi <geojson>`):** for each other
  session on that slide, compute overlap with the reference dwell (cosine + IoU), **coverage of the
  reference's hotspot region** (fraction of the reference's above-threshold cells the participant
  also dwelt on), and **time-on-reference vs off** (participant dwell-ms inside vs outside the
  reference region). `--roi` reads a QuPath-exported GeoJSON polygon, rasterizes it to the grid as
  the reference region (pure-Python point-in-polygon). Output ranked per participant. Answers "did
  the trainee look where the expert / the marked ROI is?".
- **Scanpath sequence (schema/3, optional):** a coarse sequence-similarity between sessions — map
  each path to the ordered list of grid cells visited (deduped run-length), compare via normalized
  Levenshtein on the cell-index sequence (pure Python); report a matrix. (MultiMatch-lite; a
  documented approximation, not the full 5-dimension MultiMatch.)
- **Summary → `<out>/summary.md`** — counts, per-slide participant lists, headline agreement
  numbers, and the reference ranking when given.
- Selftest: synthetic multi-session + a reference → asserts metrics.csv columns/rows, a compare
  matrix is symmetric with 1.0 on the diagonal, and reference overlap is 1.0 for the reference vs
  itself.

## 6. Data reality / honest scoping (state in the tools' `--help` + summary)

- The **grid** is accumulated dwell → spatial (where) + total/per-region time (how long); it does
  NOT carry order. The **path** (schema/3, new) adds the ordered navigation for sequence analysis.
- Fragments recorded **before** schema/3 have no path → scanpath outputs are skipped for them (the
  spatial/dwell analysis still runs). The tools handle mixed /2 + /3 inputs.
- "User" = `sessionId` (a recording sitting). One person across sittings = multiple sessionIds; the
  coordinator maps identity out-of-band (optionally via `--labels`). The data stays anonymous.
- Scanpath is more behavioral than a heatmap; still no PII (coordinates/time only), for consented
  participants — consistent with the feature's governance.

## 7. Testing

- **Extension (Java):** no new unit test for the FX path append (per convention); the existing
  blinded tests + build stay green; manual: a schema/3 fragment carries a non-empty `path` capped at
  the max.
- **Python:** each tool ships a `--selftest` (stdlib `assert`-based, runs in this environment since
  it's numpy-free) generating synthetic fragments and validating outputs; plus a
  `tools/test-blinded-tooling.sh`/py runner. Verified end-to-end on synthetic data before commit.

## 7b. Revision (user, 2026-07-22): advanced R + Python for experts, separate environments

The analysis tools are for **experts running in their own R / Python environments** — so the
stdlib-only constraint is lifted for the *analysis* layer (the extension stays untouched; the
aggregator stays stdlib). Deliver **two parallel advanced toolkits** under a top-level `analysis/`
tree (standalone, "outside the extension"):

- `analysis/python/` — a `blinded_focus` package using **numpy / pandas / matplotlib / scipy**
  (`requirements.txt`, install in a venv), with a CLI + importable API + a synthetic `selftest`.
- `analysis/R/` — a parallel toolkit using **jsonlite / dplyr / tidyr / ggplot2 / irr / proxy**
  (a `requirements.R` installer), with a `run_analysis.R` + functions + a synthetic `selftest.R`.

Both compute the standard eye-tracking/saliency evaluation set (not just cosine/IoU): spatial
similarity **CC (Pearson), SIM (histogram intersection), KLD, NSS, AUC-Judd/IoU**; inter-observer
agreement (**ICC / mean pairwise CC**, a per-slide consensus/"inter-observer congruency" map);
reference-vs-participant (NSS/AUC of each participant against the reference fixation/ROI map,
time-on-ROI); and **scanpath** metrics (ScanMatch-/Levenshtein-style sequence similarity over the
visited-cell sequence, plus a transition matrix + transition entropy). Publication-quality figures
(matplotlib / ggplot2): per-user heatmap, scanpath overlay (time-graded), consensus, difference,
coverage-over-time. Env note: **R 4.4 + those packages are already available; the Python stack
installs via the requirements file in a venv** — both ship a numpy/ggplot-driven synthetic selftest.
(A tiny zero-dep `tools/quicklook-blinded-focus.py` stdlib PNG viewer is a bonus for a no-setup
glance, reusing the aggregator's colormap.) This supersedes §4–§5's stdlib `render`/`analyze`
scripts.

## 8. Non-goals

- No numpy/matplotlib/scipy (stdlib-only). No network/upload. No full MultiMatch (a documented
  approximation). No GUI. The extension still never renders blinded data in-app.
