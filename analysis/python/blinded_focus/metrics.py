"""Metric formulas for blinded-focus fragment grids and scanpaths.

Grid convention throughout: a row-major flat array of length ``gw*gh`` (matching the fragment
JSON's ``grid`` field). All *cross-fragment* metrics (``cc``, ``sim``, ``kld``, ``nss``,
``auc_judd``, ``iou``) expect two equal-length, equal-shape arrays — call :func:`resample_nn`
first to bring differing-resolution grids to a common ``(tw, th)`` before comparing them (see
``analyze.py``, which does this once per slide).

Formulas follow the standard saliency/eye-tracking evaluation literature (Bylinskii et al.,
"What do different evaluation metrics tell us about saliency models?", IEEE TPAMI 2019, for
CC/SIM/KLD/NSS/AUC-Judd definitions) and are pinned exactly as specified in the project's design
doc so that a parallel R toolkit can reproduce the same numbers.

Phase 1 additions (navigation-research upgrade, docs/superpowers/specs/2026-07-22-...): a
scanpath-rasterized fine dwell grid (``raster_from_path``, independent of the recorded ``grid``
resolution) plus a zoom/navigation metric family motivated by the literature review's strongest
diagnostic-accuracy correlates -- avg/variance/range zoom, "magnification percentage" (Ghezloo),
scanning vs drilling rate (Drew), path velocity/linearity/search-focus (Roa-Peña), and
cross-session coincidence/region-coverage (Xu/Nan, Roa-Peña). All new formulas are documented
per-function with the exact edge-case behavior (blank-vs-0.0, ddof, quantile method) an R port
must match -- see each docstring below.

Phase 2 additions (schema/4's ``annotations`` GeoJSON FeatureCollection + schema/5's 8-element
path with cursor position): annotation metrics (``dwell_in_mask_pct`` -- Ghezloo's ROI time
percentage generalized to a reader's own annotated region; ``enrichment_ratio`` -- Nan 2025's
dwell-weighted enrichment; ``annotation_reentry_count`` -- Brunyé 2017's re-entry rate) that all
take an already-rasterized boolean cell mask (see ``analyze.py``'s GeoJSON-to-grid rasterizer,
reused unchanged from the existing ``--roi`` machinery) rather than GeoJSON directly, plus cursor
metrics (``cursor_over_slide_pct``, ``mouse_viewport_coupling_px``) computed straight off the
path's ``mouseX``/``mouseY`` elements. This same pass also fixes two pre-existing correctness bugs
found by literature review (``coincidence_level``'s denominator; ``magnification_percentage``'s
tie-counting) -- see each function's docstring and
``docs/superpowers/navtrack-lit-review-improvements.md`` §0 for the exact before/after and the
literature citations motivating each fix.

Tier 2 additions (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md, B1-B4 --
**some of these CHANGE existing numbers, intentionally and documented**): B1 idle/away-time
exclusion (``IDLE_GAP_MS`` = 60s, Ghezloo's >60s-frozen-viewport rule) -- ``idle_step_mask``/
``idle_ms``/``active_span_ms`` -- CHANGES ``scanning_rate_px_per_min``, ``drilling_rate_per_min``,
``path_velocity_px_per_sec``, ``search_focus_ratio``, and ``raster_from_path``'s per-step weight
for any path with a >60s gap (identical to before for a path with none, since the exclusion set is
then empty); B2 Drew-fidelity zoom -- ``avg_zoom_log2_w``/``drilling_rate_octaves_per_min``, ADDED
alongside the untouched ``avg_zoom``/``drilling_rate_per_min``; B3 canonical magnification bands --
``true_magnification``/``canonical_mag_band_labels``/``magband_labels_for_scheme`` -- CHANGES
``magbands_<slug>.csv``'s band scheme (tercile -> canonical, 3 -> 7 bands) for any session with a
known ``baseMagnification`` *and* per-point ``dsMilli`` (auto-falls back to the existing tercile
``zoom_band_labels`` scheme otherwise, unchanged); B4 magnification-source flag (additive,
``analyze.py``'s ``magnificationSource`` column) -- disambiguates true-objective-power sessions
from proxy/downsample-relative ones so pooled ``avgZoom``-family statistics are never silently
mixed across the two.

Tier 3 C1 (same spec doc, additive): ``fixations_idt`` -- a deterministic I-DT (dispersion-
threshold, Salvucci & Goldberg 2000) fixation detector over the scanpath's viewport centers, plus
``n_fixations``/``mean_fixation_ms``/``median_fixation_ms``/``sd_fixation_ms``/
``fixations_per_min`` summary statistics wired into ``metrics.csv``, and a per-fixation
``fixations_<slug>.csv`` export in ``analyze.py``. Deliberately NOT a clustering library (e.g.
DBSCAN), whose cluster assignment is not guaranteed identical across languages/library versions --
see :func:`fixations_idt`'s docstring for the full, index-based, parity-pinned algorithm.
Directional metrics (turn-angle, transitions, etc.) stay tick-based and are untouched; fixations
are an added lens, not a rebase. **Final-review fix (2026-07):** an idle-flagged step (Tier 2 B1's
``idle_step_mask``, ``dt > IDLE_GAP_MS``) is now a HARD fixation-window boundary a window may
never bridge, rather than being silently bridged into one giant cross-gap fixation -- CHANGES
``nFixations``/``meanFixationMs``/``medianFixationMs``/``sdFixationMs``/``fixationsPerMin`` for any
session with a >60s idle gap in its path (identical to before for a session with none, the common
case).

Tier 3 C2/C3/C4 (same spec doc, additive): ``mouse_raster_from_path`` -- a point-based dwell-ms
grid over schema/5 cursor positions, analogous to ``raster_from_path`` but depositing a step's dt
into the single cell containing the cursor rather than spreading it across a viewport rectangle
(``mouseCoveragePct``/``mouseEntropy`` in ``metrics.csv``, plus a ``mouse_<slug>.csv`` cross-reader
cc/iou/coincidence table in ``analyze.py``); ``dtw_distance`` -- Dynamic Time Warping (standard DP,
NOT Frechet distance) between two sessions' z-normalized viewport-center sequences, added to
``scanpath_<slug>.csv`` alongside the existing ``levenshteinSim``; ``mean_segment_linearity`` --
the mean of ``linearity`` over sub-paths split at the session's own top-hotspot cells (uniform
hotspot-based segmentation; the ROI-entry variant is intentionally not implemented), added to
``metrics.csv``. All additive; no existing metric's formula or value changes.

**C4 dedup fix (2026-07-23, docs/superpowers/sdd/t4-report.md "C4 dedup fix" section):**
``mean_segment_linearity``'s segmentation now collapses a maximal run of consecutive samples
landing in the SAME top-hotspot cell down to a single boundary (its first index) -- a "boundary"
marks a distinct hotspot VISIT, not every raw sample. Before this fix, every sample in a dwell run
became its own boundary, producing trivial 2-point within-dwell segments (``linearity == 1.0``
unconditionally) that skewed the mean toward 1.0 and defeated the metric's purpose (measuring
transit segments between attended regions, not within-dwell noise). This CHANGES
``meanSegmentLinearity``'s value for any session whose path dwells for >=2 consecutive samples in
one hotspot cell (the normal shape of real viewing) -- see :func:`mean_segment_linearity`'s
docstring for the exact rule.

Tier 3 C6 (same spec doc, additive -- ``docs/superpowers/sdd/t6-report.md``): ``js_divergence`` --
Jensen-Shannon divergence (base-2, symmetric, bounded [0,1]) between two grids, via a zero-safe KL
convention (:func:`_kl_terms`) distinct from the pre-existing :func:`kld`'s additive-EPS smoothing
-- added to ``compare_<slug>.csv`` alongside ``kld``. ``top_k_frac_mask``/
``precision_recall_at_topk`` -- the reader's top-:data:`PRECISION_K_FRAC` highest-dwell cells vs a
reference ROI mask, added to ``reference_<slug>.csv`` as ``precisionAtTopK``/``recall``.
``visit_count_grid``/``visit_count_jaccard`` -- a +1-per-cell-ENTRY (not per tick) visit-count
grid, compared via Jaccard against the dwell-time top hotspots (``visitCountJaccard`` in
``metrics.csv``, path-only) -- disagreement between "lingered longest" and "entered most often" is
a navigation-style signal. ``annotated_area_union_px`` -- area of the UNIONED rasterized
annotation mask (``annotatedAreaUnionPx`` in ``metrics.csv``), the overlap-correct companion to
the existing sum-based ``annotatedAreaPx`` (:func:`blinded_focus.analyze.annotations_area_px`,
which double-counts overlapping Features). Per-slide ``consensus_count_<slug>.csv`` (per-cell
reader count above :data:`HOTSPOT_THRESH_FRAC`) is built directly in ``analyze.py`` from
:func:`normalise_max`, reusing the same threshold :func:`count_hotspots` already uses -- no new
metrics.py function needed for it. All 5 are purely additive; no existing metric's formula or
value changes.
"""
import math
from collections import Counter

import numpy as np
from scipy import ndimage
from scipy.stats import rankdata

#: Small constant added to denominators/logs to avoid division-by-zero / log(0).
EPS = 1e-12

#: Tier 2 B1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): a scanpath step
#: whose Δt exceeds this threshold (ms) is "idle" -- the user stepped away from the slide/mouse for
#: over a minute (Ghezloo's >60s-frozen-viewport idle-time-exclusion rule) -- and is excluded from
#: every rate/weight computation listed in :func:`idle_step_mask`'s docstring. An R port must use
#: the identical literal `60000`.
IDLE_GAP_MS = 60_000

#: Tier 3 C6 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): the reader's
#: "top-K" attended cells for ``precisionAtTopK``/``recall`` are the cells whose dwell value falls
#: in the top 10% (by value, tie-inclusive -- see :func:`top_k_frac_mask`). An R port must use the
#: identical literal `0.10`.
PRECISION_K_FRAC = 0.10


# ---------------------------------------------------------------------------
# Grid helpers
# ---------------------------------------------------------------------------

def normalise_max(grid):
    """``grid / max(grid)``. An all-zero (or empty) grid stays all-zero."""
    g = np.asarray(grid, dtype=float)
    m = g.max() if g.size else 0.0
    return g / m if m > 0 else np.zeros_like(g)


def normalise_sum(grid):
    """``grid / sum(grid)``. An all-zero (or empty) grid stays all-zero."""
    g = np.asarray(grid, dtype=float)
    s = g.sum()
    return g / s if s > 0 else np.zeros_like(g)


