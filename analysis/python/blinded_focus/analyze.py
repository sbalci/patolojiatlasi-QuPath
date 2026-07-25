"""CLI orchestrator for the blinded_focus analysis toolkit.

Usage::

    python -m blinded_focus.analyze <input...> --out DIR [--reference SESSIONID]
        [--roi geojson] [--labels csv] [--key csv] [--graded csv] [--figures]
        [--res 512] [--magbands 3] [--magband-scheme canonical|tercile]

``<input...>`` may be fragment JSON files, directories (recursed for ``*.json``), and/or
``.zip`` archives, in any mix. See ``../README.md`` for the full output-file contract.

Output files (written to ``--out DIR``):

- ``metrics.csv`` — one row per (slide, session); includes the Phase-1 zoom/navigation metric
  family (``avgZoom``, ``zoomVariance``, ``zoomRange``, ``magnificationPercentage``,
  ``scanningRatePxPerMin``, ``drillingRatePerMin``, ``pathVelocityPxPerSec``, ``linearity``,
  ``searchFocusRatio``) plus ``baseMagnification``/``pathTruncated`` passthrough — blank for
  sessions with no ``path`` (schema /1, /2, or path-less recordings) — and (Phase 2) annotation
  metrics ``nAnnotations``, ``annotatedAreaPx``, ``dwellInAnnotationPct``,
  ``annotationReentryCount``, ``enrichmentRatio`` (the first three populated for every session —
  0/0.0 when a session has no ``annotations``; ``annotationReentryCount`` blank without a
  ``path``; ``enrichmentRatio`` blank when its mask has no in/out split to compare) plus cursor
  metrics ``cursorOverSlidePct``, ``mouseViewportCouplingPx`` (blank unless the session's ``path``
  carries schema/5 8-element points with ``mouseX``/``mouseY``) plus (Tier 1, additive) turn-angle
  directionality ``meanAbsTurnAngleDeg``/``turnAngleEntropy`` (path-only, blank for <3 points),
  mouse kinematics ``mousePathLengthPx``/``mouseVelocityPxPerSec`` (schema/5 only), and
  ``activeFractionPct`` (path-only, ``100*durationMs/(tRel_last-tRel_first)``, not clamped >100%)
  plus (Tier 2, docs/superpowers/specs/2026-07-23-...) B1 idle-exclusion transparency columns
  ``idleMs``/``activeSpanMs`` (path-only; ``0.0`` when the path has no >60s gap -- see
  :data:`blinded_focus.metrics.IDLE_GAP_MS`), B2 Drew-fidelity zoom ``avgZoomLog2W``/
  ``drillingRateOctavesPerMin`` (ADD-alongside the untouched ``avgZoom``/``drillingRatePerMin``;
  blank when the underlying weighted mean/rate is undefined, e.g. an all-idle path), and B4
  ``magnificationSource`` (``"true"``/``"proxy-downsample"``, path-only). **B1 CHANGES**
  ``scanningRatePxPerMin``/``drillingRatePerMin``/``pathVelocityPxPerSec``/``searchFocusRatio``
  numbers for any session with a >60s idle gap (byte-identical to before for sessions without one).
  (Tier 3 C1, additive) ``nFixations``, ``meanFixationMs``, ``medianFixationMs``, ``sdFixationMs``,
  ``fixationsPerMin`` -- a deterministic I-DT (dispersion-threshold, Salvucci & Goldberg 2000)
  fixation-extraction summary over the scanpath, path-only (blank without a path); see
  :func:`blinded_focus.metrics.fixations_idt` for the pinned algorithm. (Tier 3 C2, additive)
  ``mouseCoveragePct``/``mouseEntropy`` -- coverage/entropy of a point-based mouse-dwell grid (see
  :func:`blinded_focus.metrics.mouse_raster_from_path`), schema/5 only, blank when there's no mouse
  data or zero on-slide points. (Tier 3 C4, additive) ``meanSegmentLinearity`` -- mean
  :func:`blinded_focus.metrics.linearity` over sub-paths split at the session's own top-hotspot
  cells, path-only (blank if fewer than 2 hotspots or fewer than 2 boundary points are found).
  (Tier 3 C6, additive) ``annotatedAreaUnionPx`` -- area of the UNIONED rasterized annotation mask
  (see :func:`blinded_focus.metrics.annotated_area_union_px`), always populated (``0.0`` with no
  annotations) — the overlap-correct companion to the sum-based ``annotatedAreaPx`` above (which
  double-counts overlapping/nested annotation Features); and ``visitCountJaccard`` -- Jaccard
  similarity between the session's dwell-time top hotspots and its visit-count top hotspots (see
  :func:`blinded_focus.metrics.visit_count_jaccard`), path-only (blank without a path). Both
  columns are appended at the END of the CSV (after ``meanSegmentLinearity``), not interleaved
  with the columns they conceptually relate to, per the additive/append-only column-order
  invariant. (PT3, docs/superpowers/specs/2026-07-25-enrichment-polish.md P3, additive)
  ``meanSegmentLinearityROI`` -- the ROI-entry-boundary complement to ``meanSegmentLinearity``:
  mean :func:`blinded_focus.metrics.linearity` over sub-paths split at entries into the reader's
  own union annotation mask (reusing :func:`rasterize_feature_collection`'s output at the
  session's native ``(gw, gh)``, same mask ``dwellInAnnotationPct``/``annotationReentryCount``
  use) instead of at dwell-hotspot cells; see
  :func:`blinded_focus.metrics.mean_segment_linearity_roi` for the pinned algorithm. Path +
  annotations only (blank with no annotations, no path, or fewer than 2 ROI-entry boundaries).
  Appended at the END of the CSV (after ``visitCountJaccard``), same append-only convention as
  the Tier 3 C6 pair above.
- per slide: ``compare_<slug>.csv`` (pairwise cc/sim/iou, tidy long format — see below),
  ``consensus_<slug>.png``. Also carries a slide-level ``coincidenceLevel`` (one row) and a
  per-session ``regionCoveragePct`` (vs the slide consensus). (Tier 3 C6, appended) ``jsDivergence``
  -- Jensen-Shannon divergence (base-2, symmetric, bounded ``[0, 1]``; see
  :func:`blinded_focus.metrics.js_divergence`) of every pairwise row (not diagonal-only — unlike
  ``diffFromConsensus``/``coincidenceLevel``, this is a genuine pairwise quantity), exactly ``0.0``
  on the diagonal.
- per slide, when ``--reference``/``--roi`` given: ``reference_<slug>.csv``. (Tier 3 C6, appended)
  ``precisionAtTopK``/``recall`` -- the reader's top-:data:`blinded_focus.metrics.PRECISION_K_FRAC`
  (10%) highest-dwell cells vs the SAME reference mask this file already compares against (whichever
  of ``--roi``/``--reference`` built it — see :func:`blinded_focus.metrics.precision_recall_at_topk`),
  separating "missed target" (low recall) from "wasted attention" (low precision). Blank if the
  reference mask or the top-K set is empty.
- per slide, when the slide has >=2 sessions (Tier 3 C6): ``consensus_count_<slug>.csv`` — the
  spatial structure ``coincidenceLevel`` collapses to one scalar: for every grid cell (on the
  slide's common resampled grid) with ``nReaders`` (count of sessions whose own
  :func:`blinded_focus.metrics.normalise_max`-normalized dwell exceeds
  :data:`HOTSPOT_THRESH_FRAC`, the SAME threshold :func:`blinded_focus.metrics.count_hotspots`
  already uses for ``nHotspots`` — a different, coarser threshold than ``IOU_THRESH`` used by
  ``coincidenceLevel`` itself) ``>= 1``: ``cellRow``, ``cellCol``, ``nReaders``. Cells with
  ``nReaders == 0`` are omitted (not written). Written (possibly with zero data rows) for every
  slide with >=2 sessions, regardless of whether any cell clears the threshold.
- per slide, when any session has a schema/3+ ``path``: ``scanpath_<slug>.csv`` (``sessionA``,
  ``sessionB``, ``levenshteinSim``, ``transitionEntropy`` (diagonal-only), plus (Tier 3 C3,
  additive) ``dtwDistance`` -- Dynamic Time Warping between the two sessions' z-normalized
  viewport-center sequences, standard DP (NOT Frechet distance), raw accumulated cost (not
  path-length-normalized); see :func:`blinded_focus.metrics.dtw_distance` for the pinned
  algorithm -- always exactly ``0.0`` on the diagonal), and (Phase 1; Tier 2 B1/B3)
  ``magbands_<slug>.csv`` — per-session dwell time (Tier 2 B1: idle-excluded) in
  each zoom band, plus a ``bandScheme`` column (``"canonical"``/``"tercile"``, Tier 2 B3): by
  default (``--magband-scheme canonical``) sessions with a computable true magnification
  (``baseMagnification`` + per-point ``dsMilli``) get 7 fixed bands via
  :data:`blinded_focus.metrics.MAG_BAND_CUTS`; every other session (or every session, under
  ``--magband-scheme tercile``) falls back to the pre-B3 ``--magbands`` (default 3) within-path
  quantile scheme (tidy long format; see :func:`blinded_focus.metrics.magband_labels_for_scheme`).
  **B3 CHANGES** the scheme (and band count) for any session whose true magnification is
  computable (unchanged for null-``baseMagnification`` sessions, which keep terciles).
- per slide, when any session has at least one annotation: (Phase 2) ``annotations_<slug>.csv`` —
  pairwise IoU of each session's own rasterized annotated region (tidy long format, same
  diagonal-reuse convention as ``compare_<slug>.csv``) plus a slide-level ``coincidenceLevel``
  over those same regions.
- per slide, when at least one session carries schema/5 mouse data (Tier 3 C2):
  ``mouse_<slug>.csv`` — pairwise ``sessionA``, ``sessionB``, ``cc``, ``iou`` of each session's
  own point-based mouse-dwell grid (resampled to the slide's common grid; a session without mouse
  data contributes an all-zero grid, same convention ``annotations_<slug>.csv`` uses for a
  session with no annotations), plus a slide-level ``coincidenceLevel`` (same diagonal-reuse
  convention as ``compare_<slug>.csv``/``annotations_<slug>.csv``) and (PT2,
  docs/superpowers/specs/2026-07-25-enrichment-polish.md P2) ``mouseICC`` — ICC(2,1) over only the
  sessions that actually carry mouse data (a filtered population, unlike ``cc``/``iou``/
  ``coincidenceLevel`` above, which include every session for CSV matrix completeness), placed on
  the same diagonal-reuse row.
- per slide, when >=2 sessions have a computable **canonical** magnification band scheme (PT4,
  docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): ``magband_agreement_<slug>.csv`` —
  answers "do readers agree more at overview vs cell power" (Chakraborty). One row per canonical
  magnification band (:data:`blinded_focus.metrics.MAG_BAND_LABELS`, ascending magnification):
  ``band``, ``nSessions``, ``meanPairwiseCC``, ``coincidenceLevel`` — each session's dwell-time
  raster is restricted to the steps assigned to that band (reusing
  :func:`blinded_focus.metrics.raster_from_path`'s ``step_mask``, idle-excluded automatically),
  resampled to the slide's common grid, then compared with the same
  :func:`blinded_focus.metrics.mean_pairwise_cc`/:func:`blinded_focus.metrics.coincidence_level`
  ``compare_<slug>.csv`` uses. A band is emitted only when >=2 sessions actually dwelled in it.
  Skipped entirely (no file) when fewer than 2 sessions on the slide use the canonical scheme --
  i.e. under ``--magband-scheme tercile`` or when fewer than 2 sessions have a computable
  ``baseMagnification``/``dsMilli`` (both collapse to "0 or 1 canonical-scheme sessions").
- (Phase 3) ``decisions.csv`` — one row per (slide, session) with a hand-entered ``decision``
  object (``diagnosis``, ``confidence``, ``decisionMs``): ``slide``, ``sessionId`` (stable join
  key), ``session`` (display label), ``diagnosis``, ``confidence``, ``confidenceScaled``
  (``(confidence-1)/4``, blank if ``confidence`` isn't numeric), ``decisionMs``,
  ``decisionLatencyMs`` (``== decisionMs``, i.e. time-from-slide-open — this is now permanently
  distinct from ``responseLatencyMs`` below, not a placeholder), ``correctDx`` (DISPLAY-ONLY, from
  ``--key``), ``correct`` (HAND-GRADED ONLY, from ``--graded`` — blank otherwise; never
  auto-derived by comparing ``diagnosis`` to ``correctDx``), (Tier 3 C5, appended) ``promptShownMs``
  (passthrough of the recorder's dialog-shown timestamp, relative to slide-record start; blank for
  a fragment recorded before the recorder gained this field) and ``responseLatencyMs`` (=
  ``decisionMs - promptShownMs``, the true once-prompted deliberation time; blank unless BOTH are
  numeric). Written only if at least one fragment carries a decision; otherwise a stderr warning
  and no file.
- (Phase 3) ``nav_accuracy.csv`` — written only when ``--graded`` supplies at least one graded
  decision: one row per :data:`NAV_ACCURACY_COLS` navigation metric (Tier 1 A1: extended with
  ``durationMs``, ``cursorOverSlidePct``, ``mouseViewportCouplingPx``) plus one row for
  ``decisionLatencyMs`` (Tier 1 A1, sourced directly from ``decision_rows``, not ``metrics_rows``),
  joined to ``correct`` by ``(slide, sessionId)`` — ``metric``, ``n``, ``pointBiserialR`` (plain
  Pearson, blank unless ``n >= 5`` and both sides have non-zero variance — see
  :func:`_pearson_guarded`), ``meanCorrect``, ``meanIncorrect``, ``medianCorrect``,
  ``medianIncorrect``, ``meanDiff`` (blank unless both groups have ``n >= 2``). No p-values or
  confidence intervals at this pilot scale.
- per slide (Tier 1 A5): ``hotspots_<slug>.csv`` — top-:data:`HOTSPOT_TOP_N` dwell cells per
  session (every session, at its own native grid resolution): ``session``, ``rank``, ``cellRow``,
  ``cellCol``, ``centerImageX``, ``centerImageY``, ``dwellMs``, ``dwellFrac``. Ties broken
  deterministically (value descending, flat index ascending — see
  :func:`blinded_focus.metrics.top_hotspots`).
- per slide, when any session has a schema/3+ ``path`` (Tier 1 A5): ``transitions_<slug>.csv`` —
  top-:data:`TRANSITIONS_TOP_N` directed cell-to-cell transitions per session: ``session``,
  ``fromCell``, ``toCell``, ``count`` (ties broken deterministically — see
  :func:`blinded_focus.metrics.top_transitions`).
- per slide, when any session has a schema/3+ ``path`` AND at least one fixation was found on this
  slide (Tier 3 C1): ``fixations_<slug>.csv`` — one row per I-DT fixation, per session: ``session``,
  ``idx`` (1-based, in scanpath order), ``startMs``, ``durationMs``, ``centerImageX``,
  ``centerImageY``, ``nPoints`` — see :func:`blinded_focus.metrics.fixations_idt`.
- ``summary.md`` — counts, (Tier 2 B4, when any path-carrying session exists) a magnification-
  source caveat line, per-slide agreement, reference ranking, headline zoom/scanning numbers,
  (Phase 2) headline annotation-coverage + cursor-coupling numbers, and (Phase 3, gated on
  ``--graded``) a "Navigation ↔ diagnostic accuracy" section — overall accuracy, per-metric r/n/
  group means, and a calibration summary (``calibrationGap``, ``brierScore``,
  ``confidenceAccuracyR``).
- with ``--figures``: per-(slide,session) heatmap/scanpath/coverage-over-time PNGs under
  ``<out>/<slug>/``, plus (Phase 1, when a path exists) a scanpath-rasterized fine heatmap at
  ``--res`` resolution (``..._scanpath_raster.png`` — the trustworthy high-magnification map,
  independent of the recorded grid) and one heatmap per magnification band
  (``..._magband<N>.png``); (Tier 3 C2, when the session carries schema/5 mouse data)
  ``..._mousemap.png`` — a heatmap of the point-based mouse-dwell grid at ``--res`` resolution
  (reuses the same heatmap plotting helper; not part of the numeric-parity contract); plus, per
  slide when any session has a path (Tier 1 A6): ``overlay_<slug>_scanpaths.png`` — every
  path-carrying session's viewport-center path on one shared axis (not part of the numeric-parity
  contract — a PNG, existence/valid-magic only).

Design note on "matrices" (``compare_<slug>.csv`` / ``scanpath_<slug>.csv`` / ``magbands_<slug>.csv``
/ ``annotations_<slug>.csv`` / ``mouse_<slug>.csv``): these are written as *tidy long-format* tables (one row per pair or
per (session, band)) rather than 2D matrix-shaped CSVs, so a single file can carry multiple metrics
and stays trivial to `pivot()`/parse in either Python or R. As a compact way to attach per-session
(not per-pair) values, the *diagonal* row of each pair table also carries extra columns
(``diffFromConsensus``/``regionCoveragePct`` in ``compare_<slug>.csv``, ``transitionEntropy`` in
``scanpath_<slug>.csv``) — off-diagonal rows leave them blank. ``coincidenceLevel`` is a
slide-level (not per-session) statistic; by convention it is written on exactly one row per
slide — the diagonal row of the *first* session in insertion order (``session_ids[0]``) — all
other rows leave it blank. ``annotations_<slug>.csv`` and ``mouse_<slug>.csv`` reuse this exact
same diagonal-reuse convention for their own (annotation-region / mouse-dwell) ``coincidenceLevel``.
An R port must place it identically for the two toolkits' CSVs to diff-match.
"""
import argparse
import csv
import json
import os
import statistics
import sys

