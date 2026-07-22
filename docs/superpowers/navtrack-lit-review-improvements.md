# Navigation-Recorder Literature Improvement Plan

Grounded against the current implementation (`analysis/python/blinded_focus/metrics.py`) and the existing design doc (`docs/superpowers/specs/2026-07-22-navigation-research-upgrade-design.md`), which already implements most of "Phase 1." This report identifies what to refine in that implementation and what remains missing, against the 12-paper set.

## 0. Two correctness findings from code inspection (do these first)

**A. `coincidence_level()` denominator bug vs. Roa-Peña 2010.** Current code (`metrics.py:630-644`):
```python
return float(np.count_nonzero(counts >= 2)) / counts.size   # counts.size = ALL grid cells
```
Roa-Peña's definition: coincidence% = area(≥2 visitors) / area(**≥1 visitor**), i.e. normalized to the *visited footprint*, not the whole slide. Their own sanity check: a 48%-visited slide still showed 97% coincidence — impossible under a whole-grid denominator. Our current formula silently returns a **much lower** number than the literature's metric for any partially-explored slide, and the two aren't comparable to their reported ~70.5% benchmark. **This is the single highest-value fix.**

**B. `magnification_percentage()` uses `>=` (Ghezloo requires strict `>`).** Current code (`metrics.py:462-476`) counts zoom-*unchanged* transitions as "consecutive zooming." Ghezloo's MP is explicitly "zoom level **strictly increases**." Ties (held zoom) should not count. One-line fix: `diffs > 0` not `diffs >= 0`.

## 1. Improvement table