def resample_nn(grid, gw, gh, tw, th):
    """Nearest-neighbour resample a row-major ``(gh, gw)`` grid to ``(th, tw)``.

    Returns a flat ``(tw*th,)`` array. Identical algorithm to ``tools/aggregate-focus.py``'s
    ``nearest_resample`` (independently re-implemented here in numpy; no import between them).

    **Zero-size-grid guard (polish final-review Finding 2, defense-in-depth):** the root cause --
    a schema-valid fragment recording ``gridWidth``/``gridHeight`` of ``0`` -- is now rejected at
    load by :func:`blinded_focus.io._is_valid_fragment`, so ``analyze()`` never calls this function
    with a degenerate ``(gw, gh)`` or ``(tw, th)``. This guard exists purely for a DIRECT caller of
    ``resample_nn`` that bypasses ``load_fragments`` (e.g. a script or test constructing a grid by
    hand): without it, ``g.reshape(gh, gw)`` on a ``gw<=0``/``gh<=0`` shape raises immediately, or
    (if ``gw``/``gh`` are valid but ``tw<=0``/``th<=0``) the ``np.arange(th)``/``np.arange(tw)``
    index arrays collapse to empty and the ``g[np.ix_(ys, xs)]`` fancy-index either raises or
    silently returns a wrongly-shaped empty array -- neither of which is the well-defined "nothing
    to resample" result callers expect. Returns an all-zero flat array of the (clamped-non-negative)
    target size instead."""
    gw, gh, tw, th = int(gw), int(gh), int(tw), int(th)
    if gw <= 0 or gh <= 0 or tw <= 0 or th <= 0:
        return np.zeros(max(tw, 0) * max(th, 0), dtype=float)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
    if gw == tw and gh == th:
        return g.flatten()
    ys = np.minimum(gh - 1, (np.arange(th) * gh) // th).astype(int)
    xs = np.minimum(gw - 1, (np.arange(tw) * gw) // tw).astype(int)
    return g[np.ix_(ys, xs)].flatten()


def coverage(grid):
    """``count(g>0) / len(g)``."""
    g = np.asarray(grid, dtype=float)
    return float(np.count_nonzero(g > 0)) / g.size if g.size else 0.0


def entropy(grid):
    """``-sum(p*log2(p+eps))``, ``p = g/sum(g)``. 0.0 for an all-zero grid."""
    g = np.asarray(grid, dtype=float)
    s = g.sum()
    p = g / s if s > 0 else np.zeros_like(g)
    return float(-np.sum(p * np.log2(p + EPS)))


def center_of_mass(grid, gw, gh):
    """Intensity-weighted centroid; each coord normalised by gw/gh -> ``(x, y)`` in ``[0, 1]``.

    Returns ``(0.5, 0.5)`` (grid center) for an all-zero grid.
    """
    gw, gh = int(gw), int(gh)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
    total = g.sum()
    if total <= 0:
        return 0.5, 0.5
    ys, xs = np.indices((gh, gw))
    cx = float((xs * g).sum() / total)
    cy = float((ys * g).sum() / total)
    return cx / gw, cy / gh


def top_hotspots(grid, gw, gh, n=5):
    """Top-``n`` ``(row, col, value)`` cells by dwell value, descending.

    Tie-break is **deterministic**: value descending, then flat row-major index ascending
    (``sorted(..., key=lambda idx: (-flat[idx], idx))``) -- ``np.argsort`` alone is not
    guaranteed stable (default ``kind="quicksort"``), so two cells sharing the same dwell value
    (common for sparse/all-zero grids) could otherwise come back in a different order on every
    call, and in a different order than the R port's tie-break. This matters once the function is
    wired into a written CSV (``hotspots_<slug>.csv``, Tier 1 A5) where the ordering itself is
    part of the file contents an R port must reproduce to 1e-6 (well, exactly, since indices are
    integers) parity.
    """
    gw, gh = int(gw), int(gh)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
    flat = g.flatten()
    order = sorted(range(flat.size), key=lambda idx: (-flat[idx], idx))[:n]
    out = []
    for idx in order:
        row, col = divmod(int(idx), gw)
        out.append((row, col, float(g[row, col])))
    return out


def count_hotspots(grid, gw, gh, thresh_frac=0.5):
    """Number of 4-connected regions with value ``> thresh_frac * max(grid)``.

    0 for an all-zero or zero-size (empty) grid. Used as ``nHotspots`` in ``metrics.csv`` — a
    simple, reproducible "distinct attended regions" count (not part of the pinned Bylinskii
    metric set, but a natural complement to ``peakDwell``).
    """
    gw, gh = int(gw), int(gh)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
    if g.size == 0:
        return 0
    m = g.max()
    if m <= 0:
        return 0
    mask = g > (thresh_frac * m)
    _, n = ndimage.label(mask)
    return int(n)


# ---------------------------------------------------------------------------
# Spatial similarity (equal-shape arrays; resample_nn first for cross-fragment use)
# ---------------------------------------------------------------------------

def cc(a, b):
    """Pearson correlation coefficient of the two flattened grids.

    Returns 0.0 if either grid is constant (correlation undefined; matplotlib/numpy would emit a
    NaN + RuntimeWarning otherwise).

    **Incidental parity fix (surfaced by the final-review Finding 1/2 zero-size-grid guards,
    previously unreachable behind their crash):** also returns 0.0 for a zero-length grid (``a.size
    < 2`` / ``b.size < 2``), checked BEFORE ``.std()`` is ever called -- ``numpy``'s ``.std()`` on
    a size-0 array is ``nan`` (not exactly ``0.0``), so the ``a.std() == 0`` guard alone silently
    missed this one degenerate case (``nan == 0`` is ``False``), falling through to
    ``np.corrcoef`` on two empty arrays and returning ``nan`` -- out of contract with this
    docstring's own "0.0 if ... correlation undefined" promise, and a cross-language parity gap
    (the R port's ``length(a) < 2`` guard already covered it). A size-1 grid was already handled
    correctly (``.std()`` of a single point is exactly ``0.0``, no warning) -- only size-0 was
    affected."""
    a = np.asarray(a, dtype=float).flatten()
    b = np.asarray(b, dtype=float).flatten()
    if a.size < 2 or b.size < 2 or a.std() == 0 or b.std() == 0:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def sim(a, b):
    """Histogram intersection: ``sum(min(a/sum(a), b/sum(b)))``."""
    pa = normalise_sum(a)
    pb = normalise_sum(b)
    return float(np.sum(np.minimum(pa, pb)))


def kld(ref, pred):
    """``sum(P*log((P+eps)/(Q+eps)))``, ``P=ref/sum(ref)``, ``Q=pred/sum(pred)`` (KL divergence,
    ``ref`` as the "true" distribution)."""
    p = normalise_sum(ref)
    q = normalise_sum(pred)
    return float(np.sum(p * np.log((p + EPS) / (q + EPS))))


def _kl_terms(p, q):
    """Elementwise zero-safe KL contribution ``p_i * log2(p_i / q_i)``, with the standard
    information-theory convention ``0 * log(0/x) = 0`` -- a cell where ``p_i`` is exactly 0
    contributes 0 regardless of ``q_i`` (never evaluates ``log2(0/q_i)``). This is a DIFFERENT
    convention from :func:`kld`'s additive-EPS smoothing (which would make even a self-comparison
    a tiny nonzero number) -- :func:`js_divergence` needs the zero-safe form specifically so a
    cell where ONE grid is exactly 0 doesn't force a nonzero floor onto the divergence, and so the
    diagonal self-pair is exactly 0.0. Base-2 (``log2``), pinned for both languages.

    Contract (relied on by :func:`js_divergence`, not re-checked here): ``q_i`` is never exactly 0
    at a cell where ``p_i`` is nonzero -- true when ``q = (p + other) / 2``, since then
    ``q_i >= p_i / 2 > 0`` whenever ``p_i > 0``. Called with any other ``q`` risks a divide-by-zero
    at a cell this convention does not protect."""
    p = np.asarray(p, dtype=float)
    q = np.asarray(q, dtype=float)
    out = np.zeros_like(p)
    nz = p > 0
    out[nz] = p[nz] * np.log2(p[nz] / q[nz])
    return out


def js_divergence(a, b):
    """Jensen-Shannon divergence (base-2), symmetric and bounded in ``[0, 1]``.

    ``P = normalise_sum(a)``, ``Q = normalise_sum(b)``, ``M = (P + Q) / 2``;
    ``JSD = 0.5*sum(_kl_terms(P, M)) + 0.5*sum(_kl_terms(Q, M))``. Deliberately NOT built on
    :func:`kld` (whose additive-EPS convention is the wrong tool here -- see :func:`_kl_terms`'s
    docstring for why). ``M``'s zero-safety contract is satisfied by construction: wherever
    ``P``/``Q`` is nonzero, ``M`` is too, so :func:`_kl_terms` never divides by zero.

    Exactly ``0.0`` for two identical grids -- including the both-all-zero case (``P=Q=`` all
    zero, so both ``_kl_terms`` sums are 0 by the zero-safe convention, no special-casing needed --
    this is the pinned, deliberate convention for ``compare_<slug>.csv``'s diagonal rows and for
    two grids that are each entirely empty)."""
    p = normalise_sum(a)
    q = normalise_sum(b)
    mgrid = (p + q) / 2.0
    kl_pm = float(np.sum(_kl_terms(p, mgrid)))
    kl_qm = float(np.sum(_kl_terms(q, mgrid)))
    return 0.5 * kl_pm + 0.5 * kl_qm


def nss(salmap, mask):
    """Normalized Scanpath Saliency: mean, over ``mask==1`` cells, of the z-scored ``salmap``:
    ``(salmap - mean(salmap)) / std(salmap)``.

    ``mask`` is a binary attended-region array (same shape as ``salmap``). Returns 0.0 if
    ``salmap`` is constant or the mask has no positive cells (undefined otherwise).
    """
    s = np.asarray(salmap, dtype=float).flatten()
    msk = np.asarray(mask).flatten().astype(bool)
    std = s.std()
    if std == 0 or not msk.any():
        return 0.0
    z = (s - s.mean()) / std
    return float(z[msk].mean())


def auc_judd(salmap, mask):
    """Standard Judd ROC-AUC: ``mask==1`` cells are the positive (fixated) class, all other cells
    are negatives, thresholds swept over ``salmap`` values.

    Computed exactly via the Mann-Whitney U / rank-sum identity, which is algebraically
    equivalent to the trapezoidal-integrated ROC-AUC (ties broken by average rank, matching
    ``scipy.stats.rankdata``'s default): ``AUC = (sum(rank(pos)) - n1*(n1+1)/2) / (n1*n0)``.
    Returns NaN if the mask is all-0 or all-1 (AUC undefined without both classes).
    """
    s = np.asarray(salmap, dtype=float).flatten()
    msk = np.asarray(mask).flatten().astype(bool)
    pos = s[msk]
    neg = s[~msk]
    n1, n0 = len(pos), len(neg)
    if n1 == 0 or n0 == 0:
        return float("nan")
    ranks = rankdata(np.concatenate([pos, neg]))
    rank_pos_sum = ranks[:n1].sum()
    return float((rank_pos_sum - n1 * (n1 + 1) / 2.0) / (n1 * n0))


def iou(a, b, thresh=0.1):
    """``|{a>thresh*max(a)} ∩ {b>thresh*max(b)}| / |union|``. 0.0 if the union is empty."""
    a = np.asarray(a, dtype=float).flatten()
    b = np.asarray(b, dtype=float).flatten()
    ma = a.max() if a.size else 0.0
    mb = b.max() if b.size else 0.0
    am = a > (thresh * ma) if ma > 0 else np.zeros_like(a, dtype=bool)
    bm = b > (thresh * mb) if mb > 0 else np.zeros_like(b, dtype=bool)
    union = np.logical_or(am, bm).sum()
    if union == 0:
        return 0.0
    inter = np.logical_and(am, bm).sum()
    return float(inter) / float(union)


def top_k_frac_mask(grid, frac=PRECISION_K_FRAC):
    """Tier 3 C6: boolean mask of the top ``frac`` (fraction, e.g. ``0.10`` = top 10%)
    highest-dwell cells in ``grid``: ``k = ceil(frac * n)`` (at least 1 for a non-empty grid),
    ``cutoff`` = the value of the ``k``-th largest cell (descending order), mask = ``(grid >=
    cutoff) & (grid > 0)``.

    Tie-inclusive **by construction**: this returns the full ``>= cutoff`` set, not a fixed-size
    top-``k`` slice, so every cell sharing the exact cutoff value is included even when that pushes
    the mask's True-count above ``k`` (e.g. several cells tied at the cutoff value) -- pinned,
    deterministic, and identical across languages regardless of a sort's tie-break, since the final
    mask only depends on the cutoff VALUE, not on which index a sort happened to rank ``k``-th
    among ties. ``k``-th-largest is computed via a full descending sort (``n`` is small --
    grid-cell counts, not path lengths -- so this is not a performance concern).

    **Strictly-positive guard (final-review fix):** for a sparse/focused grid where fewer than
    ``k`` cells have nonzero dwell (the ORDINARY case for a focused reader on a fine grid),
    ``cutoff`` is ``0.0`` -- the plain ``grid >= cutoff`` mask would then select *every* cell
    (including every untouched one), silently degrading "top-K" to "the whole grid" and making
    downstream recall report ``1.0`` for a reader who never touched the reference region at all
    (the opposite of reality). The extra ``& (grid > 0)`` restricts the tie-inclusive ``>=cutoff``
    set to cells the reader actually dwelled in. This is a no-op whenever ``cutoff > 0`` (``grid >=
    cutoff`` already implies ``grid > 0`` in that case), so every non-degenerate/dense case is
    numerically unchanged -- only the ``cutoff == 0`` case's mask shrinks from "every cell" to "the
    strictly-positive cells" (possibly zero of them, then handled by
    :func:`precision_recall_at_topk`'s own ``topk_n == 0 -> NaN`` guard).

    Returns an all-``False`` mask for an empty (0-length) grid (no cutoff to compute)."""
    g = np.asarray(grid, dtype=float).flatten()
    n = g.size
    if n == 0:
        return np.zeros(0, dtype=bool)
    k = max(1, int(math.ceil(frac * n)))
    cutoff = float(np.sort(g)[::-1][k - 1])
    return (g >= cutoff) & (g > 0)


def precision_recall_at_topk(dwell, roi_mask, frac=PRECISION_K_FRAC):
    """Tier 3 C6: ``precisionAtTopK``/``recall`` of a reader's top-``frac`` highest-dwell cells
    (:func:`top_k_frac_mask`) against a reference ROI boolean mask (``roi_mask``, same shape as
    ``dwell``) -- separates "missed target" (low recall) from "wasted attention" (low precision).

    ``precisionAtTopK = |topK ∩ roi_mask| / |topK|`` -- fraction of the reader's own most-attended
    cells that fall inside the reference region.
    ``recall = |topK ∩ roi_mask| / |roi_mask|`` -- fraction of the reference region the reader's
    top-attended cells cover.

    Returns ``(float("nan"), float("nan"))`` (blank in the CSV) if the ROI mask is empty (no
    reference region defined at all) OR the top-K set is empty (degenerate zero-size grid only --
    :func:`top_k_frac_mask` otherwise always returns >=1 cell for a non-empty grid)."""
    topk = top_k_frac_mask(dwell, frac)
    roi = np.asarray(roi_mask).astype(bool).flatten()
    topk_n = int(topk.sum())
    roi_n = int(roi.sum())
    if topk_n == 0 or roi_n == 0:
        return float("nan"), float("nan")
    inter = int(np.logical_and(topk, roi).sum())
    return float(inter) / float(topk_n), float(inter) / float(roi_n)


# ---------------------------------------------------------------------------
# Scanpath (schema/3, /4 "path" only)
# ---------------------------------------------------------------------------

def visited_sequence(path, gw, gh, img_w, img_h):
    """Map each path point ``[t, cx, cy, w, h]`` (image px) to a grid-cell index
    ``row*gw + col`` (``col = floor(cx/img_w*gw)``, ``row = floor(cy/img_h*gh)``, clamped to
    valid range), then run-length-dedup consecutive repeats (so dwelling in one cell across many
    samples collapses to a single visit in the sequence)."""
    gw, gh = int(gw), int(gh)
    img_w = float(img_w) if img_w else 1.0
    img_h = float(img_h) if img_h else 1.0
    seq = []
    for pt in path:
        cx, cy = float(pt[1]), float(pt[2])
        col = int(math.floor(cx / img_w * gw))
        row = int(math.floor(cy / img_h * gh))
        col = min(max(col, 0), gw - 1)
        row = min(max(row, 0), gh - 1)
        seq.append(row * gw + col)
    deduped = []
    for idx in seq:
        if not deduped or deduped[-1] != idx:
            deduped.append(idx)
    return deduped


def _edit_distance(a, b):
    """Standard Levenshtein DP edit distance over arbitrary-token sequences (not chars)."""
    n, m = len(a), len(b)
    if n == 0:
        return m
    if m == 0:
        return n
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        ai = a[i - 1]
        for j in range(1, m + 1):
            cost = 0 if ai == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[m]


def levenshtein_sim(seq_a, seq_b):
    """``1 - edit_distance(seqA,seqB) / max(len(seqA), len(seqB), 1)``. Tokens are grid-cell
    indices (from :func:`visited_sequence`), compared for exact equality (not string chars)."""
    seq_a, seq_b = list(seq_a), list(seq_b)
    d = _edit_distance(seq_a, seq_b)
    return 1.0 - d / max(len(seq_a), len(seq_b), 1)


def transition_matrix(seq):
    """``Counter`` of consecutive-transition pairs ``(seq[i], seq[i+1])``."""
    seq = list(seq)
    return Counter(zip(seq, seq[1:]))


def top_transitions(seq, top_n=15):
    """Top-``top_n`` ``(fromCell, toCell, count)`` directed transitions from :func:`transition_matrix`,
    by count descending, ties broken by ``(fromCell, toCell)`` ascending -- **deterministic**
    across languages. ``Counter.items()`` (Python) and ``table()`` (R) do not promise the same
    iteration order for equal-count entries, so an unordered tie-break would let the two toolkits'
    ``transitions_<slug>.csv`` (Tier 1 A5) disagree on which transitions make the top-``top_n``
    cut whenever counts tie -- common on short scanpaths. ``[]`` for a sequence with fewer than 2
    elements (no transitions)."""
    counts = transition_matrix(seq)
    items = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1]))
    return [(frm, to, cnt) for (frm, to), cnt in items[:top_n]]


def transition_entropy(seq):
    """Shannon entropy (base-2) of the normalized consecutive-transition distribution.

    0.0 if the sequence has fewer than 2 elements (no transitions).
    """
    seq = list(seq)
    trans = list(zip(seq, seq[1:]))
    if not trans:
        return 0.0
    counts = Counter(trans)
    total = len(trans)
    p = np.array([c / total for c in counts.values()])
    return float(-np.sum(p * np.log2(p + EPS)))


def scanpath_length_px(path):
    """Sum of consecutive-center Euclidean distances, in image px (``cx``, ``cy`` of each point)."""
    total = 0.0
    for i in range(1, len(path)):
        x0, y0 = float(path[i - 1][1]), float(path[i - 1][2])
        x1, y1 = float(path[i][1]), float(path[i][2])
        total += math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2)
    return total


