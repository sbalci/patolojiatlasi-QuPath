# Blinded focus — evaluation & analysis

Offline analysis of the **blinded focus recordings** produced by the QuPath atlas extension
(`Extensions → Araştırma → Odak ısı haritası → Gezinme kaydı (araştırma)` and blinded-research projects). The
extension records, silently and anonymously, **where** a viewer looked and **for how long** — and,
since schema `/3`, the **ordered navigation path (scanpath)**, and since schema `/4`, the
**per-point zoom/magnification** (`dsMilli` + a fragment-level `baseMagnification`). These tools
turn that data into per-user metrics (including a Phase-1 zoom/navigation family motivated by the
navigation-tracking literature), figures, cross-user agreement, reference comparison, and scanpath
analysis.

Nothing here runs inside QuPath — analysis is deliberately offline (the in-app blinding forbids
showing the map *during* recording, not analyzing it afterwards).

## The data

Each recording writes one JSON **fragment per slide** (in `<project>/atlas-focus/`, bundled into a
timestamped `atlas-focus_*.zip` on session end). The extension currently writes schema **`/1`**
(visible "Contribute" mode — fixed-weight sample counts) or **`/4`** (blinded recording — dwell
milliseconds + scanpath + zoom); schemas `/2` and `/3` were superseded and are no longer produced,
but the tools below still **accept** them for backward compatibility with older recordings. Fields
(schema `atlas-focus-contribution/{1,2,3,4}`):

| field | meaning |
|-------|---------|
| `slideKey` | stable slide id (public DZI URL, or `sha256:` for local slides) — groups sessions |
| `sessionId` | random per-recording UUID = **"user"** (one sitting; map to a participant out-of-band) |
| `grid` | row-major `gridWidth×gridHeight` dwell map — **milliseconds** (`/2`,`/3`,`/4`) or sample counts (`/1`) |
| `durationMs`, `sampleCount` | total active dwell / samples |
| `imageWidth/Height`, `gridWidth/Height` | dimensions to map image px ↔ grid cells |
| `path` (`/3`, `/4`) | ordered scanpath — viewport center + extent over time. `/3` points are 5-element `[tMs, cx, cy, w, h]`; `/4` points are 6-element `[tMs, cx, cy, w, h, dsMilli]` (`dsMilli` = downsample×1000). |
| `baseMagnification` (`/4` only) | the slide's objective power (number), or absent/`null` if unknown — combines with `dsMilli` into a true magnification |
| `pathTruncated` (`/4` only) | bool: the recorder's point cap was hit and further points were dropped |
| `date` | date only (no time) — anonymized |

**Anonymized by construction:** no username, no absolute paths, random session id, date-only. A
`sessionId` is a *sitting*, not a person — the study coordinator maps `sessionId → participant`
separately (optionally via a `--labels sessionId,label` CSV the tools accept). Scanpath is viewport
coordinates + relative time only — behavioral, but still no PII.

## Pipeline

```
record (QuPath, blinded)  ->  atlas-focus_*.zip  ->  analysis (here)
```

Collect the participants' zips (or the `atlas-focus/` folders), then run one of:

### 1. Quick look — zero dependencies

```bash
python3 ../tools/quicklook-blinded-focus.py <zip-or-dir...> --out quicklook
```
Pure standard library (no install). One heatmap PNG per (slide, session), with the scanpath overlaid
(time-graded blue→red) when present. For eyeballing; not for stats.

### 2. Python toolkit (advanced) — `python/`

numpy · pandas · matplotlib · scipy. See [`python/README.md`](python/README.md).
```bash
python3 -m venv .venv && .venv/bin/pip install -r python/requirements.txt
cd python && python -m blinded_focus.analyze <zip-or-dir...> --out out --figures \
    [--reference <sessionId>] [--roi expert.geojson] [--labels labels.csv] \
    [--res 512] [--magbands 3]
```

### 3. R toolkit (advanced) — `R/`

