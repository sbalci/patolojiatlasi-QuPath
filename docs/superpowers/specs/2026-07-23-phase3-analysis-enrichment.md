# Phase 3 analysis enrichment — spec (Tier 1+2+3, 2026-07-23)

- **Branch:** `feat/phase3-analysis-enrichment` (off Phase 3 HEAD `72af4b5`; merges to master after Phase 3).
- **Driver:** the data-dimension audit (`docs/superpowers/phase3-data-dimension-audit.md`). All 5 nav
  dimensions are already captured end-to-end; this closes the enrichment gaps the user approved (all 3 tiers).
- **Invariants (unchanged, enforced every task):** Python↔R numeric parity to 1e-6 (manual diff on a shared
  fixture each phase); blank-not-crash on every degenerate input; additive CSV columns keep existing column
  order (new columns appended); the recorder stays schema `atlas-focus-contribution/5` (any new fragment
  field is additive); anonymized/data-only. Analysis changes are covered by the selftest+parity harness (no
  GUI); the ONE recorder change (C5) needs a live smoke test and is flagged.
- **Locked methodology choices** (grounded in the roadmap's cited papers; deterministic + parity-safe forms
  chosen where a library would diverge across languages):

## Tier 1 — additive (no existing number changes)

**A1 — nav↔accuracy completeness.** Extend `NAV_ACCURACY_COLS` with the recorded-but-uncorrelated
dimensions: `durationMs`, `cursorOverSlidePct`, `mouseViewportCouplingPx` (all already in `metrics.csv`).
Add `decisionLatencyMs` (from `decision_rows`) to the correlation by joining it in on `(slide, sessionId)`
— same hard guard (n≥5, var>0) + group means. So all 5 dimensions appear in `nav_accuracy.csv`.

**A2 — turn-angle directionality** (`metrics.csv`, path-only). On the ordered viewport centers `(cx,cy)`:
per interior point, heading `θ_i = atan2(Δy, Δx)`; turn `φ_i = wrapToPi(θ_{i+1} − θ_i)`.
- `meanAbsTurnAngleDeg` = mean(|φ_i|) in degrees.
- `turnAngleEntropy` = Shannon entropy (bits) of φ binned into 8 equal bins over (−180°,180°], normalized
  by log2(8) to [0,1]. Blank when <3 points. (Movement-ecology/visual-search directionality; not in roadmap.)

**A3 — mouse kinematics** (`metrics.csv`, schema/5 only). Sentinel-aware over on-slide cursor points
(skip any segment touching `(-1,-1)`):
- `mousePathLengthPx` = Σ Euclidean distance between consecutive on-slide cursor points.
- `mouseVelocityPxPerSec` = median of (dist / Δt_sec) over consecutive on-slide point pairs. Blank if none.

**A4 — active fraction** (`metrics.csv`, path-only): `activeFractionPct` = 100 × `durationMs` /
(tRel_last − tRel_first). Blank if <2 points or zero span. (Mello-Thoms engagement confound.)

**A5 — surface built-but-unwired outputs.**
- `top_hotspots(grid, gw, gh, top_n=5)` → `hotspots_<slug>.csv`: `session, rank, cellRow, cellCol,
  centerImageX, centerImageY, dwellMs, dwellFrac`. Written when any session has a grid (i.e. always).
- `transition_matrix(seq)` → `transitions_<slug>.csv`: `session, fromCell, toCell, count` for the top-15
  most frequent directed cell→cell transitions per session (path sessions only).

**A6 — multi-reader scanpath overlay figure** (`--figures`): `overlay_<slug>_scanpaths.png` — every
session's viewport-center path on one axis, distinct color per session, alpha≈0.5, start=o/end=x, legend.

## Tier 2 — literature/correctness (some CHANGE existing numbers — documented)

**B1 — idle/away-time exclusion (CHANGES rate numbers).** `IDLE_GAP_MS = 60_000` (Ghezloo >60s frozen
rule). A scanpath step whose Δt > IDLE_GAP_MS is "idle": excluded from time denominators AND its pan
distance/zoom-change/dwell-weight excluded, in `scanning_rate_px_per_min`, `drilling_rate_per_min`,
`path_velocity_px_per_sec`, `search_focus_ratio`, `raster_from_path` (step weight), and magband
`bandTimeMs`. Add transparency columns to `metrics.csv`: `idleMs` (Σ excluded idle Δt), `activeSpanMs`
(wall-clock span − idleMs). Document that scanning/drilling/band numbers shift for idle-containing sessions.

**B2 — Drew-fidelity zoom (ADD-alongside, existing columns retained).** Add `avgZoomLog2W` = duration-
weighted mean of log2(magnification) (Δt-weighted, idle-excluded per B1); `drillingRateOctavesPerMin` =
Σ|Δlog2(zoom)| per active minute. Keep the existing `avgZoom`/`drillingRatePerMin` unchanged (reproducibility)
— the faithful metrics are added, not substituted (least-regret; user can drop the originals later).

**B3 — canonical magnification bands (CHANGES magbands for true-mag slides).** `MAG_BANDS` cut points
`[1,2,4,10,20,40]` → 7 labeled bands. Requires true magnification (`baseMagnification × (dsMilli/1000)^-1`).
`magbands_<slug>.csv` gains a `bandScheme` column: `"canonical"` when baseMagnification is present, else
`"tercile"` (the existing within-path fallback, unchanged). New `--magband-scheme canonical|tercile` CLI
(default `canonical`, auto-fallback to tercile when baseMag null). Atlas DZI slides (null baseMag) keep terciles.