def n_revisits(seq):
    """Count of steps entering a cell already seen earlier in the (run-length-deduped) sequence."""
    seen = set()
    revisits = 0
    for idx in seq:
        if idx in seen:
            revisits += 1
        seen.add(idx)
    return revisits


def visit_count_grid(seq, gw, gh):
    """Tier 3 C6: +1 per cell ENTRY in a run-length-deduped visited-cell sequence (see
    :func:`visited_sequence`) -- NOT per raw path tick, so a long dwell in one cell counts as a
    single entry, not one count per sample. Returns a flat ``(gw*gh,)`` float array (float, not
    int, to match :func:`top_hotspots`'s expected grid dtype; every value is still a whole number
    of dwell-run entries).

    **Zero-size-grid guard (final-review fix):** ``gw<=0`` or ``gh<=0`` (a schema-valid fragment
    with ``gridWidth``/``gridHeight`` of ``0``, ``grid=[]``) returns an EMPTY ``(0,)`` array
    without touching ``seq`` at all. Without this guard, ``seq`` here is
    :func:`visited_sequence`'s output at this SAME degenerate ``(gw, gh)`` -- every one of its
    entries clamps to index ``-1`` (see that function's clamp), so ``counts[-1]`` on a genuinely
    size-``0`` array raises ``IndexError`` and aborts the whole batch run the moment any session
    on a zero-size grid has a non-empty path. This mirrors :func:`visit_count_jaccard`'s own
    documented "blank for a degenerate zero-size grid" convention -- the empty array here makes
    that function's ``top_hotspots`` calls return empty hotspot sets, so the blank propagates
    cleanly instead of crashing."""
    gw, gh = int(gw), int(gh)
    if gw <= 0 or gh <= 0:
        return np.zeros(0, dtype=float)
    counts = np.zeros(gw * gh, dtype=float)
    for idx in seq:
        counts[idx] += 1.0
    return counts


def visit_count_jaccard(dwell_grid, gw, gh, seq, top_n=5):
    """Tier 3 C6: Jaccard similarity between the top-``top_n`` DWELL-TIME hotspot cells and the
    top-``top_n`` VISIT-COUNT hotspot cells (:func:`visit_count_grid`) at the same ``(gw, gh)``
    grid resolution -- disagreement between "where the reader lingered longest" and "which cells
    the reader entered most often" is a navigation-style signal (e.g. one long dwell vs many brief
    revisits). Both hotspot sets reuse :func:`top_hotspots` (deterministic tie-break) so flat cell
    indices align 1:1 between the two grids.

    ``float("nan")`` (blank) if either hotspot set is empty (only possible for a degenerate
    zero-size grid -- :func:`top_hotspots` otherwise always returns >=1 cell for any grid with
    >=1 cell)."""
    visit_grid = visit_count_grid(seq, gw, gh)
    dwell_top = top_hotspots(dwell_grid, gw, gh, top_n)
    visit_top = top_hotspots(visit_grid, gw, gh, top_n)
    gw_i = int(gw)
    set_a = set(row * gw_i + col for row, col, _ in dwell_top)
    set_b = set(row * gw_i + col for row, col, _ in visit_top)
    if not set_a or not set_b:
        return float("nan")
    inter = len(set_a & set_b)
    union = len(set_a | set_b)
    return float(inter) / float(union) if union > 0 else float("nan")


# ---------------------------------------------------------------------------
# Scanpath -> fine dwell raster (schema/3, /4; independent of the recorded grid resolution)
# ---------------------------------------------------------------------------

def step_durations_ms(path):
    """Per-step (``path[i] -> path[i+1]``) time delta in ms, length ``len(path)-1``.

    A step with non-positive ``Δt`` (out-of-order or duplicate timestamps -- defensive, should not
    happen with a monotonic recorder clock) is clamped to ``0.0`` rather than raising or going
    negative, so every consumer of this helper (raster_from_path, the zoom-unchanged/velocity
    helpers below) treats "no time elapsed" uniformly. ``[]`` if ``path`` has fewer than 2 points.
    R equivalent: ``pmax(0, diff(t))``.
    """
    if not path or len(path) < 2:
        return []
    out = []
    for i in range(len(path) - 1):
        dt = float(path[i + 1][0]) - float(path[i][0])
        out.append(dt if dt > 0 else 0.0)
    return out


def idle_step_mask(path):
    """Tier 2 B1: boolean list (length ``len(path)-1``), ``True`` iff step ``i``'s Δt (see
    :func:`step_durations_ms`) exceeds :data:`IDLE_GAP_MS` -- a "step-away" gap (Ghezloo's >60s-
    frozen-viewport idle-time-exclusion rule) the user was not actively looking at the slide
    during. Every rate/weight metric below documented as "idle-excluded"
    (:func:`scanning_rate_px_per_min`, :func:`drilling_rate_per_min`,
    :func:`path_velocity_px_per_sec`, :func:`search_focus_ratio`, :func:`raster_from_path`'s step
    weight, :func:`avg_zoom_log2_w`, :func:`drilling_rate_octaves_per_min`, and the
    ``magbands_<slug>.csv`` ``bandTimeMs`` aggregation in ``analyze.py``) drops steps where this is
    ``True`` entirely from its computation -- not just caps their contribution.

    ``[]`` if the path has fewer than 2 points (no steps at all)."""
    dts = step_durations_ms(path)
    return [dt > IDLE_GAP_MS for dt in dts]


def idle_ms(path):
    """Tier 2 B1: Σ of step Δt (see :func:`step_durations_ms`) over steps flagged idle by
    :func:`idle_step_mask` -- the ``idleMs`` ``metrics.csv`` transparency column. ``0.0`` if the
    path has fewer than 2 points (no steps) or no step is idle -- a session with no >60s gap
    therefore always reports ``idleMs == 0.0``, which is what keeps every idle-excluded rate metric
    below numerically identical to its pre-B1 value for such sessions (the exclusion set is
    empty)."""
    dts = step_durations_ms(path)
    idle = idle_step_mask(path)
    return float(sum(dt for dt, is_idle in zip(dts, idle) if is_idle))


def active_span_ms(path):
    """Tier 2 B1: wall-clock span (``tRel_last - tRel_first``) minus :func:`idle_ms` -- the
    ``activeSpanMs`` ``metrics.csv`` transparency column, and the denominator every idle-excluded
    rate metric below (:func:`scanning_rate_px_per_min`, :func:`drilling_rate_per_min`,
    :func:`drilling_rate_octaves_per_min`) divides by (in minutes) in place of the old
    total-duration denominator. ``0.0`` if the path has fewer than 2 points (no span at all) --
    matches the file's existing "0.0 for an insufficient path" convention for administrative/
    passthrough-style fields (as opposed to the NaN-blank convention used for genuinely undefined
    statistics like :func:`avg_zoom_log2_w`)."""
    if not path or len(path) < 2:
        return 0.0
    span = float(path[-1][0]) - float(path[0][0])
    return span - idle_ms(path)


def raster_from_path(path, img_w, img_h, gw, gh, step_mask=None):
    """Rebuild a ``gh x gw`` dwell-ms grid directly from the scanpath, independent of the
    recorded ``grid`` resolution -- this is what makes a 40x+ (very zoomed-in) navigation
    faithfully resolvable at any chosen output resolution (``gw``, ``gh``), unlike the recorder's
    fixed-size ``grid``.

    For each consecutive pair of points ``path[i] -> path[i+1]``: the elapsed time
    ``Δt = step_durations_ms(path)[i]`` is attributed to the **viewport rectangle of point i**
    (center ``(cx, cy)``, extent ``(w, h)``, all in image px) -- i.e. the view the user was
    actually looking at during that interval -- clamped to the image bounds ``[0, img_w] x
    [0, img_h]``, then divided **evenly** across every grid cell the clamped rectangle overlaps
    (cell membership by the standard "floor" cell-index convention used throughout this module:
    a rect spanning image-px range ``[x0, x1)`` covers grid columns
    ``floor(x0/img_w*gw) .. ceil(x1/img_w*gw)-1`` -- the ``ceil(...)-1`` upper bound, rather than
    ``floor``, is so a rect edge sitting exactly on a cell boundary does not spuriously include
    the next cell; same convention for rows). If the clamped rectangle collapses to nothing
    (viewport entirely off-image), the whole ``Δt`` instead lands on the single cell containing
    the (clamped) center point. Steps with ``Δt <= 0`` contribute nothing.

    ``step_mask``, if given, is a boolean sequence of length ``len(path)-1``; steps where it is
    ``False`` are skipped entirely (used by the magnification-band split to build one raster per
    zoom band from the same path). Default (``None``) includes every step.

    **Tier 2 B1:** a step flagged idle by :func:`idle_step_mask` (Δt > :data:`IDLE_GAP_MS`) is
    *always* skipped as well, regardless of ``step_mask`` -- the user was not looking at that
    viewport, so it should contribute no dwell-weight to the raster at any resolution. For a path
    with no idle step (``idle_step_mask`` all ``False``) this is a no-op, so the raster is
    numerically identical to the pre-B1 behavior.

    Returns a flat ``(gw*gh,)`` float array (dwell-ms per cell), or **``None``** if ``path`` has
    fewer than 2 points (a Δt requires two points; single-point/empty paths carry no raster).

    **Zero-size-grid guard (polish final-review Finding 3, defense-in-depth):** also returns
    ``None`` for ``gw<=0``/``gh<=0``. The root cause -- a schema-valid fragment recording
    ``gridWidth``/``gridHeight`` of ``0`` -- is now rejected at load by
    :func:`blinded_focus.io._is_valid_fragment`, so ``analyze()`` never reaches this function with
    a degenerate grid; this guard is defense-in-depth for a direct caller. Without it,
    ``np.zeros((gh, gw))`` at ``gw==0``/``gh==0`` is a genuinely empty array, and every cell-index
    clamp below (``min(max(...), gw - 1)``, ``gw - 1 == -1``) resolves to ``-1`` -- so
    ``grid[row, -1] += dt`` (or the fallback single-cell branch's identical clamp) raises
    ``IndexError`` the moment any step has a positive, non-idle Δt, aborting the whole batch run
    (callers already null-check this function's return, e.g. the magband-split export's
    ``if raster_b is None: continue``, so folding this into the same ``None`` sentinel the
    <2-point case already uses costs nothing at any call site)."""
    if not path or len(path) < 2:
        return None
    gw, gh = int(gw), int(gh)
    if gw <= 0 or gh <= 0:
        return None
    img_w = float(img_w) if img_w else 1.0
    img_h = float(img_h) if img_h else 1.0
    dts = step_durations_ms(path)
    idle = idle_step_mask(path)
    grid = np.zeros((gh, gw), dtype=float)
    for i, dt in enumerate(dts):
        if dt <= 0:
            continue
        if idle[i]:
            continue
        if step_mask is not None and not step_mask[i]:
            continue
        cx, cy = float(path[i][1]), float(path[i][2])
        w = float(path[i][3]) if float(path[i][3]) > 0 else 1.0
        h = float(path[i][4]) if float(path[i][4]) > 0 else 1.0
        x0, x1 = max(0.0, cx - w / 2.0), min(img_w, cx + w / 2.0)
        y0, y1 = max(0.0, cy - h / 2.0), min(img_h, cy + h / 2.0)
        if x1 <= x0 or y1 <= y0:
            # Viewport rect is entirely outside the image after clamping -- fall back to the
            # single cell containing the (clamped) center point rather than dropping the Δt.
            ccx = min(max(cx, 0.0), img_w - EPS)
            ccy = min(max(cy, 0.0), img_h - EPS)
            col = min(max(int(math.floor(ccx / img_w * gw)), 0), gw - 1)
            row = min(max(int(math.floor(ccy / img_h * gh)), 0), gh - 1)
            grid[row, col] += dt
            continue
        col0 = min(max(int(math.floor(x0 / img_w * gw)), 0), gw - 1)
        col1 = min(max(int(math.ceil(x1 / img_w * gw)) - 1, 0), gw - 1)
        row0 = min(max(int(math.floor(y0 / img_h * gh)), 0), gh - 1)
        row1 = min(max(int(math.ceil(y1 / img_h * gh)) - 1, 0), gh - 1)
        if col1 < col0:
            col1 = col0
        if row1 < row0:
            row1 = row0
        n_cells = (col1 - col0 + 1) * (row1 - row0 + 1)
        grid[row0:row1 + 1, col0:col1 + 1] += dt / n_cells
    return grid.flatten()


# ---------------------------------------------------------------------------
# Zoom / magnification (schema/4 dsMilli+baseMagnification; schema/3 w-proxy fallback)
# ---------------------------------------------------------------------------

def point_zoom(point, base_mag=None, img_w=None):
    """Magnification (or a zoom proxy, when the true value is unavailable) for one scanpath
    point, in documented fallback order -- **higher value always means "more zoomed in"** in
    every branch, so the three cases stay comparable within one path/session even when they
    can't be compared in absolute terms across sessions with different fallback levels:

    1. ``base_mag`` known (fragment-level, schema/4) **and** the point has a 6th element
       (``dsMilli``, schema/4): true magnification = ``base_mag / (dsMilli / 1000.0)``.
    2. Point has ``dsMilli`` (6 elements, schema/4) but ``base_mag`` is ``None``/unknown: a
       unitless zoom level = ``1000.0 / dsMilli`` (== ``1 / downsample``).
    3. Point has no ``dsMilli`` (5 elements, schema/3): a width-proxy zoom =
       ``img_w / w`` (``w`` = visible viewport width in image px at that tick; since ``w``
       grows with the downsample factor, this ratio also grows with true zoom, on the same
       "larger = more zoomed in" convention as branches 1/2 -- but on a different, session/
       window-size-dependent numeric scale, so it must not be compared across sessions or mixed
       with branches 1/2 within one metric).

    ``dsMilli <= 0`` or ``w <= 0`` are treated defensively as full-resolution / 1px respectively
    (malformed-data guard; should not occur with a well-behaved recorder).

    **Non-numeric ``base_mag`` guard (polish final-review Finding 1, completing the fix):** a
    fragment-level ``baseMagnification`` that is present but not numeric (e.g. the string
    ``"unknown"`` -- schema-valid, since the field is typed loosely) degrades to branch 2 (the
    ``base_mag``-unknown zoom level), identical to ``base_mag=None``, rather than raising. Before
    this guard, ``float(base_mag) > 0`` on a non-numeric ``base_mag`` raised ``ValueError``
    directly out of this function -- and since every one of :func:`avg_zoom`/:func:`zoom_variance`/
    :func:`zoom_range`/:func:`magnification_percentage`/:func:`scanning_rate_px_per_min`/
    :func:`drilling_rate_per_min`/:func:`avg_zoom_log2_w`/:func:`drilling_rate_octaves_per_min`
    calls this per scanpath point, this was reachable on the very first ``dsMilli``-carrying path
    point (``analyze.py``'s ``row["avgZoom"] = m.avg_zoom(path, base_mag, img_w)``, well before the
    ``magnificationSource`` assignment PT1 separately guarded) -- aborting the whole batch the
    instant any real schema/4+ session recorded a non-numeric ``baseMagnification``. Mirrors
    :func:`true_magnification`'s existing ``try/except (TypeError, ValueError)`` pattern exactly.
    """
    has_ds = len(point) >= 6
    if has_ds:
        ds_milli = float(point[5])
        if ds_milli <= 0:
            ds_milli = 1000.0
        bm = None
        if base_mag is not None:
            try:
                bm = float(base_mag)
            except (TypeError, ValueError):
                bm = None
        if bm is not None and bm > 0:
            return bm / (ds_milli / 1000.0)
        return 1000.0 / ds_milli
    w = float(point[3])
    if w <= 0:
        w = 1.0
    iw = float(img_w) if img_w else 1.0
    return iw / w


