# Navigation data-dimension audit (2026-07-23)

## Status (2026-07-24; updated 2026-07-25)

**Tier 1 + Tier 2 + Tier 3 gaps below are now IMPLEMENTED** on `feat/phase3-analysis-enrichment`
(spec: `docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md`; tasks T1–T6, Python + R
+ parity; documented in `analysis/python/README.md` / `analysis/R/README.md`'s "Phase 3
enrichment" sections). A handful of items were explicitly left out of scope at that time —
**all three have since been IMPLEMENTED** on `feat/enrichment-polish` (spec:
`docs/superpowers/specs/2026-07-25-enrichment-polish.md`; tasks PT1–PT4, Python + R + parity;
documented in the same two READMEs' "Phase 3 enrichment" sections):

- **Mouse ICC** — **IMPLEMENTED (PT2).** Cross-reader mouse-dwell agreement (`mouse_<slug>.csv`)
  now also carries `mouseICC`, a slide-level ICC(2,1) of the mouse-dwell grids (computed only over
  the sessions that actually carry mouse data, unlike the `cc`/`iou`/`coincidenceLevel` columns it
  already had), on the same diagonal-reuse row as `coincidenceLevel` — plus a `summary.md` "cursor
  agreement" line printed per slide.
- **ROI-entry segment-linearity variant** — **IMPLEMENTED (PT3).** `meanSegmentLinearityROI`
  (`metrics.csv`, path + annotations only) segments the path at annotation-ROI-entry boundaries
  (an outside→inside transition into the reader's own union annotation mask) rather than at
  dwell-hotspot cells — a complement to the existing hotspot-based `meanSegmentLinearity`, appended
  at the end of `metrics.csv`.
- **Per-band spatial agreement** — **IMPLEMENTED (PT4).** New `magband_agreement_<slug>.csv`:
  `band,nSessions,meanPairwiseCC,coincidenceLevel` per canonical magnification band. Written only
  when >=2 sessions on the slide have a computable **canonical** magnification-band scheme —
  skipped entirely (no file) under `--magband-scheme tercile`, or when fewer than 2 sessions have a
  computable `baseMagnification`+`dsMilli`.

(The same `feat/enrichment-polish` branch also landed PT1, defensive-consistency hardening with no
user-facing column change — 5 `math.hypot` call sites in the Python toolkit switched to
`math.sqrt(dx*dx+dy*dy)` for bit-parity with the R port, and the `magnificationSource` flag now
requires `baseMagnification > 0`, not just non-null, on both sides.)

Everything else listed under Tier 1/2/3 below was built. (Fréchet distance, also mentioned below,
was a deliberate design choice **against** — DTW was implemented instead, not a deferred item.)
Tier 3 C5 (`promptShownMs` recorder change) still needs its live QuPath smoke test per the spec's
own flag; the analysis-side changes (all of Tier 1–3, plus PT1–PT4) are covered by the selftest +
Python↔R parity harness with no GUI required.

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
