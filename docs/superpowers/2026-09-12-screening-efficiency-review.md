# Screening efficiency (Abe 2026) — review & viewport-proxy implementation (2026-09-12)

**Paper.** Abe N, Nishimura Y, Yamashita K, Kawamorita T, Takatori Y, Murakumo Y, Furuta R.
*Screening efficiency over experience: Rapid target detection in low-power field as a modifiable
cognitive biomarker for diagnostic accuracy in digital cytology.* **Cancer Cytopathology**
2026;e70132 (issue 2026;8). doi:[10.1002/cncy.70132](https://doi.org/10.1002/cncy.70132).
CC BY-NC-ND (no code/data to reuse anyway — data unavailable for privacy reasons; everything
below is implemented from the published construct definitions).

## What the paper shows

- **Design.** Phase 1: 100 board-certified cytotechnologists (1–40 y experience) diagnosed 30
  static digital cytology composites (sample info + a low-power-field image + a high-power-field
  image); Tobii Pro Spark 60 Hz, I-VT fixation filter; AOIs = sample info, background, LPF normal
  cells, **LPF main object** (the diagnostic target), HPF cytoplasm, HPF main object. Metrics:
  time to first fixation (detection speed), fixation count (search intensity), fixation duration
  (processing time), visit count (recursive checking). Phase 2: 28 students, pre/post a standard
  3-month program (no gaze feedback; the tracker measured only), matched sets A/B.
- **Experience ≠ accuracy:** r = 0.189, p = .0596 across 100 readers (accuracy 63.3–96.7 %,
  mean 82.1 %). Experience correlated instead with *sample-information* attention (visit count
  r = 0.228).
- **The biomarker:** in nominal logistic regression, **total fixation duration on the LPF main
  object was the sole independent predictor of high accuracy** (estimate −0.055, OR 0.946,
  p = .0454) — *shorter* is better ("pop-out" detection vs serial search). High performers also
  had shorter total examination time (r = −0.235), less background dwell (p = .016), and faster
  time-to-first-fixation on HPF cytoplasm (p = .039). ROC strata: 5 years experience (AUC 0.625),
  83 % accuracy.
- **It is teachable:** after 3 months, students' time-to-first-fixation on targets dropped
  (r = 0.62 LPF / 0.86 HPF), attention to LPF normal cells dropped (time-to-first-fixation on
  normal cells ↑, r = 0.71 — "learning what not to look at" / selective neglect); accuracy trend
  +, n.s. (r = 0.25). "LPF efficiency" ≡ reduced total fixation time on the primary target +
  shorter time to first target fixation.
- **Limitation the authors state:** static images — "further large-scale studies incorporating
  **dynamic WSI navigation** are needed." Our blinded recorder captures precisely that dynamic
  navigation; the implementation below is an instrument for the study they call for.

## Construct mapping — gaze → our viewport tracking

Our recorder logs the **field of view** (`[tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY]` at
4 Hz, slide px) not gaze. The toolkit's deterministic I-DT detector (`fixations_idt`) already
reduces the viewport path to fixation-like dwell events — a *field-of-view proxy* for attention.
Caveats stated once: viewport-center ≠ fovea (mouse↔gaze coupling is only moderate; a true gaze
channel is the W1/W2 webcam watchlist), and our "reference ROI" plays the paper's "main object"
AOI. Proxy metrics implemented (reference-ROI-conditional, in `reference_<slug>.csv`):

| Abe metric (AOI = LPF/HPF main object) | Our column | Definition |
|---|---|---|
| Time to first fixation on main object | `timeToFirstRefFixMs` | Start time of the first I-DT fixation whose center lies inside the reference mask |
| — same, restricted to low power | `timeToFirstRefFixLpfMs` | First on-reference fixation whose zoom (path point at fixation start) is in the LOW magnification band |
| **Total fixation duration on LPF main object** (the predictor) | `refFixTotalLpfMs` | Summed duration of on-reference fixations in the LOW band |
| Total fixation duration on main object | `refFixTotalMs` | Summed duration of all on-reference fixations |
| Fixation count on main object | `refFixCount` | Count of on-reference fixations |
| Visit count on main object (recursive checking) | `refFixVisitCount` | Runs of consecutive on-reference fixations (a revisit = re-entry after ≥1 off-reference fixation) |
| Detection magnification | `firstRefFixDownsample`, `firstRefFixMagnification` | Zoom at the first on-reference fixation (magnification blank when `baseMagnification` unknown) |
| Background dwell / exam time | already shipped | `timeOffRefMs`, `durationMs` |

All new columns are appended to `NAV_ACCURACY_COLS`, so `nav_accuracy.csv` (point-biserial vs
graded correctness) reproduces the paper's accuracy analysis on dynamic-WSI data — including the
headline test: *does shorter `refFixTotalLpfMs` associate with correct diagnoses?*

**"LPF" definition:** reuses the toolkit's existing canonical magnification-band scheme (the LOW
band), so the split is identical to `magbands_<slug>.csv`; when `baseMagnification` is
null/non-numeric, LPF-conditional metrics render **blank** (blank-not-crash), never a guess.

## Discipline

Dual Python + R implementation at 1e-6 parity, selftests extended in both toolkits, deterministic
(no clock/randomness), blank-not-crash for: no reference, no fixations, no zoom channel
(schema /3 paths), null `baseMagnification`. No recorder (Java) changes needed — the fragment
schema already carries everything.

## Out of scope

The paper's sample-information AOI (no analogue — our recorder has no metadata-panel gaze), its
HPF cytoplasm/nucleus split (sub-cellular AOIs need gaze, not viewport), and any training-program
feature. A teaching companion ("screening efficiency" as feedback in the atlas quiz/tour) would be
a separate, user-approved feature.