def _zoom_series(path, base_mag=None, img_w=None):
    """``point_zoom`` for every point in ``path``, as a numpy array (empty if ``path`` is
    empty/``None``)."""
    if not path:
        return np.array([], dtype=float)
    return np.array([point_zoom(p, base_mag, img_w) for p in path], dtype=float)


def avg_zoom(path, base_mag=None, img_w=None):
    """Mean of :func:`point_zoom` over every point in the path. ``0.0`` for an empty path."""
    z = _zoom_series(path, base_mag, img_w)
    return float(z.mean()) if z.size else 0.0


def zoom_variance(path, base_mag=None, img_w=None):
    """Sample variance (``ddof=1`` -- matches R's default ``var()``) of :func:`point_zoom` over
    every point in the path. ``0.0`` (not NaN/NA) if the path has fewer than 2 points -- R's
    ``var()`` returns ``NA`` on a length-1 input, so an R port must special-case ``n<2 -> 0`` to
    match this."""
    z = _zoom_series(path, base_mag, img_w)
    return float(z.var(ddof=1)) if z.size >= 2 else 0.0


def zoom_range(path, base_mag=None, img_w=None):
    """``max(point_zoom) - min(point_zoom)`` over the path. ``0.0`` for an empty path."""
    z = _zoom_series(path, base_mag, img_w)
    return float(z.max() - z.min()) if z.size else 0.0


def magnification_percentage(path, base_mag=None, img_w=None):
    """Fraction of consecutive scanpath transitions that are strictly zoom-IN (a "consecutive
    zooming" measure after Ghezloo): ``|{i : zoom[i+1] > zoom[i]}| / (n-1)`` where
    ``zoom = point_zoom(path[i])`` and ``n = len(path)``. Exact ``>`` comparison, no tolerance:
    :func:`point_zoom` is a deterministic function of integer-quantized inputs (``dsMilli``,
    rounded ``w``) plus a fragment-constant ``base_mag``, so two ticks at a held zoom level
    produce bit-identical floats -- a tolerance would only be needed if this were re-derived from
    a noisy/continuous zoom signal, which it is not. A held zoom level (an exact tie) does
    **not** count -- see the bug-fix note below.

    ``0.0`` if the path has fewer than 2 points (no transitions -- not NaN/NA).

    **Bug fix (2026-07, see ``docs/superpowers/navtrack-lit-review-improvements.md`` §0.B):** this
    used to count zoom-*unchanged* transitions too (``diffs >= 0``). Ghezloo's definition is that
    the zoom level must *strictly* increase; a held zoom level (an exact tie) must not count as
    "consecutive zooming". Changed ``np.count_nonzero(diffs >= 0)`` to
    ``np.count_nonzero(diffs > 0)`` below -- an R port must use the same strict ``>``."""
    if not path or len(path) < 2:
        return 0.0
    zooms = _zoom_series(path, base_mag, img_w)
    diffs = zooms[1:] - zooms[:-1]
    return float(np.count_nonzero(diffs > 0)) / len(diffs)


def _step_zoom_changed(path, base_mag=None, img_w=None):
    """Per-step boolean (length ``len(path)-1``): ``True`` iff ``point_zoom`` differs (exact
    ``!=``, see :func:`magnification_percentage`'s determinism note) between the step's two
    endpoints. Caveat for schema/3 (w-proxy) paths: a mid-session viewer-window resize changes
    ``w`` without the user having zoomed, and would misread here as a zoom-change step; schema/4
    (``dsMilli``-based) zoom is not affected by window resizes."""
    zooms = _zoom_series(path, base_mag, img_w)
    return zooms[1:] != zooms[:-1]


def scanning_rate_px_per_min(path, base_mag=None, img_w=None):
    """"Scanning" rate (px/min): total center pan-distance (Euclidean, consecutive points)
    accumulated over steps where zoom is unchanged (see :func:`_step_zoom_changed`) -- panning
    around at a held zoom level, as opposed to "drilling" (see :func:`drilling_rate_per_min`) --
    **normalized by the path's ACTIVE duration** (Tier 2 B1: :func:`active_span_ms`, in minutes,
    replacing the pre-B1 total-duration denominator ``t[-1] - t[0]``; the design doc does not pin
    this denominator, so this is documented as the deliberate choice: active session time, not
    time-spent-scanning, so the rate is comparable across sessions with different scanning/
    drilling mixes -- idle "stepped away" time is neither "scanning" nor "drilling" time and would
    otherwise dilute the rate). ``0.0`` if the path has fewer than 2 points or non-positive active
    duration.

    **Tier 2 B1:** a step flagged idle by :func:`idle_step_mask` also contributes nothing to the
    pan-distance numerator, even if its zoom is unchanged -- an idle "parked at this zoom level for
    5 minutes" step is not scanning. For a path with no idle step this is numerically identical to
    the pre-B1 formula (idle set empty, and ``active_span_ms`` reduces to the total duration)."""
    if not path or len(path) < 2:
        return 0.0
    duration_min = active_span_ms(path) / 60000.0
    if duration_min <= 0:
        return 0.0
    changed = _step_zoom_changed(path, base_mag, img_w)
    idle = idle_step_mask(path)
    pan = 0.0
    for i in range(len(path) - 1):
        if idle[i]:
            continue
        if not changed[i]:
            x0, y0 = float(path[i][1]), float(path[i][2])
            x1, y1 = float(path[i + 1][1]), float(path[i + 1][2])
            pan += math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2)
    return pan / duration_min


def drilling_rate_per_min(path, base_mag=None, img_w=None):
    """"Drilling" rate (events/min): count of zoom-change steps (see :func:`_step_zoom_changed`)
    per minute of the path's ACTIVE duration (Tier 2 B1: :func:`active_span_ms`, same denominator
    as :func:`scanning_rate_px_per_min`). ``0.0`` if the path has fewer than 2 points or
    non-positive active duration.

    **Tier 2 B1:** a zoom-change step that is *also* idle (see :func:`idle_step_mask` -- e.g. the
    user zoomed in right before stepping away for 5+ minutes, so the change is only discovered on
    return) does not count as a "drilling event" -- it was not an active navigation action. For a
    path with no idle step this is numerically identical to the pre-B1 formula."""
    if not path or len(path) < 2:
        return 0.0
    duration_min = active_span_ms(path) / 60000.0
    if duration_min <= 0:
        return 0.0
    changed = _step_zoom_changed(path, base_mag, img_w)
    idle = idle_step_mask(path)
    n_changed_active = sum(1 for i in range(len(changed)) if changed[i] and not idle[i])
    return float(n_changed_active) / duration_min


def avg_zoom_log2_w(path, base_mag=None, img_w=None):
    """Tier 2 B2 (Drew-fidelity zoom, ADD-alongside :func:`avg_zoom` -- does not replace it): Δt-
    weighted mean of ``log2(magnification)`` over ACTIVE (non-idle, see :func:`idle_step_mask`)
    steps:

    ``avgZoomLog2W = Σ_active Δt_i * log2(zoom_i) / Σ_active Δt_i``

    where ``zoom_i = point_zoom(path[i], base_mag, img_w)`` (the step-i-owns-point-i convention
    used throughout this module, e.g. :func:`raster_from_path`). ``log2`` via ``math.log2`` (not
    ``numpy.log2``) so an R port's plain ``log2()`` reproduces the identical IEEE-754 primitive.

    ``float("nan")`` (blank in ``metrics.csv``) if the path has fewer than 2 points, every step is
    idle, or the total active Δt weight is otherwise 0 -- a 0/0 weighted mean has no defensible
    value (unlike :func:`avg_zoom`'s unweighted ``0.0``-for-empty-path convention, ``0.0`` here
    would misleadingly read as "1x magnification", a real, specific claim -- so this uses the same
    NaN-for-genuinely-undefined convention as e.g. :func:`enrichment_ratio` instead)."""
    if not path or len(path) < 2:
        return float("nan")
    dts = step_durations_ms(path)
    idle = idle_step_mask(path)
    total_w = 0.0
    total_wl = 0.0
    for i, dt in enumerate(dts):
        if idle[i] or dt <= 0:
            continue
        z = point_zoom(path[i], base_mag, img_w)
        total_w += dt
        total_wl += dt * math.log2(z)
    return (total_wl / total_w) if total_w > 0 else float("nan")


def drilling_rate_octaves_per_min(path, base_mag=None, img_w=None):
    """Tier 2 B2 (Drew-fidelity zoom, ADD-alongside :func:`drilling_rate_per_min` -- does not
    replace it): Σ of the absolute per-step ``log2(zoom)`` change over ACTIVE (non-idle, see
    :func:`idle_step_mask`) steps, per active minute (:func:`active_span_ms` / 60000, same
    denominator as :func:`scanning_rate_px_per_min`/:func:`drilling_rate_per_min`) -- a continuous
    zoom-change-magnitude complement to :func:`drilling_rate_per_min`'s discrete event count:

    ``drillingRateOctavesPerMin = Σ_active |log2(zoom_{i+1}) - log2(zoom_i)| / activeDurationMin``

    ``log2`` via ``math.log2`` for Python<->R parity. ``float("nan")`` (blank in ``metrics.csv``)
    if the path has fewer than 2 points or the active duration is non-positive (all steps idle, or
    a degenerate zero-span path) -- same NaN-for-undefined convention as :func:`avg_zoom_log2_w`
    (as opposed to :func:`drilling_rate_per_min`'s ``0.0``-for-zero-duration convention: a 0/0 rate
    of continuous change is undefined, not "no change happened over a real duration")."""
    if not path or len(path) < 2:
        return float("nan")
    duration_min = active_span_ms(path) / 60000.0
    if duration_min <= 0:
        return float("nan")
    idle = idle_step_mask(path)
    zooms = _zoom_series(path, base_mag, img_w)
    total = 0.0
    for i in range(len(path) - 1):
        if idle[i]:
            continue
        total += abs(math.log2(zooms[i + 1]) - math.log2(zooms[i]))
    return total / duration_min


def zoom_band_labels(path, base_mag=None, img_w=None, n_bands=3):
    """Assign each *step* (``path[i] -> path[i+1]``, the point-i-owns-the-step convention used by
    :func:`raster_from_path`) to one of ``n_bands`` zoom bands (band ``0`` = lowest zoom,
    ``n_bands-1`` = highest), by **within-path quantile cut points** (terciles by default: cuts at
    the ``1/n_bands, 2/n_bands, ...`` quantiles of the per-step :func:`point_zoom` values).

    Quantiles use numpy's default (linear-interpolation) method, which is numerically identical
    to R's ``quantile(x, probs, type=7)`` (R's default) on the same input -- so an R port using
    plain ``quantile()`` reproduces the same cut points bit-for-bit. Band assignment is
    ``np.searchsorted(cuts, zooms, side="right")``, equivalent to R's ``findInterval(zooms,
    cuts)``.

    Returns a list of length ``len(path)-1`` (one band index per step), or ``[]`` if the path has
    fewer than 2 points.
    """
    if not path or len(path) < 2:
        return []
    zooms = np.array([point_zoom(p, base_mag, img_w) for p in path[:-1]], dtype=float)
    if n_bands < 2:
        return [0] * len(zooms)
    qs = [i / n_bands for i in range(1, n_bands)]
    cuts = np.quantile(zooms, qs)
    bands = np.searchsorted(cuts, zooms, side="right")
    return bands.tolist()


#: Tier 2 B3: canonical magnification-band cut points (objective power, x). 6 cuts -> 7 labeled
#: bands. An R port must use the identical literal cut points.
MAG_BAND_CUTS = [1.0, 2.0, 4.0, 10.0, 20.0, 40.0]
#: Tier 2 B3: human-readable labels for the 7 canonical bands (index-aligned with the band index
#: :func:`canonical_mag_band_labels` returns; not itself written to ``magbands_<slug>.csv`` -- kept
#: here as the single documented source of what each integer band index means).
MAG_BAND_LABELS = ["<1x", "1-2x", "2-4x", "4-10x", "10-20x", "20-40x", ">=40x"]


def true_magnification(point, base_mag):
    """Tier 2 B3: true objective magnification for one scanpath point -- ``base_mag /
    (dsMilli / 1000.0)`` -- requiring BOTH a known, positive ``base_mag`` (fragment-level
    ``baseMagnification``, schema/4+) and the point's own ``dsMilli`` (6th element, schema/4+).

    Returns ``None`` (a plain sentinel for "not computable" -- checked via ``is None`` at every
    call site, never participating in arithmetic) when either is absent/non-positive or the point
    has no ``dsMilli`` at all (schema/3, 5-element points) -- callers fall back to the existing
    tercile scheme (:func:`zoom_band_labels`) in that case, per B3's auto-fallback rule. ``dsMilli
    <= 0`` is treated defensively as full-resolution (matches :func:`point_zoom`'s own guard)."""
    if base_mag is None:
        return None
    try:
        bm = float(base_mag)
    except (TypeError, ValueError):
        return None
    if bm <= 0:
        return None
    if len(point) < 6:
        return None
    ds_milli = float(point[5])
    if ds_milli <= 0:
        ds_milli = 1000.0
    return bm / (ds_milli / 1000.0)


def canonical_mag_band_labels(path, base_mag):
    """Tier 2 B3: assign each *step* (``path[i] -> path[i+1]``, the same point-i-owns-the-step
    convention as :func:`zoom_band_labels`/:func:`raster_from_path`) a canonical magnification-band
    index in ``[0, 6]`` via :data:`MAG_BAND_CUTS` (``np.searchsorted(cuts, tm, side="right")``,
    same convention as :func:`zoom_band_labels`), using :func:`true_magnification` (path[i],
    base_mag)`.

    Returns ``None`` (not ``[]``) the moment any step's true magnification is not computable
    (missing/non-positive ``base_mag``, or a point with no ``dsMilli`` at all) -- signalling "the
    canonical scheme does not apply to this session at all; use the tercile fallback instead"
    (checked via ``is None``, never truthiness, so a genuinely empty/too-short path -- which
    returns ``[]`` -- is distinguished from "not computable"). ``[]`` for a <2-point path (no
    steps; canonical is trivially inapplicable but not a fallback signal)."""
    if not path or len(path) < 2:
        return []
    bands = []
    for i in range(len(path) - 1):
        tm = true_magnification(path[i], base_mag)
        if tm is None:
            return None
        idx = int(np.searchsorted(MAG_BAND_CUTS, tm, side="right"))
        bands.append(min(idx, len(MAG_BAND_LABELS) - 1))
    return bands