import numpy as np

from . import figures as fig
from . import io as bf_io
from . import metrics as m

#: Threshold fraction (of a grid's own max) used for the connected-region hotspot count.
HOTSPOT_THRESH_FRAC = 0.5
#: Threshold fraction used for all IoU / above-threshold-region computations (matches the
#: spec's iou(a,b,thresh=0.1) default and the reference attended-map mask definition), also
#: reused for coincidence_level / region_coverage_pct's "above-threshold" cell masks.
IOU_THRESH = 0.1
#: Default resolution (longest grid side, aspect-preserved) for the scanpath-rasterized fine
#: heatmap and the magnification-band heatmaps, overridable via ``--res``.
DEFAULT_RES = 512
#: Default number of within-path zoom bands (terciles) for the magnification-split analysis,
#: overridable via ``--magbands``.
DEFAULT_MAGBANDS = 3
#: Tier 1 A5: number of top-dwell cells exported per session to ``hotspots_<slug>.csv``.
HOTSPOT_TOP_N = 5
#: Tier 1 A5: number of top directed cell-transitions exported per session to
#: ``transitions_<slug>.csv``.
TRANSITIONS_TOP_N = 15
#: Tier 2 B3: default ``--magband-scheme`` -- canonical (true-magnification) bands, auto-falling
#: back to the tercile scheme per-session when a session's baseMagnification/dsMilli aren't
#: computable (see :func:`blinded_focus.metrics.magband_labels_for_scheme`).
DEFAULT_MAGBAND_SCHEME = "canonical"
#: Tier 2 B3: number of canonical magnification bands (fixed by
#: :data:`blinded_focus.metrics.MAG_BAND_CUTS`'s 6 cut points -- NOT overridable via
#: ``--magbands``, which only sizes the tercile fallback scheme).
CANONICAL_MAGBAND_COUNT = len(m.MAG_BAND_LABELS)


def _res_grid_dims(img_w, img_h, res):
    """Aspect-preserving grid dims with the longest side capped at ``res`` (mirrors the QuPath
    extension's own ``GRID_MAX``-style longest-side cap): ``max(gw, gh) == res`` (rounded), the
    other side scaled by the image's aspect ratio, floored at 1."""
    img_w = float(img_w) if img_w else 1.0
    img_h = float(img_h) if img_h else 1.0
    longest = max(img_w, img_h)
    gw = max(1, int(round(res * img_w / longest)))
    gh = max(1, int(round(res * img_h / longest)))
    return gw, gh


# ---------------------------------------------------------------------------
# ROI (GeoJSON polygon, image-px coordinates) rasterization
# ---------------------------------------------------------------------------

def _point_in_poly(x, y, rings):
    """Even-odd ray-casting point-in-polygon test across all rings (handles holes naturally:
    a point inside an odd number of rings is inside the polygon)."""
    inside = False
    for ring in rings:
        n = len(ring)
        if n < 3:
            continue
        for i in range(n):
            x1, y1 = ring[i]
            x2, y2 = ring[(i + 1) % n]
            if (y1 > y) != (y2 > y):
                x_int = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                if x < x_int:
                    inside = not inside
    return inside


def _extract_rings(geometry):
    """Flatten a GeoJSON Polygon/MultiPolygon geometry into a list of rings (each a list of
    (x, y) tuples). All rings (exterior + holes) are returned; even-odd counting handles holes."""
    gtype = geometry.get("type")
    coords = geometry.get("coordinates") or []
    rings = []
    if gtype == "Polygon":
        rings = [[(pt[0], pt[1]) for pt in ring] for ring in coords]
    elif gtype == "MultiPolygon":
        for poly in coords:
            rings.extend([(pt[0], pt[1]) for pt in ring] for ring in poly)
    return rings


def rings_from_feature_collection(fc):
    """Same ring-extraction as :func:`load_roi_rings`, but from an already-parsed GeoJSON dict
    (a ``Feature``, ``FeatureCollection``, or bare geometry) rather than a file path -- used for
    a fragment's embedded ``annotations`` field (Phase 2), which arrives as parsed JSON, not a
    file on disk. Returns a flat list of rings (image-px coordinates) suitable for
    :func:`rasterize_roi`/point-in-polygon (holes handled via the even-odd rule). ``[]`` for a
    falsy/malformed input (e.g. an empty ``{"type": "FeatureCollection", "features": []}``, the
    default from :func:`blinded_focus.io.get_annotations`)."""
    if not fc or not isinstance(fc, dict):
        return []
    if fc.get("type") == "FeatureCollection":
        rings = []
        for feat in fc.get("features", []) or []:
            if not isinstance(feat, dict):
                continue
            rings.extend(_extract_rings(feat.get("geometry", {}) or {}))
        return rings
    if fc.get("type") == "Feature":
        return _extract_rings(fc.get("geometry", {}) or {})
    return _extract_rings(fc)


def load_roi_rings(roi_path):
    """Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon and
    return its rings (image-px coordinates) for rasterization."""
    with open(roi_path, "r", encoding="utf-8") as fh:
        d = json.load(fh)
    return rings_from_feature_collection(d)


