# Enrichment polish/harden — spec (2026-07-25)

Branch `feat/enrichment-polish` off master (`fd314c5`, post enrichment merge). Cleanup/hardening of
the merged Phase-3 analysis enrichment — closes the deferred items + Minor findings the reviews logged.
Same invariants: Python↔R 1e-6 parity (manual diff per task), blank-not-crash, additive columns
appended (existing order preserved), no unintended drift.

## P1 — parity + defensive-consistency hardening (no new columns)
- **hypot→sqrt (parity-class cleanup):** the 5 pre-existing `math.hypot(dx,dy)` sites in
  `analysis/python/blinded_focus/metrics.py` (scanpath_length_px:524, scanning_rate:860,
  path_velocity:1076, linearity:1102, mouse_viewport_coupling:1355) diverge ≤1 ULP from R's
  `sqrt(dx^2+dy^2)`. Switch them to `math.sqrt(dx*dx+dy*dy)` for bit-parity with R (the Tier-1 A3
  sites already did this). Below-tolerance so selftests must still pass; note the ≤1-ULP change.
- **magnificationSource `>0` guard (consistency, final-review Minor — refuted-as-unreachable but the
  on-paper inconsistency stands):** `analyze.py:884` / `blinded_focus.R:3210` set `"true"` when
  `base_mag is not None`, while `point_zoom`/`true_magnification` require `> 0`. Make it
  `"true" if base_mag is not None and float(base_mag) > 0 else "proxy-downsample"` (R equivalent), so a
  (recorder-impossible but hand-editable) non-positive base_mag isn't mislabeled "true" while its zoom
  used the proxy branch. Recorder never emits ≤0, so no real-data change — pure consistency.
- **selftest coverage gaps (logged Minors):** add asserts for `consensus_count_<slug>.csv` with **3
  sessions** (not just 2); `meanDiff` blank when one group has **n=1** (not just n=0/zero-var); and
  independent asserts on `calibrationGap`/`brierScore`/`confidenceAccuracyR` values (not just section
  presence). Both languages.

## P2 — mouse ICC (deferred item)
Add `mouseICC` — ICC(2,1) cross-reader agreement of the mouse-dwell grids — reusing the existing
`icc()` the viewport `compare_<slug>.csv` already uses. A slide-level stat (not pairwise), so place it
on `mouse_<slug>.csv`'s diagonal-reuse row (the same cell convention `coincidenceLevel` uses there) +
a `summary.md` "cursor agreement" line. Blank when <2 mouse sessions. Python+R+parity.

## P3 — ROI-entry segment-linearity variant (deferred item)
Add `meanSegmentLinearityROI` (`metrics.csv`, path + annotations sessions only): segment the path at
**annotation-ROI-entry** boundaries (a boundary = a path point whose grid cell is inside the reader's
union annotation mask AND whose previous point's cell was outside — an outside→inside transition, or
index 0 already inside). Mean of per-segment `linearity` over ≥2-point segments. Complements the
hotspot-based `meanSegmentLinearity` (Roa-Peña whole vs segment). Reuses `rasterize_feature_collection`
(union mask) + the grid-cell mapping. Blank if no annotations / <2 ROI-entry boundaries / no valid
segment. Python+R+parity.

## P4 — per-magnification-band cross-reader agreement (deferred item)
New `magband_agreement_<slug>.csv` (per slide, when ≥2 sessions AND canonical bands available):
`band, nSessions, meanPairwiseCC, coincidenceLevel`. For each canonical mag band, build each session's
dwell grid restricted to that band (rasterize the scanpath steps whose band == this band, idle-excluded
— reuse the existing per-band `raster_from_path(step_mask=band)` the magband figures already use),
resample to the common grid, then `mean_pairwise_cc`/`coincidence_level` across sessions for that band.
Only emit bands with ≥2 sessions having any dwell in the band. Answers "do readers agree more at
overview vs cell power" (Chakraborty). Python+R+parity. (If canonical bands unavailable — null baseMag,
tercile scheme — skip the file, documented.)

## P5 — docs
Update `analysis/python/README.md` + `analysis/R/README.md` for `mouseICC`, `meanSegmentLinearityROI`,
`magband_agreement_<slug>.csv`; flip those three items from "deferred" to "done" in
`docs/superpowers/phase3-data-dimension-audit.md`'s status header.

## Tasks (SDD, each Python+R+parity + selftest + manual parity diff)
- PT1: P1 hardening (hypot/sqrt + magSource guard + selftest coverage).
- PT2: P2 mouse ICC.
- PT3: P3 ROI-entry segment linearity.
- PT4: P4 per-band agreement.
- PT5: P5 docs + final review.