def magband_labels_for_scheme(path, base_mag, img_w, n_bands, scheme="canonical"):
    """Tier 2 B3: returns ``(bands, band_scheme_used)`` for one session's path, honoring the
    ``--magband-scheme`` CLI flag:

    - ``scheme == "tercile"``: always use the existing within-path quantile bands
      (:func:`zoom_band_labels`); ``band_scheme_used == "tercile"``.
    - ``scheme == "canonical"`` (default, and the fallback for any other value): try
      :func:`canonical_mag_band_labels` first; if it returns ``None`` (this session's
      ``base_mag``/``dsMilli`` are not computable -- e.g. an Atlas DZI slide with a null
      ``baseMagnification``, or a schema/3 5-element w-proxy path), fall back to
      :func:`zoom_band_labels`, ``band_scheme_used == "tercile"``. Otherwise
      ``band_scheme_used == "canonical"``.

    Returns ``([], "tercile")`` for a <2-point path (matches :func:`zoom_band_labels`'s own
    ``[]``-for-<2-points convention; the scheme label is unused in that case since no rows are
    ever emitted for an empty band list)."""
    if not path or len(path) < 2:
        return [], "tercile"
    if scheme == "tercile":
        return zoom_band_labels(path, base_mag, img_w, n_bands), "tercile"
    bands = canonical_mag_band_labels(path, base_mag)
    if bands is None:
        return zoom_band_labels(path, base_mag, img_w, n_bands), "tercile"
    return bands, "canonical"


# ---------------------------------------------------------------------------
# Path descriptors (Roa-Peña)
# ---------------------------------------------------------------------------

def _step_velocities_px_per_sec(path):
    """Per-step instantaneous speed (image px/sec): ``distance(i, i+1) / (Δt/1000)``. A step with
    non-positive ``Δt`` (see :func:`step_durations_ms`) gets velocity ``0.0`` (treated as "no
    motion" rather than undefined/dropped) so every step contributes a well-defined value.
    Length ``len(path)-1``; ``[]`` if ``path`` has fewer than 2 points."""
    if not path or len(path) < 2:
        return []
    dts = step_durations_ms(path)
    out = []
    for i, dt in enumerate(dts):
        if dt <= 0:
            out.append(0.0)
            continue
        x0, y0 = float(path[i][1]), float(path[i][2])
        x1, y1 = float(path[i + 1][1]), float(path[i + 1][2])
        out.append(math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2) / (dt / 1000.0))
    return out


def path_velocity_px_per_sec(path):
    """Median of per-step velocities (see :func:`_step_velocities_px_per_sec`), **Tier 2 B1:
    excluding steps flagged idle** by :func:`idle_step_mask` (a step "traversed" during a >60s
    away-gap is not a real navigation velocity -- it is dropped from the median entirely, not
    counted as a near-zero speed). ``0.0`` (not NaN/NA) if the path has fewer than 2 points or
    every step is idle. For a path with no idle step this is numerically identical to the pre-B1
    formula (idle set empty)."""
    vels = _step_velocities_px_per_sec(path)
    idle = idle_step_mask(path)
    active_vels = [v for v, is_idle in zip(vels, idle) if not is_idle]
    return float(np.median(active_vels)) if active_vels else 0.0


def linearity(path):
    """Net displacement (straight-line first-point -> last-point distance) divided by the total
    scanpath length (:func:`scanpath_length_px`, sum of consecutive-step distances) -- 1.0 for a
    dead-straight path, near 0 for a path that wanders back on itself. ``0.0`` if the total length
    is 0 (degenerate/empty/stationary path -- avoids division by zero)."""
    if not path or len(path) < 2:
        return 0.0
    x0, y0 = float(path[0][1]), float(path[0][2])
    x1, y1 = float(path[-1][1]), float(path[-1][2])
    net = math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2)
    total = scanpath_length_px(path)
    return net / total if total > 0 else 0.0


def search_focus_ratio(path, base_mag=None, img_w=None):
    """Fraction of dwell-time spent in "focused" steps, Δt-weighted (same
    step-i-owns-point-i convention as :func:`raster_from_path`/:func:`step_durations_ms`), **Tier 2
    B1: computed entirely over ACTIVE (non-idle, see :func:`idle_step_mask`) steps** -- idle steps
    are dropped before either the median thresholds or the Δt-weighted sum are computed, exactly as
    if they never existed, rather than being included in ``total_dt`` (which would otherwise let a
    single long away-gap dominate the denominator and dilute the ratio toward whatever its own
    zoom/velocity happened to be) or in the threshold computation (which would otherwise let a
    stale zoom/near-zero velocity from an away-gap skew the "focused" cutoff for every other step).

    A step (``path[i] -> path[i+1]``) counts as **focused** iff *either*:

    - ``point_zoom(path[i]) >= median(per-step zooms over ACTIVE steps)`` (high zoom), **or**
    - its velocity (:func:`_step_velocities_px_per_sec`) ``<= median(per-step velocities over
      ACTIVE steps)`` (low velocity -- includes stationary/paused steps, which get velocity 0 and
      are always <= the median).

    Both thresholds are the path's own median over active steps only (data-driven per session, not
    a fixed absolute pixel/magnification cutoff -- documented here for an R port to reuse
    ``median()`` identically). ``0.0`` if the path has fewer than 2 points, every step is idle, or
    zero total active Δt. For a path with no idle step this is numerically identical to the pre-B1
    formula (idle set empty, active steps == all steps)."""
    if not path or len(path) < 2:
        return 0.0
    n = len(path) - 1
    zooms_all = np.array([point_zoom(path[i], base_mag, img_w) for i in range(n)], dtype=float)
    dts_all = np.array(step_durations_ms(path), dtype=float)
    vels_all = np.array(_step_velocities_px_per_sec(path), dtype=float)
    idle = np.array(idle_step_mask(path), dtype=bool)
    active = ~idle
    if not active.any():
        return 0.0
    zooms = zooms_all[active]
    dts = dts_all[active]
    vels = vels_all[active]
    zoom_thresh = float(np.median(zooms))
    vel_thresh = float(np.median(vels))
    focused = (zooms >= zoom_thresh) | (vels <= vel_thresh)
    total_dt = float(dts.sum())
    if total_dt <= 0:
        return 0.0
    return float(dts[focused].sum() / total_dt)


# ---------------------------------------------------------------------------
# Cross-session consistency (dwell grids; Xu/Nan, Roa-Peña)
# ---------------------------------------------------------------------------

def coincidence_level(grids, thresh=0.1):
    """Fraction of cells that are above-threshold (``> thresh`` of each grid's own max, via
    :func:`normalise_max`) in **2 or more** of the given grids (already resampled to a common
    shape), normalized to the **visited footprint** -- cells above-threshold in **at least 1**
    grid -- not the whole grid shape. Roa-Peña reports ~70.5% with this style of rule on real
    multi-reader data.

    ``coincidence% = |cells visited by >=2 readers| / |cells visited by >=1 reader|``

    **Bug fix (2026-07, see ``docs/superpowers/navtrack-lit-review-improvements.md`` §0.A):** this
    used to normalize by the *whole grid* (``counts.size``, every cell including never-visited
    ones), which silently under-reports coincidence for any partially-explored slide and isn't
    comparable to the literature's ~70.5% benchmark -- Roa-Peña's own sanity check is a
    48%-visited slide still showing 97% coincidence, which is impossible under a whole-grid
    denominator. An R port must normalize by the visited-footprint count, not the grid length.

    ``float("nan")`` if fewer than 2 grids are given (undefined for a single reader). ``0.0`` if
    the visited footprint (``counts >= 1``) is empty -- nothing was visited by anyone, so there is
    nothing to compute a coincidence fraction over (guarded to avoid division by zero)."""
    grids = list(grids)
    if len(grids) < 2:
        return float("nan")
    normed = [normalise_max(g) for g in grids]
    counts = np.zeros_like(normed[0], dtype=float)
    for g in normed:
        counts += (g > thresh).astype(float)
    visited = int(np.count_nonzero(counts >= 1))
    if visited == 0:
        return 0.0
    return float(np.count_nonzero(counts >= 2)) / visited


def region_coverage_pct(session_grid, consensus_grid, thresh=0.1):
    """Percentage of the consensus's above-threshold cells (``> thresh`` of its own max) that
    this session's grid *also* has above its own threshold -- "how much of the group's attended
    region did this reader cover", per session. ``0.0`` if the consensus grid has no
    above-threshold cells (nothing to cover)."""
    cons = normalise_max(consensus_grid)
    sess = normalise_max(session_grid)
    cons_mask = cons > thresh
    n = int(cons_mask.sum())
    if n == 0:
        return 0.0
    covered = int(np.logical_and(cons_mask, sess > thresh).sum())
    return float(covered) / n * 100.0


# ---------------------------------------------------------------------------
# Annotation metrics (Phase 2; schema/4+ "annotations" GeoJSON FeatureCollection).
#
# These functions take an already-rasterized boolean cell mask (see ``analyze.py``'s
# ``rasterize_roi``, the same GeoJSON-polygon-to-grid rasterizer already used for the ``--roi``
# CLI flag) rather than GeoJSON themselves -- this module stays free of GeoJSON parsing, matching
# the existing convention that ``metrics.py`` only ever operates on numpy arrays/paths.
# ---------------------------------------------------------------------------

def dwell_in_mask_pct(grid, mask):
    """Percentage of total dwell that falls inside ``mask`` -- Ghezloo's "ROI time percentage",
    generalized from an expert reference ROI to a reader's own annotated region:

    ``dwellInAnnotationPct = 100 * sum(grid[mask]) / sum(grid)``

    ``grid`` and ``mask`` (boolean, same shape) are both flattened before comparison. ``0.0`` if
    ``sum(grid)`` is 0 (no dwell recorded at all, or an empty grid) -- there is genuinely 0% dwell
    anywhere, not an undefined ratio; also ``0.0`` (not undefined) when ``mask`` has no ``True``
    cells (no annotation on this slide) since ``sum(grid[mask])`` is then simply 0."""
    g = np.asarray(grid, dtype=float).flatten()
    msk = np.asarray(mask).astype(bool).flatten()
    total = g.sum()
    if total <= 0:
        return 0.0
    return float(g[msk].sum()) / total * 100.0


def enrichment_ratio(grid, mask):
    """Mean dwell-ms per annotated cell divided by mean dwell-ms per non-annotated cell (Nan 2025
    Nat Commun's enrichment ratio, a companion to the area-based :func:`region_coverage_pct`):

    ``enrichmentRatio = mean(grid[mask]) / mean(grid[~mask])``

    ``float("nan")`` (blank in ``metrics.csv`` via ``analyze._sanitize_nan``) in three distinct
    undefined cases, all mapped to the same NaN/blank rather than 0 or Inf -- an R port must
    special-case all three the same way:

    - ``mask`` has no ``True`` cells (no annotation on this slide -- nothing to enrich);
    - ``mask`` has no ``False`` cells (the whole grid is annotated -- no "outside" to compare
      against);
    - the non-annotated mean is exactly 0 (division by zero)."""
    g = np.asarray(grid, dtype=float).flatten()
    msk = np.asarray(mask).astype(bool).flatten()
    if not msk.any() or msk.all():
        return float("nan")
    mean_out = float(g[~msk].mean())
    if mean_out == 0:
        return float("nan")
    mean_in = float(g[msk].mean())
    return mean_in / mean_out


def annotated_area_union_px(mask, gw, gh, img_w, img_h):
    """Tier 3 C6: area (image px^2) of the UNIONED rasterized annotation mask
    (:func:`blinded_focus.analyze.rasterize_feature_collection`) -- the overlap-correct companion
    to the existing sum-based ``annotatedAreaPx``
    (:func:`blinded_focus.analyze.annotations_area_px`, which double-counts overlapping/nested
    Features -- see its docstring).

    ``count(mask) * (img_w/gw) * (img_h/gh)`` -- each ``True`` cell contributes its rasterized
    footprint area (approximating each grid cell as an ``img_w/gw`` x ``img_h/gh`` rectangle), NOT
    the exact vector polygon-union area (no polygon-clipping library is used here, consistent with
    the rest of this module's dependency-free approach -- the raster resolution is the grid's own
    ``(gw, gh)``, same as every other per-session native-grid metric).

    ``0.0`` for an all-``False`` (no-annotations, or a degenerate zero-size grid) mask."""
    gw, gh = int(gw), int(gh)
    if gw <= 0 or gh <= 0:
        return 0.0
    cell_area = (float(img_w) / gw) * (float(img_h) / gh)
    return float(np.count_nonzero(mask)) * cell_area


def annotation_reentry_count(path, mask, gw, gh, img_w, img_h):
    """Count of scanpath re-entries into the annotated region (Brunyé 2017's re-entry rate).

    Maps every path point to a grid cell via :func:`visited_sequence` (the same floor-division
    convention used throughout this module), which also run-length-dedups consecutive repeats so
    dwelling in one cell across many samples doesn't inflate the count. Looks up ``mask``
    (boolean, ``gh x gw`` flattened) at each deduped visited cell to get a per-visit
    inside/outside boolean sequence, then counts the number of maximal ``True`` runs ("visits") in
    that sequence:

    ``annotationReentryCount = max(0, n_visits - 1)``

    The first visit is an *entry*, not a *re*-entry -- this holds regardless of whether the very
    first visited cell happens to already be inside the region (there is nothing to have "left"
    yet either way), so the ``- 1`` is unconditional once ``n_visits >= 1``.

    ``0`` if the path is empty/``None``, ``mask`` has no ``True`` cells (no annotation on this
    slide), or the deduped visited sequence never enters the region at all (``n_visits == 0``)."""
    mask = np.asarray(mask).astype(bool).flatten()
    if not path or not mask.any():
        return 0
    seq = visited_sequence(path, gw, gh, img_w, img_h)
    if not seq:
        return 0
    n_visits = 0
    prev_inside = False
    for idx in seq:
        inside = bool(mask[idx])
        if inside and not prev_inside:
            n_visits += 1
        prev_inside = inside
    return max(0, n_visits - 1)


# ---------------------------------------------------------------------------
# Cursor / mouse metrics (Phase 2; schema/5 8-element path points only:
# [tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY] -- a partial-attention proxy after Raghunath).
# ---------------------------------------------------------------------------

def has_mouse_data(path):
    """``True`` iff ``path`` is non-empty and its points carry cursor data (8-element schema/5
    points) rather than the shorter schema/3-/4 point shapes. Checked on the first point only -- a
    single fragment's path is uniformly one shape (the recorder never mixes point lengths within
    one session), so this never needs to scan the whole list."""
    return bool(path) and len(path[0]) >= 8


