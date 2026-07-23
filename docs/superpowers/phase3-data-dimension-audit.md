# Navigation data-dimension audit (2026-07-23)

Adversarial 5-agent audit of the blinded navigation-research pipeline: are the five data dimensions
captured **end-to-end** in DATA GENERATION (recorder → fragment fields) and ANALYSIS (Python + R
metrics/outputs)? Verdict per dimension + ranked gaps. Source: workflow `waz99m104`.

## Headline: all five dimensions are present end-to-end, with full Python↔R parity

| Dimension | Generation | Analysis | R parity | Verdict |
|---|---|---|---|---|
| **Temporal** (dwell-ms, durationMs, tRelMs, decisionMs, time-in-ROI, time-per-band) | ✅ | ✅ | ✅ 1:1 | PARTIAL* |
| **Spatial** (dwell grid, coverage/entropy/COM/hotspots, scanpath cx/cy, ROI overlap, cross-reader agreement) | ✅ | ✅ | ✅ 1:1 | PARTIAL* |
| **Zoom** (dsMilli/point, baseMag, avgZoom/variance/range, magPct, drilling, mag-bands) | ✅ | ✅ | ✅ 1:1 | PARTIAL* |
| **Movement trajectories** (ordered scanpath, length, revisits, transition-entropy, linearity, velocity, Levenshtein) | ✅ | ✅ | ✅ 1:1 | PARTIAL* |
| **Mouse/cursor** (mouseX/mouseY viewer-scoped, over-slide %, viewport coupling) | ✅ | ✅ | ✅ 1:1 | PARTIAL* |

\*PARTIAL = **present with enhancement gaps**, not missing. Every dimension is recorded into the
fragment and consumed by both toolkits identically. The gaps below are enrichments/corrections.

## Gaps, tiered by risk

### Tier 1 — additive, high-value, changes NO existing metric's numbers
- **Complete the nav↔accuracy correlation.** `NAV_ACCURACY_COLS` correlates zoom/spatial/trajectory
  metrics vs graded accuracy but OMITS **temporal** (`durationMs`, `decisionLatencyMs`) and **mouse**
  (`cursorOverSlidePct`, `mouseViewportCouplingPx`) — so 2 of the 5 dimensions are captured but never
  tested against the outcome Phase 3 exists to test. (S, both toolkits, additive rows.)
- **Turn-angle directionality** (`meanAbsTurnAngle`, `turnAngleEntropy`): no heading/turn-angle metric
  exists at all — the standard way to tell systematic vs erratic search; not even in the roadmap. (S)
- **Mouse kinematics** (`mousePathLengthPx`, `mouseVelocityPxPerSec`, sentinel-aware): cursor travel/speed
  not computed anywhere. (S)
- **Active-fraction** (`activeFractionPct` = durationMs / path wall-clock span): housekeeping confound
  (Mello-Thoms) not surfaced. (S)
- **Surface already-built dead code:** `top_hotspots()` (WHERE the peaks are, not just `nHotspots`) →
  `hotspots_<slug>.csv`; `transition_matrix()` (directed cell→cell edges) → `transitions_<slug>.csv`.
  Both implemented, never wired to output. (S each)
- **Multi-reader scanpath overlay figure** (semi-transparent paths on one image). (S, figure-only)

### Tier 2 — corrections/literature-fidelity that CHANGE existing metric numbers (need a methodology call)
- **Idle/away-time exclusion.** Path-Δt-derived metrics (scanningRate, drillingRate, bandTimeMs, raster,
  searchFocusRatio) count idle gaps as real time, while grid `durationMs` correctly excludes them
  (DT_CAP_MS clamp) — an internal inconsistency. Fix aligns them (Ghezloo >60s rule, or per-step Δt cap).
  **Changes rate numbers for any session with a tab-away gap.** (M)
- **Drew-fidelity zoom:** `avgZoom` → duration-weighted mean of log2(magnification); `drillingRate` →
  weighted by |Δlog2(zoom)| octaves. The cited motivating formulas; **changes existing zoom numbers.** (S)
- **Canonical magnification bands** (1x/4x/10x/20x/40x) instead of within-path terciles, so bands mean the
  same physical zoom across readers. **Changes magbands semantics.** (M)
- **baseMagnification is null for atlas DZI slides** (DziImageServer never sets magnification) → zoom
  metrics silently fall to a scale-relative proxy with no flag. Needs a proxy/true flag + docs. (M, both)

### Tier 3 — larger research subsystems (bigger builds)
- **Fixation extraction** (DBSCAN / dispersion-threshold over the ~250ms tick stream) → fixation-duration
  distributions; every directional metric currently measures tick noise as much as structure. (L)
- **Mouse-dwell heatmap** + reuse the cc/IoU/coincidence/ICC machinery for **cross-reader mouse agreement**
  (a mouse analog of the viewport-dwell consensus). (M)
- **DTW / Fréchet** continuous-trajectory scanpath comparison (Levenshtein-on-grid-cells is
  resolution-fragile). (M)
- **Segment-level linearity** (per ROI-to-ROI sub-path, Roa-Peña whole ~0.41 vs segment ~0.8). (M)
- **True decision latency** (`promptShownMs` in the recorder → time-from-prompt-to-submit, distinct from
  time-on-slide). (S, generation)
- Per-band spatial agreement; JSD alongside KLD; Precision@k/Recall vs ROI; visit-count (vs dwell) hotspots;
  polygon-union `annotatedAreaPx`; per-cell reader-count weak-annotation map. (S–M each)

## Note
Analysis-side changes are covered by the selftest + Python↔R parity harness (no QuPath GUI needed).
Only the Java recorder needs the live smoke test — and it is frozen after the 6-finding fixes.
