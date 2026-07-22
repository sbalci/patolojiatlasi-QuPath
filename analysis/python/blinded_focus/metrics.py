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
"""
import math
from collections import Counter

import numpy as np
from scipy import ndimage
from scipy.stats import rankdata

#: Small constant added to denominators/logs to avoid division-by-zero / log(0).
EPS = 1e-12


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
    """
    gw, gh, tw, th = int(gw), int(gh), int(tw), int(th)
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
    """Top-``n`` ``(row, col, value)`` cells by dwell value, descending."""
    gw, gh = int(gw), int(gh)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
    flat_idx = np.argsort(g.flatten())[::-1][:n]
    out = []
    for idx in flat_idx:
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
    """
    a = np.asarray(a, dtype=float).flatten()
    b = np.asarray(b, dtype=float).flatten()
    if a.std() == 0 or b.std() == 0:
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
        total += math.hypot(x1 - x0, y1 - y0)
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

    Returns a flat ``(gw*gh,)`` float array (dwell-ms per cell), or **``None``** if ``path`` has
    fewer than 2 points (a Δt requires two points; single-point/empty paths carry no raster).
    """
    if not path or len(path) < 2:
        return None
    gw, gh = int(gw), int(gh)
    img_w = float(img_w) if img_w else 1.0
    img_h = float(img_h) if img_h else 1.0
    dts = step_durations_ms(path)
    grid = np.zeros((gh, gw), dtype=float)
    for i, dt in enumerate(dts):
        if dt <= 0:
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
    """
    has_ds = len(point) >= 6
    if has_ds:
        ds_milli = float(point[5])
        if ds_milli <= 0:
            ds_milli = 1000.0
        if base_mag is not None and float(base_mag) > 0:
            return float(base_mag) / (ds_milli / 1000.0)
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
    **normalized by the path's total duration** (``t[-1] - t[0]``, in minutes; the design doc
    does not pin this denominator, so this is documented as the deliberate choice: total session
    time, not time-spent-scanning, so the rate is comparable across sessions with different
    scanning/drilling mixes). ``0.0`` if the path has fewer than 2 points or non-positive total
    duration."""
    if not path or len(path) < 2:
        return 0.0
    duration_min = (float(path[-1][0]) - float(path[0][0])) / 60000.0
    if duration_min <= 0:
        return 0.0
    changed = _step_zoom_changed(path, base_mag, img_w)
    pan = 0.0
    for i in range(len(path) - 1):
        if not changed[i]:
            x0, y0 = float(path[i][1]), float(path[i][2])
            x1, y1 = float(path[i + 1][1]), float(path[i + 1][2])
            pan += math.hypot(x1 - x0, y1 - y0)
    return pan / duration_min


def drilling_rate_per_min(path, base_mag=None, img_w=None):
    """"Drilling" rate (events/min): count of zoom-change steps (see :func:`_step_zoom_changed`)
    per minute of the path's total duration (same denominator as
    :func:`scanning_rate_px_per_min`). ``0.0`` if the path has fewer than 2 points or non-positive
    total duration."""
    if not path or len(path) < 2:
        return 0.0
    duration_min = (float(path[-1][0]) - float(path[0][0])) / 60000.0
    if duration_min <= 0:
        return 0.0
    changed = _step_zoom_changed(path, base_mag, img_w)
    return float(np.count_nonzero(changed)) / duration_min


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
        out.append(math.hypot(x1 - x0, y1 - y0) / (dt / 1000.0))
    return out


def path_velocity_px_per_sec(path):
    """Median of per-step velocities (see :func:`_step_velocities_px_per_sec`). ``0.0`` (not
    NaN/NA) if the path has fewer than 2 points."""
    vels = _step_velocities_px_per_sec(path)
    return float(np.median(vels)) if vels else 0.0


def linearity(path):
    """Net displacement (straight-line first-point -> last-point distance) divided by the total
    scanpath length (:func:`scanpath_length_px`, sum of consecutive-step distances) -- 1.0 for a
    dead-straight path, near 0 for a path that wanders back on itself. ``0.0`` if the total length
    is 0 (degenerate/empty/stationary path -- avoids division by zero)."""
    if not path or len(path) < 2:
        return 0.0
    x0, y0 = float(path[0][1]), float(path[0][2])
    x1, y1 = float(path[-1][1]), float(path[-1][2])
    net = math.hypot(x1 - x0, y1 - y0)
    total = scanpath_length_px(path)
    return net / total if total > 0 else 0.0


def search_focus_ratio(path, base_mag=None, img_w=None):
    """Fraction of dwell-time spent in "focused" steps, Δt-weighted (same
    step-i-owns-point-i convention as :func:`raster_from_path`/:func:`step_durations_ms`).

    A step (``path[i] -> path[i+1]``) counts as **focused** iff *either*:

    - ``point_zoom(path[i]) >= median(per-step zooms for this path)`` (high zoom), **or**
    - its velocity (:func:`_step_velocities_px_per_sec`) ``<= median(per-step velocities for this
      path)`` (low velocity -- includes stationary/paused steps, which get velocity 0 and are
      always <= the median).

    Both thresholds are the path's own median (data-driven per session, not a fixed absolute
    pixel/magnification cutoff -- documented here for an R port to reuse ``median()`` identically).
    ``0.0`` if the path has fewer than 2 points or zero total Δt."""
    if not path or len(path) < 2:
        return 0.0
    n = len(path) - 1
    zooms = np.array([point_zoom(path[i], base_mag, img_w) for i in range(n)], dtype=float)
    dts = np.array(step_durations_ms(path), dtype=float)
    vels = np.array(_step_velocities_px_per_sec(path), dtype=float)
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
        math.hypot(float(p[6]) - float(p[1]), float(p[7]) - float(p[2]))
        for p in path
        if len(p) >= 8 and (p[6] != -1 or p[7] != -1)
    ]
    if not dists:
        return float("nan")
    return float(np.median(dists))


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