def cursor_over_slide_pct(path):
    """Percentage of path points where the cursor was over the slide viewer.

    The recorder's off-viewer sentinel is exactly ``(mouseX, mouseY) == (-1, -1)``, so a point
    counts as "on-slide" iff ``mouseX != -1 or mouseY != -1`` (checking either coordinate with
    ``or`` also tolerates a malformed single ``-1`` defensively, though the recorder always writes
    both together): ``100 * count(on-slide) / len(path)``. Points without mouse data at all
    (``len(point) < 8``) are treated as off-slide (excluded from the on-slide count, but still
    counted in the denominator) -- in practice this never happens within one fragment since point
    shape is uniform per :func:`has_mouse_data`.

    ``0.0`` for an empty path. Callers should gate on :func:`has_mouse_data` before calling this at
    all -- ``metrics.csv`` leaves the column blank (not ``0.0``) for schema </5 fragments, which
    have no mouse data whatsoever, rather than reporting a spurious 0%."""
    if not path:
        return 0.0
    on_slide = sum(1 for p in path if len(p) >= 8 and (p[6] != -1 or p[7] != -1))
    return float(on_slide) / len(path) * 100.0


def mouse_viewport_coupling_px(path):
    """Median Euclidean distance (image px) between the cursor position (``mouseX``, ``mouseY``)
    and the viewport center (``cx``, ``cy``) of the *same* path point, over on-slide points only
    (see :func:`cursor_over_slide_pct`'s on-slide test) -- smaller means the cursor tracks the
    visible view more tightly (a partial-attention proxy: a cursor glued to the viewport center
    suggests active visual engagement with the current view, versus one that wanders or leaves
    the window while the viewport itself stays put).

    ``float("nan")`` (blank in ``metrics.csv``) if the path is empty or has zero on-slide points --
    undefined, not 0, since an all-off-slide path says nothing about cursor/viewport coupling."""
    if not path:
        return float("nan")
    dists = [
        math.sqrt((float(p[6]) - float(p[1])) ** 2 + (float(p[7]) - float(p[2])) ** 2)
        for p in path
        if len(p) >= 8 and (p[6] != -1 or p[7] != -1)
    ]
    if not dists:
        return float("nan")
    return float(np.median(dists))


# ---------------------------------------------------------------------------
# Tier 1 additive metrics (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md):
# A2 turn-angle directionality, A3 mouse kinematics (schema/5 only), A4 active fraction. Every
# function here degrades to ``float("nan")`` (blank in ``metrics.csv`` via ``analyze._sanitize_nan``)
# on its documented degenerate input -- never a raised exception -- matching the file's existing
# blank-vs-0.0 convention for path-only metrics that are genuinely undefined (not "zero") absent
# enough data.
# ---------------------------------------------------------------------------

def _headings_rad(path):
    """``atan2(Δy, Δx)`` heading (radians) for each consecutive segment ``path[i] -> path[i+1]``,
    length ``len(path)-1``. ``[]`` if ``path`` has fewer than 2 points."""
    n = len(path)
    if n < 2:
        return []
    heads = []
    for i in range(n - 1):
        dx = float(path[i + 1][1]) - float(path[i][1])
        dy = float(path[i + 1][2]) - float(path[i][2])
        heads.append(math.atan2(dy, dx))
    return heads


def _turn_angles_deg(path):
    """Turn angle (degrees, wrapped to ``(-180, 180]``) at each interior point: ``φ_i =
    wrapToPi(θ_{i+1} - θ_i)`` over the per-segment headings from :func:`_headings_rad`, where
    ``wrapToPi`` is computed as ``atan2(sin(Δ), cos(Δ))`` (not a hand-rolled modulo -- Python's
    ``%`` and R's ``%%`` disagree on the sign of a negative dividend, which would silently diverge
    the two toolkits' wrap-around behavior right at the ``±180°`` boundary).

    ``[]`` if ``path`` has fewer than 3 points -- a turn needs two consecutive segments, i.e. an
    "interior" point with both a preceding and a following segment.

    **Parity note:** ``math.degrees(wrapped)`` is CPython's ``x * (180.0 / Py_MATH_PI)`` -- a
    single multiply by a precomputed constant, not ``x * 180.0 / pi`` (left-to-right: multiply
    then divide) -- and an R port's degree conversion must use the same precomputed-constant form
    (``wrapped * (180.0 / pi)``) to stay bit-identical, since float multiplication/division is not
    associative and the two evaluation orders can round differently. This matters more here than
    for most float math in this module: :func:`turn_angle_entropy`'s ``floor((deg+180)/45)`` bin
    assignment is a *discontinuous* step function of this value, so even a 1-ULP difference right
    at a 45-degree-multiple boundary (the common case for exact-integer-pixel diagonal/
    axis-aligned pans) could flip a turn into a different bin and shift the whole entropy
    histogram by far more than the 1e-6 parity tolerance -- unlike :func:`mean_abs_turn_angle_deg`,
    which stays a continuous (1e-6-tolerant) function of the same value."""
    if not path or len(path) < 3:
        return []
    heads = _headings_rad(path)
    turns = []
    for i in range(len(heads) - 1):
        d = heads[i + 1] - heads[i]
        wrapped = math.atan2(math.sin(d), math.cos(d))
        turns.append(math.degrees(wrapped))
    return turns


def mean_abs_turn_angle_deg(path):
    """A2: mean of ``|turn angle|`` (degrees) over every interior point of the ordered viewport
    centers (see :func:`_turn_angles_deg`). ``float("nan")`` (blank) if the path has fewer than 3
    points -- movement-ecology/visual-search directionality measure, not part of the pinned
    Bylinskii saliency set."""
    turns = _turn_angles_deg(path)
    if not turns:
        return float("nan")
    return float(np.mean(np.abs(turns)))


def turn_angle_entropy(path):
    """A2: Shannon entropy (bits) of the turn-angle distribution (see :func:`_turn_angles_deg`),
    binned into 8 equal bins over ``(-180°, 180°]`` (bin ``i`` = ``[-180+45i, -180+45(i+1))``,
    with the ``+180°`` edge case folded into the last bin via ``min(7, ...)``), normalized by
    ``log2(8)`` to ``[0, 1]`` (0 = all turns in one bin/perfectly directional, 1 = turns spread
    evenly across all 8 bins). ``float("nan")`` (blank) if the path has fewer than 3 points."""
    turns = _turn_angles_deg(path)
    if not turns:
        return float("nan")
    bins = [0] * 8
    for d in turns:
        idx = int(min(7, math.floor((d + 180.0) / 45.0)))
        idx = max(0, idx)
        bins[idx] += 1
    total = float(len(turns))
    p = np.array([c / total for c in bins], dtype=float)
    ent = float(-np.sum(p * np.log2(p + EPS)))
    return ent / math.log2(8)


def mouse_path_length_px(path):
    """A3 (schema/5 only): sum of Euclidean distance (image px) between consecutive **on-slide**
    cursor points (``mouseX``, ``mouseY``), sentinel-aware -- a segment is skipped entirely (not
    bridged) if *either* endpoint is the off-viewer sentinel ``(-1, -1)`` (see
    :func:`cursor_over_slide_pct`'s on-slide test), so a single off-slide sample does not inflate
    the path length with a spurious long jump to/from the sentinel coordinate.

    ``float("nan")`` (blank) if the path doesn't carry schema/5 mouse data at all (see
    :func:`has_mouse_data`), or carries mouse data but has zero valid on-slide consecutive pairs
    (e.g. every sample is off-slide) -- distinct from ``0.0``, which would misleadingly read as "a
    measured, stationary cursor" rather than "nothing measurable"."""
    if not has_mouse_data(path):
        return float("nan")
    total = 0.0
    n_segments = 0
    for i in range(len(path) - 1):
        mx0, my0 = float(path[i][6]), float(path[i][7])
        mx1, my1 = float(path[i + 1][6]), float(path[i + 1][7])
        if not ((mx0 != -1 or my0 != -1) and (mx1 != -1 or my1 != -1)):
            continue
        total += math.sqrt((mx1 - mx0) ** 2 + (my1 - my0) ** 2)  # sqrt (not hypot) to bit-match R's sqrt(dx^2+dy^2)
        n_segments += 1
    if n_segments == 0:
        return float("nan")
    return total


def mouse_velocity_px_per_sec(path):
    """A3 (schema/5 only): median of (distance / Δt_sec) over consecutive **on-slide** cursor
    point pairs (same sentinel-aware segment rule as :func:`mouse_path_length_px` -- a segment
    touching ``(-1, -1)`` at either endpoint is skipped, never bridged). A segment with
    non-positive ``Δt`` is also skipped (not clamped to 0 velocity like
    :func:`_step_velocities_px_per_sec` -- an undefined-duration on-slide segment has no rate to
    report, so it is dropped from the median rather than counted as "no motion").

    ``float("nan")`` (blank) if the path doesn't carry schema/5 mouse data at all, or carries
    mouse data but has zero valid (on-slide, ``Δt > 0``) segments."""
    if not has_mouse_data(path):
        return float("nan")
    vels = []
    for i in range(len(path) - 1):
        mx0, my0 = float(path[i][6]), float(path[i][7])
        mx1, my1 = float(path[i + 1][6]), float(path[i + 1][7])
        if not ((mx0 != -1 or my0 != -1) and (mx1 != -1 or my1 != -1)):
            continue
        dt = float(path[i + 1][0]) - float(path[i][0])
        if dt <= 0:
            continue
        dist = math.sqrt((mx1 - mx0) ** 2 + (my1 - my0) ** 2)  # sqrt (not hypot) to bit-match R's sqrt(dx^2+dy^2)
        vels.append(dist / (dt / 1000.0))
    if not vels:
        return float("nan")
    return float(np.median(vels))


def active_fraction_pct(path, duration_ms):
    """A4: ``activeFractionPct = 100 * durationMs / (tRel_last - tRel_first)`` -- the fragment's
    recorded total dwell duration as a percentage of the scanpath's wall-clock span. **Not
    clamped** at 100% -- a value above 100% is a real signal (the recorder's dwell-weight
    accounting can exceed the raw tick-to-tick span for reasons upstream of this toolkit, e.g.
    overlapping dwell attribution), not a data error to be hidden (Mello-Thoms engagement
    confound).

    ``float("nan")`` (blank) if the path has fewer than 2 points, ``duration_ms`` is missing/not a
    real number, or the wall-clock span is zero or negative (degenerate/stationary-timestamp
    path)."""
    if not path or len(path) < 2:
        return float("nan")
    try:
        d = float(duration_ms)
    except (TypeError, ValueError):
        return float("nan")
    if d != d:  # NaN duration_ms
        return float("nan")
    span = float(path[-1][0]) - float(path[0][0])
    if span <= 0:
        return float("nan")
    return 100.0 * d / span


# ---------------------------------------------------------------------------
# Tier 3 C1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): I-DT (dispersion-
# threshold, Salvucci & Goldberg 2000) fixation extraction over the scanpath's viewport centers.
# Deterministic and index-based -- deliberately NOT DBSCAN (or any other library clustering
# routine), whose cluster assignment is not guaranteed identical across languages/library versions
# and would break the toolkit's 1e-6 Python<->R parity contract. Directional metrics
# (turnAngle/transitions/etc., above) stay tick-based and are untouched by this section -- fixations
# are an added lens on the scanpath, not a rebase of any existing metric.
# ---------------------------------------------------------------------------

#: Minimum time span (ms) a candidate fixation window must cover before its dispersion is even
#: tested -- Salvucci & Goldberg's duration threshold. An R port must use the identical literal.
MIN_FIXATION_MS = 250.0
#: Fraction of the window's starting viewport width used as its dispersion threshold (image px):
#: ``threshold = DISPERSION_FRAC * w_at_window_start``, where ``w_at_window_start`` is FIXED to the
#: ``w`` of the window's first point at the moment step 1 (see :func:`fixations_idt`) finds it, and
#: is never recomputed as the window later expands -- recomputing it from a later point would make
#: the threshold depend on exactly which points already got folded into the window, a circular
#: dependency the spec explicitly rules out. An R port must use the identical literal.
DISPERSION_FRAC = 0.25


def _window_dispersion(path, start, end):
    """``(max(cx)-min(cx)) + (max(cy)-min(cy))`` over ``path[start..end]`` inclusive (image px,
    0-based Python indices) -- the I-DT dispersion of one candidate fixation window."""
    cxs = [float(path[i][1]) for i in range(start, end + 1)]
    cys = [float(path[i][2]) for i in range(start, end + 1)]
    return (max(cxs) - min(cxs)) + (max(cys) - min(cys))


def _fixations_idt_run(path):
    """Tier 3 C1: the exact original I-DT window-growing loop (Salvucci & Goldberg 2000), factored
    out of :func:`fixations_idt` so it can be run independently over each of a path's IDLE-FREE
    "runs" (see that function's idle-boundary-splitting docstring for why). Operates on ``path``'s
    own 0-based index order -- ``start = 0`` is THIS run's own first point, not the original,
    un-split path's -- otherwise byte-for-byte the same algorithm as before the final-review idle
    fix (see :func:`fixations_idt`'s docstring for the full step-by-step description).

    Returns ``[]`` (never ``None``) if the run has fewer than 2 points -- a run boundary is not
    the same "insufficient path" case :func:`fixations_idt` itself guards with ``None``; the
    ``None``-vs-``[]`` sentinel distinction stays owned entirely by that caller."""
    n = len(path)
    if n < 2:
        return []
    out = []
    start = 0
    while start < n:
        t_start = float(path[start][0])
        end = start
        while end < n and (float(path[end][0]) - t_start) < MIN_FIXATION_MS:
            end += 1
        if end >= n:
            break
        w_start = float(path[start][3])
        threshold = DISPERSION_FRAC * w_start
        disp = _window_dispersion(path, start, end)
        if disp <= threshold:
            cur_end = end
            while cur_end + 1 < n:
                if _window_dispersion(path, start, cur_end + 1) <= threshold:
                    cur_end += 1
                else:
                    break
            cxs = [float(path[i][1]) for i in range(start, cur_end + 1)]
            cys = [float(path[i][2]) for i in range(start, cur_end + 1)]
            out.append({
                "startMs": t_start,
                "durationMs": float(path[cur_end][0]) - t_start,
                "centerImageX": sum(cxs) / len(cxs),
                "centerImageY": sum(cys) / len(cys),
                "nPoints": cur_end - start + 1,
            })
            start = cur_end + 1
        else:
            start += 1
    return out


