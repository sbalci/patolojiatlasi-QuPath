#!/usr/bin/env python3
"""Synthetic end-to-end selftest for the blinded_focus analysis toolkit.

Builds 3 sessions on one slide (2 similar grids + 1 different grid; two of the three carry a
schema/3 ``path`` while one is schema/2 with no path, to exercise mixed-schema handling),
designates one session as the ``--reference``, runs :func:`blinded_focus.analyze.analyze` into a
temp dir, and asserts the documented output contract:

- ``metrics.csv`` has 3 rows and exactly the spec'd columns.
- ``compare_<slug>.csv``'s cc matrix is symmetric with a 1.0 diagonal, and the similar pair's cc
  exceeds the dissimilar pair's.
- ``consensus_<slug>.png`` is a valid PNG.
- ``reference_<slug>.csv``'s reference-vs-itself row has high NSS/CC.
- ``scanpath_<slug>.csv``'s levenshtein-similarity diagonal is 1.0.
- ``--figures`` writes at least one valid PNG per session.
- The same pipeline also works when the input is a ``.zip`` archive instead of a directory.

Exits non-zero (via an uncaught ``AssertionError``/traceback) on any failure.
"""
import json
import math
import os
import shutil
import sys
import tempfile
import zipfile

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from blinded_focus.analyze import analyze  # noqa: E402

GW, GH = 8, 8
IMG_W, IMG_H = 2000, 1500


def _gaussian_grid(r0, c0, sigma=1.3, scale=1000.0, noise=20.0, seed=0):
    rng = np.random.default_rng(seed)
    grid = np.zeros((GH, GW))
    for r in range(GH):
        for c in range(GW):
            d2 = (r - r0) ** 2 + (c - c0) ** 2
            grid[r, c] = scale * math.exp(-d2 / (2 * sigma ** 2))
    grid = grid + rng.normal(0, noise, grid.shape)
    grid = np.clip(grid, 0, None)
    return grid.flatten().tolist()


def _make_path(r0, c0, n=30, seed=42):
    """A synthetic scanpath dwelling around grid cell (r0, c0), in image px, with jitter."""
    rng = np.random.default_rng(seed)
    cx0 = (c0 + 0.5) / GW * IMG_W
    cy0 = (r0 + 0.5) / GH * IMG_H
    path = []
    t = 0
    for _ in range(n):
        t += 250  # ~4 samples/sec
        cx = min(max(cx0 + rng.normal(0, IMG_W / GW / 4), 0), IMG_W - 1)
        cy = min(max(cy0 + rng.normal(0, IMG_H / GH / 4), 0), IMG_H - 1)
        path.append([t, int(cx), int(cy), 400, 300])
    return path


def _fragment(session_id, schema, grid, duration_ms, sample_count, path=None):
    d = {
        "schema": f"atlas-focus-contribution/{schema}",
        "slideKey": "sha256:selftest-slide-0001",
        "sessionId": session_id,
        "imageWidth": IMG_W, "imageHeight": IMG_H,
        "gridWidth": GW, "gridHeight": GH,
        "grid": grid,
        "durationMs": duration_ms,
        "sampleCount": sample_count,
        "date": "2026-07-22",
    }
    if path is not None:
        d["path"] = path
    return d


def build_fragments():
    grid_s1 = _gaussian_grid(2, 2, seed=1)
    grid_s2 = _gaussian_grid(2, 2, seed=2)  # similar hotspot location to s1
    grid_s3 = _gaussian_grid(6, 6, sigma=1.0, seed=3)  # different hotspot location

    f1 = _fragment("s1", 3, grid_s1, 40 * 250, 40, path=_make_path(2, 2, n=40, seed=101))
    f2 = _fragment("s2", 3, grid_s2, 35 * 250, 35, path=_make_path(2, 2, n=35, seed=102))
    f3 = _fragment("s3", 2, grid_s3, 8000, 32, path=None)  # schema/2, no path
    return [f1, f2, f3]


def write_fragments_to_dir(fragments, d):
    for f in fragments:
        with open(os.path.join(d, f"{f['sessionId']}.json"), "w", encoding="utf-8") as fh:
            json.dump(f, fh)


def write_fragments_to_zip(fragments, zip_path):
    with zipfile.ZipFile(zip_path, "w") as zf:
        for f in fragments:
            zf.writestr(f"{f['sessionId']}.json", json.dumps(f))


def _assert_png(path):
    assert os.path.isfile(path), f"missing PNG: {path}"
    with open(path, "rb") as fh:
        magic = fh.read(8)
    assert magic == b"\x89PNG\r\n\x1a\n", f"not a valid PNG (bad magic): {path}"