**B4 — magnification-source flag (additive).** `metrics.csv` gains `magnificationSource`:
`"true"` (baseMagnification present) or `"proxy-downsample"` (null → `point_zoom` scale-relative branch),
so pooled avgZoom rows are never silently mixed. Plus a `summary.md` caveat line.

## Tier 3 — larger subsystems

**C1 — fixation extraction (I-DT, deterministic/parity-safe — NOT DBSCAN).** Salvucci–Goldberg
dispersion-threshold over the scanpath: a fixation = a maximal run of consecutive points whose
(cx,cy) dispersion (`(max_x−min_x)+(max_y−min_y)`, image px) stays ≤ a threshold scaled to the current
view (`DISPERSION_FRAC=0.25 ×` the visible width `w` at the window's FIRST point — fixed once the window
starts, avoiding a circular threshold) for ≥ `MIN_FIXATION_MS=250`.
Outputs (`metrics.csv`): `nFixations`, `meanFixationMs`, `medianFixationMs`, `sdFixationMs`,
`fixationsPerMin`. `fixations_<slug>.csv`: `session, idx, startMs, durationMs, centerImageX, centerImageY,
nPoints`. Directional metrics stay tick-based (documented); fixations are an added lens, not a rebase.

**C2 — mouse-dwell map + cross-reader mouse agreement.** Rasterize on-slide `(mouseX,mouseY)` Δt-weighted
into a grid (mouse analog of `raster_from_path`); reuse `coverage/entropy/cc/iou/coincidence_level/icc` on
it. `metrics.csv`: `mouseCoveragePct`, `mouseEntropy`. `mouse_<slug>.csv`: pairwise cursor cc/iou +
coincidence (cross-reader cursor convergence). `--figures`: `<slug>_mousemap.png`.

**C3 — DTW trajectory similarity (deterministic DP; NOT Fréchet).** Add `dtwDistance` (Dynamic Time
Warping on z-normalized `(cx,cy)` sequences, Euclidean local cost) to the pairwise `scanpath_<slug>.csv`,
as a resolution-independent complement to the grid-cell `levenshteinSim`.

**C4 — segment-level linearity.** `meanSegmentLinearity` (`metrics.csv`): mean of per-segment linearity
over sub-paths split at the session's top-hotspot cells (or ROI-entry boundaries when annotations exist),
complementing whole-path `linearity` (Roa-Peña whole ~0.41 vs segment ~0.8).

**C5 — true decision latency (RECORDER change — needs a smoke test).** Stamp `promptShownMs` (relative to
`blindedSlideStartMs`) when the decision dialog is shown (menu path: in `promptDecisionInteractive`; leave
path: in `deferredDecisionPromptAndSave`), and add it to the `decision` object (additive, schema stays /5).
Analysis: `decisions.csv` gains `promptShownMs` + `responseLatencyMs` (= decisionMs − promptShownMs =
deliberation once prompted, distinct from `decisionLatencyMs` = time from slide open). Flag for smoke test.

**C6 — misc robustness metrics.**
- `jsDivergence` (Jensen–Shannon, base-2, symmetric/bounded) alongside `kld` in `compare_<slug>.csv`.
- `precisionAtTopK` / `recall` vs the `--roi`/`--reference` mask in `reference_<slug>.csv` (top-K% = the
  reader's highest-dwell cells matching the ROI), separating "missed target" from "wasted attention".
- Visit-count hotspots: `visitCountJaccard` (`metrics.csv`) = Jaccard between dwell-time top-hotspots and
  visit-count (flat +1 per visit) top-hotspots — disagreement = navigation-style signal.
- Polygon-union `annotatedAreaUnionPx` (`metrics.csv`) = area of the unioned rasterized annotation mask
  (× cell area), the overlap-correct companion to the existing sum-based `annotatedAreaPx`.
- Per-cell reader-count weak-annotation map export: `consensus_count_<slug>.csv` (cellRow, cellCol, nReaders
  with dwell above threshold) — the spatial structure `coincidenceLevel` collapses to one scalar.

## Task decomposition (SDD; each = reviewable, Python+R+parity in one task, selftest asserts + parity diff)

- **T1** — Tier 1 (A1–A6) additive metrics/outputs/figure, Python + R + parity.
- **T2** — Tier 2 (B1–B4) idle-exclusion + zoom fidelity + canonical bands + mag-source flag, Python + R + parity.
- **T3** — Tier 3 C1 (fixations, I-DT), Python + R + parity.
- **T4** — Tier 3 C2 (mouse-dwell map + cross-reader mouse agreement) + C3 (DTW) + C4 (segment linearity), Python + R + parity.
- **T5** — Tier 3 C5 (recorder promptShownMs + responseLatencyMs), Java recorder + Python/R decisions.csv + parity. **Recorder change → smoke test.**
- **T6** — Tier 3 C6 (JSD, precision@k/recall, visit-count Jaccard, union area, reader-count map), Python + R + parity.
- **T7** — docs: analysis READMEs (all new columns/outputs/flags + the B1/B3 changed-number notes + honest-limits), SHARING if user-facing, the audit doc's "resolved" status.

Each task: brief → implement (Python+R together to keep parity coherent) → selftest green both languages →
manual parity diff on a shared fixture → task review → fix → next. Final whole-branch adversarial review
(privacy N/A here; correctness + parity + blank-not-crash + backward-compat of the CSV contract) before merge.
