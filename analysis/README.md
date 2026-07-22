# Blinded focus — evaluation & analysis

Offline analysis of the **blinded focus recordings** produced by the QuPath atlas extension
(`Extensions → Patoloji Atlası → … → Kör kayıt (araştırma)` and blinded-research projects). The
extension records, silently and anonymously, **where** a viewer looked and **for how long** — and,
since schema `/3`, the **ordered navigation path (scanpath)**. These tools turn that data into
per-user metrics, figures, cross-user agreement, reference comparison, and scanpath analysis.

Nothing here runs inside QuPath — analysis is deliberately offline (the in-app blinding forbids
showing the map *during* recording, not analyzing it afterwards).

## The data

Each recording writes one JSON **fragment per slide** (in `<project>/atlas-focus/`, bundled into a
timestamped `atlas-focus_*.zip` on session end). Fields (schema `atlas-focus-contribution/{1,2,3}`):

| field | meaning |
|-------|---------|
| `slideKey` | stable slide id (public DZI URL, or `sha256:` for local slides) — groups sessions |
| `sessionId` | random per-recording UUID = **"user"** (one sitting; map to a participant out-of-band) |
| `grid` | row-major `gridWidth×gridHeight` dwell map — **milliseconds** (`/2`,`/3`) or sample counts (`/1`) |
| `durationMs`, `sampleCount` | total active dwell / samples |
| `imageWidth/Height`, `gridWidth/Height` | dimensions to map image px ↔ grid cells |
| `path` (`/3` only) | ordered scanpath `[[tMs, cx, cy, w, h], …]` — viewport center + extent over time |
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
    [--reference <sessionId>] [--roi expert.geojson] [--labels labels.csv]
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
`scanpath_<slide>.csv`, `consensus_<slide>.png`, `summary.md`) — verified to agree numerically — so
use whichever environment you prefer.

## What the analysis computes

- **Per session** (`metrics.csv`): total dwell time, coverage %, dwell **entropy** (focused vs
  scattered), center-of-mass, hotspot count, and (scanpath) path length, revisits, transition entropy.
- **Spatial similarity / cross-user agreement** (per slide): the standard saliency set — **CC**
  (Pearson), **SIM** (histogram intersection), **KLD**, **NSS**, **AUC-Judd**, **IoU** — a pairwise
  matrix, a **consensus** heatmap, each user's **difference-from-consensus**, and an inter-observer
  agreement score (mean pairwise CC + **ICC**). *"Do readers attend to the same regions?"*
- **Reference comparison** (`--reference <sessionId>` = an expert's session, or `--roi` = a
  QuPath-exported GeoJSON): each participant's NSS/AUC/CC/IoU against the reference attended-map,
  fraction of the reference region covered, and **time-on-reference vs off**. *"Did the trainee look
  where the expert (or the marked ROI) did?"*
- **Scanpath** (schema `/3`): pairwise sequence similarity (Levenshtein over the visited-cell
  sequence) + per-session transition entropy. *"Do they navigate in the same order / systematically?"*

## Notes / limits

- Fragments recorded **before schema `/3`** carry no `path`; the spatial/dwell analysis still runs,
  scanpath sections are skipped. The tools handle mixed `/2`+`/3` inputs.
- The dwell grid is **zoom-weighted** (a zoomed-in view concentrates dwell; a wide glance spreads it)
  — it reflects zoom-adjusted attention, not pixel-exact gaze.
- Scanpath sequence similarity here is a documented Levenshtein/transition approximation, not full
  MultiMatch.