def fixations_idt(path):
    """Tier 3 C1: I-DT (dispersion-threshold, Salvucci & Goldberg 2000) fixation detector over the
    scanpath's ordered viewport centers -- per-point data ``(t=path[i][0], cx=path[i][1],
    cy=path[i][2], w=path[i][3])``. Deterministic and index-based (see the module-section note
    above for why this is NOT DBSCAN).

    Algorithm (:func:`_fixations_idt_run`), operating on ``path``'s existing point order (no
    sorting/resampling), starting with ``start = 0``:

    1. Grow the window ``[start, end]`` one point at a time until its time span (``t[end] -
       t[start]``) first reaches :data:`MIN_FIXATION_MS` -- the smallest possible window that could
       qualify as a fixation. If the path runs out of points before the span is reached (``end``
       would run past the last index), STOP -- no more fixations.
    2. The window's dispersion threshold is fixed **once**, from the ``w`` of the window's first
       point (``path[start][3]``) at the moment this minimal window is found:
       ``threshold = DISPERSION_FRAC * w_at_window_start``. It is never recomputed as the window
       later expands (see :data:`DISPERSION_FRAC`'s docstring).
    3. If the minimal window's dispersion (:func:`_window_dispersion`) is ``<= threshold``: expand
       the window one point at a time for as long as the NEXT point keeps the (whole, from
       ``start``) window's dispersion ``<= threshold``; the moment adding the next point would
       exceed the threshold (or the path runs out of points), STOP expanding and emit one fixation
       over the final window: ``startMs = t[start]``, ``durationMs = t[final_end] - t[start]``,
       ``centerImageX = mean(cx over the window)``, ``centerImageY = mean(cy over the window)``,
       ``nPoints = final_end - start + 1``. Advance ``start`` to ``final_end + 1`` (past the whole
       emitted window, so its points are never reused by a later fixation) and go to step 1.
    4. Else (the minimal window is already over threshold -- no fixation starts here): advance
       ``start`` by exactly ONE point (not past the whole rejected window) and go to step 1 -- this
       lets a fixation start at ``start + 1`` even though it was itself part of the rejected window.

    **Idle-gap hard boundary (final-review fix):** a step flagged idle by :func:`idle_step_mask`
    (``dt > IDLE_GAP_MS`` -- Tier 2 B1's "reader stepped away, viewport didn't move" gap) is a
    HARD window boundary a fixation may never bridge -- before this fix, the algorithm above ran
    over the WHOLE path with no idle awareness at all, so a >60s away-gap (with an otherwise
    near-stationary viewport either side of it -- exactly the case a low-dispersion window would
    happily keep expanding through) got silently bridged into ONE giant fixation spanning the
    entire gap, corrupting every summary stat (``nFixations``/``meanFixationMs``/
    ``medianFixationMs``/``sdFixationMs``) and leaving ``fixationsPerMin`` (whose idle-excluded
    ``activeSpanMs`` denominator already existed, Tier 2 B1) numerically inconsistent with a
    numerator that was NOT idle-aware. The fix splits ``path`` at every idle step into maximal
    idle-free "runs" and calls the unmodified :func:`_fixations_idt_run` algorithm independently on
    each run in order, concatenating the results -- a window can then never grow across a run
    boundary (each run is processed as if it were the whole path), and each run's own
    "path runs out of points" termination (step 1's existing STOP, already handled above) does
    double duty as "close the window at the idle boundary: emit it if it already qualified as a
    fixation over ``[start, end]``, else discard the never-yet-qualified partial window" -- exactly
    the required idle-boundary semantics, with no new termination logic needed. A run of exactly 1
    point (an idle step immediately after another idle step, or at the very start/end of ``path``)
    contributes zero fixations (:func:`_fixations_idt_run`'s own <2-point guard), never a crash.
    For a path with NO idle step at all (the common case), this is a no-op -- the single "run" is
    the whole path, identical to the pre-fix behavior.

    Returns ``None`` if ``path`` has fewer than 2 points -- fixation extraction is not computable at
    all, distinct from an empty list (see below); this is the sentinel every metrics.csv column
    below (:func:`n_fixations` etc.) maps to a blank cell. Returns ``[]`` (a real, well-defined
    "zero fixations found" -- NOT blank) if the path has >=2 points but no window ever qualifies,
    e.g. a path shorter than :data:`MIN_FIXATION_MS` in total span, or one whose dispersion never
    drops to or below its own threshold. Otherwise returns a list of dicts (in start-index/time
    order): ``{"startMs", "durationMs", "centerImageX", "centerImageY", "nPoints"}``.

    Every arithmetic step here (max/min, sum/len mean, ``<``/``<=`` comparisons) is plain, order-
    independent float arithmetic over already-float-coerced inputs -- ``durationMs`` is an exact
    integer difference of integer millisecond timestamps (so mean/median/sd of durations are exact
    across languages), and an R port using the same step-by-step index loop (not a vectorized/
    library shortcut) reproduces the identical fixation sequence to 1e-6 (only the
    ``centerImageX``/``centerImageY`` mean of non-integer real coordinates carries the sub-1e-6
    long-double-vs-double summation wobble a mean over few, small-magnitude values can show)."""
    if not path or len(path) < 2:
        return None
    n = len(path)
    idle = idle_step_mask(path)
    out = []
    run_start = 0
    for i in range(n - 1):
        if idle[i]:
            out.extend(_fixations_idt_run(path[run_start:i + 1]))
            run_start = i + 1
    out.extend(_fixations_idt_run(path[run_start:n]))
    return out


def n_fixations(fixations):
    """``len(fixations)`` (an ``int``, including ``0`` for a genuinely empty-but-computed list), or
    ``float("nan")`` (blank in ``metrics.csv``) if ``fixations`` is ``None`` (the path had fewer
    than 2 points -- see :func:`fixations_idt`). Takes the already-computed
    ``fixations_idt(path)`` result (not ``path`` itself) so a caller that needs the list anyway
    (e.g. ``fixations_<slug>.csv``) computes it exactly once."""
    if fixations is None:
        return float("nan")
    return len(fixations)


def mean_fixation_ms(fixations):
    """Mean ``durationMs`` over ``fixations`` (see :func:`fixations_idt`). ``float("nan")`` (blank)
    if ``fixations`` is ``None`` or empty -- the mean of zero fixations is undefined, not ``0.0``."""
    if not fixations:
        return float("nan")
    durs = [f["durationMs"] for f in fixations]
    return float(sum(durs) / len(durs))


def median_fixation_ms(fixations):
    """Median ``durationMs`` over ``fixations``. ``float("nan")`` (blank) if ``fixations`` is
    ``None`` or empty."""
    if not fixations:
        return float("nan")
    return float(np.median([f["durationMs"] for f in fixations]))


def sd_fixation_ms(fixations):
    """Sample standard deviation (``ddof=1``, matching R's default ``sd()``) of ``durationMs``
    over ``fixations`` -- same ``ddof=1`` convention as :func:`zoom_variance`. ``float("nan")``
    (blank) if ``fixations`` is ``None`` or has fewer than 2 fixations -- a single fixation (or
    none) has no defensible sample spread."""
    if not fixations or len(fixations) < 2:
        return float("nan")
    return float(np.std([f["durationMs"] for f in fixations], ddof=1))


def fixations_per_min(fixations, path):
    """``len(fixations) / active_minutes``, where ``active_minutes = active_span_ms(path) /
    60000.0`` -- Tier 2 B1's idle-excluded active span, the SAME denominator convention
    :func:`scanning_rate_px_per_min`/:func:`drilling_rate_per_min` use, so idle "stepped away" time
    does not inflate the rate. ``float("nan")`` (blank) if ``fixations`` is ``None`` (path had
    fewer than 2 points) or the active span is non-positive (a 0/0 rate has no defensible value,
    matching :func:`avg_zoom_log2_w`'s NaN-for-undefined convention rather than
    :func:`drilling_rate_per_min`'s 0.0-for-zero-duration one). ``0.0`` (not blank) when
    ``fixations`` is a genuinely empty list (zero fixations found) but the active span is positive
    -- a well-defined rate of zero events over a real duration."""
    if fixations is None:
        return float("nan")
    active_min = active_span_ms(path) / 60000.0
    if active_min <= 0:
        return float("nan")
    return float(len(fixations)) / active_min


# ---------------------------------------------------------------------------
# Tier 3 C2/C3/C4 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md):
# C2 mouse-dwell map (analogous to raster_from_path, but point-based -- deposits a step's dt into
# the single cell containing the cursor, not the viewport rectangle), C3 DTW trajectory similarity
# (deterministic DP over z-normalized viewport centers -- NOT Frechet), C4 segment-level linearity
# (mean linearity() over sub-paths split at the session's own top-hotspot cells). All additive; no
# existing metric's formula or value changes.
# ---------------------------------------------------------------------------

def mouse_cursor_over_slide(point):
    """``True`` iff an 8-element schema/5 point's cursor was on-slide -- the shared sentinel test
    (``mouseX != -1 or mouseY != -1``) used by :func:`cursor_over_slide_pct`,
    :func:`mouse_path_length_px`, and :func:`mouse_raster_from_path`, factored out here so all
    three apply the identical rule (a point with fewer than 8 elements has no mouse data at all
    and is treated as off-slide)."""
    return len(point) >= 8 and (float(point[6]) != -1 or float(point[7]) != -1)


def mouse_raster_from_path(path, img_w, img_h, gw, gh):
    """Tier 3 C2: rebuild a ``gh x gw`` dwell-ms grid (flat, row-major) from the scanpath's
    schema/5 cursor positions (``mouseX``, ``mouseY``), analogous to :func:`raster_from_path` but
    **point-based rather than rectangle-based** -- there is no cursor "extent" to spread a step's
    weight across, so the whole step's ``dt`` (see :func:`step_durations_ms`) is deposited into
    the single grid cell containing the cursor position of **point i** (the step's start point;
    same step-i-owns-the-interval convention :func:`raster_from_path` uses for the viewport
    rectangle), using the SAME floor/clamp cell-mapping :func:`raster_from_path` itself falls back
    to when a viewport rectangle collapses entirely off-image (and that :func:`visited_sequence`
    uses throughout): ``col = floor(mouseX/img_w*gw)``, ``row = floor(mouseY/img_h*gh)``, each
    clamped to ``[0, gw-1]``/``[0, gh-1]``.

    A step is skipped entirely (contributes no weight, not bridged/interpolated) if:

    - it is flagged idle by :func:`idle_step_mask` (``dt > IDLE_GAP_MS`` -- Tier 2 B1, the same
      exclusion :func:`raster_from_path` applies), or its ``dt <= 0``;
    - *either* endpoint's cursor is off-slide (the ``(-1, -1)`` sentinel -- same sentinel-aware
      rule :func:`mouse_path_length_px`/:func:`mouse_velocity_px_per_sec` use: a segment touching
      the sentinel at either point is dropped whole, never bridged across).

    Returns **``None``** (blank ``mouseCoveragePct``/``mouseEntropy`` in ``metrics.csv``) in two
    cases, matching the spec's "blank when no mouse data / no on-slide points":

    - ``path`` doesn't carry schema/5 mouse data at all (see :func:`has_mouse_data`);
    - ``path`` carries mouse data but has **zero on-slide points** anywhere in it (every sample is
      the off-viewer sentinel) -- there is nothing measurable, not a real "nothing was ever
      touched" grid.

    Otherwise returns a flat ``(gw*gh,)`` float array -- **all-zero is a legitimate result**, not
    blank, whenever there is at least one on-slide point but zero valid on-slide *consecutive
    pairs* to deposit a step's dt into (e.g. a single on-slide sample surrounded by off-slide
    ones, or a 1-point path) -- this mirrors the file's existing "0.0 for a well-defined but empty
    computation" convention (e.g. :func:`coverage`/:func:`entropy` on an all-zero grid), distinct
    from the two `None` cases above where nothing is measurable at all.

    **Zero-size-grid guard (final-review fix):** also returns ``None`` if ``gw<=0`` or ``gh<=0``
    (a schema-valid fragment recording ``gridWidth``/``gridHeight`` of ``0``) -- this is a THIRD
    "nothing measurable" case, folded into the same ``None`` sentinel the two above already use.
    Without this guard, a zero-size grid's column/row clamp (``min(max(...), gw-1)`` with
    ``gw-1=-1``) always resolves to ``-1``, so ``grid[row, -1] += dt`` on a genuinely size-``(gh,
    0)`` array raises ``IndexError`` the moment there is at least one valid on-slide step,
    aborting the whole batch run."""
    if not has_mouse_data(path):
        return None
    if not any(mouse_cursor_over_slide(p) for p in path):
        return None
    gw, gh = int(gw), int(gh)
    if gw <= 0 or gh <= 0:
        return None
    img_w = float(img_w) if img_w else 1.0
    img_h = float(img_h) if img_h else 1.0
    grid = np.zeros((gh, gw), dtype=float)
    if len(path) < 2:
        return grid.flatten()
    dts = step_durations_ms(path)
    idle = idle_step_mask(path)
    for i, dt in enumerate(dts):
        if dt <= 0:
            continue
        if idle[i]:
            continue
        if not (mouse_cursor_over_slide(path[i]) and mouse_cursor_over_slide(path[i + 1])):
            continue
        mx, my = float(path[i][6]), float(path[i][7])
        ccx = min(max(mx, 0.0), img_w - EPS)
        ccy = min(max(my, 0.0), img_h - EPS)
        col = min(max(int(math.floor(ccx / img_w * gw)), 0), gw - 1)
        row = min(max(int(math.floor(ccy / img_h * gh)), 0), gh - 1)
        grid[row, col] += dt
    return grid.flatten()


def _zscore(values):
    """Tier 3 C3: sample-sd (``ddof=1``) z-normalization of a 1-D sequence: ``z = (v - mean(v)) /
    sd(v)``. ``sd`` is treated as ``0.0`` (rather than raising/NaN) whenever the sequence has
    fewer than 2 elements OR is genuinely constant -- in either case every z-value is ``0.0``
    (a "no information on this axis" value that still participates safely in
    :func:`dtw_distance`'s local-cost Euclidean formula, rather than a division-by-zero NaN
    poisoning the whole DP)."""
    arr = np.asarray(values, dtype=float)
    n = arr.size
    if n == 0:
        return arr
    mean = float(arr.mean())
    sd = float(np.std(arr, ddof=1)) if n >= 2 else 0.0
    if sd == 0.0:
        return np.zeros(n, dtype=float)
    return (arr - mean) / sd