jsonlite · dplyr · tidyr · ggplot2 · irr · proxy. See [`R/README.md`](R/README.md).
```bash
Rscript R/requirements.R      # installs missing packages
Rscript R/run_analysis.R <zip-or-dir...> --out out --figures \
    [--reference <sessionId>] [--roi expert.geojson] [--labels labels.csv]
```

The Python and R toolkits are **parallel implementations of the same metrics** and emit the **same
output files/columns** (`metrics.csv`, `compare_<slide>.csv`, `reference_<slide>.csv`,
`scanpath_<slide>.csv`, `magbands_<slide>.csv`, `consensus_<slide>.png`, `summary.md`) — verified to
agree numerically — so use whichever environment you prefer. (The Phase-1 zoom/navigation metric
family below is currently Python-only; the R port is a separate, planned task and must match the
formulas documented in `python/blinded_focus/metrics.py`.)

## What the analysis computes

- **Per session** (`metrics.csv`): total dwell time, coverage %, dwell **entropy** (focused vs
  scattered), center-of-mass, hotspot count, and (scanpath) path length, revisits, transition
  entropy. Since schema `/4`, also a **zoom/navigation metric family** motivated by the strongest
  diagnostic-accuracy correlates in the navigation-tracking literature: `avgZoom`/`zoomVariance`/
  `zoomRange`, `magnificationPercentage` (consecutive zoom-in-or-same fraction), `scanningRatePxPerMin`
  / `drillingRatePerMin` (panning at held zoom vs zoom-change events), `pathVelocityPxPerSec`,
  `linearity`, `searchFocusRatio`.
- **Spatial similarity / cross-user agreement** (per slide): the standard saliency set — **CC**
  (Pearson), **SIM** (histogram intersection), **KLD**, **NSS**, **AUC-Judd**, **IoU** — a pairwise
  matrix, a **consensus** heatmap, each user's **difference-from-consensus**, and an inter-observer
  agreement score (mean pairwise CC + **ICC**), plus a **coincidence level** (fraction of cells
  above-threshold in ≥2 readers) and per-session **region coverage %** vs the consensus.
  *"Do readers attend to the same regions?"*
- **Reference comparison** (`--reference <sessionId>` = an expert's session, or `--roi` = a
  QuPath-exported GeoJSON): each participant's NSS/AUC/CC/IoU against the reference attended-map,
  fraction of the reference region covered, and **time-on-reference vs off**. *"Did the trainee look
  where the expert (or the marked ROI) did?"*
- **Scanpath** (schema `/3`, `/4`): pairwise sequence similarity (Levenshtein over the visited-cell
  sequence) + per-session transition entropy. *"Do they navigate in the same order / systematically?"*
- **Scanpath-rasterized fine heatmap** (`--figures`, schema `/3`/`/4`): rebuilds the dwell heatmap
  directly from the scanpath's viewport rectangles at a chosen resolution (`--res`, default 512),
  independent of the recorded grid — the trustworthy map for very-zoomed-in (40×+) navigation.
- **Magnification-split** (`--figures` + `magbands_<slide>.csv`): bins scanpath points into
  within-path zoom bands (terciles by default, `--magbands`) and renders a heatmap + reports dwell
  time per band — *"at what zoom level did they inspect this region?"*

## Notes / limits

- Fragments recorded **before schema `/3`** carry no `path`; the spatial/dwell analysis still runs,
  scanpath sections are skipped. Fragments **before schema `/4`** carry no per-point zoom
  (`dsMilli`) or `baseMagnification`; the zoom/navigation metrics fall back to a width-proxy zoom
  (schema `/3`) or are skipped entirely (schema `/1`/`/2`, no path at all). The tools handle mixed
  `/1`+`/2`+`/3`+`/4` inputs.
- The dwell grid is **zoom-weighted** (a zoomed-in view concentrates dwell; a wide glance spreads it)
  — it reflects zoom-adjusted attention, not pixel-exact gaze.
- Scanpath sequence similarity here is a documented Levenshtein/transition approximation, not full
  MultiMatch.
