# Blinded Analysis Toolkits (advanced Python + R) — Implementation Plan

> Supersedes the stdlib Tasks 2–3 of `2026-07-22-blinded-evaluation-tooling.md` (per user: analysis
> runs in separate expert R/Python environments). Task 1 (extension scanpath, schema/3) is done.
> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Two standalone, expert-grade analysis toolkits — Python (`analysis/python/`) and R
(`analysis/R/`) — that turn blinded focus fragments (schema /1,/2,/3, from dirs or zips) into
per-user metrics, publication figures, cross-user agreement, reference comparison, and scanpath
analysis.

**Tech Stack:** Python 3 + numpy/pandas/matplotlib/scipy (venv, `requirements.txt`); R 4.4 +
jsonlite/dplyr/tidyr/ggplot2/irr/proxy (`requirements.R`). Both standalone (no extension/QuPath).

## Global Constraints

- **Standalone / separate envs:** live under `analysis/` (not `tools/`); no import from the
  extension; each ships its own requirements + a synthetic `selftest`.
- **Input:** fragment JSONs from directories AND `.zip` archives (participant sends a zip). Accept
  schema `atlas-focus-contribution/{1,2,3}`; fragments without `path` (pre-/3) → scanpath sections
  skipped, spatial/dwell still run.
- **"User" = sessionId** (a recording sitting); optional `--labels sessionId,label` CSV for
  human-readable output; identity mapping is out-of-band (data stays anonymous).
- **Grid** = `grid` (row-major `gridWidth×gridHeight`, ms for /2-/3, counts for /1); normalize per
  fragment (÷max or ÷sum as the metric needs) so unit/length differences wash out. Resample to a
  common grid before cross-fragment math.
- **Metrics = the standard saliency/eye-tracking set** (documented formulas): CC (Pearson), SIM
  (histogram intersection of ÷sum maps), KLD, NSS, AUC-Judd, IoU; scanpath: visited-cell-sequence
  Levenshtein similarity + transition-matrix entropy; inter-observer: mean pairwise CC + ICC (R
  `irr`, Python via a documented formula).
- Both toolkits are **importable** (a package / sourced functions) AND runnable as a CLI.

---

### Task A: `analysis/python/` — advanced Python toolkit

**Files (create):**
- `analysis/python/requirements.txt` — `numpy pandas matplotlib scipy` (pinned or `>=`).
- `analysis/python/blinded_focus/__init__.py`
- `analysis/python/blinded_focus/io.py` — `load_fragments(paths) -> pandas.DataFrame`-friendly list of dicts (dirs recurse `*.json`; `.zip` read via `zipfile`); schema filter `{/1,/2,/3}`; `group_by_slide`; `slug`; `load_labels`.
- `analysis/python/blinded_focus/metrics.py` — grid helpers (`normalise_max`, `normalise_sum`, `resample_nn(grid,gw,gh,tw,th)`, `coverage`, `entropy`, `center_of_mass`, `top_hotspots`); similarity (`cc`, `sim`, `kld`, `nss(map, fixation_mask)`, `auc_judd(map, mask)`, `iou`); scanpath (`visited_sequence(path, gw, gh, img_w, img_h)`, `levenshtein_sim(seqA,seqB)`, `transition_matrix`, `transition_entropy`, `scanpath_length_px`, `n_revisits`); inter-observer (`mean_pairwise_cc`, `icc`).
- `analysis/python/blinded_focus/figures.py` — matplotlib: `heatmap(grid,gw,gh, title, out)`, `scanpath_overlay(grid, path, ..., out)` (time-graded line + start marker), `consensus(grids, out)`, `difference(grid, consensus, out)` (diverging), `coverage_over_time(path, gw, gh, out)`.
- `analysis/python/blinded_focus/analyze.py` — the CLI orchestrator.
- `analysis/python/selftest.py` — synthetic-data end-to-end asserts.
- `analysis/python/README.md` — install (venv + requirements) + usage.

- [ ] **Step 1: Implement `io.py` + `metrics.py`** per the contracts above. Pure functions; numpy arrays internally. Every metric has a one-line docstring with the formula/reference.

- [ ] **Step 2: Implement `figures.py`** (matplotlib, `Agg` backend so it runs headless). Each returns/saves a PNG; heatmap uses a perceptually-ok colormap (e.g. `magma`/`inferno`) with a colorbar + title.

- [ ] **Step 3: Implement `analyze.py` CLI**

`python -m blinded_focus.analyze <input...> --out DIR [--reference SESSIONID] [--roi geojson] [--labels csv] [--figures]`:
- `metrics.csv` — one row per (slide, session): durationMs, sampleCount, coveragePct, entropy, comX, comY, peakDwell, nHotspots, pathPoints, pathLengthPx, nRevisits, transitionEntropy.
- Per slide: `compare_<slug>.csv` — pairwise CC + SIM + IoU matrices; `consensus_<slug>.png`; per-session `diffFromConsensus`; agreement summary (mean pairwise CC, ICC); coverage/time spread.
- Reference (`--reference` = a sessionId, and/or `--roi` = a QuPath GeoJSON polygon rasterized to the grid): per other session, NSS + AUC-Judd + CC + IoU vs the reference fixation/ROI map, `refCoveragePct`, `timeOnRefMs`/`timeOffRefMs`, ranked → `reference_<slug>.csv`.
- Scanpath (schema/3): `scanpath_<slug>.csv` — pairwise Levenshtein similarity of visited-cell sequences; per session transitionEntropy; optional transition-graph figure.
- `--figures` → also write per-(slide,session) heatmap + scanpath overlay + coverage-over-time PNGs under `<out>/<slug>/`.
- `summary.md` — counts, per-slide session list, headline agreement, reference ranking.