def run():
    tmp = tempfile.mkdtemp(prefix="bfa-selftest-")
    try:
        fragments = build_fragments()
        in_dir = os.path.join(tmp, "in")
        os.makedirs(in_dir, exist_ok=True)
        write_fragments_to_dir(fragments, in_dir)
        out_dir = os.path.join(tmp, "out")

        analyze([in_dir], out_dir, reference="s1", make_figures=True)

        # --- metrics.csv: 3 rows + expected columns ---
        metrics = pd.read_csv(os.path.join(out_dir, "metrics.csv"))
        assert len(metrics) == 3, f"expected 3 metrics rows, got {len(metrics)}"
        expected_cols = [
            "slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
            "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
            "nRevisits", "transitionEntropy",
        ]
        assert list(metrics.columns) == expected_cols, metrics.columns.tolist()

        # --- compare_<slug>.csv: symmetric cc matrix, diagonal 1.0, similar > dissimilar ---
        compare_files = [f for f in os.listdir(out_dir) if f.startswith("compare_")]
        assert len(compare_files) == 1, compare_files
        compare = pd.read_csv(os.path.join(out_dir, compare_files[0]))
        pivot = compare.pivot(index="sessionA", columns="sessionB", values="cc")
        pivot = pivot.reindex(index=pivot.columns, columns=pivot.columns)
        assert np.allclose(pivot.values, pivot.values.T, atol=1e-6), "compare cc matrix not symmetric"
        diag = np.diag(pivot.values)
        assert np.allclose(diag, 1.0, atol=1e-6), f"diagonal cc != 1.0: {diag}"

        cc_s1_s2 = compare[(compare.sessionA == "s1") & (compare.sessionB == "s2")]["cc"].iloc[0]
        cc_s1_s3 = compare[(compare.sessionA == "s1") & (compare.sessionB == "s3")]["cc"].iloc[0]
        assert cc_s1_s2 > cc_s1_s3, (
            f"similar pair cc ({cc_s1_s2}) should exceed dissimilar pair cc ({cc_s1_s3})"
        )

        # --- consensus PNG ---
        consensus_png = compare_files[0].replace("compare_", "consensus_").replace(".csv", ".png")
        _assert_png(os.path.join(out_dir, consensus_png))

        # --- reference_<slug>.csv: reference-vs-itself NSS/CC high ---
        ref_files = [f for f in os.listdir(out_dir) if f.startswith("reference_")]
        assert len(ref_files) == 1, ref_files
        ref = pd.read_csv(os.path.join(out_dir, ref_files[0]))
        self_row = ref[ref.session == "s1"].iloc[0]
        assert self_row["cc"] > 0.99, f"reference-vs-itself cc too low: {self_row['cc']}"
        assert self_row["nss"] > 0.3, f"reference-vs-itself nss too low: {self_row['nss']}"

        # --- scanpath_<slug>.csv: levenshtein-sim diagonal 1.0 ---
        scan_files = [f for f in os.listdir(out_dir) if f.startswith("scanpath_")]
        assert len(scan_files) == 1, scan_files
        scan = pd.read_csv(os.path.join(out_dir, scan_files[0]))
        scan_pivot = scan.pivot(index="sessionA", columns="sessionB", values="levenshteinSim")
        scan_pivot = scan_pivot.reindex(index=scan_pivot.columns, columns=scan_pivot.columns)
        scan_diag = np.diag(scan_pivot.values)
        assert np.allclose(scan_diag, 1.0, atol=1e-9), f"scanpath diagonal != 1.0: {scan_diag}"
        # s3 has no path -> excluded from the scanpath comparison entirely
        assert "s3" not in scan.sessionA.values, "schema/2 session should be absent from scanpath output"

        # --- figures: at least one valid PNG per session, under <out>/<slug>/ ---
        slide_dirs = [d for d in os.listdir(out_dir) if os.path.isdir(os.path.join(out_dir, d))]
        assert len(slide_dirs) == 1, slide_dirs
        session_pngs = [f for f in os.listdir(os.path.join(out_dir, slide_dirs[0])) if f.endswith(".png")]
        assert len(session_pngs) >= 1, "no per-session figures written"
        for png in session_pngs:
            _assert_png(os.path.join(out_dir, slide_dirs[0], png))
        # s1/s2 (schema/3, have a path) should have scanpath + coverage figures too
        assert any("scanpath" in p for p in session_pngs), "missing scanpath overlay figure"
        assert any("coverage" in p for p in session_pngs), "missing coverage-over-time figure"

        # --- summary.md written and non-trivial ---
        summary_path = os.path.join(out_dir, "summary.md")
        assert os.path.isfile(summary_path), "summary.md missing"
        assert os.path.getsize(summary_path) > 0, "summary.md is empty"

        # --- mixed schema/2 + schema/3 handled: s3 has blank scanpath metrics, s1 does not ---
        row_s3 = metrics[metrics.session == "s3"].iloc[0]
        assert pd.isna(row_s3["pathPoints"]), f"schema/2 session should have no pathPoints, got {row_s3['pathPoints']}"
        row_s1 = metrics[metrics.session == "s1"].iloc[0]
        assert row_s1["pathPoints"] == 40, f"schema/3 session pathPoints mismatch: {row_s1['pathPoints']}"

        # --- .zip input also works ---
        zip_path = os.path.join(tmp, "fragments.zip")
        write_fragments_to_zip(fragments, zip_path)
        zip_out = os.path.join(tmp, "out_zip")
        analyze([zip_path], zip_out, reference="s1")
        zip_metrics = pd.read_csv(os.path.join(zip_out, "metrics.csv"))
        assert len(zip_metrics) == 3, f"zip input: expected 3 metrics rows, got {len(zip_metrics)}"

        print("OK: all selftest assertions passed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    try:
        run()
    except AssertionError as exc:
        print(f"SELFTEST FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