def load_roi_fc(roi_path):
    """Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon file
    and return the parsed dict (not its flattened rings), for per-Feature union rasterization via
    :func:`rasterize_feature_collection` -- a multi-polygon/overlapping reference ROI must be
    rasterized feature-by-feature and unioned, not with a single pooled-rings even-odd test (see
    that function's docstring); this is the ``--roi`` counterpart to the annotation-mask fix."""
    with open(roi_path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _shoelace_area(ring):
    """Absolute area (px^2) of a simple polygon ring (a list of ``(x, y)`` tuples) via the
    shoelace formula: ``abs(sum(x_i*y_{i+1} - x_{i+1}*y_i)) / 2``. ``0.0`` for a degenerate ring
    (fewer than 3 points)."""
    n = len(ring)
    if n < 3:
        return 0.0
    s = 0.0
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


def _polygon_area(poly_coords):
    """Area (px^2) of one GeoJSON ``Polygon`` geometry's ``coordinates`` array: the exterior
    ring's area (first ring) minus each hole ring's area (subsequent rings), each computed via
    :func:`_shoelace_area` -- using the *absolute* area of every ring sidesteps any ambiguity in
    ring winding order (CW vs CCW), which GeoJSON does not strictly mandate a producer follow.
    Clamped at 0.0 (a malformed polygon whose holes exceed its exterior should never report a
    negative area). ``0.0`` for an empty ``coordinates`` array."""
    if not poly_coords:
        return 0.0
    rings = [[(pt[0], pt[1]) for pt in ring] for ring in poly_coords]
    area = _shoelace_area(rings[0])
    for hole in rings[1:]:
        area -= _shoelace_area(hole)
    return max(area, 0.0)


def _feature_geometry_area(geometry):
    """Area (px^2) of one GeoJSON ``Polygon``/``MultiPolygon`` geometry: :func:`_polygon_area` of
    each constituent polygon, summed for ``MultiPolygon``. ``0.0`` for any other geometry type
    (e.g. ``Point``/``LineString`` annotations, which QuPath also allows but which have no area)."""
    gtype = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if gtype == "Polygon":
        return _polygon_area(coords)
    if gtype == "MultiPolygon":
        return sum(_polygon_area(poly) for poly in coords)
    return 0.0


def annotations_area_px(fc):
    """Total area (image px^2) of every ``Polygon``/``MultiPolygon`` feature in a GeoJSON
    ``FeatureCollection`` dict (a fragment's ``annotations`` field), via
    :func:`_feature_geometry_area` summed across features -- the ``annotatedAreaPx`` column.
    ``0.0`` for an empty/missing/malformed FeatureCollection (including non-area annotation
    geometries only, e.g. a lone point annotation).

    Caveat: this is the **sum of per-feature areas, not de-duplicated for overlap** -- two
    overlapping/nested annotation Features (unlike the mask-based metrics, which correctly union
    them via :func:`rasterize_feature_collection`) report a combined ``annotatedAreaPx`` larger
    than their true union area. True polygon-union area would need geometric clipping, which
    neither toolkit implements."""
    if not fc or not isinstance(fc, dict) or fc.get("type") != "FeatureCollection":
        return 0.0
    total = 0.0
    for feat in fc.get("features", []) or []:
        if not isinstance(feat, dict):
            continue
        geom = feat.get("geometry")
        if isinstance(geom, dict):
            total += _feature_geometry_area(geom)
    return total


def rasterize_roi(rings, gw, gh, img_w, img_h):
    """Rasterize polygon rings (image-px coords) to a flat ``(gw*gh,)`` boolean mask by testing
    each grid cell's center point for containment."""
    gw, gh = int(gw), int(gh)
    mask = np.zeros((gh, gw), dtype=bool)
    for row in range(gh):
        cy = (row + 0.5) / gh * img_h
        for col in range(gw):
            cx = (col + 0.5) / gw * img_w
            mask[row, col] = _point_in_poly(cx, cy, rings)
    return mask.flatten()


def rasterize_feature_collection(fc, gw, gh, img_w, img_h):
    """Rasterize a GeoJSON dict (``FeatureCollection``, ``Feature``, or bare geometry -- the same
    shapes :func:`rings_from_feature_collection` accepts) to a flat ``(gw*gh,)`` boolean mask by
    rasterizing **each Feature's own rings separately** (that Feature's exterior + its own holes --
    even-odd within the Feature, via :func:`rasterize_roi`) and taking the **union (logical OR)**
    across Features.

    This is deliberately NOT the same as pooling every Feature's rings into one flat list and
    running a single even-odd test across all of them (:func:`rings_from_feature_collection` +
    :func:`rasterize_roi`): even-odd correctly handles holes *within one polygon*, but pooling
    rings from separate Features breaks down when two distinct Features geometrically overlap
    (e.g. a coarse "tumor" annotation with a smaller "high-grade focus" annotation nested inside
    it) -- a point inside both gets even parity under the pooled test and is wrongly reported
    outside. Rasterizing feature-by-feature and OR-ing the results sidesteps this: single-feature
    and hole-within-one-feature behaviour is unchanged (this is a strict superset of the
    pooled-rings result -- only cross-feature overlap changes, from wrongly-excluded to
    correctly-included).

    A falsy/malformed ``fc``, or one with no features/rings at all, returns an all-``False`` mask
    without walking any grid cell (same short-circuit the pooled-rings call sites relied on)."""
    gw, gh = int(gw), int(gh)
    if not fc or not isinstance(fc, dict):
        return np.zeros(gw * gh, dtype=bool)
    if fc.get("type") == "FeatureCollection":
        features = [feat for feat in (fc.get("features", []) or []) if isinstance(feat, dict)]
    elif fc.get("type") == "Feature":
        features = [fc]
    else:
        features = [{"geometry": fc}]  # bare geometry: treat as a single implicit feature
    mask = np.zeros(gw * gh, dtype=bool)
    for feat in features:
        rings = _extract_rings(feat.get("geometry", {}) or {})
        if not rings:
            continue
        mask |= rasterize_roi(rings, gw, gh, img_w, img_h)
    return mask


# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------

def _session_label(frag, labels):
    sid = frag.get("sessionId", "unknown")
    return labels.get(sid, sid)


def _target_grid_dims(frags):
    """Pick the largest-resolution (gridWidth, gridHeight) among a slide's fragments as the
    common nearest-neighbour resample target for cross-session metrics."""
    return max(
        ((int(f["gridWidth"]), int(f["gridHeight"])) for f in frags),
        key=lambda wh: wh[0] * wh[1],
    )


def _sanitize_nan(value):
    """Map a float NaN to ``""`` (blank), matching the R toolkit's ``write.csv(..., na="")``
    convention (R's ``NaN``/``NA`` both render as blank there). Without this, ``csv.DictWriter``
    would str()-ify a NaN (e.g. ``auc_judd()``'s documented NaN-on-degenerate-mask return) to the
    literal text ``"nan"``, which is a different on-disk representation of "undefined" than R's
    blank cell for the same metric on the same input."""
    if isinstance(value, float) and value != value:  # NaN != NaN
        return ""
    return value


def _write_csv(path, rows, fieldnames):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        # extrasaction="ignore": a row dict may carry extra in-memory-only keys not in
        # `fieldnames` (e.g. metrics_rows' "sessionId", used only by _nav_accuracy_rows's join --
        # see the module docstring's "coincidenceLevel" section and the row-build comment in
        # analyze()). Without this, DictWriter's default extrasaction="raise" would abort the
        # write the moment such an extra key showed up; "ignore" drops it and writes exactly the
        # same columns as before (byte-identical output for every existing call site, whose row
        # dicts already match `fieldnames` 1:1).
        w = csv.DictWriter(fh, fieldnames=fieldnames, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({k: _sanitize_nan(v) for k, v in r.items()})


def _fmt(x, nd=3):
    try:
        xf = float(x)
    except (TypeError, ValueError):
        return "n/a"
    if xf != xf:  # NaN
        return "n/a"
    return f"{xf:.{nd}f}"


#: Navigation-metric columns (from ``metrics.csv``) correlated against graded diagnostic accuracy
#: in :func:`_nav_accuracy_rows`. All are grid/path-level per-session metrics already present as
#: ``row`` keys in the main loop above.
#:
#: Tier 1 A1 extension: ``durationMs``, ``cursorOverSlidePct``, ``mouseViewportCouplingPx`` close
#: the "recorded-but-uncorrelated" gap identified by the data-dimension audit -- they were already
#: written to ``metrics.csv`` but never joined against graded accuracy. (``decisionLatencyMs``, the
#: fourth recorded-but-uncorrelated dimension, is sourced directly from ``decision_rows`` rather
#: than this metrics.csv-backed list -- see :func:`_nav_accuracy_rows`'s dedicated block below.)
NAV_ACCURACY_COLS = [
    "avgZoom", "zoomVariance", "magnificationPercentage", "scanningRatePxPerMin",
    "drillingRatePerMin", "coveragePct", "dwellInAnnotationPct", "enrichmentRatio",
    "searchFocusRatio", "linearity", "pathVelocityPxPerSec", "entropy", "transitionEntropy",
    "durationMs", "cursorOverSlidePct", "mouseViewportCouplingPx",
]
#: Minimum sample size for a defensible point-biserial r at this pilot scale -- below this (or
#: with zero variance on either side) :func:`_pearson_guarded` returns blank, never a numerically
#: unstable/undefined statistic.
MIN_CORRELATION_N = 5


def _pearson_guarded(xs, ys, min_n=MIN_CORRELATION_N):
    """Plain Pearson r (point-biserial when one side is 0/1), or ``float('nan')`` if ``n < min_n``
    or either side has zero variance. Parity-safe with the planned R port: ``numpy.corrcoef``,
    statistic only -- no p-value, no confidence interval (pilot-scale n doesn't support them)."""
    xs = np.asarray(xs, dtype=float)
    ys = np.asarray(ys, dtype=float)
    if len(xs) < min_n or np.var(xs) == 0.0 or np.var(ys) == 0.0:
        return float("nan")
    return float(np.corrcoef(xs, ys)[0, 1])


def _nav_stat_row(metric_name, xs, ys):
    """Shared point-biserial-r + group mean/median/meanDiff computation for one navigation metric,
    given already-filtered/zipped ``(xs, ys)`` pairs (``xs`` = the metric's value, ``ys`` = the
    matching 0/1 graded ``correct``). Factored out of :func:`_nav_accuracy_rows`'s per-column loop
    so the Tier 1 A1 ``decisionLatencyMs`` row (sourced directly from ``decision_rows``, not via
    the ``metrics_rows`` column loop) computes its stats identically, not via a parallel
    reimplementation that could silently drift from the original."""
    r = _pearson_guarded(ys, xs)
    correct_vals = [x for x, y in zip(xs, ys) if y == 1]
    incorrect_vals = [x for x, y in zip(xs, ys) if y == 0]
    mean_correct = float(statistics.mean(correct_vals)) if correct_vals else float("nan")
    mean_incorrect = float(statistics.mean(incorrect_vals)) if incorrect_vals else float("nan")
    median_correct = float(statistics.median(correct_vals)) if correct_vals else float("nan")
    median_incorrect = float(statistics.median(incorrect_vals)) if incorrect_vals else float("nan")
    mean_diff = (
        (mean_correct - mean_incorrect)
        if (len(correct_vals) >= 2 and len(incorrect_vals) >= 2)
        else float("nan")
    )
    return {
        "metric": metric_name,
        "n": len(xs),
        "pointBiserialR": r,
        "meanCorrect": mean_correct,
        "meanIncorrect": mean_incorrect,
        "medianCorrect": median_correct,
        "medianIncorrect": median_incorrect,
        "meanDiff": mean_diff,
    }


def _nav_accuracy_rows(metrics_rows, decision_rows):
    """Join decisions' hand-graded ``correct`` (0/1) onto ``metrics_rows`` by the stable
    ``(slide, sessionId)`` key (never the display label), then compute a guarded point-biserial r
    plus group means/medians per navigation column in :data:`NAV_ACCURACY_COLS` (via
    :func:`_nav_stat_row`), plus (Tier 1 A1) a ``decisionLatencyMs`` row sourced directly from
    ``decision_rows``.

    Returns ``(rows, had_any_graded)`` -- ``had_any_graded`` gates whether ``nav_accuracy.csv`` and
    the summary section get written at all (only when at least one ``--graded`` row was supplied).
    """
    # Direct sessionId join: metrics_rows carries the stable sessionId (stamped in analyze()'s
    # per-session loop, right next to the human DISPLAY LABEL) -- so no label-based bridge is
    # needed here at all. (Prior versions recovered the sessionId via a (slide, display-label)
    # lookup into decision_rows, which silently collapsed two sessions sharing a display label
    # -- e.g. via `--labels` mapping distinct sessionIds to the same name -- onto whichever
    # session's decision row was built last, misattributing/erasing the other's grade. Stamping
    # sessionId directly on metrics_rows removes the label from this join entirely.)
    correct_by = {
        (r["slide"], r["sessionId"]): r["correct"] for r in decision_rows if r["correct"] in (0, 1)
    }
    rows = []
    had_any_graded = bool(correct_by)
    for col in NAV_ACCURACY_COLS:
        xs, ys = [], []
        for mr in metrics_rows:
            key = (mr["slide"], mr["sessionId"])
            if key not in correct_by:
                continue
            # Some metrics (e.g. enrichmentRatio) store a literal float NaN in the in-memory row
            # for a well-defined "undefined" case (only rendered as a blank cell later, at
            # metrics.csv write time, via _sanitize_nan) -- treat that identically to a blank
            # here, or a stray NaN would silently poison statistics.mean/np.var below.
            v = _sanitize_nan(mr.get(col, ""))
            if v == "" or v is None:
                continue
            xs.append(float(v))
            ys.append(correct_by[key])
        rows.append(_nav_stat_row(col, xs, ys))

    # Tier 1 A1: decisionLatencyMs is sourced directly from decision_rows -- it and `correct`
    # already live on the SAME (slide, sessionId) decision entry, so there is no cross-table join
    # to perform here at all (unlike the metrics_rows columns above); iterating decision_rows
    # directly is itself the (slide, sessionId)-keyed join, with no label bridge in sight.
    lat_xs, lat_ys = [], []
    for r in decision_rows:
        if r["correct"] not in (0, 1):
            continue
        lat = r.get("decisionLatencyMs")
        if lat == "" or lat is None:
            continue
        try:
            lat_val = float(lat)
        except (TypeError, ValueError):
            continue
        lat_xs.append(lat_val)
        lat_ys.append(r["correct"])
    rows.append(_nav_stat_row("decisionLatencyMs", lat_xs, lat_ys))

    return rows, had_any_graded


def _calibration_stats(decision_rows):
    """Soft-guarded calibration summary over graded rows with a non-blank ``confidenceScaled``:
    ``calibrationGap = mean(confidenceScaled) - mean(correct)`` (positive = overconfident),
    ``brierScore = mean((confidenceScaled - correct) ** 2)`` (lower = better calibrated), and
    ``confidenceAccuracyR`` (hard-guarded via :func:`_pearson_guarded`). All three are ``float('nan')``
    (renders blank) when there are zero eligible rows; ``confidenceAccuracyR`` additionally needs
    ``n >= MIN_CORRELATION_N`` and non-zero variance on both sides (same guard as
    :func:`_nav_accuracy_rows`)."""
    pairs = [
        (r["confidenceScaled"], r["correct"]) for r in decision_rows
        if r["correct"] in (0, 1)
        and isinstance(r["confidenceScaled"], (int, float))
        and r["confidenceScaled"] == r["confidenceScaled"]  # exclude NaN (NaN != NaN)
    ]
    if not pairs:
        return float("nan"), float("nan"), float("nan"), 0
    conf_vals = [p[0] for p in pairs]
    correct_vals = [p[1] for p in pairs]
    gap = float(statistics.mean(conf_vals) - statistics.mean(correct_vals))
    brier = float(statistics.mean((c - a) ** 2 for c, a in pairs))
    conf_acc_r = _pearson_guarded(conf_vals, correct_vals)
    return gap, brier, conf_acc_r, len(pairs)


# ---------------------------------------------------------------------------
# core pipeline
# ---------------------------------------------------------------------------

def analyze(
    inputs, out_dir, reference=None, roi=None, labels_csv=None, make_figures=False,
    res=DEFAULT_RES, magbands=DEFAULT_MAGBANDS, key_csv=None, graded_csv=None,
    magband_scheme=DEFAULT_MAGBAND_SCHEME,
):
    """Run the full pipeline over ``inputs`` (files/dirs/zips) into ``out_dir``. Returns the list
    of metrics-row dicts written to ``metrics.csv`` (for programmatic/test use).

    ``res`` sets the longest-side resolution of the scanpath-rasterized fine/magband heatmaps
    (see :func:`_res_grid_dims`); ``magbands`` sets the number of within-path zoom bands for the
    tercile-fallback magnification-split analysis (see
    :func:`blinded_focus.metrics.zoom_band_labels`).

    ``key_csv`` (``--key``) is a DISPLAY-ONLY ``slideKey,correctDx`` answer key -- it populates
    ``decisions.csv``'s ``correctDx`` column beside the reader's own ``diagnosis`` for a human to
    compare, but is never string-matched against ``diagnosis`` to derive ``correct``. ``correct``
    comes only from ``graded_csv`` (``--graded``, a ``slideKey,sessionId,correct`` hand-graded
    sheet); without it every ``correct`` cell is blank and no navigation-accuracy correlation is
    computed (see :func:`_nav_accuracy_rows`).

    ``magband_scheme`` (Tier 2 B3, ``--magband-scheme``) is ``"canonical"`` (default) or
    ``"tercile"``. ``"canonical"`` uses true-objective-magnification bands
    (:func:`blinded_focus.metrics.MAG_BAND_CUTS`, 7 bands) for every session whose
    ``baseMagnification``/``dsMilli`` are computable, auto-falling back to the tercile scheme
    per-session otherwise (see :func:`blinded_focus.metrics.magband_labels_for_scheme`).
    ``"tercile"`` forces the pre-B3 within-path quantile scheme for every session regardless of
    ``baseMagnification`` availability. ``magbands_<slug>.csv`` gains a ``bandScheme`` column
    recording which scheme was actually used for each session's rows.
    """
    os.makedirs(out_dir, exist_ok=True)
    fragments = bf_io.load_fragments(inputs)
    if not fragments:
        print(
            "warning: no valid fragments found (schema atlas-focus-contribution/{1,2,3,4,5})",
            file=sys.stderr,
        )
    labels = bf_io.load_labels(labels_csv)
    answer_key = bf_io.load_answer_key(key_csv)
    graded = bf_io.load_graded(graded_csv)
    groups = bf_io.group_by_slide(fragments)
    roi_fc = load_roi_fc(roi) if roi else None
    # Kept only for the reference/ROI gate below (truthy iff the ROI file has >=1 ring) -- the
    # actual reference mask is built per-Feature + unioned via
    # rasterize_feature_collection(roi_fc, ...) below, so a multi-polygon/overlapping reference
    # ROI composes correctly (same fix as the annotation mask; see its docstring).
    roi_rings = rings_from_feature_collection(roi_fc) if roi_fc else None

    metrics_rows = []
    decision_rows = []
    slide_summaries = []
    reference_summaries = []

    for slide_key, frags in sorted(groups.items()):
        slide_slug = bf_io.slug(slide_key)

        # One fragment per session: keep the richest (max sampleCount) if a session contributed
        # more than once (mirrors tools/aggregate-focus.py's dedup rule).
        by_session = {}
        for f in frags:
            sid = f.get("sessionId") or f"anon-{len(by_session)}"
            prev = by_session.get(sid)
            if prev is None or f.get("sampleCount", 0) > prev.get("sampleCount", 0):
                by_session[sid] = f
        sessions = list(by_session.items())
        if not sessions:
            continue

        tw, th = _target_grid_dims([f for _, f in sessions])

        resampled = {}      # sessionId -> resampled (tw*th,) np.array, raw units
        native_grid = {}     # sessionId -> (grid list, gw, gh)
        path_seq = {}        # sessionId -> visited-cell sequence (schema/3 only)
        common_ann_masks = {}  # sessionId -> this session's own annotated region, resampled to
                                # (tw, th) boolean -- used only by the cross-user annotations_<slug>.csv
        mouse_native = {}    # sessionId -> this session's own NATIVE (gw, gh) point-based mouse-
                             # dwell grid (Tier 3 C2) -- all-zero (never None) for a session with
                             # no schema/5 mouse data or zero on-slide points, mirroring
                             # common_ann_masks' all-False convention for annotation-less sessions,
                             # so mouse_<slug>.csv's cross-session resample/compare never needs a
                             # None-check.

        for sid, f in sessions:
            gw, gh = int(f["gridWidth"]), int(f["gridHeight"])
            grid = [float(v) for v in f["grid"]]
            native_grid[sid] = (grid, gw, gh)
            resampled[sid] = m.resample_nn(grid, gw, gh, tw, th)
            mouse_native[sid] = np.zeros(gw * gh, dtype=float)

            comx, comy = m.center_of_mass(grid, gw, gh)
            base_mag = f.get("baseMagnification")
            img_w = f.get("imageWidth", 1)
            img_h = f.get("imageHeight", 1)

            # ---- Phase 2: this session's own annotations, rasterized to its native (gw, gh)
            # grid (matching the resolution `grid`/`dwell` are already in) -- reuses the same
            # GeoJSON-polygon-to-grid rasterizer as the `--roi` CLI flag. Each annotation Feature
            # is rasterized on its own and the per-feature masks are unioned (OR'd), NOT pooled
            # into one flat ring list and tested with a single even-odd pass -- pooling
            # misclassifies a point inside two overlapping/nested Features (e.g. a "high-grade
            # focus" annotation drawn inside a coarser "tumor" annotation) as outside the annotated
            # region. See rasterize_feature_collection's docstring. It also keeps the short-circuit
            # for the no-annotations case (all-False, no per-cell work).
            ann_fc = bf_io.get_annotations(f)
            n_ann = len(ann_fc.get("features", []) or [])
            ann_area = annotations_area_px(ann_fc)
            native_ann_mask = rasterize_feature_collection(ann_fc, gw, gh, img_w, img_h)
            # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): overlap-correct companion to
            # ann_area above -- reuses the SAME native_ann_mask (no re-rasterization), so this is
            # always in sync with dwellInAnnotationPct/enrichmentRatio's mask.
            ann_area_union = m.annotated_area_union_px(native_ann_mask, gw, gh, img_w, img_h)
            # Cross-user (annotations_<slug>.csv) comparisons need every session's mask on the
            # slide's common (tw, th) grid -- resample the already-rasterized native mask (as
            # 0.0/1.0 floats) via the same nearest-neighbour resampler used for dwell grids,
            # rather than re-rasterizing from scratch at (tw, th).
            common_ann_masks[sid] = (
                m.resample_nn(native_ann_mask.astype(float), gw, gh, tw, th) > 0.5
            )

            row = {
                "slide": slide_key,
                "session": _session_label(f, labels),
                # Stable join key for _nav_accuracy_rows -- NOT written to metrics.csv (its
                # fieldnames list below deliberately omits "sessionId"; _write_csv's
                # extrasaction="ignore" drops it silently at write time). Kept in-memory only so
                # the nav-accuracy join below never has to bridge back through the (possibly
                # colliding) human display label -- see _nav_accuracy_rows's docstring.
                "sessionId": sid,
                "durationMs": f.get("durationMs", ""),
                "sampleCount": f.get("sampleCount", ""),
                "coveragePct": m.coverage(grid) * 100.0,
                "entropy": m.entropy(grid),
                "comX": comx,
                "comY": comy,
                "peakDwell": max(grid) if grid else 0.0,
                "nHotspots": m.count_hotspots(grid, gw, gh, HOTSPOT_THRESH_FRAC),
                "pathPoints": "",
                "pathLengthPx": "",
                "nRevisits": "",
                "transitionEntropy": "",
                "avgZoom": "",
                "zoomVariance": "",
                "zoomRange": "",
                "magnificationPercentage": "",
                "scanningRatePxPerMin": "",
                "drillingRatePerMin": "",
                "pathVelocityPxPerSec": "",
                "linearity": "",
                "searchFocusRatio": "",
                # Passthrough fragment-level fields (schema/4+; blank for /1,/2,/3 which lack them).
                "baseMagnification": base_mag if base_mag is not None else "",
                "pathTruncated": f.get("pathTruncated", ""),
                # Phase 2 annotation metrics: nAnnotations/annotatedAreaPx/dwellInAnnotationPct
                # only need the grid + this session's own annotation mask (no path required), so
                # they're always populated (0/0.0 when there are no annotations at all).
                # enrichmentRatio is likewise grid-only, but blank (NaN) whenever its mask has no
                # meaningful in/out split to compare -- see metrics.enrichment_ratio.
                "nAnnotations": n_ann,
                "annotatedAreaPx": ann_area,
                "dwellInAnnotationPct": m.dwell_in_mask_pct(grid, native_ann_mask),
                "enrichmentRatio": m.enrichment_ratio(grid, native_ann_mask),
                # Path-dependent Phase 2 metrics: blank without a path at all (schema /1, /2).
                "annotationReentryCount": "",
                "cursorOverSlidePct": "",
                "mouseViewportCouplingPx": "",
                # Tier 1 additive metrics (docs/superpowers/specs/2026-07-23-...): A2 turn-angle
                # directionality + A4 active fraction are path-only (blank without a path at all,
                # like the block above); A3 mouse kinematics is additionally gated on schema/5
                # mouse data (populated in the `has_mouse_data` branch below, alongside the
                # existing Phase 2 cursor metrics).
                "meanAbsTurnAngleDeg": "",
                "turnAngleEntropy": "",
                "mousePathLengthPx": "",
                "mouseVelocityPxPerSec": "",
                "activeFractionPct": "",
                # Tier 2 (docs/superpowers/specs/2026-07-23-...) additive columns: B1 transparency
                # (idleMs/activeSpanMs), B2 Drew-fidelity zoom (avgZoomLog2W/
                # drillingRateOctavesPerMin), B4 magnification-source flag. All path-only (blank
                # without a path at all, like the Tier 1 block above).
                "idleMs": "",
                "activeSpanMs": "",
                "avgZoomLog2W": "",
                "drillingRateOctavesPerMin": "",
                "magnificationSource": "",
                # Tier 3 C1 (docs/superpowers/specs/2026-07-23-...): I-DT fixation extraction --
                # path-only (blank without a path at all, like the Tier 1/2 blocks above).
                "nFixations": "",
                "meanFixationMs": "",
                "medianFixationMs": "",
                "sdFixationMs": "",
                "fixationsPerMin": "",
                # Tier 3 C2/C4 (docs/superpowers/specs/2026-07-23-...): mouse-dwell coverage/
                # entropy (schema/5 only, populated in the `has_mouse_data` branch below) and
                # segment-level linearity (path-only, populated in the `if path:` block below).
                "mouseCoveragePct": "",
                "mouseEntropy": "",
                "meanSegmentLinearity": "",
                # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended at the END of
                # metrics.csv's fieldnames (additive/append-only column order), NOT interleaved
                # next to annotatedAreaPx/meanSegmentLinearity above despite the conceptual
                # relation -- see the module docstring's Tier 3 C6 note.
                # annotatedAreaUnionPx is grid+annotation-mask-only (no path required), so it's
                # always populated (0.0 with no annotations), like annotatedAreaPx above.
                "annotatedAreaUnionPx": ann_area_union,
                # visitCountJaccard is path-only (blank without a path), populated in the `if
                # path:` block below.
                "visitCountJaccard": "",
                # PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): appended at the
                # END of metrics.csv's fieldnames (additive/append-only column order, same
                # convention as the Tier 3 C6 pair above), not interleaved next to
                # meanSegmentLinearity despite the conceptual relation. Path + annotations only
                # (blank without a path, populated in the `if path:` block below).
                "meanSegmentLinearityROI": "",
            }

            path = f.get("path")
            if path:
                # Use the slide's common (tw, th) so metrics.csv values match the diagonal reuse
                # in scanpath_<slug>.csv (same visited-cell sequence definition either place).
                seq = m.visited_sequence(path, tw, th, img_w, img_h)
                path_seq[sid] = seq
                row["pathPoints"] = len(path)
                row["pathLengthPx"] = m.scanpath_length_px(path)
                row["nRevisits"] = m.n_revisits(seq)
                row["transitionEntropy"] = m.transition_entropy(seq)
                row["avgZoom"] = m.avg_zoom(path, base_mag, img_w)
                row["zoomVariance"] = m.zoom_variance(path, base_mag, img_w)
                row["zoomRange"] = m.zoom_range(path, base_mag, img_w)
                row["magnificationPercentage"] = m.magnification_percentage(path, base_mag, img_w)
                row["scanningRatePxPerMin"] = m.scanning_rate_px_per_min(path, base_mag, img_w)
                row["drillingRatePerMin"] = m.drilling_rate_per_min(path, base_mag, img_w)
                row["pathVelocityPxPerSec"] = m.path_velocity_px_per_sec(path)
                row["linearity"] = m.linearity(path)
                row["searchFocusRatio"] = m.search_focus_ratio(path, base_mag, img_w)
                # Phase 2: reentry uses the session's own NATIVE (gw, gh) mask/grid resolution
                # (not the slide's common (tw, th)) -- a per-session metric, not a cross-session
                # one, so it should stay at the resolution the fragment actually recorded.
                row["annotationReentryCount"] = m.annotation_reentry_count(
                    path, native_ann_mask, gw, gh, img_w, img_h
                )
                # Tier 1 A2 (turn-angle directionality) + A4 (active fraction): path-only, no
                # mouse data or annotation needed -- populated whenever a path exists at all
                # (blank/NaN internally on their own documented degenerate cases, e.g. <3 points).
                row["meanAbsTurnAngleDeg"] = m.mean_abs_turn_angle_deg(path)
                row["turnAngleEntropy"] = m.turn_angle_entropy(path)
                row["activeFractionPct"] = m.active_fraction_pct(path, f.get("durationMs"))
                if m.has_mouse_data(path):
                    row["cursorOverSlidePct"] = m.cursor_over_slide_pct(path)
                    row["mouseViewportCouplingPx"] = m.mouse_viewport_coupling_px(path)
                    # Tier 1 A3: mouse kinematics, schema/5 only (same gate as the two cursor
                    # metrics above).
                    row["mousePathLengthPx"] = m.mouse_path_length_px(path)
                    row["mouseVelocityPxPerSec"] = m.mouse_velocity_px_per_sec(path)
                    # Tier 3 C2 (docs/superpowers/specs/2026-07-23-...): point-based mouse-dwell
                    # grid at this session's own NATIVE (gw, gh) resolution (same resolution
                    # convention coveragePct/entropy use for the recorded grid above). `None` (blank
                    # mouseCoveragePct/mouseEntropy) iff there are zero on-slide points anywhere in
                    # the path -- see mouse_raster_from_path's docstring; `mouse_native[sid]` stays
                    # the all-zero default in that case, which is exactly right for the cross-
                    # session mouse_<slug>.csv comparison below (an all-zero grid, not a missing
                    # one).
                    mouse_grid = m.mouse_raster_from_path(path, img_w, img_h, gw, gh)
                    if mouse_grid is not None:
                        row["mouseCoveragePct"] = m.coverage(mouse_grid) * 100.0
                        row["mouseEntropy"] = m.entropy(mouse_grid)
                        mouse_native[sid] = mouse_grid
                # Tier 2 B1 transparency columns + B2 Drew-fidelity zoom + B4 magnification-source
                # flag: path-only (like the Tier 1 block above), populated regardless of mouse data.
                row["idleMs"] = m.idle_ms(path)
                row["activeSpanMs"] = m.active_span_ms(path)
                row["avgZoomLog2W"] = m.avg_zoom_log2_w(path, base_mag, img_w)
                row["drillingRateOctavesPerMin"] = m.drilling_rate_octaves_per_min(path, base_mag, img_w)
                row["magnificationSource"] = (
                    "true" if (base_mag is not None and float(base_mag) > 0) else "proxy-downsample"
                )
                # Tier 3 C1: I-DT fixation extraction (docs/superpowers/specs/2026-07-23-...) --
                # deterministic dispersion-threshold detector over the viewport centers. Computed
                # once here for metrics.csv's summary columns; the per-fixation
                # fixations_<slug>.csv rows are built later (per slide) by recomputing this same
                # call directly off each path session's own fragment (mirrors the magband-split
                # export's recompute-don't-cache convention), so no extra per-slide cache dict is
                # needed here.
                fx = m.fixations_idt(path)
                row["nFixations"] = m.n_fixations(fx)
                row["meanFixationMs"] = m.mean_fixation_ms(fx)
                row["medianFixationMs"] = m.median_fixation_ms(fx)
                row["sdFixationMs"] = m.sd_fixation_ms(fx)
                row["fixationsPerMin"] = m.fixations_per_min(fx, path)
                # Tier 3 C4 (docs/superpowers/specs/2026-07-23-...): segment-level linearity, split
                # at this session's own top-hotspot cells -- reuses the session's own NATIVE
                # (grid, gw, gh) recorded dwell grid (same resolution hotspots_<slug>.csv's
                # top_hotspots call uses), not the slide's common (tw, th) or a scanpath raster.
                row["meanSegmentLinearity"] = m.mean_segment_linearity(
                    path, grid, gw, gh, img_w, img_h, HOTSPOT_TOP_N
                )
                # PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): ROI-entry
                # segment linearity -- reuses this session's own NATIVE (gw, gh) union annotation
                # mask (native_ann_mask, the SAME mask annotationReentryCount/dwellInAnnotationPct
                # already use above), not the slide's common (tw, th).
                row["meanSegmentLinearityROI"] = m.mean_segment_linearity_roi(
                    path, native_ann_mask, gw, gh, img_w, img_h
                )
                # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): visit-count Jaccard -- the
                # visit-count grid needs the session's own NATIVE (gw, gh) visited-cell sequence,
                # recomputed here (NOT `path_seq[sid]`, which is built at the slide's common
                # (tw, th) resolution for cross-session scanpath_<slug>.csv comparisons) -- same
                # native-resolution convention meanSegmentLinearity/hotspots_<slug>.csv use.
                native_seq = m.visited_sequence(path, gw, gh, img_w, img_h)
                row["visitCountJaccard"] = m.visit_count_jaccard(
                    grid, gw, gh, native_seq, HOTSPOT_TOP_N
                )

            metrics_rows.append(row)

            # ---- decisions.csv: this (slide, session)'s hand-entered diagnosis/confidence, if
            # any -- HAND-GRADE ONLY. ``correctDx`` (from ``--key``) is display-only; ``correct``
            # (from ``--graded``) is the only source of grading and is never derived from
            # comparing ``diagnosis`` to ``correctDx``. Join key for both the ``--graded`` lookup
            # here and the navigation<->accuracy correlation later is the stable ``sessionId``
            # (``sid_stable``), never the human display label.
            dec = bf_io.get_decision(f)
            diagnosis = dec.get("diagnosis", "")
            confidence = dec.get("confidence")
            conf_scaled = (
                "" if not isinstance(confidence, (int, float)) or isinstance(confidence, bool)
                else (float(confidence) - 1.0) / 4.0
            )
            decision_ms = dec.get("decisionMs", "")
            # Tier 3 C5: promptShownMs is a passthrough, mirroring decisionMs's own handling above
            # -- absent (older fragment, recorded before the recorder gained this field) degrades
            # to blank, never a crash. responseLatencyMs = decisionMs - promptShownMs is computed
            # only when BOTH are real numbers (never bool -- same guard style as confidence below).
            prompt_shown_ms = dec.get("promptShownMs", "")
            _decision_ms_numeric = isinstance(decision_ms, (int, float)) and not isinstance(decision_ms, bool)
            _prompt_shown_ms_numeric = isinstance(prompt_shown_ms, (int, float)) and not isinstance(prompt_shown_ms, bool)
            response_latency_ms = (
                decision_ms - prompt_shown_ms
                if _decision_ms_numeric and _prompt_shown_ms_numeric
                else ""
            )
            # Blank iff absent/None or its string form is empty; otherwise the string form -- so a
            # numeric 0 sessionId stably maps to "0" (matches the R toolkit's
            # nzchar(as.character(...)) rule exactly; the prior `f.get("sessionId") or ""` treated
            # a falsy-but-present 0 the same as absent, diverging from R's "0").
            _sid = f.get("sessionId")
            sid_stable = "" if _sid is None or str(_sid) == "" else str(_sid)
            correct_dx = answer_key.get(slide_key, "")
            graded_val = graded.get((slide_key, sid_stable), "")
            decision_rows.append({
                "slide": slide_key,
                "sessionId": sid_stable,
                "session": _session_label(f, labels),
                "diagnosis": diagnosis,
                # Blank unless confidence is a real number (never a bool -- bool is an int
                # subclass in Python, so `isinstance(x, (int, float))` alone would let True/False
                # through as 1/0 -- matches confidenceScaled's existing guard below, extended to
                # this raw column too; the R sibling's `is.numeric(confidence)` already excludes
                # logical values, so this keeps both toolkits' raw `confidence` cell identical for
                # a malformed boolean input).
                "confidence": (
                    confidence
                    if isinstance(confidence, (int, float)) and not isinstance(confidence, bool)
                    else ""
                ),
                "confidenceScaled": conf_scaled,
                "decisionMs": decision_ms,
                # == decisionMs (both relative to the slide's recording start): "time from slide
                # open to submit". Tier 3 C5 (2026-07-23) added the recorder's promptShownMs, which
                # made the previously-anticipated "time from leave-prompt to submit" column real --
                # that's responseLatencyMs below, appended as its own column rather than replacing
                # this one. decisionLatencyMs itself stays permanently == decisionMs.
                "decisionLatencyMs": decision_ms,
                "correctDx": correct_dx,
                # blank unless --graded supplied a (slideKey, sessionId) row -- NEVER auto-derived
                # from diagnosis == correctDx string comparison.
                "correct": graded_val,
                # Tier 3 C5 (appended, additive): passthrough of the recorder's dialog-shown
                # timestamp and the derived once-prompted response latency. Blank unless numeric
                # (same guard as confidence + the R sibling's is.numeric()), so a malformed
                # non-numeric promptShownMs renders blank identically in both toolkits.
                "promptShownMs": prompt_shown_ms if _prompt_shown_ms_numeric else "",
                "responseLatencyMs": response_latency_ms,
            })

        # ------------------------------------------------------------------
        # cross-user compare (pairwise cc/sim/iou + consensus + agreement summary)
        # ------------------------------------------------------------------
        session_ids = [sid for sid, _ in sessions]
        frag_by_sid = dict(sessions)
        # This slide's just-appended metrics_rows entries (one per session, same order as
        # `sessions`) -- reused below for the zoom/scanning summary aggregates.
        slide_metric_rows = metrics_rows[-len(sessions):]
        # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): factored out of the consensus_grid
        # one-liner below so consensus_count_<slug>.csv can reuse the SAME per-session normalized
        # grids (identical np.mean input as before -- byte-identical consensus_grid, no drift).
        norm_grids = [m.normalise_max(resampled[sid]) for sid in session_ids]
        consensus_grid = np.mean(norm_grids, axis=0)
        # Slide-level (not per-session) statistic -- placed on exactly one row below (see module
        # docstring's "coincidenceLevel" convention note).
        coincidence_val = m.coincidence_level([resampled[sid] for sid in session_ids], IOU_THRESH)

        compare_rows = []
        for idx_a, a in enumerate(session_ids):
            for b in session_ids:
                row = {
                    "sessionA": labels.get(a, a),
                    "sessionB": labels.get(b, b),
                    "cc": m.cc(resampled[a], resampled[b]),
                    "sim": m.sim(resampled[a], resampled[b]),
                    "iou": m.iou(resampled[a], resampled[b], IOU_THRESH),
                    "diffFromConsensus": "",
                    "coincidenceLevel": "",
                    "regionCoveragePct": "",
                    # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): a genuine PAIRWISE
                    # quantity (unlike diffFromConsensus/coincidenceLevel), so it is computed on
                    # EVERY row, not diagonal-only -- exactly 0.0 for a==b (self-comparison).
                    "jsDivergence": m.js_divergence(resampled[a], resampled[b]),
                }
                if a == b:
                    row["diffFromConsensus"] = 1.0 - m.cc(resampled[a], consensus_grid)
                    row["regionCoveragePct"] = m.region_coverage_pct(
                        resampled[a], consensus_grid, IOU_THRESH
                    )
                    if idx_a == 0:
                        row["coincidenceLevel"] = coincidence_val
                compare_rows.append(row)
        _write_csv(
            os.path.join(out_dir, f"compare_{slide_slug}.csv"),
            compare_rows,
            ["sessionA", "sessionB", "cc", "sim", "iou", "diffFromConsensus",
             "coincidenceLevel", "regionCoveragePct",
             # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended, existing column order
             # above is unchanged.
             "jsDivergence"],
        )
        fig.heatmap(
            consensus_grid, tw, th, f"Consensus - {slide_key}",
            os.path.join(out_dir, f"consensus_{slide_slug}.png"),
        )

        # ------------------------------------------------------------------
        # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): per-cell reader-count "weak
        # annotation" map -- the spatial structure coincidenceLevel collapses to one scalar.
        # Reuses norm_grids (same per-session normalise_max arrays as consensus_grid above, at the
        # slide's common (tw, th) grid) and HOTSPOT_THRESH_FRAC (the SAME threshold
        # count_hotspots/nHotspots already use -- a coarser per-session-max-relative threshold,
        # distinct from coincidence_level's own IOU_THRESH). Written whenever the slide has >=2
        # sessions (a single session's "reader count" is a degenerate/uninformative 0-or-1 map),
        # regardless of whether any cell actually clears the threshold (possibly a header-only,
        # zero-row file -- blank-not-crash, not a missing file).
        # ------------------------------------------------------------------
        if len(session_ids) >= 2:
            reader_counts = np.sum(
                [g > HOTSPOT_THRESH_FRAC for g in norm_grids], axis=0
            )
            consensus_count_rows = []
            for idx in range(tw * th):
                n_readers = int(reader_counts[idx])
                if n_readers >= 1:
                    row_i, col_i = divmod(idx, tw)
                    consensus_count_rows.append({
                        "cellRow": row_i, "cellCol": col_i, "nReaders": n_readers,
                    })
            _write_csv(
                os.path.join(out_dir, f"consensus_count_{slide_slug}.csv"),
                consensus_count_rows,
                ["cellRow", "cellCol", "nReaders"],
            )

        # ------------------------------------------------------------------
        # Tier 1 A5: top-hotspots export -- surfaces the already-implemented
        # blinded_focus.metrics.top_hotspots at each session's own NATIVE (gw, gh) grid
        # resolution (per-session metric, not cross-session -- same resolution convention as
        # annotationReentryCount above). Written for every session unconditionally ("when any
        # session has a grid, i.e. always" -- every fragment always carries a grid).
        # ------------------------------------------------------------------
        hotspot_rows = []
        for sid in session_ids:
            grid, gw, gh = native_grid[sid]
            f = frag_by_sid[sid]
            img_w = f.get("imageWidth", 1)
            img_h = f.get("imageHeight", 1)
            total = float(sum(grid))
            label = labels.get(sid, sid)
            for rank, (row_i, col_i, value) in enumerate(
                m.top_hotspots(grid, gw, gh, HOTSPOT_TOP_N), start=1
            ):
                hotspot_rows.append({
                    "session": label,
                    "rank": rank,
                    "cellRow": row_i,
                    "cellCol": col_i,
                    "centerImageX": (col_i + 0.5) / gw * img_w,
                    "centerImageY": (row_i + 0.5) / gh * img_h,
                    "dwellMs": value,
                    "dwellFrac": (value / total) if total > 0 else "",
                })
        _write_csv(
            os.path.join(out_dir, f"hotspots_{slide_slug}.csv"),
            hotspot_rows,
            ["session", "rank", "cellRow", "cellCol", "centerImageX", "centerImageY",
             "dwellMs", "dwellFrac"],
        )

        # ------------------------------------------------------------------
        # Phase 2: cross-user annotation agreement -- pairwise IoU of each session's own
        # rasterized annotated region + a coincidence level, mirroring compare_<slug>.csv's
        # tidy-long + diagonal-reuse convention exactly. Gated on at least one session having
        # drawn at least one annotation (mirrors the scanpath_<slug>.csv path-presence gate), so a
        # slide with zero annotations anywhere doesn't emit a trivially-all-zero file.
        # ------------------------------------------------------------------
        annotation_coincidence_val = float("nan")
        if any(r["nAnnotations"] > 0 for r in slide_metric_rows):
            annotation_coincidence_val = m.coincidence_level(
                [common_ann_masks[sid].astype(float) for sid in session_ids], IOU_THRESH
            )
            ann_rows = []
            for idx_a, a in enumerate(session_ids):
                for b in session_ids:
                    ann_row = {
                        "sessionA": labels.get(a, a),
                        "sessionB": labels.get(b, b),
                        "iou": m.iou(
                            common_ann_masks[a].astype(float),
                            common_ann_masks[b].astype(float),
                            IOU_THRESH,
                        ),
                        "coincidenceLevel": "",
                    }
                    if a == b and idx_a == 0:
                        ann_row["coincidenceLevel"] = annotation_coincidence_val
                    ann_rows.append(ann_row)
            _write_csv(
                os.path.join(out_dir, f"annotations_{slide_slug}.csv"),
                ann_rows,
                ["sessionA", "sessionB", "iou", "coincidenceLevel"],
            )

        # ------------------------------------------------------------------
        # Tier 3 C2 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md):
        # cross-reader mouse agreement -- pairwise cc/iou of each session's own point-based
        # mouse-dwell grid (mouse_native, resampled to the slide's common (tw, th) grid) + a
        # coincidence level, mirroring annotations_<slug>.csv's tidy-long + diagonal-reuse
        # convention exactly. Gated on at least one session carrying schema/5 mouse data at all
        # (NOT on whether that session's mouse grid ended up non-empty -- a session whose mouse
        # data has zero on-slide points still "has mouse data" in the schema sense and should still
        # trigger the file, same as the annotations gate is on nAnnotations > 0, not on the mask
        # being non-empty). A session without mouse data contributes its all-zero mouse_native
        # placeholder (never None), so every session_ids entry participates in the pairwise matrix.
        #
        # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): mouseICC, a SLIDE-LEVEL
        # ICC(2,1) of the mouse-dwell grids (reusing the same `icc()` compare_<slug>.csv's
        # meanPairwiseCC/icc already use), placed on the same diagonal-reuse row as
        # coincidenceLevel. Unlike the cc/iou/coincidenceLevel columns above (deliberately computed
        # across EVERY session_ids entry, including mouse-data-less placeholder grids, for CSV
        # matrix completeness), mouseICC is computed only over the sessions that actually carry
        # mouse data -- an all-zero placeholder grid isn't a second "reader" to agree with, and
        # icc() itself would silently mix a real dwell grid with a meaningless constant-zero one
        # otherwise. NaN (-> blank) when fewer than 2 sessions have mouse data, mirroring icc()'s
        # own "<2 grids" guard.
        # ------------------------------------------------------------------
        mouse_mean_cc = float("nan")
        mouse_icc_val = float("nan")
        if any(m.has_mouse_data(f.get("path")) for _, f in sessions):
            mouse_resampled = {
                sid: m.resample_nn(mouse_native[sid], native_grid[sid][1], native_grid[sid][2], tw, th)
                for sid in session_ids
            }
            mouse_coincidence_val = m.coincidence_level(
                [mouse_resampled[sid] for sid in session_ids], IOU_THRESH
            )
            mouse_data_sids = [sid for sid, f in sessions if m.has_mouse_data(f.get("path"))]
            mouse_mean_cc = m.mean_pairwise_cc([mouse_resampled[sid] for sid in mouse_data_sids])
            mouse_icc_val = m.icc([mouse_resampled[sid] for sid in mouse_data_sids])
            mouse_rows = []
            for idx_a, a in enumerate(session_ids):
                for b in session_ids:
                    mouse_row = {
                        "sessionA": labels.get(a, a),
                        "sessionB": labels.get(b, b),
                        "cc": m.cc(mouse_resampled[a], mouse_resampled[b]),
                        "iou": m.iou(mouse_resampled[a], mouse_resampled[b], IOU_THRESH),
                        "coincidenceLevel": "",
                        "mouseICC": "",
                    }
                    if a == b and idx_a == 0:
                        mouse_row["coincidenceLevel"] = mouse_coincidence_val
                        mouse_row["mouseICC"] = mouse_icc_val
                    mouse_rows.append(mouse_row)
            _write_csv(
                os.path.join(out_dir, f"mouse_{slide_slug}.csv"),
                mouse_rows,
                ["sessionA", "sessionB", "cc", "iou", "coincidenceLevel", "mouseICC"],
            )

        mean_cc = m.mean_pairwise_cc([resampled[sid] for sid in session_ids])
        icc_val = m.icc([resampled[sid] for sid in session_ids])
        coverages = [m.coverage(native_grid[sid][0]) * 100.0 for sid in session_ids]
        durations = [float(f.get("durationMs") or 0) for _, f in sessions]

        def _mean_of(col):
            vals = [r[col] for r in slide_metric_rows if r[col] != ""]
            return float(statistics.mean(vals)) if vals else float("nan")

        slide_summaries.append({
            "slide": slide_key,
            "slug": slide_slug,
            "sessions": [labels.get(sid, sid) for sid in session_ids],
            "meanPairwiseCC": mean_cc,
            "icc": icc_val,
            "coverageMin": min(coverages), "coverageMedian": statistics.median(coverages),
            "coverageMax": max(coverages),
            "durationMin": min(durations), "durationMedian": statistics.median(durations),
            "durationMax": max(durations),
            "coincidenceLevel": coincidence_val,
            "meanAvgZoom": _mean_of("avgZoom"),
            "meanScanningRatePxPerMin": _mean_of("scanningRatePxPerMin"),
            "meanDrillingRatePerMin": _mean_of("drillingRatePerMin"),
            "meanMagnificationPercentage": _mean_of("magnificationPercentage"),
            # Phase 2 headline numbers.
            "meanDwellInAnnotationPct": _mean_of("dwellInAnnotationPct"),
            "annotationCoincidenceLevel": annotation_coincidence_val,
            "meanCursorOverSlidePct": _mean_of("cursorOverSlidePct"),
            # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): mouse-dwell
            # cross-reader agreement, for the new summary.md "cursor agreement" line. NaN (->
            # "n/a" via _fmt) when the slide has no mouse data at all, or fewer than 2 sessions
            # carry it.
            "meanPairwiseMouseCC": mouse_mean_cc,
            "mouseICC": mouse_icc_val,
        })

        # ------------------------------------------------------------------
        # reference / ROI comparison
        # ------------------------------------------------------------------
        if reference and reference not in resampled:
            print(
                f"warning: --reference {reference!r} matches no session on slide "
                f"{slide_key!r} (sessions present: {', '.join(session_ids)})",
                file=sys.stderr,
            )

        if reference or roi_rings:
            ref_map, ref_mask = None, None
            if roi_rings:
                img_w = sessions[0][1].get("imageWidth", 1)
                img_h = sessions[0][1].get("imageHeight", 1)
                ref_mask = rasterize_feature_collection(roi_fc, tw, th, img_w, img_h)
                ref_map = ref_mask.astype(float)
            if reference and reference in resampled:
                ref_grid = resampled[reference]
                if ref_map is None:
                    ref_map = m.normalise_max(ref_grid)
                if ref_mask is None:
                    mx = ref_grid.max()
                    ref_mask = (
                        ref_grid > (IOU_THRESH * mx) if mx > 0 else np.zeros_like(ref_grid, dtype=bool)
                    )

            if ref_map is not None and ref_mask is not None:
                def _ref_row(sid):
                    other = resampled[sid]
                    time_on = float(other[ref_mask].sum())
                    time_off = float(other[~ref_mask].sum())
                    denom = max(int(ref_mask.sum()), 1)
                    ref_cov = float(np.count_nonzero(other[ref_mask] > 0)) / denom
                    # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): precisionAtTopK/recall vs
                    # the SAME ref_mask this row already compares against (whichever of
                    # --roi/--reference built it above) -- see
                    # blinded_focus.metrics.precision_recall_at_topk's docstring.
                    precision_at_topk, recall_val = m.precision_recall_at_topk(other, ref_mask)
                    return {
                        "session": labels.get(sid, sid),
                        "nss": m.nss(other, ref_mask),
                        "aucJudd": m.auc_judd(other, ref_mask),
                        "cc": m.cc(other, ref_map),
                        "iou": m.iou(other, ref_map, IOU_THRESH),
                        "refCoveragePct": ref_cov * 100.0,
                        "timeOnRefMs": time_on,
                        "timeOffRefMs": time_off,
                        "precisionAtTopK": precision_at_topk,
                        "recall": recall_val,
                    }

                ref_rows = [_ref_row(sid) for sid in session_ids if sid != reference]
                if reference and reference in resampled:
                    ref_rows.insert(0, _ref_row(reference))

                ref_rows.sort(key=lambda r: r["nss"] if r["nss"] == r["nss"] else -1e9, reverse=True)
                _write_csv(
                    os.path.join(out_dir, f"reference_{slide_slug}.csv"),
                    ref_rows,
                    ["session", "nss", "aucJudd", "cc", "iou", "refCoveragePct",
                     "timeOnRefMs", "timeOffRefMs",
                     # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended, existing column
                     # order above is unchanged.
                     "precisionAtTopK", "recall"],
                )
                reference_summaries.append({"slide": slide_key, "slug": slide_slug, "rows": ref_rows})

        # ------------------------------------------------------------------
        # scanpath (schema/3 sessions only)
        # ------------------------------------------------------------------
        scan_sids = [sid for sid in session_ids if sid in path_seq]
        if scan_sids:
            scan_rows = []
            for a in scan_sids:
                for b in scan_sids:
                    row = {
                        "sessionA": labels.get(a, a),
                        "sessionB": labels.get(b, b),
                        "levenshteinSim": m.levenshtein_sim(path_seq[a], path_seq[b]),
                        "transitionEntropy": "",
                        # Tier 3 C3 (docs/superpowers/specs/2026-07-23-...): DTW distance between
                        # the two sessions' raw viewport-center paths (NOT the grid-cell
                        # path_seq/levenshtein sequence) -- a resolution-independent complement.
                        # Every scan_sids session has a non-empty path by construction (that's the
                        # gate for being in scan_sids at all), so this is never blank here; always
                        # exactly 0.0 on the diagonal (a == b), by construction of the DP itself.
                        "dtwDistance": m.dtw_distance(frag_by_sid[a]["path"], frag_by_sid[b]["path"]),
                    }
                    if a == b:
                        row["transitionEntropy"] = m.transition_entropy(path_seq[a])
                    scan_rows.append(row)
            _write_csv(
                os.path.join(out_dir, f"scanpath_{slide_slug}.csv"),
                scan_rows,
                ["sessionA", "sessionB", "levenshteinSim", "transitionEntropy", "dtwDistance"],
            )

        # ------------------------------------------------------------------
        # Tier 1 A5: top-15 per-session directed cell-transitions (path sessions only) -- surfaces
        # the already-implemented blinded_focus.metrics.transition_matrix (via top_transitions'
        # deterministic top-N ranking) on each session's own visited-cell sequence (path_seq,
        # already computed above at the slide's common (tw, th) grid, same sequence
        # nRevisits/transitionEntropy use).
        # ------------------------------------------------------------------
        if scan_sids:
            transition_rows = []
            for sid in scan_sids:
                label = labels.get(sid, sid)
                for frm, to, cnt in m.top_transitions(path_seq[sid], TRANSITIONS_TOP_N):
                    transition_rows.append({
                        "session": label, "fromCell": frm, "toCell": to, "count": cnt,
                    })
            if transition_rows:
                _write_csv(
                    os.path.join(out_dir, f"transitions_{slide_slug}.csv"),
                    transition_rows,
                    ["session", "fromCell", "toCell", "count"],
                )

        # ------------------------------------------------------------------
        # Tier 3 C1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): per-session
        # I-DT fixations (path sessions only), same tidy long-format / gate-on-nonempty-rows
        # convention as transitions_<slug>.csv above -- the file is only written if at least one
        # fixation was found across every path-carrying session on this slide (a session
        # contributing zero rows, e.g. a very short scanpath, is simply absent from the file rather
        # than emitting an empty per-session block). Recomputes blinded_focus.metrics.fixations_idt
        # directly off each session's own path (same recompute-don't-cache convention the
        # magnification-split section below uses for its own per-session band assignment).
        # ------------------------------------------------------------------
        if scan_sids:
            fixation_rows = []
            for sid in scan_sids:
                label = labels.get(sid, sid)
                fx = m.fixations_idt(frag_by_sid[sid]["path"]) or []
                for idx, fxn in enumerate(fx, start=1):
                    fixation_rows.append({
                        "session": label,
                        "idx": idx,
                        "startMs": fxn["startMs"],
                        "durationMs": fxn["durationMs"],
                        "centerImageX": fxn["centerImageX"],
                        "centerImageY": fxn["centerImageY"],
                        "nPoints": fxn["nPoints"],
                    })
            if fixation_rows:
                _write_csv(
                    os.path.join(out_dir, f"fixations_{slide_slug}.csv"),
                    fixation_rows,
                    ["session", "idx", "startMs", "durationMs", "centerImageX", "centerImageY",
                     "nPoints"],
                )

        # ------------------------------------------------------------------
        # magnification-split (Phase 1; Tier 2 B1 idle-exclusion + B3 canonical-band scheme):
        # per-session dwell time in each zoom band -- band ASSIGNMENT is unaffected by idle
        # exclusion (matches the tercile scheme's pre-existing behavior: quantile cuts, or the
        # canonical MAG_BAND_CUTS, are computed/applied over every step regardless of idle), only
        # the per-band bandTimeMs/bandTimePct SUM excludes idle steps' dt (B1).
        # ------------------------------------------------------------------
        if scan_sids:
            magband_rows = []
            for sid in scan_sids:
                f = frag_by_sid[sid]
                path = f["path"]
                base_mag = f.get("baseMagnification")
                bands, scheme_used = m.magband_labels_for_scheme(
                    path, base_mag, f.get("imageWidth", 1), magbands, magband_scheme,
                )
                if not bands:
                    continue
                dts = m.step_durations_ms(path)
                idle = m.idle_step_mask(path)
                bands_arr = np.asarray(bands)
                n_bands_used = CANONICAL_MAGBAND_COUNT if scheme_used == "canonical" else magbands
                total_dt = float(sum(dt for dt, is_idle in zip(dts, idle) if not is_idle))
                label = labels.get(sid, sid)
                for band in range(n_bands_used):
                    band_dt = float(sum(
                        dt for dt, b, is_idle in zip(dts, bands_arr, idle)
                        if b == band and not is_idle
                    ))
                    magband_rows.append({
                        "session": label,
                        "band": band,
                        "bandTimeMs": band_dt,
                        "bandTimePct": (band_dt / total_dt * 100.0) if total_dt > 0 else 0.0,
                        "bandScheme": scheme_used,
                    })
            if magband_rows:
                _write_csv(
                    os.path.join(out_dir, f"magbands_{slide_slug}.csv"),
                    magband_rows,
                    ["session", "band", "bandTimeMs", "bandTimePct", "bandScheme"],
                )

        # ------------------------------------------------------------------
        # PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): per-magnification-band
        # cross-reader agreement -- "do readers agree more at overview vs cell power" (Chakraborty).
        # For each of the 7 canonical magnification bands (:data:`blinded_focus.metrics.MAG_BAND_LABELS`),
        # build every CANONICAL-scheme session's own dwell-time-restricted raster for that band
        # (reusing raster_from_path's step_mask mechanism -- idle-excluded automatically, same
        # mechanism the magband_rows/figures blocks above already use), resample it to the slide's
        # common (tw, th) grid (same resampler compare_<slug>.csv/mouse_<slug>.csv use), then
        # mean_pairwise_cc/coincidence_level across the sessions that actually dwelled in that band.
        #
        # "Canonical-scheme session" is determined by RE-DERIVING (bands, scheme_used) via
        # magband_labels_for_scheme -- the SAME per-step band assignment the magband_rows loop above
        # already computes (recomputed here, not cached -- mirrors the fixations_<slug>.csv/
        # meanSegmentLinearity recompute-don't-cache convention elsewhere in this module) -- rather
        # than calling canonical_mag_band_labels directly, so that a slide run under
        # ``--magband-scheme tercile`` transitively yields 0 canonical sessions (every session's
        # scheme_used comes back "tercile") and the file is skipped, exactly like a session whose
        # own baseMagnification/dsMilli are individually not computable (per-session tercile
        # fallback) is excluded from this file's population -- the filtered-population approach
        # PT2's mouseICC established for schema-gated sessions, not an all-or-nothing block.
        #
        # File-level gate: written iff >=2 sessions are canonical-scheme -- this single condition
        # subsumes both "--magband-scheme tercile" (0 canonical sessions) and "null
        # baseMagnification" (<2 canonical-capable sessions on this slide), both of which the spec
        # says should skip the file entirely. "Has any dwell in this band" is checked on the
        # POST-resample grid (consistent with what mean_pairwise_cc/coincidence_level actually
        # consume) -- a session whose dwell in this band falls entirely into a grid cell dropped by
        # nearest-neighbour resampling is (correctly) excluded from that band's row. Written
        # (possibly with zero data rows, mirroring consensus_count_<slug>.csv's own "header-only is
        # fine" convention) whenever the file-level gate passes, regardless of whether any
        # individual band clears its own >=2-dwelling-sessions per-row threshold.
        # ------------------------------------------------------------------
        if scan_sids:
            canonical_band_grids = {b: [] for b in range(CANONICAL_MAGBAND_COUNT)}
            n_canonical_sessions = 0
            for sid in scan_sids:
                f = frag_by_sid[sid]
                path = f["path"]
                base_mag = f.get("baseMagnification")
                img_w = f.get("imageWidth", 1)
                img_h = f.get("imageHeight", 1)
                bands, scheme_used = m.magband_labels_for_scheme(
                    path, base_mag, img_w, magbands, magband_scheme,
                )
                if scheme_used != "canonical" or not bands:
                    continue
                n_canonical_sessions += 1
                gw, gh = native_grid[sid][1], native_grid[sid][2]
                bands_arr_pt4 = np.asarray(bands)
                for band in range(CANONICAL_MAGBAND_COUNT):
                    band_step_mask = (bands_arr_pt4 == band)
                    if not band_step_mask.any():
                        continue
                    raster_b = m.raster_from_path(
                        path, img_w, img_h, gw, gh, step_mask=band_step_mask,
                    )
                    if raster_b is None:
                        continue
                    resampled_b = m.resample_nn(raster_b, gw, gh, tw, th)
                    if not np.any(resampled_b > 0):
                        continue
                    canonical_band_grids[band].append(resampled_b)
            if n_canonical_sessions >= 2:
                magband_agreement_rows = []
                for band in range(CANONICAL_MAGBAND_COUNT):
                    grids_b = canonical_band_grids[band]
                    if len(grids_b) < 2:
                        continue
                    magband_agreement_rows.append({
                        "band": m.MAG_BAND_LABELS[band],
                        "nSessions": len(grids_b),
                        "meanPairwiseCC": m.mean_pairwise_cc(grids_b),
                        "coincidenceLevel": m.coincidence_level(grids_b, IOU_THRESH),
                    })
                _write_csv(
                    os.path.join(out_dir, f"magband_agreement_{slide_slug}.csv"),
                    magband_agreement_rows,
                    ["band", "nSessions", "meanPairwiseCC", "coincidenceLevel"],
                )

        # ------------------------------------------------------------------
        # figures
        # ------------------------------------------------------------------
        if make_figures:
            slide_out = os.path.join(out_dir, slide_slug)
            os.makedirs(slide_out, exist_ok=True)
            for sid, f in sessions:
                grid, gw, gh = native_grid[sid]
                label = labels.get(sid, sid)
                sess_slug = bf_io.slug(sid)
                img_w, img_h = f.get("imageWidth", 1), f.get("imageHeight", 1)
                fig.heatmap(
                    grid, gw, gh, f"{slide_key} - {label}",
                    os.path.join(slide_out, f"{sess_slug}_heatmap.png"),
                )
                path = f.get("path")
                if path:
                    fig.scanpath_overlay(
                        grid, gw, gh, path, img_w, img_h,
                        f"{label} scanpath", os.path.join(slide_out, f"{sess_slug}_scanpath.png"),
                    )
                    fig.coverage_over_time(
                        path, gw, gh, img_w, img_h,
                        f"{label} coverage over time",
                        os.path.join(slide_out, f"{sess_slug}_coverage.png"),
                    )

                    # Phase 1: scanpath-rasterized fine heatmap at --res, independent of the
                    # recorded grid -- the trustworthy high-magnification map.
                    res_gw, res_gh = _res_grid_dims(img_w, img_h, res)
                    raster = m.raster_from_path(path, img_w, img_h, res_gw, res_gh)
                    if raster is not None:
                        fig.heatmap(
                            raster, res_gw, res_gh, f"{label} scanpath raster ({res}px)",
                            os.path.join(slide_out, f"{sess_slug}_scanpath_raster.png"),
                        )

                    # Phase 1 (Tier 2 B3: canonical-scheme-aware): magnification-split heatmaps,
                    # one per zoom band. raster_from_path itself excludes idle steps (Tier 2 B1),
                    # so an idle step contributes no heat to any band's figure either.
                    base_mag = f.get("baseMagnification")
                    bands, scheme_used = m.magband_labels_for_scheme(
                        path, base_mag, img_w, magbands, magband_scheme,
                    )
                    if bands:
                        bands_arr = np.asarray(bands)
                        n_bands_used = CANONICAL_MAGBAND_COUNT if scheme_used == "canonical" else magbands
                        for band in range(n_bands_used):
                            step_mask = (bands_arr == band)
                            if not step_mask.any():
                                continue
                            raster_b = m.raster_from_path(
                                path, img_w, img_h, res_gw, res_gh, step_mask=step_mask,
                            )
                            if raster_b is not None:
                                fig.heatmap(
                                    raster_b, res_gw, res_gh, f"{label} - zoom band {band}",
                                    os.path.join(slide_out, f"{sess_slug}_magband{band}.png"),
                                )

                    # Tier 3 C2 (docs/superpowers/specs/2026-07-23-...): mouse-dwell map figure,
                    # schema/5 only -- reuses the same heatmap plotting helper + --res resolution
                    # as the scanpath-raster figure above. Not part of the numeric-parity contract
                    # (a PNG, existence/valid-magic only).
                    if m.has_mouse_data(path):
                        mouse_raster_fig = m.mouse_raster_from_path(path, img_w, img_h, res_gw, res_gh)
                        if mouse_raster_fig is not None:
                            fig.heatmap(
                                mouse_raster_fig, res_gw, res_gh, f"{label} mouse dwell",
                                os.path.join(slide_out, f"{sess_slug}_mousemap.png"),
                            )

            # Tier 1 A6: multi-reader scanpath overlay -- one PNG per slide, every path-carrying
            # session's viewport-center path on a shared axis. Gated on scan_sids (same
            # path-presence gate as scanpath_<slug>.csv/magbands_<slug>.csv) so a slide with no
            # paths at all doesn't emit a trivially-empty overlay.
            if scan_sids:
                overlay_sessions = [(labels.get(sid, sid), frag_by_sid[sid]["path"]) for sid in scan_sids]
                fig.scanpath_multi_overlay(
                    overlay_sessions, f"{slide_key} - all scanpaths",
                    os.path.join(out_dir, f"overlay_{slide_slug}_scanpaths.png"),
                )

    _write_csv(
        os.path.join(out_dir, "metrics.csv"),
        metrics_rows,
        ["slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
         "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
         "nRevisits", "transitionEntropy",
         "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
         "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
         "linearity", "searchFocusRatio", "baseMagnification", "pathTruncated",
         "nAnnotations", "annotatedAreaPx", "dwellInAnnotationPct", "annotationReentryCount",
         "enrichmentRatio", "cursorOverSlidePct", "mouseViewportCouplingPx",
         # Tier 1 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
         # column order above is unchanged.
         "meanAbsTurnAngleDeg", "turnAngleEntropy", "mousePathLengthPx", "mouseVelocityPxPerSec",
         "activeFractionPct",
         # Tier 2 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
         # column order above (incl. Tier 1) is unchanged. B1: idleMs/activeSpanMs. B2:
         # avgZoomLog2W/drillingRateOctavesPerMin. B4: magnificationSource.
         "idleMs", "activeSpanMs", "avgZoomLog2W", "drillingRateOctavesPerMin",
         "magnificationSource",
         # Tier 3 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
         # column order above (incl. Tier 1/2) is unchanged. C1: I-DT fixation extraction.
         "nFixations", "meanFixationMs", "medianFixationMs", "sdFixationMs", "fixationsPerMin",
         # Tier 3 C2/C4 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
         # column order above (incl. Tier 1/2/C1) is unchanged. C2: mouse-dwell coverage/entropy.
         # C4: segment-level linearity.
         "mouseCoveragePct", "mouseEntropy", "meanSegmentLinearity",
         # Tier 3 C6 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
         # column order above (incl. Tier 1/2/C1/C2/C4) is unchanged.
         "annotatedAreaUnionPx", "visitCountJaccard",
         # PT3 additive column (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3):
         # appended at the very END, existing column order above (incl. Tier 1/2/3/C6) is
         # unchanged -- see the module docstring's PT3 note for why this isn't interleaved next to
         # meanSegmentLinearity despite the conceptual relation.
         "meanSegmentLinearityROI"],
    )

    if any(r["diagnosis"] != "" for r in decision_rows):
        _write_csv(
            os.path.join(out_dir, "decisions.csv"), decision_rows,
            ["slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
             "decisionMs", "decisionLatencyMs", "correctDx", "correct",
             # Tier 3 C5 (2026-07-23): appended, existing column order above is unchanged.
             "promptShownMs", "responseLatencyMs"],
        )
    else:
        print(
            "warning: no decisions found in any fragment; decisions.csv not written",
            file=sys.stderr,
        )

    nav_rows, had_graded = _nav_accuracy_rows(metrics_rows, decision_rows)
    if had_graded:
        _write_csv(
            os.path.join(out_dir, "nav_accuracy.csv"), nav_rows,
            ["metric", "n", "pointBiserialR", "meanCorrect", "meanIncorrect",
             "medianCorrect", "medianIncorrect", "meanDiff"],
        )

    _write_summary(
        out_dir, groups, slide_summaries, reference_summaries, decision_rows, nav_rows, had_graded,
        metrics_rows,
    )
    return metrics_rows


def _write_summary(
    out_dir, groups, slide_summaries, reference_summaries,
    decision_rows=None, nav_rows=None, had_graded=False, metrics_rows=None,
):
    lines = ["# Blinded-focus analysis summary", "", f"- Slides analyzed: {len(groups)}", ""]
    # Tier 2 B4: magnification-source caveat -- how many path-carrying sessions used a proxy
    # (downsample-relative or w-proxy) magnification rather than the true objective power, so a
    # reader knows pooled avgZoom-family numbers may mix the two. Only emitted when at least one
    # path-carrying session exists at all (nothing to caveat otherwise).
    magsrc = [r["magnificationSource"] for r in (metrics_rows or []) if r.get("magnificationSource")]
    if magsrc:
        n_true = sum(1 for v in magsrc if v == "true")
        n_proxy = sum(1 for v in magsrc if v == "proxy-downsample")
        lines.append(
            f"> Magnification source: {n_true}/{len(magsrc)} path-carrying sessions use true "
            f"objective magnification (baseMagnification present); {n_proxy}/{len(magsrc)} use a "
            f"proxy (downsample- or window-width-relative) value -- see `magnificationSource` in "
            f"metrics.csv. Proxy-magnification rows are not directly comparable to true-"
            f"magnification rows for avgZoom/zoomVariance/zoomRange/avgZoomLog2W."
        )
        lines.append("")
    lines.append("## Per-slide agreement")
    lines.append("")
    for s in slide_summaries:
        lines.append(f"### {s['slide']}")
        lines.append(f"- sessions ({len(s['sessions'])}): {', '.join(map(str, s['sessions']))}")
        lines.append(f"- mean pairwise CC: {_fmt(s['meanPairwiseCC'])}")
        lines.append(f"- ICC(2,1): {_fmt(s['icc'])}")
        lines.append(
            f"- coverage % (min/median/max): {_fmt(s['coverageMin'], 1)} / "
            f"{_fmt(s['coverageMedian'], 1)} / {_fmt(s['coverageMax'], 1)}"
        )
        lines.append(
            f"- durationMs (min/median/max): {_fmt(s['durationMin'], 0)} / "
            f"{_fmt(s['durationMedian'], 0)} / {_fmt(s['durationMax'], 0)}"
        )
        lines.append(f"- coincidence level (>=2 readers, thresh={IOU_THRESH}): {_fmt(s['coincidenceLevel'], 3)}")
        lines.append(
            f"- mean avg zoom / scanning rate (px/min) / drilling rate (per min) / "
            f"magnification %: {_fmt(s['meanAvgZoom'], 2)} / "
            f"{_fmt(s['meanScanningRatePxPerMin'], 1)} / "
            f"{_fmt(s['meanDrillingRatePerMin'], 2)} / "
            f"{_fmt(s['meanMagnificationPercentage'], 3)}"
        )
        lines.append(
            f"- annotation coverage: mean dwell-in-annotation % = "
            f"{_fmt(s['meanDwellInAnnotationPct'], 1)}, cross-user annotation coincidence "
            f"(>=2 readers, thresh={IOU_THRESH}) = {_fmt(s['annotationCoincidenceLevel'], 3)}"
        )
        lines.append(
            f"- cursor coupling: mean % of path time cursor was over the slide = "
            f"{_fmt(s['meanCursorOverSlidePct'], 1)}"
        )
        # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2).
        lines.append(
            f"- cursor agreement: mean pairwise mouse CC = {_fmt(s['meanPairwiseMouseCC'])}, "
            f"mouse ICC(2,1) = {_fmt(s['mouseICC'])}"
        )
        lines.append("")
    if reference_summaries:
        lines.append("## Reference ranking (higher NSS/CC = closer to the reference/ROI)")
        lines.append("")
        for r in reference_summaries:
            lines.append(f"### {r['slide']}")
            for row in r["rows"]:
                lines.append(
                    f"- {row['session']}: NSS={_fmt(row['nss'])}, "
                    f"AUC-Judd={_fmt(row['aucJudd'])}, CC={_fmt(row['cc'])}, "
                    f"IoU={_fmt(row['iou'])}"
                )
            lines.append("")

    # ------------------------------------------------------------------
    # Navigation <-> diagnostic accuracy (Phase 3): only when hand-graded decisions exist at all
    # (--graded). Never rendered otherwise -- no section, no nav_accuracy.csv, nothing implying a
    # correlation was computed from ungraded data.
    # ------------------------------------------------------------------
    if had_graded:
        lines.append("## Navigation ↔ diagnostic accuracy")
        lines.append("")
        lines.append(
            "> Pilot-scale caveats: viewport-only navigation has a null-result precedent; at "
            "n=5–20 only r, its n, and group means/medians are defensible — no p-values/CIs. "
            "Coincidence/accuracy numbers are cohort-composition-dependent."
        )
        lines.append("")
        graded_dec = [r for r in decision_rows if r["correct"] in (0, 1)]
        n_all = len(graded_dec)
        if n_all:
            acc = sum(r["correct"] for r in graded_dec) / n_all
            lines.append(
                f"- overall accuracy: {sum(r['correct'] for r in graded_dec)}/{n_all} = "
                f"{_fmt(acc * 100, 1)}%" + (" (n<5 → descriptive only)" if n_all < 5 else "")
            )
        for nr in nav_rows or []:
            lines.append(
                f"- {nr['metric']}: r={_fmt(nr['pointBiserialR'])} (n={nr['n']}), "
                f"meanCorrect={_fmt(nr['meanCorrect'])}, meanIncorrect={_fmt(nr['meanIncorrect'])}, "
                f"meanDiff={_fmt(nr['meanDiff'])}"
            )
        calibration_gap, brier_score, conf_acc_r, n_conf = _calibration_stats(decision_rows or [])
        lines.append(
            f"- calibration (n={n_conf}): calibrationGap={_fmt(calibration_gap)}, "
            f"brierScore={_fmt(brier_score)}, confidenceAccuracyR={_fmt(conf_acc_r)}"
        )
        lines.append("")

    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="python -m blinded_focus.analyze",
        description="Analyze blinded-focus fragments (schema atlas-focus-contribution/1,2,3,4,5): "
                    "per-session metrics (incl. zoom/scanning/drilling navigation metrics, "
                    "annotation coverage, and cursor coupling), cross-user agreement (incl. "
                    "annotation-region IoU/coincidence), reference/ROI comparison, scanpath "
                    "sequence metrics, scanpath-rasterized fine heatmaps, and magnification-band "
                    "split.",
    )
    ap.add_argument("inputs", nargs="+", help="fragment JSON files, directories, and/or .zip archives")
    ap.add_argument("--out", required=True, help="output directory")
    ap.add_argument("--reference", default=None, help="sessionId to use as the reference attended-map")
    ap.add_argument(
        "--roi", default=None,
        help="QuPath-exported GeoJSON polygon (image px) rasterized as the reference region",
    )
    ap.add_argument("--labels", default=None, help="sessionId,label CSV for human-readable output")
    ap.add_argument(
        "--key", default=None,
        help="slideKey,correctDx CSV -- DISPLAY-ONLY reference answer shown beside the reader's "
             "diagnosis in decisions.csv; never used to auto-derive `correct` (use --graded)",
    )
    ap.add_argument(
        "--graded", default=None,
        help="slideKey,sessionId,correct CSV -- the ONLY source of decisions.csv's `correct` "
             "column (hand-graded); also enables the navigation<->accuracy correlation "
             "(nav_accuracy.csv + summary.md section)",
    )
    ap.add_argument(
        "--figures", action="store_true",
        help="also write per-(slide,session) heatmap/scanpath/coverage-over-time PNGs",
    )
    ap.add_argument(
        "--res", type=int, default=DEFAULT_RES,
        help=f"longest-side resolution for the scanpath-rasterized fine/magband heatmaps "
             f"(default {DEFAULT_RES})",
    )
    ap.add_argument(
        "--magbands", type=int, default=DEFAULT_MAGBANDS,
        help=f"number of within-path zoom bands for the tercile-fallback magnification-split "
             f"analysis (default {DEFAULT_MAGBANDS}) -- canonical-scheme sessions always use "
             f"{CANONICAL_MAGBAND_COUNT} fixed bands regardless of this value",
    )
    ap.add_argument(
        "--magband-scheme", choices=["canonical", "tercile"], default=DEFAULT_MAGBAND_SCHEME,
        help="magnification-band scheme for magbands_<slug>.csv (Tier 2 B3): 'canonical' "
             f"(default) uses true-objective-magnification bands "
             f"({CANONICAL_MAGBAND_COUNT} fixed bands) for sessions whose baseMagnification/"
             "dsMilli are computable, auto-falling back to the tercile scheme per-session "
             "otherwise; 'tercile' forces the within-path quantile scheme for every session",
    )
    args = ap.parse_args(argv)
    analyze(
        args.inputs, args.out, reference=args.reference, roi=args.roi,
        labels_csv=args.labels, make_figures=args.figures,
        res=args.res, magbands=args.magbands,
        key_csv=args.key, graded_csv=args.graded,
        magband_scheme=args.magband_scheme,
    )


if __name__ == "__main__":
    main()
