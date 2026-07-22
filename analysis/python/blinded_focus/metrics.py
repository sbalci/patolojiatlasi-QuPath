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

    0 for an all-zero grid. Used as ``nHotspots`` in ``metrics.csv`` — a simple, reproducible
    "distinct attended regions" count (not part of the pinned Bylinskii metric set, but a natural
    complement to ``peakDwell``).
    """
    gw, gh = int(gw), int(gh)
    g = np.asarray(grid, dtype=float).reshape(gh, gw)
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
    ma, mb = a.max(), b.max()
    am = a > (thresh * ma) if ma > 0 else np.zeros_like(a, dtype=bool)
    bm = b > (thresh * mb) if mb > 0 else np.zeros_like(b, dtype=bool)
    union = np.logical_or(am, bm).sum()
    if union == 0:
        return 0.0
    inter = np.logical_and(am, bm).sum()
    return float(inter) / float(union)


# ---------------------------------------------------------------------------
# Scanpath (schema/3 "path" only)
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