| # | Improvement | Status | Phase | Source(s) |
|---|---|---|---|---|
| 1 | `coincidence_level` denominator → area(≥2)/area(≥1), not /total cells | **[refine]** — correctness bug | 1 | Roa-Peña 2010 |
| 2 | `magnification_percentage`: `diffs > 0` not `>= 0` | **[refine]** — off-by-tie | 1 | Ghezloo 2022 |
| 3 | `avg_zoom`: duration-weighted mean of **log2**(magnification), not naive arithmetic mean of `point_zoom` | **[refine]** | 1 | Drew 2021 |
| 4 | `drilling_rate_per_min`: weight by `\|Δlog2(zoom)\|` (magnitude of the jump), not a flat +1 per zoom-change event | **[refine]** | 1 | Drew 2021 |
| 5 | `scanning_rate_px_per_min` — clarify/split: current metric conflates Ghezloo's "scanning %" (fraction of *transitions* at fixed zoom, dimensionless) with Drew's "scanning rate" (Euclidean distance/duration, a rate). We compute a hybrid (Drew's distance formula, gated by Ghezloo's fixed-zoom condition). Keep it, but **add** the plain Ghezloo scanning-percentage (transition-count based) as a separate named field so both literature metrics are independently reproducible | **[add]** (companion metric) + **[refine]** (rename/document existing one) | 1 | Ghezloo 2022, Drew 2021 |
| 6 | Idle-viewport exclusion (>60s frozen → drop before summing time/coverage) | **[add]** | 1 | Ghezloo 2022 |
| 7 | Fixation-extraction preprocessing (collapse raw scanpath → discrete fixation events before Levenshtein/transition-entropy) via 3-threshold (angular/temporal/dispersion, always split on mag-change) or DBSCAN | **[add]** | 1 | Chakraborty 2025 MedIA (3-threshold); Nan 2025 Nat Commun (DBSCAN) |
| 8 | Canonical magnification bands (replace `zoom_band_labels`'s 3 within-path terciles with fixed cross-session bands, e.g. thumbnail/1×/2×/4×/10×/20×/40× or a 3-tier Overview/HPF/Cell scheme) | **[refine]** | 1 | Chakraborty 2025 (6-tier); Gu/NaviPath (3-tier); Xu 2026 (5-band) |
| 9 | Magnification-profile: cumulative per-band fixation-count vector + Δmag∈{−1,0,+1} transition sequence (order-aware drilling/scanning fingerprint), beyond the current scalar `drilling_rate_per_min` | **[add]** | 1 | Chakraborty 2025 MedIA |
| 10 | Dual hotspot definitions + Jaccard cross-check: visit-**count** footprint heatmap (each viewport contributes flat +1) vs existing dwell-**time** heatmap, report their Jaccard overlap per case | **[add]** | 1 | Chakraborty 2022 ISBI; Roa-Peña 2010 |
| 11 | Segment-level `linearity` (per hotspot-to-hotspot or per-annotation-transition segment), reported **alongside** and clearly labeled apart from existing whole-path `linearity` — 0.41 (whole) vs 0.8–0.85 (segment) are not the same number | **[add]** | 1 | Roa-Peña 2010 |
| 12 | Velocity-profile shape descriptor (e.g. time-to-peak-velocity as fraction of segment duration), beyond the current median-scalar `path_velocity_px_per_sec` | **[add]** | 1 | Roa-Peña 2010 |
| 13 | Spearman ρ alongside existing Pearson `cc()` for dwell-grid comparisons (dwell is heavy-tailed) | **[add]** | 1 | Xu 2026 |
| 14 | Jensen-Shannon divergence alongside existing `kld()` (symmetric, bounded, defined at zero-dwell cells where KLD isn't) | **[add]** | 1 | Xu 2026 |
| 15 | Average-Precision@top-k% (reference's top-5%/10% dwell cells as positive class, score comparison user's dwell against that mask) as a threshold-free hotspot-agreement complement to existing `iou()` | **[add]** | 1 | Xu 2026 |
| 16 | Cross-case reliability (Cronbach's α / ICC — `icc()` already exists for cross-*user* grids; add a cross-*case*, same-user reliability check) as a pipeline QC gate | **[add]** | 1 | Drew 2021 |
| 17 | Document/expose Gaussian-smoothing σ as a tunable, stated parameter before any heatmap-based CC/SIM comparison | **[refine]** | 1 | Chakraborty 2022 ISBI |
| 18 | Superimposed semi-transparent multi-user scanpath overlay visualization (cheap, complements quantitative metrics) | **[add]** | 1 | Gu/NaviPath 2023 |
| 19 | Reorganize report output into 3 named levels: Region/Tumor Coverage · Navigation Consistency · Decision | **[add]**, cross-cutting | 1 (labeling only) | Xu 2026 MMNavAgent |
| 20 | ROI time percentage (dwell-ms fraction inside annotated region) + ROI re-entry rate (point-in-polygon re-entries after exit) | **[add]** | 2 | Ghezloo 2022 (RTP); Brunyé 2017 (re-entry) |
| 21 | Dwell-weighted hit-rate + enrichment ratio (mean dwell-ms inside vs. outside annotation, as a ratio) — complements existing area-based `region_coverage_pct` | **[add]** | 2 | Nan 2025 Nat Commun |
| 22 | Precision@top-10%/Recall for tumor/ROI coverage (splits "wasted attention outside target" from "missed target", which IoU conflates) | **[add]** | 2 | Xu 2026 |
| 23 | Semantic Sequence Score (Needleman-Wunsch alignment of per-annotation-class label strings) as companion to existing `levenshtein_sim`/`transition_entropy` | **[add]** | 2 | Chakraborty 2022 ISBI; Chakraborty 2025 MedIA |
| 24 | Blur + histogram-match reference/annotation mask before CC-based comparison (complement to hard-boundary IoU) | **[add]** | 2 | Chakraborty 2022 ISBI |
| 25 | `consensus_dwell_grid`: per-cell count of users whose dwell exceeds a threshold, as a derived weak-annotation product (not just pairwise similarity) | **[add]** | 2 | Nan 2025 Nat Commun |
| 26 | Case-metadata covariates in schema (difficulty rating, tissue/density type) — not navigation data, but needed to interpret nav→accuracy interactions | **[add]** — schema note | 2/3 | Brunyé 2017 |
| 27 | Navigation-efficiency (GT-confirmed findings visited / task time) and visited-region-quality (mean target density in dwelled region, regardless of what was reported) | **[add]** | 3 | Gu/NaviPath 2023 |
| 28 | UI-friction flag: % session time actively panning/zooming vs static, reported as a housekeeping confound separate from search-and-focus interpretation | **[add]** | 3 | Mello-Thoms 2011 |
| 29 | Ceiling/floor case exclusion (>98%/<2% correct) + MAD-based (not SD) outlier trimming before any nav→accuracy model | **[add]** | 3 | Drew 2021 |
| 30 | Statistical templates for nav↔accuracy: crossed-random-effects GLMM (`lme4::glmer`), GEE (`geepack::geeglm`), conditional logistic (`survival::clogit`) — offer as three named, literature-matched options | **[add]** | 3 | Ghezloo 2022 (GLMM); Brunyé 2017 (GEE); Drew 2021 (clogit) |
| 31 | Expertise stratification (resident/general/specialist) + lightweight "ExpertiseNet"-style classifier from nav features as a validation sanity-check | **[add]** | 3 | Chakraborty MICCAI 2024 (2403.17255) |
| 32 | Replicate CC-agreement-vs-grading-concordance stratified by expertise (r=0.88/0.73/0.15 pattern) as a falsifiable hypothesis once decisions are linked | **[add]** | 3 | Chakraborty MICCAI 2024 |
| 33 | Outcome-validity gate: paired significance test (t-test/Wilcoxon) that dwell-weighted metrics predict decision correctness/concordance better than an unweighted baseline, before treating dwell as clinically meaningful | **[add]** | 3 | Nan 2025 Nat Commun |
| 34 | Correlate coincidence/hotspot masks with tissue-level pixel-classifier features (stain OD, nuclear density, tumor probability) — same coordinate space as the dwell grid | **[add]** — new phase | **New Phase 4** | Mello-Thoms 2011 (15-yr-old open call) |
| 35 | Nofallah 2022 — corroborative only, cite Ghezloo's definitions verbatim in our metric docs/docstrings for a peer-reviewed precedent | **[have]** (docs task only) | — | Nofallah 2022 |
| 36 | CC/NSS/AUC/KLD/JSD reported **per magnification tier**, not only pooled whole-slide | **[refine]** | 1 | Chakraborty 2022 ISBI; Chakraborty 2025 MedIA |

## 2. Highest-value items with exact formulas

**(1) Coincidence-level denominator fix (Roa-Peña 2010).**
```
coincidence% = |cells visited by ≥2 readers| / |cells visited by ≥1 reader| × 100
```
Not `/ total cells`. Implementation: build `visited_mask = counts >= 1` first, then `count_nonzero(counts>=2) / count_nonzero(visited_mask)` (guard divide-by-zero → NaN if nothing was visited).

**(2) Idle-viewport exclusion (Ghezloo 2022).** Before summing any duration/dwell-based metric (total time, ROI time %, coverage-by-time), drop any single point-to-point interval where `Δt > 60_000 ms` from the sum (treat as "stepped away", not part of active interpretation). Apply identically to `entropy()`'s implicit sum-of-dwell and any future ROI-time-% computation.

**(3) Drew 2021's scanning/drilling/avgZoom trio, in physical units (µm, since we have no fixed monitor width — an explicit improvement over Drew's screen-width normalization):**
```
scanningRate = Σ euclid((cx_i, cy_i)*downsample_i, (cx_{i+1}, cy_{i+1})*downsample_{i+1}) / duration_sec   [µm/sec]
drillingRate = Σ |log2(1/downsample_{i+1}) - log2(1/downsample_i)| / duration_sec                          [octaves/sec]
avgZoom      = Σ (Δt_i · log2(zoom_i)) / Σ Δt_i     [duration-weighted mean of log2(magnification)]
```
Current code computes `avgZoom` as a naive `z.mean()` over instantaneous samples (`metrics.py:441-444`) and `drilling_rate_per_min` as a flat event count (`metrics.py:513-524`) — both need the log2/weighting fix above.

**(4) Magnification-percentage strict inequality (Ghezloo 2022):**
```
MP = |{i : zoom[i+1] > zoom[i]}| / (n-1)
```
Change `metrics.py:476`'s `diffs >= 0` to `diffs > 0`.

**(5) Spearman ρ + Jensen-Shannon divergence (Xu 2026), as safe companions to existing Pearson `cc()`/`kld()`:**
```python
from scipy.stats import spearmanr
from scipy.spatial.distance import jensenshannon
rho, _ = spearmanr(a.flatten(), b.flatten())
jsd = jensenshannon(normalise_sum(a), normalise_sum(b))  # base-2, bounded [0,1]
```

**(6) Average-Precision@top-k% (Xu 2026):**
```python
from sklearn.metrics import average_precision_score
ref_top_mask = (ref >= np.quantile(ref, 1 - k))   # k=0.05 or 0.10
ap = average_precision_score(ref_top_mask.flatten(), pred.flatten())
```

**(7) Dwell-weighted hit-rate + enrichment (Nan 2025), once Phase 2 annotations land:**
```
hitRate    = Σ dwell_ms[cell] for cell in ROI  /  Σ dwell_ms[cell] for all cells
enrichment = mean(dwell_ms | inside ROI) / mean(dwell_ms | outside ROI)
```
(PEAN's reference numbers: 87.4% hit-rate area-based / 0.822 vs 0.357 = 2.3× enrichment, time-weighted — report both area- and time-weighted variants since they answer different questions.)

**(8) Phase 3 statistical templates (three literature-matched options, offered explicitly rather than picking one):**
```r
# Ghezloo-style crossed random effects (residual: true GLMM extension beyond all 3 source papers)
lme4::glmer(accuracy ~ navFeatures + (1|pathologistId) + (1|caseId), family=binomial)
# Brunyé-style marginal GEE
geepack::geeglm(accuracy ~ dwellInROIProportion + fixationDuration + zoomSD + caseDifficulty + experience,
                 id=pathologistId, family=binomial, corstr="exchangeable")   # forward-select by QIC
# Drew-style conditional/fixed-effects (case as matched stratum)
survival::clogit(accuracy ~ scanRate + drillRate + duration + strata(caseId), cluster=pathologistId)
```

## 3. Capture/schema gap in the recorder itself

The current point tuple `[tMs, centerX, centerY, viewportW, viewportH, downsampleMilli]` is sufficient for everything above **except**:

- **No explicit magnification band field.** Every band scheme above (Chakraborty's 6-tier, Gu's 3-tier, Xu's 5-band) is derivable post-hoc from `downsampleMilli` + `baseMagnification`, so no recorder change is strictly needed — but worth a schema *documentation* note pinning the canonical band cut points so Python/R both bucket identically (currently `zoom_band_labels` uses within-path quantiles, which are **not** cross-session comparable; the canonical-band fix in item 8 is analysis-side only).
- **No idle/away flag.** The >60s-frozen-viewport rule (item 2) is currently reconstructable from consecutive `tMs` deltas, so also analysis-side — but if a future "pause/resume" UI action exists, capturing it explicitly would be more reliable than inferring idle purely from elapsed time.
- **Nothing missing for Phase 1 metrics** — schema `/4`'s addition of `downsampleMilli` already covers what Ghezloo/Drew/Chakraborty need (x, y, zoom, t).
- **Phase 2/3 fields are already scoped in the design doc** (annotations GeoJSON, decision object) and need no further schema changes based on this literature pass.

## 4. Honest limits

- **Our "fixations" are viewport-derived, not oculomotor.** Any fixation-extraction step (item 7) simplifies a *viewport-recenter* stream, not true gaze. Saccade-amplitude, fixation duration, and blink-rate are literally eye-tracking measurements (Brunyé/Drew/Nan all use SMI/Tobii trackers at 60–250Hz with angular accuracy specs); our closest analog — log-transformed jump distance between consecutive scanpath points — must be labeled a "viewport-jump proxy," never presented as a fixation/saccade measurement.
- **The viewport-only precedent already found null results.** Drew 2021 cites Mercan et al.'s prior viewport-only (no eye-tracking) scanning-vs-drilling metric, which found **no** accuracy association — a direct precedent that our nav→accuracy correlations (Phase 3) may be weaker or null compared to the eye-tracked literature's effect sizes. State this as a limitation up front rather than expecting Ghezloo/Drew-sized effects.
- **Navigation-efficiency, visited-region-quality, and the whole nav↔accuracy model family require a decision + answer key we don't have until Phase 3**, and ROI-based metrics (RTP, hit-rate, Precision@k/Recall, SSS) require expert-annotated ROIs we don't have until Phase 2. None of items 20-33 can be computed on Phase-1-only data; don't backfill them with a proxy ROI.
- **Navigation ≠ segmentation (Nan 2025, Xu 2026 — both state this explicitly).** Dwell/attention data must never be treated as a lesion/tissue label — a pathologist's fixation set includes non-diagnostic tissue by construction (context-scanning, ID-checking, artifact-avoidance). Any weak-annotation use of the `consensus_dwell_grid` (item 25) should be framed the way PEAN frames it: a contextual soft signal validated by downstream task effect, not a ground-truth substitute.
- **Coincidence/consistency numbers are tissue-type- and cohort-composition-dependent** (Mello-Thoms 2011): a mixed-specimen QuPath cohort should expect a wider, lower-floor coincidence distribution than a single-tissue-type study (Roa-Peña's 41–97%, mean 70.5%, vs. Krupinski's single-tissue ~80%) — report per tissue-type stratum and don't treat a lower pooled number as a tool bug.

## Files referenced

- `D:\patolojiatlasi-QuPath\analysis\python\blinded_focus\metrics.py` — current metric implementations (lines cited above: 441-524 zoom family, 630-644 `coincidence_level`, 132-197 similarity metrics, 257-311 scanpath metrics)
- `D:\patolojiatlasi-QuPath\docs\superpowers\specs\2026-07-22-navigation-research-upgrade-design.md` — existing phased design this report extends/refines