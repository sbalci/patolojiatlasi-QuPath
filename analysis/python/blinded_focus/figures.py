"""Matplotlib figure builders for blinded-focus analysis.

Uses the ``Agg`` backend so it runs headless (CI / no display). Each function saves a PNG to
``out`` and returns nothing. Heatmaps use ``magma`` (perceptually-uniform, print-safe); the
difference map uses a diverging colormap centered at zero.
"""
import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from . import metrics as m  # noqa: E402


def _grid2d(grid, gw, gh):
    return np.asarray(grid, dtype=float).reshape(int(gh), int(gw))


def heatmap(grid, gw, gh, title, out):
    """Save a heatmap PNG of the grid (magma colormap + colorbar)."""
    g = _grid2d(grid, gw, gh)
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(g, cmap="magma", origin="upper", aspect="auto")
    ax.set_title(title)
    ax.set_xlabel("grid col")
    ax.set_ylabel("grid row")
    fig.colorbar(im, ax=ax, label="dwell")
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    plt.close(fig)


def scanpath_overlay(grid, gw, gh, path, img_w, img_h, title, out):
    """Heatmap background + time-graded scanpath line (start = green circle, end = red x).

    ``path`` is the raw ``[t, cx, cy, w, h]`` list (image px); it is mapped to grid-cell
    coordinates the same way :func:`blinded_focus.metrics.visited_sequence` does, for overlay
    onto the heatmap's grid axes.
    """
    gw, gh = int(gw), int(gh)
    g = _grid2d(grid, gw, gh)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.imshow(g, cmap="magma", origin="upper", aspect="auto", extent=[0, gw, gh, 0])
    if path:
        xs = [float(p[1]) / img_w * gw for p in path]
        ys = [float(p[2]) / img_h * gh for p in path]
        n = len(xs)
        colors = plt.cm.cool(np.linspace(0, 1, max(n, 1)))
        for i in range(1, n):
            ax.plot(xs[i - 1:i + 1], ys[i - 1:i + 1], color=colors[i], linewidth=1.2)
        ax.scatter([xs[0]], [ys[0]], color="lime", s=70, marker="o", zorder=5, label="start")
        ax.scatter([xs[-1]], [ys[-1]], color="red", s=70, marker="x", zorder=5, label="end")
        ax.legend(loc="upper right", fontsize=8)
    ax.set_title(title)
    ax.set_xlim(0, gw)
    ax.set_ylim(gh, 0)
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    plt.close(fig)


def consensus(grids, gw, gh, title, out):
    """Heatmap of the mean of normalised (÷max) grids (already resampled to a common ``(gw,gh)``)."""
    mean_grid = np.mean([m.normalise_max(g) for g in grids], axis=0)
    heatmap(mean_grid, gw, gh, title, out)


def difference(grid, consensus_grid, gw, gh, title, out):
    """Diverging heatmap of ``grid - consensus_grid`` (both already normalised + resampled)."""
    g = _grid2d(grid, gw, gh)
    c = _grid2d(consensus_grid, gw, gh)
    d = g - c
    lim = float(np.max(np.abs(d))) if d.size else 1.0
    lim = lim if lim > 0 else 1.0
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(d, cmap="RdBu_r", origin="upper", aspect="auto", vmin=-lim, vmax=lim)
    ax.set_title(title)
    fig.colorbar(im, ax=ax, label="session - consensus")
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    plt.close(fig)


def coverage_over_time(path, gw, gh, img_w, img_h, title, out):
    """Cumulative fraction of grid cells visited so far, plotted against relative time (ms)."""
    gw, gh = int(gw), int(gh)
    fig, ax = plt.subplots(figsize=(6, 4))
    if not path:
        ax.set_title(title + " (no path data)")
        fig.savefig(out, dpi=110)
        plt.close(fig)
        return
    seen = set()
    ts, cov = [], []
    total_cells = gw * gh
    for p in path:
        t, cx, cy = float(p[0]), float(p[1]), float(p[2])
        col = min(max(int(cx / img_w * gw), 0), gw - 1)
        row = min(max(int(cy / img_h * gh), 0), gh - 1)
        seen.add(row * gw + col)
        ts.append(t)
        cov.append(len(seen) / total_cells * 100.0)
    ax.plot(ts, cov)
    ax.set_xlabel("time (ms)")
    ax.set_ylabel("coverage (%)")
    ax.set_title(title)
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    plt.close(fig)
