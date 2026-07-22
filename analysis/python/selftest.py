#!/usr/bin/env python3
"""Synthetic end-to-end selftest for the blinded_focus analysis toolkit.

Builds 4 sessions on one slide, spanning every accepted schema and covering the Phase-2
annotation/cursor additions:

- ``s1`` — schema **/5**: 8-element path points (``[t, cx, cy, w, h, dsMilli, mouseX, mouseY]``,
  varying ``dsMilli`` + a known ``baseMagnification``, mouse mostly on-slide with a deliberate
  off-slide subset) *and* an ``annotations`` GeoJSON FeatureCollection (one rectangle covering
  grid cells rows 1-3 / cols 1-3) that overlaps the session's own dwell/path center, so
  ``dwellInAnnotationPct`` has something to concentrate into.
- ``s4`` — schema **/4**: 6-element path points (``dsMilli``, no mouse; unknown
  ``baseMagnification``, exercising ``point_zoom``'s ds-only fallback branch), *with* an
  ``annotations`` FeatureCollection (the same rectangle as ``s1``, for cross-user IoU/coincidence)
  and a scanpath deliberately built to bounce in and out of that rectangle (so
  ``annotationReentryCount`` has a known non-trivial answer).
- ``s2`` — schema **/3**: 5-element path points (no ``dsMilli``, w-proxy zoom fallback), no
  ``annotations`` at all.
- ``s3`` — schema **/2**: no ``path``, no ``annotations``.

One session (``s1``) is designated as ``--reference``. Runs :func:`blinded_focus.analyze.analyze`
into a temp dir and asserts the documented output contract:

- ``metrics.csv`` has 4 rows and exactly the spec'd columns, including the Phase-1 zoom/navigation
  family and the Phase-2 annotation/cursor family (populated for the sessions that have the
  relevant data, blank — never a crash — for the ones that don't).
- ``compare_<slug>.csv``'s cc matrix is symmetric with a 1.0 diagonal, and the similar pair's cc
  exceeds the dissimilar pair's.
- ``consensus_<slug>.png`` is a valid PNG.
- ``reference_<slug>.csv``'s reference-vs-itself row has high NSS/CC.
- ``scanpath_<slug>.csv``'s levenshtein-similarity diagonal is 1.0 (over the 3 path-carrying
  sessions s1/s2/s4; s3 is absent).
- ``magbands_<slug>.csv`` is written for the path-carrying sessions (s1, s2, s4).
- ``annotations_<slug>.csv``'s IoU matrix is symmetric, with a 1.0 self-diagonal for the sessions
  that actually drew an annotation (s1, s4 — identical rectangles, so their cross-IoU is also
  1.0), and a coincidence level of exactly 1.0 on the diagonal-reuse row (s1 and s4's identical
  annotated regions mean every visited cell is shared by both — impossible under the pre-fix
  whole-grid-denominator formula, which would instead report ~0.14).
- ``raster_from_path`` produces a non-empty grid for a >=2-point path and ``None`` for a
  0/1-point path; ``magnificationPercentage`` is in ``[0, 1]``; ``scanningRatePxPerMin`` is
  non-negative; the schema/3 5-element (w-proxy) path is handled without crashing.
- Direct, pipeline-independent regression asserts for the two literature-review bug fixes:
  ``magnification_percentage`` no longer counts held-zoom ties, and ``coincidence_level``
  normalizes by the visited footprint (>=1 reader), not the whole grid.
- Direct, pipeline-independent regression assert for the annotation-mask union fix:
  ``rasterize_feature_collection`` on two overlapping/nested annotation Features (an outer
  rectangle + a smaller rectangle nested inside it) must classify the overlap zone as **inside**
  the union mask -- the pre-fix pooled-rings even-odd test misclassified a point inside two
  overlapping Features as outside (even parity).
- ``--figures --res 256`` writes at least one valid PNG per session, including a
  scanpath-rasterized fine heatmap.
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

from blinded_focus.analyze import analyze, rasterize_feature_collection  # noqa: E402
from blinded_focus import io as bf_io  # noqa: E402
from blinded_focus import metrics as bf_metrics  # noqa: E402

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
    """A synthetic 5-element (schema/3) scanpath dwelling around grid cell (r0, c0), in image px,
    with jitter. Constant ``w``/``h`` -> constant w-proxy zoom (exercises the fallback without
    varying it -- the varying-zoom exercise is :func:`_make_path_v4`/:func:`_make_path_v5`)."""
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


def _ds_schedule(n):
    """Shared varying-``dsMilli`` schedule (two "scanning" segments separated by a "drilling"
    jump) reused by both the schema/4 and schema/5 synthetic-path builders below, so
    ``zoomVariance``/``zoomRange``/``scanningRatePxPerMin``/``drillingRatePerMin`` all have
    something non-trivial to compute for both."""
    return [2000] * 12 + [500] * 12 + [3000] * max(n - 24, 0)


def _make_path_v4(r0, c0, n=40, seed=101):
    """A synthetic 6-element (schema/4) scanpath dwelling around grid cell (r0, c0), with a
    varying ``dsMilli`` schedule (see :func:`_ds_schedule`) -- no mouse data."""
    rng = np.random.default_rng(seed)
    cx0 = (c0 + 0.5) / GW * IMG_W
    cy0 = (r0 + 0.5) / GH * IMG_H
    ds_schedule = _ds_schedule(n)
    path = []
    t = 0
    for i in range(n):
        t += 250  # ~4 samples/sec
        ds = ds_schedule[i] if i < len(ds_schedule) else ds_schedule[-1]
        cx = min(max(cx0 + rng.normal(0, IMG_W / GW / 4), 0), IMG_W - 1)
        cy = min(max(cy0 + rng.normal(0, IMG_H / GH / 4), 0), IMG_H - 1)
        path.append([t, int(cx), int(cy), 400, 300, ds])
    return path


def _make_path_v5(r0, c0, n=40, seed=101):
    """A synthetic 8-element (schema/5) scanpath: same varying-``dsMilli`` schedule as
    :func:`_make_path_v4`, dwelling around grid cell (r0, c0), with each point additionally
    carrying a cursor position (``mouseX``, ``mouseY``) -- on-slide (near the viewport center,
    small jitter) for most points, off-slide ``(-1, -1)`` sentinel for every 5th point -- so both
    ``cursorOverSlidePct`` (< 100%) and ``mouseViewportCouplingPx`` (computed only over on-slide
    points) have something non-trivial to exercise."""
    rng = np.random.default_rng(seed)
    cx0 = (c0 + 0.5) / GW * IMG_W
    cy0 = (r0 + 0.5) / GH * IMG_H
    ds_schedule = _ds_schedule(n)
    path = []
    t = 0
    for i in range(n):
        t += 250  # ~4 samples/sec
        ds = ds_schedule[i] if i < len(ds_schedule) else ds_schedule[-1]
        cx = min(max(cx0 + rng.normal(0, IMG_W / GW / 4), 0), IMG_W - 1)
        cy = min(max(cy0 + rng.normal(0, IMG_H / GH / 4), 0), IMG_H - 1)
        if i % 5 == 0:
            mouse_x, mouse_y = -1, -1  # off-slide sentinel, every 5th tick
        else:
            mouse_x = int(min(max(cx + rng.normal(0, 20), 0), IMG_W - 1))
            mouse_y = int(min(max(cy + rng.normal(0, 20), 0), IMG_H - 1))
        path.append([t, int(cx), int(cy), 400, 300, ds, mouse_x, mouse_y])
    return path


def _make_path_bouncing(inside_rc, outside_rc, n=40, seed=301, block=5):
    """A synthetic 6-element (schema/4) scanpath alternating in blocks of ``block`` samples
    between a grid cell inside the annotated region (``inside_rc``) and one outside it
    (``outside_rc``) -- built to exercise ``annotation_reentry_count``'s multi-visit run-counting
    with a known, non-trivial answer (several separate "inside" runs). With ``n=40``, ``block=5``
    this produces 8 alternating blocks (4 inside-runs), so the expected
    ``reentryCount = 4 - 1 = 3``."""
    rng = np.random.default_rng(seed)
    ir, ic = inside_rc
    orow, ocol = outside_rc
    cx_in, cy_in = (ic + 0.5) / GW * IMG_W, (ir + 0.5) / GH * IMG_H
    cx_out, cy_out = (ocol + 0.5) / GW * IMG_W, (orow + 0.5) / GH * IMG_H
    ds_schedule = [2000] * (n // 2) + [3000] * (n - n // 2)
    path = []
    t = 0
    for i in range(n):
        t += 250
        ds = ds_schedule[i]
        inside = (i // block) % 2 == 0
        cx0, cy0 = (cx_in, cy_in) if inside else (cx_out, cy_out)
        cx = min(max(cx0 + rng.normal(0, 10), 0), IMG_W - 1)
        cy = min(max(cy0 + rng.normal(0, 10), 0), IMG_H - 1)
        path.append([t, int(cx), int(cy), 400, 300, ds])
    return path


def _make_annotations_fc(row0, row1, col0, col1):
    """Build a GeoJSON FeatureCollection with one rectangular Polygon annotation covering the
    inclusive grid-cell range ``[row0, row1] x [col0, col1]``, in image-px coordinates -- mirrors
    the shape QuPath's GsonTools/FeatureCollection.wrap emits (Polygon geometry + minimal
    properties: ``name``, ``classification.name``), enough to exercise the rasterizer/area
    functions under test."""
    x0 = col0 / GW * IMG_W
    x1 = (col1 + 1) / GW * IMG_W
    y0 = row0 / GH * IMG_H
    y1 = (row1 + 1) / GH * IMG_H
    return {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {
                "type": "Polygon",
                "coordinates": [[[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]],
            },
            "properties": {"name": "tumor", "classification": {"name": "Tumor"}},
        }],
    }


def _fragment(
    session_id, schema, grid, duration_ms, sample_count, path=None,
    base_magnification=None, path_truncated=None, annotations=None,
):
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
    # Only schema/4+ fragments carry these (mirrors the real recorder); the /2,/3 fixtures below
    # never pass these kwargs, so metrics.csv should render them blank for those sessions.
    if base_magnification is not None:
        d["baseMagnification"] = base_magnification
    if path_truncated is not None:
        d["pathTruncated"] = path_truncated
    if annotations is not None:
        d["annotations"] = annotations
    return d


def build_fragments():
    grid_s1 = _gaussian_grid(2, 2, seed=1)
    grid_s2 = _gaussian_grid(2, 2, seed=2)  # similar hotspot location to s1
    grid_s3 = _gaussian_grid(6, 6, sigma=1.0, seed=3)  # different hotspot location
    grid_s4 = _gaussian_grid(4, 4, sigma=1.2, seed=4)  # yet another location, outside the shared annotation rect

    shared_annotation = _make_annotations_fc(1, 3, 1, 3)  # rows 1-3, cols 1-3 -> overlaps s1's dwell/path center

    # s1: schema/5, 8-element path (varying dsMilli + mouse, some off-slide) + a known
    # baseMagnification + an annotation overlapping its own dwell center.
    f1 = _fragment(
        "s1", 5, grid_s1, 40 * 250, 40,
        path=_make_path_v5(2, 2, n=40, seed=101),
        base_magnification=40.0, path_truncated=False,
        annotations=shared_annotation,
    )
    # s2: schema/3, 5-element path (w-proxy zoom fallback; no dsMilli/baseMagnification/annotations).
    f2 = _fragment("s2", 3, grid_s2, 35 * 250, 35, path=_make_path(2, 2, n=35, seed=102))
    # s3: schema/2, no path, no annotations at all.
    f3 = _fragment("s3", 2, grid_s3, 8000, 32, path=None)
    # s4: schema/4, 6-element path (varying dsMilli, no mouse, unknown baseMagnification ->
    # exercises point_zoom's ds-only fallback branch) that deliberately bounces in/out of the
    # SAME annotation rectangle as s1 (for cross-user IoU/coincidence + a known reentry count).
    f4 = _fragment(
        "s4", 4, grid_s4, 40 * 250, 40,
        path=_make_path_bouncing((2, 2), (6, 6), n=40, seed=301),
        path_truncated=False,
        annotations=shared_annotation,
    )
    return [f1, f2, f3, f4]


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

        # --- schema/5 is accepted at load time ---
        assert fragments[0]["schema"] == "atlas-focus-contribution/5"
        assert "atlas-focus-contribution/5" in bf_io.SCHEMAS

        in_dir = os.path.join(tmp, "in")
        os.makedirs(in_dir, exist_ok=True)
        write_fragments_to_dir(fragments, in_dir)
        out_dir = os.path.join(tmp, "out")

        analyze([in_dir], out_dir, reference="s1", make_figures=True, res=256)

        # --- metrics.csv: 4 rows + expected columns (Phase 1 + Phase 2) ---
        metrics = pd.read_csv(os.path.join(out_dir, "metrics.csv"))
        assert len(metrics) == 4, f"expected 4 metrics rows, got {len(metrics)}"
        expected_cols = [
            "slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
            "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
            "nRevisits", "transitionEntropy",
            "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
            "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
            "linearity", "searchFocusRatio", "baseMagnification", "pathTruncated",
            "nAnnotations", "annotatedAreaPx", "dwellInAnnotationPct", "annotationReentryCount",
            "enrichmentRatio", "cursorOverSlidePct", "mouseViewportCouplingPx",
        ]
        assert list(metrics.columns) == expected_cols, metrics.columns.tolist()
        assert metrics["dwellInAnnotationPct"].between(0, 100).all(), metrics["dwellInAnnotationPct"].tolist()

        row_s1 = metrics[metrics.session == "s1"].iloc[0]
        row_s2 = metrics[metrics.session == "s2"].iloc[0]
        row_s3 = metrics[metrics.session == "s3"].iloc[0]
        row_s4 = metrics[metrics.session == "s4"].iloc[0]

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

        # --- scanpath_<slug>.csv: levenshtein-sim diagonal 1.0 (s1, s2, s4 -- all have a path) ---
        scan_files = [f for f in os.listdir(out_dir) if f.startswith("scanpath_")]
        assert len(scan_files) == 1, scan_files
        scan = pd.read_csv(os.path.join(out_dir, scan_files[0]))
        scan_pivot = scan.pivot(index="sessionA", columns="sessionB", values="levenshteinSim")
        scan_pivot = scan_pivot.reindex(index=scan_pivot.columns, columns=scan_pivot.columns)
        scan_diag = np.diag(scan_pivot.values)
        assert np.allclose(scan_diag, 1.0, atol=1e-9), f"scanpath diagonal != 1.0: {scan_diag}"
        # s3 has no path -> excluded from the scanpath comparison entirely
        assert "s3" not in scan.sessionA.values, "schema/2 session should be absent from scanpath output"
        assert set(scan.sessionA.unique()) == {"s1", "s2", "s4"}, scan.sessionA.unique()

        # --- figures: at least one valid PNG per session, under <out>/<slug>/ ---
        slide_dirs = [d for d in os.listdir(out_dir) if os.path.isdir(os.path.join(out_dir, d))]
        assert len(slide_dirs) == 1, slide_dirs
        session_pngs = [f for f in os.listdir(os.path.join(out_dir, slide_dirs[0])) if f.endswith(".png")]
        assert len(session_pngs) >= 1, "no per-session figures written"
        for png in session_pngs:
            _assert_png(os.path.join(out_dir, slide_dirs[0], png))
        # s1/s2/s4 all have a path -> scanpath + coverage figures too
        assert any("scanpath" in p for p in session_pngs), "missing scanpath overlay figure"
        assert any("coverage" in p for p in session_pngs), "missing coverage-over-time figure"
        # Phase 1: scanpath-rasterized fine heatmap at --res (256 here), written per path session
        assert any("scanpath_raster" in p for p in session_pngs), (
            "missing scanpath-rasterized fine heatmap figure"
        )

        # --- magbands_<slug>.csv: written for the path-carrying sessions (s1, s2, s4) ---
        magband_files = [f for f in os.listdir(out_dir) if f.startswith("magbands_")]
        assert len(magband_files) == 1, magband_files
        magbands = pd.read_csv(os.path.join(out_dir, magband_files[0]))
        assert set(magbands["session"].unique()) == {"s1", "s2", "s4"}, (
            f"magbands sessions mismatch: {magbands['session'].unique()}"
        )

        # --- annotations_<slug>.csv (Phase 2): symmetric IoU, 1.0 diagonal for annotated
        # sessions, 1.0 cross-IoU for s1/s4 (identical rectangles), and a coincidence level of
        # exactly 1.0 (the fixed visited-footprint formula; ~0.14 under the old whole-grid one) ---
        ann_files = [f for f in os.listdir(out_dir) if f.startswith("annotations_")]
        assert len(ann_files) == 1, ann_files
        ann = pd.read_csv(os.path.join(out_dir, ann_files[0]))
        ann_pivot = ann.pivot(index="sessionA", columns="sessionB", values="iou")
        ann_pivot = ann_pivot.reindex(index=ann_pivot.columns, columns=ann_pivot.columns)
        assert np.allclose(ann_pivot.values, ann_pivot.values.T, atol=1e-6), (
            "annotations iou matrix not symmetric"
        )
        # s1/s4 both drew the same non-empty rectangle -> self-IoU and cross-IoU are both 1.0.
        # s2/s3 drew nothing -> their self-IoU is 0.0 by iou()'s documented empty-union
        # convention -- deliberately NOT asserted to be 1.0 here (that would be a different,
        # unrequested change to iou()'s general semantics).
        for sid in ("s1", "s4"):
            self_iou = ann_pivot.loc[sid, sid]
            assert abs(self_iou - 1.0) < 1e-6, f"{sid} annotation self-IoU should be 1.0, got {self_iou}"
        assert abs(ann_pivot.loc["s1", "s4"] - 1.0) < 1e-6, (
            f"s1/s4 drew the same rectangle -> cross-IoU should be 1.0, got {ann_pivot.loc['s1', 's4']}"
        )
        s1_diag = ann[(ann.sessionA == "s1") & (ann.sessionB == "s1")].iloc[0]
        assert not pd.isna(s1_diag["coincidenceLevel"]), "annotations_<slug>.csv diagonal coincidenceLevel missing"
        assert abs(s1_diag["coincidenceLevel"] - 1.0) < 1e-6, (
            f"expected annotation coincidenceLevel == 1.0 (fixed visited-footprint denominator), "
            f"got {s1_diag['coincidenceLevel']}"
        )

        # --- summary.md written and non-trivial ---
        summary_path = os.path.join(out_dir, "summary.md")
        assert os.path.isfile(summary_path), "summary.md missing"
        assert os.path.getsize(summary_path) > 0, "summary.md is empty"

        # --- mixed schema/2 + schema/3 + schema/4 + schema/5 handled: s3 has blank scanpath metrics ---
        assert pd.isna(row_s3["pathPoints"]), f"schema/2 session should have no pathPoints, got {row_s3['pathPoints']}"
        assert row_s1["pathPoints"] == 40, f"schema/5 session pathPoints mismatch: {row_s1['pathPoints']}"

        # --- Phase 1 zoom/navigation columns: populated for path sessions, blank for s3 ---
        for col in (
            "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
            "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
            "linearity", "searchFocusRatio",
        ):
            assert pd.isna(row_s3[col]), f"schema/2 session should have blank {col}, got {row_s3[col]}"
            assert not pd.isna(row_s1[col]), f"schema/5 session (s1) missing {col}"
            assert not pd.isna(row_s2[col]), f"schema/3 session (s2, w-proxy) missing {col}"
            assert not pd.isna(row_s4[col]), f"schema/4 session (s4) missing {col}"

        for row, label in ((row_s1, "s1"), (row_s2, "s2"), (row_s4, "s4")):
            assert 0.0 <= row["magnificationPercentage"] <= 1.0, (label, row["magnificationPercentage"])
            assert row["scanningRatePxPerMin"] >= 0, (label, row["scanningRatePxPerMin"])
            assert row["drillingRatePerMin"] >= 0, (label, row["drillingRatePerMin"])
        # s1's and s4's dsMilli schedules vary (2000 -> 500 -> 3000, or 2000 -> 3000) -> non-zero
        # zoom spread; s2's w is constant -> zero zoom variance/range (still "handled", just
        # degenerate).
        assert row_s1["zoomVariance"] > 0, f"s1 has varying dsMilli, expected zoomVariance > 0: {row_s1['zoomVariance']}"
        assert row_s1["zoomRange"] > 0, f"s1 has varying dsMilli, expected zoomRange > 0: {row_s1['zoomRange']}"
        assert row_s4["zoomVariance"] > 0, f"s4 has varying dsMilli, expected zoomVariance > 0: {row_s4['zoomVariance']}"
        assert row_s4["zoomRange"] > 0, f"s4 has varying dsMilli, expected zoomRange > 0: {row_s4['zoomRange']}"
        assert row_s2["zoomVariance"] == 0, f"s2 has constant w, expected zoomVariance == 0: {row_s2['zoomVariance']}"

        # --- baseMagnification / pathTruncated passthrough (schema/4+ only) ---
        assert row_s1["baseMagnification"] == 40.0, row_s1["baseMagnification"]
        assert pd.isna(row_s2["baseMagnification"]), "schema/3 has no baseMagnification field"
        assert pd.isna(row_s3["baseMagnification"]), "schema/2 has no baseMagnification field"
        assert pd.isna(row_s4["baseMagnification"]), (
            "s4 deliberately omits baseMagnification to exercise point_zoom's ds-only fallback"
        )
        assert not pd.isna(row_s1["pathTruncated"]), "schema/5 session should have pathTruncated set"
        assert pd.isna(row_s2["pathTruncated"]), "schema/3 has no pathTruncated field"
        assert pd.isna(row_s3["pathTruncated"]), "schema/2 has no pathTruncated field"
        assert not pd.isna(row_s4["pathTruncated"]), "schema/4 session (s4) should have pathTruncated set"

        # --- Phase 2 annotation columns ---
        # nAnnotations/annotatedAreaPx/dwellInAnnotationPct are grid-only (no path needed) ->
        # populated for every session, including the pathless schema/2 one (0/0.0, no annotations).
        assert row_s3["nAnnotations"] == 0, row_s3["nAnnotations"]
        assert row_s3["annotatedAreaPx"] == 0.0, row_s3["annotatedAreaPx"]
        assert row_s3["dwellInAnnotationPct"] == 0.0, row_s3["dwellInAnnotationPct"]
        assert pd.isna(row_s3["enrichmentRatio"]), "s3 has no annotations -> enrichmentRatio should be blank"
        # Path-dependent Phase 2 columns: blank without a path at all (schema/2, s3).
        assert pd.isna(row_s3["annotationReentryCount"]), "schema/2 (no path) should have blank annotationReentryCount"
        assert pd.isna(row_s3["cursorOverSlidePct"]), "schema/2 (no path) should have blank cursorOverSlidePct"
        assert pd.isna(row_s3["mouseViewportCouplingPx"]), "schema/2 (no path) should have blank mouseViewportCouplingPx"

        # s2 (schema/3, path present, no annotations, no mouse): reentry is numeric (0, nothing to
        # re-enter since there's no annotation at all), but cursor columns stay blank (5-element
        # path, no mouse data).
        assert row_s2["nAnnotations"] == 0, row_s2["nAnnotations"]
        assert row_s2["annotationReentryCount"] == 0, row_s2["annotationReentryCount"]
        assert pd.isna(row_s2["cursorOverSlidePct"]), "schema/3 path has no mouse data -> should be blank"
        assert pd.isna(row_s2["mouseViewportCouplingPx"]), "schema/3 path has no mouse data -> should be blank"

        # s4 (schema/4, path present w/ annotations, no mouse): annotations populated, reentry
        # non-trivial (the bouncing path was built for exactly this), cursor columns still blank.
        assert row_s4["nAnnotations"] == 1, row_s4["nAnnotations"]
        assert row_s4["annotatedAreaPx"] > 0, row_s4["annotatedAreaPx"]
        assert row_s4["annotationReentryCount"] >= 1, (
            f"s4's bouncing path should re-enter its own annotated region at least once, "
            f"got {row_s4['annotationReentryCount']}"
        )
        assert pd.isna(row_s4["cursorOverSlidePct"]), "schema/4 path has no mouse data -> should be blank"
        assert pd.isna(row_s4["mouseViewportCouplingPx"]), "schema/4 path has no mouse data -> should be blank"

        # s1 (schema/5, path present w/ mouse + annotations): everything populated.
        assert row_s1["nAnnotations"] == 1, row_s1["nAnnotations"]
        assert abs(row_s1["annotatedAreaPx"] - 421875.0) < 1.0, (
            f"expected the 3x3-cell rectangle's shoelace area (750*562.5=421875), got {row_s1['annotatedAreaPx']}"
        )
        assert row_s1["dwellInAnnotationPct"] > 50.0, (
            f"s1's dwell is centered inside its own annotation -> expected a high "
            f"dwellInAnnotationPct, got {row_s1['dwellInAnnotationPct']}"
        )
        assert not pd.isna(row_s1["cursorOverSlidePct"]), "schema/5 path has mouse data -> should be populated"
        assert 0.0 < row_s1["cursorOverSlidePct"] < 100.0, (
            f"s1's synthetic path has ~20% off-slide points by design: {row_s1['cursorOverSlidePct']}"
        )
        assert not pd.isna(row_s1["mouseViewportCouplingPx"]), "schema/5 path has mouse data -> should be populated"
        assert row_s1["mouseViewportCouplingPx"] >= 0.0, row_s1["mouseViewportCouplingPx"]

        # --- direct metrics-function unit checks (raster_from_path, w-proxy zoom, magPct/scanRate) ---
        s1_path = fragments[0]["path"]  # schema/5, 8-element points, varying dsMilli + mouse
        s2_path = fragments[1]["path"]  # schema/3, 5-element points, constant w

        raster = bf_metrics.raster_from_path(s1_path, IMG_W, IMG_H, 16, 16)
        assert raster is not None, "raster_from_path returned None for a >=2-point path"
        assert raster.size == 16 * 16, f"unexpected raster size: {raster.size}"
        assert raster.sum() > 0, "raster_from_path grid is all-zero"
        assert bf_metrics.raster_from_path([], IMG_W, IMG_H, 16, 16) is None, (
            "raster_from_path should return None for an empty path"
        )
        assert bf_metrics.raster_from_path(s1_path[:1], IMG_W, IMG_H, 16, 16) is None, (
            "raster_from_path should return None for a 1-point path"
        )

        magpct = bf_metrics.magnification_percentage(s1_path, 40.0, IMG_W)
        assert 0.0 <= magpct <= 1.0, f"magnificationPercentage out of [0,1]: {magpct}"
        scan_rate = bf_metrics.scanning_rate_px_per_min(s1_path, 40.0, IMG_W)
        assert scan_rate >= 0.0, f"scanningRatePxPerMin negative: {scan_rate}"

        # schema/3 5-element path (no dsMilli) -> point_zoom falls back to the w-proxy (img_w/w)
        zoom_5el = bf_metrics.point_zoom(s2_path[0], None, IMG_W)
        assert zoom_5el > 0, f"w-proxy zoom should be positive: {zoom_5el}"
        assert len(s2_path[0]) == 5, "s2's path points should be 5-element (schema/3)"
        assert len(s1_path[0]) == 8, "s1's path points should be 8-element (schema/5, incl. mouse)"
        assert bf_metrics.has_mouse_data(s1_path), "s1 (schema/5) should be detected as carrying mouse data"
        assert not bf_metrics.has_mouse_data(s2_path), "s2 (schema/3) should NOT be detected as carrying mouse data"

        # --- Fix B targeted assert: magnification_percentage no longer counts held-zoom ties ---
        tie_path = [
            [0, 100, 100, 400, 300, 1000],
            [250, 100, 100, 400, 300, 1000],
            [500, 100, 100, 400, 300, 1000],
        ]
        magpct_tie = bf_metrics.magnification_percentage(tie_path, None, IMG_W)
        assert magpct_tie == 0.0, (
            f"a constant-zoom path (all ties) must yield magnificationPercentage == 0.0 under "
            f"the strict '>' fix, got {magpct_tie}"
        )

        # --- Fix A targeted assert: coincidence_level uses the visited-footprint denominator ---
        g_a = np.array([1.0, 1.0, 0.0, 0.0])
        g_b = np.array([1.0, 0.0, 1.0, 0.0])
        # normalise_max(g_a) > 0.1 -> [T,T,F,F]; normalise_max(g_b) > 0.1 -> [T,F,T,F]
        # counts = [2,1,1,0] -> visited footprint (>=1) = 3 cells, coincident (>=2) = 1 cell -> 1/3
        old_broken_value = 1.0 / 4.0  # what the pre-fix whole-grid-denominator formula returned
        new_value = bf_metrics.coincidence_level([g_a, g_b], 0.1)
        assert abs(new_value - (1.0 / 3.0)) < 1e-9, (
            f"expected 1/3 (visited-footprint denominator), got {new_value}"
        )
        assert abs(new_value - old_broken_value) > 1e-6, (
            "coincidence_level should no longer match the old whole-grid-denominator formula"
        )

        # --- Fix C targeted assert: the annotation mask is the UNION of each Feature's own
        # rasterization, not a single even-odd test over every Feature's rings pooled together.
        # Two overlapping/nested annotation Features -- an outer rectangle (image px [20,80] x
        # [20,80]) and a smaller rectangle nested fully inside it (image px [40,60] x [40,60]) --
        # on a 10x10 grid over a 100x100 image (cell size 10px, centers at 5,15,...,95). The outer
        # rect's centers fall at grid indices 2-7 (px 25..75); the inner rect's centers fall at
        # indices 4-5 (px 45,55) -- the "overlap zone" that is inside BOTH Features.
        #
        # Hand-derived expected value: since the inner rect is fully nested inside the outer one,
        # the true union of the two Features is just the outer rectangle -- so the overlap-zone
        # cells (rows/cols 4-5) must be classified INSIDE the mask. Concentrating all dwell weight
        # (value 100 in each of the 4 overlap cells, 0 everywhere else -- 400 total) exactly on
        # those cells makes dwellInAnnotationPct's hand-derived expected value a clean 100.0%.
        #
        # Under the pre-fix bug (pool every Feature's rings into one flat list, single even-odd
        # test): a point inside both rectangles crosses one edge of each ring set (1 + 1 = 2
        # crossings) -> EVEN parity -> wrongly classified OUTSIDE. Since all the dwell weight sits
        # exactly on those wrongly-excluded cells, the pre-fix value would instead be 0.0%.
        overlap_gw = overlap_gh = 10
        overlap_img_w = overlap_img_h = 100
        overlap_fc = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[20, 20], [80, 20], [80, 80], [20, 80], [20, 20]]],
                    },
                    "properties": {"name": "tumor", "classification": {"name": "Tumor"}},
                },
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[40, 40], [60, 40], [60, 60], [40, 60], [40, 40]]],
                    },
                    "properties": {"name": "focus", "classification": {"name": "HighGradeFocus"}},
                },
            ],
        }
        overlap_grid = [0.0] * (overlap_gw * overlap_gh)
        for r in (4, 5):
            for c in (4, 5):
                overlap_grid[r * overlap_gw + c] = 100.0

        overlap_mask = rasterize_feature_collection(
            overlap_fc, overlap_gw, overlap_gh, overlap_img_w, overlap_img_h
        )
        overlap_mask_2d = overlap_mask.reshape(overlap_gh, overlap_gw)
        for r in (4, 5):
            for c in (4, 5):
                assert overlap_mask_2d[r, c], (
                    f"overlap-zone cell (row {r}, col {c}) -- inside both the outer and inner "
                    f"annotation Features -- should be inside the UNION mask"
                )

        overlap_pct = bf_metrics.dwell_in_mask_pct(overlap_grid, overlap_mask)
        assert abs(overlap_pct - 100.0) < 1e-6, (
            f"expected dwellInAnnotationPct == 100.0 (all dwell weight sits in the overlap zone, "
            f"which the union mask correctly includes); the pre-fix pooled-rings bug would instead "
            f"yield 0.0 (the overlap zone misclassified as outside) -- got {overlap_pct}"
        )

        # --- .zip input also works ---
        zip_path = os.path.join(tmp, "fragments.zip")
        write_fragments_to_zip(fragments, zip_path)
        zip_out = os.path.join(tmp, "out_zip")
        analyze([zip_path], zip_out, reference="s1")
        zip_metrics = pd.read_csv(os.path.join(zip_out, "metrics.csv"))
        assert len(zip_metrics) == 4, f"zip input: expected 4 metrics rows, got {len(zip_metrics)}"

        print("OK: all selftest assertions passed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    try:
        run()
    except AssertionError as exc:
        print(f"SELFTEST FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