def dtw_distance(path_a, path_b):
    """Tier 3 C3: Dynamic Time Warping distance between two scanpaths' viewport-center ``(cx,
    cy)`` sequences -- a resolution-independent complement to the grid-cell
    :func:`levenshtein_sim`. Deliberately the standard DP formulation (deterministic, exactly
    reproducible across languages), NOT Frechet distance or any library shortcut.

    Pinned algorithm (must match an R port to 1e-6):

    1. Each sequence's ``cx`` and ``cy`` are z-normalized **independently per axis, per sequence**
       via :func:`_zscore` (sample sd, ``ddof=1``; ``0.0`` for a constant/degenerate axis).
    2. Local cost between point ``i`` of sequence A and point ``j`` of sequence B:
       ``sqrt((zAx_i - zBx_j)**2 + (zAy_i - zBy_j)**2)`` -- deliberately
       ``math.sqrt(dx**2 + dy**2)``, **not** ``math.hypot``, to bit-match an R port's
       ``sqrt(dx^2+dy^2)`` (same rationale as :func:`mouse_path_length_px`'s identical
       sqrt-not-hypot convention documented elsewhere in this file).
    3. Standard DTW dynamic program over the full ``nA x nB`` cost matrix: ``D[0][0] =
       cost(0,0)``; first row/column are cumulative sums along that row/column; every other cell
       ``D[i][j] = cost(i,j) + min(D[i-1][j], D[i][j-1], D[i-1][j-1])``.
    4. ``dtwDistance = D[nA-1][nB-1]`` -- the **raw accumulated cost**, deliberately **NOT**
       normalized by path/warping-path length (unlike some DTW variants), to keep the definition
       simple and exactly reproducible. This makes it a resolution/scale-independent (thanks to
       z-normalization) but still LENGTH-dependent quantity -- a longer pair of paths accumulates
       more total cost even at equal per-step similarity. Documented, not a bug.

    A **self-comparison** (``path_a is path_b``, e.g. the ``scanpath_<slug>.csv`` diagonal row) is
    always exactly ``0.0`` without any special-cased branch: with identical z-normalized
    sequences, the straight ``i == j`` alignment has cost ``0.0`` at every step (a valid warping
    path), and every local cost is ``>= 0`` (a Euclidean distance), so ``0.0`` is also the DP's
    global minimum -- this falls out of the DP itself.

    ``float("nan")`` (blank in ``scanpath_<slug>.csv``) if either path is empty/``None`` --
    matches the file's existing "blank when either session lacks a path" convention."""
    if not path_a or not path_b:
        return float("nan")
    ax = _zscore([float(p[1]) for p in path_a])
    ay = _zscore([float(p[2]) for p in path_a])
    bx = _zscore([float(p[1]) for p in path_b])
    by = _zscore([float(p[2]) for p in path_b])
    n_a, n_b = len(ax), len(bx)

    def cost(i, j):
        dx = ax[i] - bx[j]
        dy = ay[i] - by[j]
        return math.sqrt(dx * dx + dy * dy)

    d = [[0.0] * n_b for _ in range(n_a)]
    d[0][0] = cost(0, 0)
    for j in range(1, n_b):
        d[0][j] = d[0][j - 1] + cost(0, j)
    for i in range(1, n_a):
        d[i][0] = d[i - 1][0] + cost(i, 0)
    for i in range(1, n_a):
        for j in range(1, n_b):
            d[i][j] = cost(i, j) + min(d[i - 1][j], d[i][j - 1], d[i - 1][j - 1])
    return float(d[n_a - 1][n_b - 1])


def mean_segment_linearity(path, grid, gw, gh, img_w, img_h, top_n=5):
    """Tier 3 C4: mean :func:`linearity` over the sub-paths a scanpath splits into at the
    session's own top-``top_n`` dwell hotspot cells (reusing :func:`top_hotspots` on the
    session's own NATIVE recorded dwell ``grid``/``gw``/``gh`` -- the same triple
    ``hotspots_<slug>.csv`` uses) -- a complement to the whole-path :func:`linearity` (Roa-Peña
    reports whole-path ~0.41 vs a between-hotspot segment ~0.8: segments transiting between two
    attended regions tend to be far straighter than the whole meandering scanpath).

    **Pinned, deterministic segmentation** (uniform hotspot-based; the ROI-entry variant the spec
    also mentions is a separate function, :func:`mean_segment_linearity_roi`
    (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3) -- segmented at annotation
    boundaries instead of hotspot cells):

    1. Compute the top-``top_n`` hotspot cells of ``grid`` via :func:`top_hotspots` (deterministic
       tie-break already built in).
    2. Walk every RAW path point (**no** run-length dedup of the WALK itself, unlike
       :func:`visited_sequence` -- the 1:1 correspondence between a path INDEX and its point must
       be preserved here, since a "boundary" marks an actual index to slice segments at) and map
       it to a grid cell via the same floor/clamp convention used throughout this module. A point
       is a candidate "boundary hit" iff its cell is one of the top-``top_n`` hotspot cells.
    3. **Consecutive same-hotspot-cell hits are collapsed to a single boundary** (bug fix, see
       below): a candidate hit at index ``i`` becomes an actual boundary iff it is the FIRST
       candidate hit found at all, OR the immediately-preceding boundary's cell differs from this
       hit's cell. Concretely: track the cell of the most-recently-added boundary; a hit whose
       cell matches it is skipped (it is inside the same dwell run, not a new visit); a hit whose
       cell differs (a different hotspot, or the same hotspot re-entered after visiting a
       different one) becomes a new boundary, keeping its REAL path index (never re-indexed) so
       segment endpoints stay actual path points. This marks a boundary per DISTINCT hotspot
       *visit*, not per raw sample.
    4. Segments are the sub-paths **between consecutive (deduped) boundary points** (boundary
       index ``b[k]`` to boundary index ``b[k+1]``, inclusive of both endpoints -- so a boundary
       point is shared by its two adjacent segments, per the spec). Any portion of the path BEFORE
       the first boundary or AFTER the last boundary is not part of any segment (excluded, not
       counted as a leading/trailing segment) -- "sub-paths BETWEEN consecutive boundary points"
       is read literally. If fewer than 2 (deduped) boundary points are found at all, there are
       zero segments.
    5. :func:`linearity` (unchanged, reused as-is) is computed on every segment with ``>= 2``
       points; ``meanSegmentLinearity`` is the mean of those per-segment linearities.

    **Bug fix (2026-07, C4 dedup):** this used to mark EVERY raw sample landing in a top-``top_n``
    hotspot cell as its own boundary, with no run-length dedup. During a dwell (a run of
    consecutive samples in the same hotspot cell -- the normal shape of real viewing), each
    sample became its own boundary, creating trivial within-dwell 2-point segments whose
    ``linearity == 1.0`` unconditionally (a straight line is the only possible shape between 2
    points) -- a long dwell run could contribute many such trivial segments, skewing
    ``meanSegmentLinearity`` toward 1.0 and defeating its purpose (Roa-Peña: TRANSIT segments
    between distinct attended regions should be measured, not within-dwell noise). Step 3 above
    now collapses a maximal run of consecutive same-hotspot-cell hits down to its FIRST index, so
    a "boundary" marks a distinct hotspot visit -- the dwell itself contributes no trivial
    segments, and only genuine transits between (the same or different) hotspots are measured.
    Two DIFFERENT hotspot cells adjacent in the path are still two separate boundaries (a
    zero-or-short transit segment between them), which is correct and unaffected by this fix.

    ``float("nan")`` (blank) if: fewer than 2 hotspot cells are found at all (only possible for a
    degenerate grid with fewer than 2 cells total, e.g. ``gw*gh < 2``); ``path`` has fewer than 2
    points; fewer than 2 DISTINCT-hotspot boundaries are found in the (deduped) path (zero
    segments); or every segment found has fewer than 2 points (impossible by construction here,
    since consecutive boundary indices are always distinct path positions -- kept as an explicit
    guard for defensiveness)."""
    hotspots = top_hotspots(grid, gw, gh, top_n)
    if len(hotspots) < 2:
        return float("nan")
    if not path or len(path) < 2:
        return float("nan")
    gw_i, gh_i = int(gw), int(gh)
    img_w_f = float(img_w) if img_w else 1.0
    img_h_f = float(img_h) if img_h else 1.0
    hotspot_cells = set(row * gw_i + col for row, col, _ in hotspots)
    boundary_idx = []
    last_boundary_cell = None
    for i, pt in enumerate(path):
        cx, cy = float(pt[1]), float(pt[2])
        col = min(max(int(math.floor(cx / img_w_f * gw_i)), 0), gw_i - 1)
        row = min(max(int(math.floor(cy / img_h_f * gh_i)), 0), gh_i - 1)
        cell = row * gw_i + col
        if cell not in hotspot_cells:
            continue
        if last_boundary_cell is None or cell != last_boundary_cell:
            boundary_idx.append(i)
        last_boundary_cell = cell
    if len(boundary_idx) < 2:
        return float("nan")
    linearities = []
    for j in range(len(boundary_idx) - 1):
        seg = path[boundary_idx[j]: boundary_idx[j + 1] + 1]
        if len(seg) >= 2:
            linearities.append(linearity(seg))
    if not linearities:
        return float("nan")
    return float(np.mean(linearities))


def mean_segment_linearity_roi(path, mask, gw, gh, img_w, img_h):
    """PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): mean :func:`linearity`
    over the sub-paths a scanpath splits into at **annotation-ROI-entry** boundaries, reusing the
    reader's own union annotation mask (:func:`blinded_focus.analyze.rasterize_feature_collection`
    -- the SAME mask/native ``(gw, gh)`` :func:`dwell_in_mask_pct`/:func:`annotation_reentry_count`
    already use). A complement to the hotspot-based :func:`mean_segment_linearity` (Roa-Peña
    whole-path vs region-transit): this variant segments at entries into the reader's own
    annotated region instead of at dwell-hotspot visits.

    **Boundary definition** (deliberately simpler than :func:`mean_segment_linearity`'s
    cell-identity dedup -- here the state is a single inside/outside boolean, not a specific cell
    identity, so an outside-excursion is *required* between any two boundaries and the dedup falls
    out of the state machine for free, needing no explicit "same cell as last boundary" check):

    1. Walk every RAW path point (**no** run-length dedup of the walk itself, same rationale as
       :func:`mean_segment_linearity` -- the 1:1 correspondence between a path INDEX and its point
       must be preserved so a "boundary" marks an actual index to slice segments at) and map it to
       a grid cell via the same floor/clamp convention used throughout this module
       (:func:`visited_sequence`'s mapping). A point is **inside** iff its cell is ``True`` in
       ``mask``.
    2. A path point at index ``i`` is an **ROI-entry boundary** iff it is inside AND EITHER
       ``i == 0`` (already inside at the very start) OR the immediately-preceding point's cell was
       **outside** -- an outside-to-inside transition. This is checked purely via the inside/
       outside boolean, not cell identity, so a maximal run of consecutive inside points (whether
       it stays in one cell or wanders between several inside cells without ever leaving the
       region) naturally collapses to exactly ONE boundary, at the run's first index -- no
       separate dedup step is needed (unlike :func:`mean_segment_linearity`'s per-cell tracking):
       reaching a second boundary structurally requires at least one intervening OUTSIDE point to
       reset the state, so consecutive boundary indices are never adjacent.
    3. Segments are the sub-paths **between consecutive boundary points** (boundary index ``b[k]``
       to boundary index ``b[k+1]``, inclusive of both endpoints, same "shared endpoint" convention
       as :func:`mean_segment_linearity`). Any portion of the path before the first boundary or
       after the last is excluded, not counted as a leading/trailing segment. Fewer than 2
       boundaries at all -> zero segments.
    4. :func:`linearity` (unchanged, reused as-is) is computed on every segment with ``>= 2``
       points; ``meanSegmentLinearityROI`` is the mean of those per-segment linearities.

    ``float("nan")`` (blank) if: ``mask`` has no ``True`` cells (no annotation on this slide --
    nothing to enter); ``path`` has fewer than 2 points; fewer than 2 ROI-entry boundaries are
    found in the path (zero segments -- includes both "never entered" and "entered exactly
    once, never re-entered"); or every segment found has fewer than 2 points (unreachable by the
    structural argument in step 2 above -- two boundaries are always >= 2 path-indices apart, so
    every segment spans >= 3 points -- kept as an explicit guard for defensiveness, mirroring
    :func:`mean_segment_linearity`'s own analogous guard)."""
    mask = np.asarray(mask).astype(bool).flatten()
    if not mask.any():
        return float("nan")
    if not path or len(path) < 2:
        return float("nan")
    gw_i, gh_i = int(gw), int(gh)
    img_w_f = float(img_w) if img_w else 1.0
    img_h_f = float(img_h) if img_h else 1.0
    boundary_idx = []
    prev_inside = False
    for i, pt in enumerate(path):
        cx, cy = float(pt[1]), float(pt[2])
        col = min(max(int(math.floor(cx / img_w_f * gw_i)), 0), gw_i - 1)
        row = min(max(int(math.floor(cy / img_h_f * gh_i)), 0), gh_i - 1)
        cell = row * gw_i + col
        inside = bool(mask[cell])
        if inside and not prev_inside:
            boundary_idx.append(i)
        prev_inside = inside
    if len(boundary_idx) < 2:
        return float("nan")
    linearities = []
    for j in range(len(boundary_idx) - 1):
        seg = path[boundary_idx[j]: boundary_idx[j + 1] + 1]
        if len(seg) >= 2:
            linearities.append(linearity(seg))
    if not linearities:
        return float("nan")
    return float(np.mean(linearities))


# ---------------------------------------------------------------------------
# Inter-observer agreement
# ---------------------------------------------------------------------------

def mean_pairwise_cc(grids):
    """Mean Pearson CC over all unique ``(i<j)`` pairs of equal-shape grids. NaN if fewer than 2
    grids are given."""
    grids = list(grids)
    n = len(grids)
    if n < 2:
        return float("nan")
    vals = [cc(grids[i], grids[j]) for i in range(n) for j in range(i + 1, n)]
    return float(np.mean(vals))


def icc(grids):
    """ICC(2,1): two-way random-effects, absolute-agreement, single-rater/measurement
    intraclass correlation (Shrout & Fleiss 1979; McGraw & Wong 1996, Case 2), computed via the
    standard two-way ANOVA formula:

    ``ICC(2,1) = (MSR - MSE) / (MSR + (k-1)*MSE + k*(MSC-MSE)/n)``

    where rows = cells (subjects, n of them) and columns = sessions (raters, k of them); MSR/MSC/
    MSE are the row/column/error mean squares from the two-way ANOVA decomposition of the
    cell-by-session dwell matrix. Grids must already be resampled to a common shape. NaN if fewer
    than 2 sessions or fewer than 2 cells.
    """
    data = np.array([np.asarray(g, dtype=float).flatten() for g in grids]).T  # (n_cells, k_sessions)
    n, k = data.shape
    if k < 2 or n < 2:
        return float("nan")
    grand_mean = data.mean()
    row_means = data.mean(axis=1)
    col_means = data.mean(axis=0)
    ss_total = float(((data - grand_mean) ** 2).sum())
    ss_rows = float(k * ((row_means - grand_mean) ** 2).sum())
    ss_cols = float(n * ((col_means - grand_mean) ** 2).sum())
    ss_error = ss_total - ss_rows - ss_cols
    ms_rows = ss_rows / (n - 1)
    ms_cols = ss_cols / (k - 1)
    ms_error = ss_error / ((n - 1) * (k - 1)) if (n - 1) * (k - 1) > 0 else 0.0
    denom = ms_rows + (k - 1) * ms_error + k * (ms_cols - ms_error) / n
    if denom == 0:
        return float("nan")
    return float((ms_rows - ms_error) / denom)