- [ ] **Step 4: Implement `selftest.py`**

Synthesize 3 sessions on one slide (2 similar grids, 1 different; ≥1 with a `path`) + a reference; run `analyze` to a temp dir; assert: metrics.csv has 3 rows + expected columns; compare matrix symmetric, diagonal 1.0, similar pair scores > dissimilar; reference-vs-itself NSS/CC high; scanpath diagonal 1.0; figures written when `--figures`. Also test a `.zip` input path and a mixed /2+/3 set. Exit non-zero on failure.

- [ ] **Step 5: Verify + commit**

Create a venv, install requirements, run selftest:
```bash
python3 -m venv /tmp/bfa && /tmp/bfa/bin/python -m pip -q install -r analysis/python/requirements.txt
cd analysis/python && /tmp/bfa/bin/python selftest.py
```
(On Windows the subagent uses the venv's `Scripts/python.exe`.) If the venv install is infeasible in this environment, at minimum `python3 -m py_compile` every file and run whatever import-free portions you can; document that the selftest requires the venv. Then:
```bash
git add analysis/python
git commit -m "feat(analysis): advanced Python blinded-focus toolkit (metrics/figures/cross-user/reference/scanpath)"
```

---

### Task B: `analysis/R/` — advanced R toolkit (parallel)

**Files (create):**
- `analysis/R/requirements.R` — installs jsonlite, dplyr, tidyr, ggplot2, irr, proxy (checks + `install.packages` the missing).
- `analysis/R/blinded_focus.R` — sourced functions: `load_fragments(paths)` (dirs + `.zip` via `unzip`/`utils`), `fragment_grid`, `resample_nn`, metrics (`cc`, `sim`, `kld`, `nss`, `auc_judd`, `iou`, `coverage`, `entropy`, `center_of_mass`), scanpath (`visited_sequence`, `levenshtein_sim` via `utils::adist`, `transition_entropy`), inter-observer (`mean_pairwise_cc`, `icc` via `irr::icc`), and ggplot2 figure builders (`plot_heatmap`, `plot_scanpath`, `plot_consensus`, `plot_difference`, `plot_coverage_over_time`).
- `analysis/R/run_analysis.R` — `Rscript run_analysis.R <input...> --out DIR [--reference ID] [--labels csv] [--figures]` (parse args via `commandArgs`); writes the SAME output files as the Python CLI (`metrics.csv`, `compare_<slug>.csv`, `reference_<slug>.csv`, `scanpath_<slug>.csv`, `consensus_<slug>.png`, `summary.md`) so results are comparable across the two toolkits.
- `analysis/R/selftest.R` — synthetic-data asserts mirroring the Python selftest (`stopifnot`), exit non-zero on failure.
- `analysis/R/README.md` — install (`Rscript requirements.R`) + usage.

- [ ] **Step 1: Implement `blinded_focus.R`** (functions + ggplot2 figures). Metric formulas identical to the Python ones (so numbers match). Use `jsonlite::fromJSON(..., simplifyVector=FALSE)` then coerce; `path` is a list of length-5 numeric vectors.

- [ ] **Step 2: Implement `run_analysis.R`** producing the same output files as Task A's CLI.

- [ ] **Step 3: Implement `selftest.R`** (synthetic 3 sessions + reference; `stopifnot` the same invariants: 3 metric rows, symmetric compare matrix with 1.0 diagonal, similar>dissimilar, reference-self high, scanpath diagonal 1.0).

- [ ] **Step 4: Verify + commit**

R 4.4 + all packages are present in this environment — run it for real:
```bash
Rscript analysis/R/selftest.R
```
Fix until it passes. Then:
```bash
git add analysis/R
git commit -m "feat(analysis): advanced R blinded-focus toolkit (parallel metrics/figures/ICC/reference/scanpath)"
```

---

### Task C: bonus zero-dep quick-look + top-level README

- [ ] `tools/quicklook-blinded-focus.py` — a stdlib-only (no deps) script that renders each fragment's grid to a heatmap PNG (reuse `aggregate-focus.py`'s `heat_rgba`/`write_png`) + draws the scanpath if present. `--selftest` (numpy-free, runs here). For a no-setup glance.
- [ ] `analysis/README.md` — overview: the data format (schema /1,/2,/3; grid + path; sessionId=user; anonymized), the pipeline (record → zip → analyze), and pointers to the Python vs R toolkits + the quick-look. Note identity mapping is out-of-band.
- [ ] Verify (`python3 tools/quicklook-blinded-focus.py --selftest`), commit:
```bash
git add tools/quicklook-blinded-focus.py analysis/README.md
git commit -m "feat(analysis): zero-dep quicklook PNG + analysis README"
```

---

## Self-Review (author)

- **Coverage:** advanced Python (Task A) + advanced R (Task B) both deliver metrics/figures/cross-user/reference/scanpath with the standard saliency metric set; identical output files so the two are cross-checkable; zero-dep quick-look + README (Task C). ✅
- **Env reality:** R 4.4 + packages present → Task B fully testable here; Python stack via venv (Task A) or py_compile fallback; quick-look is stdlib (testable here).
- **Consistency:** both CLIs emit the same file names/columns; metric formulas specified once and shared; input handles dir+zip + mixed /2·/3.
- **Guarantees unaffected:** analysis is read-only on the anonymized fragments; no extension change here (Task 1 already merged the scanpath).
