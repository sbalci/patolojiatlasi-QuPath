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
import csv
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
    decision=None, slide_key=None,
):
    d = {
        "schema": f"atlas-focus-contribution/{schema}",
        "slideKey": slide_key or "sha256:selftest-slide-0001",
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
    # Phase 3: a hand-entered decision object ({"diagnosis", "confidence", "decisionMs"}).
    # Deliberately independent of `annotations`/`path` -- a reader can submit a decision on any
    # schema fragment.
    if decision is not None:
        d["decision"] = decision
    return d


def _decision(diagnosis, confidence, decision_ms):
    """A synthetic hand-entered decision object -- {"diagnosis", "confidence", "decisionMs"} --
    matching what the QuPath extension's blinded-focus recorder writes into a fragment's
    ``decision`` field."""
    return {"diagnosis": diagnosis, "confidence": confidence, "decisionMs": decision_ms}


def build_fragments():
    grid_s1 = _gaussian_grid(2, 2, seed=1)
    grid_s2 = _gaussian_grid(2, 2, seed=2)  # similar hotspot location to s1
    grid_s3 = _gaussian_grid(6, 6, sigma=1.0, seed=3)  # different hotspot location
    grid_s4 = _gaussian_grid(4, 4, sigma=1.2, seed=4)  # yet another location, outside the shared annotation rect

    shared_annotation = _make_annotations_fc(1, 3, 1, 3)  # rows 1-3, cols 1-3 -> overlaps s1's dwell/path center

    # s1: schema/5, 8-element path (varying dsMilli + mouse, some off-slide) + a known
    # baseMagnification + an annotation overlapping its own dwell center. Also carries a Phase 3
    # decision (diagnosis="tumor", confidence=4 -> confidenceScaled=(4-1)/4=0.75).
    f1 = _fragment(
        "s1", 5, grid_s1, 40 * 250, 40,
        path=_make_path_v5(2, 2, n=40, seed=101),
        base_magnification=40.0, path_truncated=False,
        annotations=shared_annotation,
        decision=_decision("tumor", 4, 5000),
    )
    # s2: schema/3, 5-element path (w-proxy zoom fallback; no dsMilli/baseMagnification/annotations).
    # Also carries a Phase 3 decision (diagnosis="benign", confidence=2).
    f2 = _fragment(
        "s2", 3, grid_s2, 35 * 250, 35, path=_make_path(2, 2, n=35, seed=102),
        decision=_decision("benign", 2, 5000),
    )
    # s3: schema/2, no path, no annotations, and (deliberately) no decision either -- exercises
    # the blank-diagnosis/confidence/correct degrade path in decisions.csv.
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


GRADED_SLIDE_KEY = "sha256:selftest-slide-graded-0001"
#: The display-only answer key's correctDx for GRADED_SLIDE_KEY.
GRADED_KEY_DX = "tumor"

COLLISION_SLIDE_KEY = "sha256:selftest-slide-collision-0001"
#: Shared display label a coordinator's --labels CSV (realistically) assigns to two DIFFERENT
#: sessions on the same slide -- the exact scenario the Finding-1 regression guards against.
COLLISION_LABEL = "Reader X"


def _n_nonzero_grid(n_nonzero, value=100.0):
    """A ``GW*GH``-length grid with the first ``n_nonzero`` cells set to ``value``, the rest 0.0
    -- gives ``coveragePct`` (``count(g>0)/len(g)*100``) a known, hand-derivable value."""
    g = [0.0] * (GW * GH)
    for i in range(n_nonzero):
        g[i] = value
    return g


def build_label_collision_fragments():
    """Regression fixture for the Finding-1 fix: two sessions on ONE slide that a coordinator's
    ``--labels`` CSV maps to the SAME display label -- a realistic mistake (e.g. two different
    readers both entered as "Reader X"). ``collide-a`` is dense (high ``coveragePct``, hand-graded
    ``correct=1``); ``collide-b`` is sparse (low ``coveragePct``, hand-graded ``correct=0``).

    Pre-fix, :func:`blinded_focus.analyze._nav_accuracy_rows` recovered each metrics row's
    sessionId via a ``(slide, display-label)`` lookup into ``decision_rows`` -- since both sessions
    share the same label, that lookup collapses to whichever session's decision row was built
    last (``collide-b``, in fragment/insertion order), so BOTH metrics rows would be
    (mis)attributed to ``collide-b``'s grade (``correct=0``). The "correct" group vanishes
    entirely (``meanCorrect`` renders blank) even though ``collide-a`` was genuinely graded
    correct. Post-fix (direct ``sessionId`` join, no label bridge), each session's own
    ``coveragePct`` lands in its own, correctly-graded group.

    Returns ``(fragments, labels_rows, graded_rows)``.
    """
    dense = _n_nonzero_grid(60)   # 60/64 -> 93.75% coverage
    sparse = _n_nonzero_grid(4)   # 4/64 -> 6.25% coverage
    frag_a = _fragment("collide-a", 2, dense, 6000, 30, slide_key=COLLISION_SLIDE_KEY)
    frag_b = _fragment("collide-b", 2, sparse, 6000, 30, slide_key=COLLISION_SLIDE_KEY)
    labels_rows = [("collide-a", COLLISION_LABEL), ("collide-b", COLLISION_LABEL)]
    graded_rows = [
        (COLLISION_SLIDE_KEY, "collide-a", 1),
        (COLLISION_SLIDE_KEY, "collide-b", 0),
    ]
    return [frag_a, frag_b], labels_rows, graded_rows


def build_graded_fragments():
    """A second, independent slide (6 sessions, no paths/annotations) purpose-built to exercise
    the navigation<->accuracy correlation (:func:`blinded_focus.analyze._nav_accuracy_rows`):

    - ``coveragePct`` (grid-only, always populated) is deliberately separable by outcome: g1-g3
      (graded ``correct=1``) get a dense grid (56/64 nonzero cells -> ~87.5% coverage), g4-g6
      (graded ``correct=0``) get a sparse grid (6/64 nonzero cells -> ~9.4% coverage) -- so
      ``meanCorrect > meanIncorrect`` (a positive ``meanDiff``) is the expected, hand-derivable
      result.
    - ``avgZoom`` (path-only) is blank for every session here (none carry a ``path``) -> n=0,
      exercising the "n < 5" blank-pointBiserialR guard.
    - ``dwellInAnnotationPct`` (grid-only, always populated) is exactly 0.0 for every session
      (none carry ``annotations``) -> zero variance, exercising the "zero variance" blank guard
      distinctly from the n=0 case above.

    Diagnoses are chosen so `correct` can NOT be reconstructed by string-matching `diagnosis`
    against the answer key ("tumor"): g1 (correct=1) is diagnosed "benign" (a MISMATCH that is
    still graded correct), and g4 (correct=0) is diagnosed "tumor" (an exact MATCH that is still
    graded incorrect) -- a hand-grade-only pipeline must report exactly the graded value in both
    cases; a string-matching one would get both backwards.

    Returns ``(fragments, graded_rows, key_rows)`` where ``graded_rows`` is a list of
    ``(slideKey, sessionId, correct)`` tuples (for a synthetic ``--graded`` CSV) and ``key_rows``
    is a list of ``(slideKey, correctDx)`` tuples (for a synthetic ``--key`` CSV).
    """
    def _sparse_grid(n_nonzero, value=100.0):
        g = [0.0] * (GW * GH)
        for i in range(n_nonzero):
            g[i] = value
        return g

    # (sessionId, diagnosis, confidence, nNonzeroCells, correct)
    spec = [
        ("g1", "benign", 4, 56, 1),   # dense/high-coverage, MISMATCHED diagnosis, graded correct
        ("g2", "tumor", 5, 55, 1),    # dense/high-coverage, matched diagnosis, graded correct
        ("g3", "tumor", 3, 54, 1),    # dense/high-coverage, matched diagnosis, graded correct
        ("g4", "tumor", 5, 6, 0),     # sparse/low-coverage, MATCHED diagnosis, graded INcorrect
        ("g5", "benign", 2, 7, 0),    # sparse/low-coverage, matched diagnosis, graded incorrect
        ("g6", "unknown", 1, 8, 0),   # sparse/low-coverage, matched diagnosis, graded incorrect
    ]
    fragments, graded_rows = [], []
    for i, (sid, dx, conf, n_nonzero, correct) in enumerate(spec):
        f = _fragment(
            sid, 2, _sparse_grid(n_nonzero), 6000, 30,
            slide_key=GRADED_SLIDE_KEY,
            decision=_decision(dx, conf, 4000 + i * 50),
        )
        fragments.append(f)
        graded_rows.append((GRADED_SLIDE_KEY, sid, correct))
    key_rows = [(GRADED_SLIDE_KEY, GRADED_KEY_DX)]
    return fragments, graded_rows, key_rows


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


def _write_simple_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(rows)


def check_decisions(out_dir):
    """Phase 3: ``decisions.csv`` exists with one row per (slide, session), hand-grade-only
    columns populated for the sessions that carry a ``decision`` (s1, s2) and blank for the ones
    that don't (s3, s4) -- and, absent ``--graded``, every row's ``correct`` is blank (nothing is
    ever auto-derived from ``diagnosis``)."""
    path = os.path.join(out_dir, "decisions.csv")
    assert os.path.isfile(path), "decisions.csv missing"
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert len(rows) == 4, f"expected 4 decisions rows (one per slide,session), got {len(rows)}"
    expected_cols = [
        "slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
        "decisionMs", "decisionLatencyMs", "correctDx", "correct",
    ]
    assert list(rows[0].keys()) == expected_cols, list(rows[0].keys())

    by_session = {r["session"]: r for r in rows}
    s1 = by_session["s1"]
    assert s1["diagnosis"] == "tumor", s1["diagnosis"]
    assert s1["confidence"] == "4", s1["confidence"]
    assert s1["confidenceScaled"] == "0.75", s1["confidenceScaled"]
    assert s1["decisionMs"] not in ("", None), "s1 decisionMs should be non-empty"
    assert s1["decisionLatencyMs"] not in ("", None), "s1 decisionLatencyMs should be non-empty"

    s3 = by_session["s3"]
    assert s3["diagnosis"] == "", f"undecided session should have blank diagnosis, got {s3['diagnosis']!r}"
    assert s3["confidence"] == "", f"undecided session should have blank confidence, got {s3['confidence']!r}"
    assert s3["correct"] == "", f"undecided session should have blank correct, got {s3['correct']!r}"

    # Without --graded, every row's `correct` is blank -- HAND-GRADE ONLY, never auto-derived.
    for r in rows:
        assert r["correct"] == "", f"correct should be blank without --graded, got row {r}"
    return rows


def check_nav_accuracy(nongraded_out_dir, graded_out_dir):
    """Phase 3: without ``--graded``, ``nav_accuracy.csv`` is not written and the summary has no
    "Navigation" section; with ``--graded`` (the ``build_graded_fragments`` fixture), the file
    exists with the documented columns, a metric with n<5 (``avgZoom``, no session here has a
    path) and a metric with zero variance (``dwellInAnnotationPct``, constant 0.0 -- no session
    here has an annotation) both have a blank ``pointBiserialR``, and the deliberately-separable
    ``coveragePct`` metric has a non-blank, positive ``meanDiff`` (dense/correct sessions have
    higher coverage than sparse/incorrect ones)."""
    # --- without --graded: no nav_accuracy.csv, no summary section ---
    assert not os.path.isfile(os.path.join(nongraded_out_dir, "nav_accuracy.csv")), (
        "nav_accuracy.csv should not be written without --graded"
    )
    with open(os.path.join(nongraded_out_dir, "summary.md"), encoding="utf-8") as fh:
        nongraded_summary = fh.read()
    assert "Navigation" not in nongraded_summary, (
        "nav-accuracy summary section should be absent without --graded"
    )

    # --- with --graded: nav_accuracy.csv + summary section present ---
    nav_path = os.path.join(graded_out_dir, "nav_accuracy.csv")
    assert os.path.isfile(nav_path), "nav_accuracy.csv missing when graded decisions exist"
    with open(nav_path, newline="", encoding="utf-8") as fh:
        nav_rows = list(csv.DictReader(fh))
    expected_cols = [
        "metric", "n", "pointBiserialR", "meanCorrect", "meanIncorrect",
        "medianCorrect", "medianIncorrect", "meanDiff",
    ]
    assert list(nav_rows[0].keys()) == expected_cols, list(nav_rows[0].keys())
    by_metric = {r["metric"]: r for r in nav_rows}

    # n<5 guard (avgZoom: path-only, no session here has a path -> n=0)
    assert by_metric["avgZoom"]["n"] == "0", by_metric["avgZoom"]
    assert by_metric["avgZoom"]["pointBiserialR"] == "", (
        f"n=0 should yield a blank pointBiserialR, got {by_metric['avgZoom']}"
    )

    # zero-variance guard (dwellInAnnotationPct: constant 0.0 across all 6 graded sessions)
    assert by_metric["dwellInAnnotationPct"]["n"] == "6", by_metric["dwellInAnnotationPct"]
    assert by_metric["dwellInAnnotationPct"]["pointBiserialR"] == "", (
        f"zero-variance navMetric should yield a blank pointBiserialR, got "
        f"{by_metric['dwellInAnnotationPct']}"
    )

    # deliberately-separable metric: non-blank meanDiff, positive sign (correct > incorrect)
    cov = by_metric["coveragePct"]
    assert cov["n"] == "6", cov
    assert cov["meanDiff"] != "", "coveragePct meanDiff should be populated (n=3 in each group)"
    assert float(cov["meanDiff"]) > 0, (
        f"expected a positive meanDiff (dense/correct sessions have higher coveragePct than "
        f"sparse/incorrect ones), got {cov['meanDiff']}"
    )

    # --- Tier 1 A1: the extended NAV_ACCURACY_COLS + decisionLatencyMs (sourced from
    # decision_rows, not metrics_rows) all appear as rows ---
    for extra_col in ("durationMs", "cursorOverSlidePct", "mouseViewportCouplingPx", "decisionLatencyMs"):
        assert extra_col in by_metric, f"{extra_col} missing from nav_accuracy.csv (Tier 1 A1)"

    # durationMs: grid-only (always populated), but constant (6000) across every graded session
    # here -> zero variance -> blank pointBiserialR (same guard as dwellInAnnotationPct above).
    assert by_metric["durationMs"]["n"] == "6", by_metric["durationMs"]
    assert by_metric["durationMs"]["pointBiserialR"] == "", (
        f"durationMs is constant across the graded fixture -> zero variance -> blank r, "
        f"got {by_metric['durationMs']}"
    )

    # cursorOverSlidePct/mouseViewportCouplingPx: none of the graded fixture's sessions carry a
    # path at all -> n=0, exercising the same n<5 guard as avgZoom above.
    assert by_metric["cursorOverSlidePct"]["n"] == "0", by_metric["cursorOverSlidePct"]
    assert by_metric["cursorOverSlidePct"]["pointBiserialR"] == "", by_metric["cursorOverSlidePct"]
    assert by_metric["mouseViewportCouplingPx"]["n"] == "0", by_metric["mouseViewportCouplingPx"]

    # decisionLatencyMs: sourced directly from decision_rows (decisionMs=4000+i*50, correct
    # decreasing from 1 to 0) -- a real, non-degenerate n=6 correlation, unlike the two guards
    # above -- and its meanDiff should be negative (higher latency associates with the
    # graded-incorrect group in this synthetic fixture).
    lat = by_metric["decisionLatencyMs"]
    assert lat["n"] == "6", lat
    assert lat["pointBiserialR"] != "", (
        f"decisionLatencyMs has n=6 with real variance on both sides -> should be a real r, got {lat}"
    )
    assert float(lat["meanDiff"]) < 0, (
        f"graded fixture's decisionLatencyMs increases while correct decreases -> expected a "
        f"negative meanDiff, got {lat['meanDiff']}"
    )

    with open(os.path.join(graded_out_dir, "summary.md"), encoding="utf-8") as fh:
        graded_summary = fh.read()
    assert "## Navigation ↔ diagnostic accuracy" in graded_summary, (
        "nav-accuracy summary section missing from summary.md"
    )
    return nav_rows


def check_hand_grade_only(graded_out_dir):
    """Phase 3 invariant: ``correct`` in ``decisions.csv`` is never derivable by string-matching
    ``diagnosis`` against ``--key``'s ``correctDx`` -- it comes only from ``--graded``. g1 (graded
    correct=1) is diagnosed "benign", a MISMATCH against the key's "tumor"; g4 (graded correct=0)
    is diagnosed "tumor", an exact MATCH. A string-matching implementation would report both
    backwards; a hand-grade-only one reports exactly what ``--graded`` said."""
    path = os.path.join(graded_out_dir, "decisions.csv")
    with open(path, newline="", encoding="utf-8") as fh:
        rows = {r["sessionId"]: r for r in csv.DictReader(fh)}
    g1, g4 = rows["g1"], rows["g4"]
    assert g1["correctDx"] == GRADED_KEY_DX, g1
    assert g1["diagnosis"] == "benign" and g1["diagnosis"] != g1["correctDx"], (
        "g1 should have a diagnosis that MISMATCHES correctDx (regression guard fixture)"
    )
    assert g1["correct"] == "1", (
        f"g1 was hand-graded correct despite a diagnosis/correctDx mismatch -- got {g1['correct']}"
    )
    assert g4["correctDx"] == GRADED_KEY_DX, g4
    assert g4["diagnosis"] == g4["correctDx"] == "tumor", (
        "g4 should have a diagnosis that MATCHES correctDx (regression guard fixture)"
    )
    assert g4["correct"] == "0", (
        f"g4 was hand-graded incorrect despite a diagnosis/correctDx match -- got {g4['correct']}"
    )


def check_label_collision_regression(tmp):
    """Finding-1 regression guard: two sessions on one slide sharing a display label via
    ``--labels`` must still be joined to their OWN grade via the stable ``sessionId``, never the
    label -- see :func:`build_label_collision_fragments`. Pre-fix, both sessions' ``coveragePct``
    would collapse into whichever session's decision row was built last, emptying the "correct"
    group entirely (``meanCorrect`` blank) instead of correctly separating the two groups."""
    fragments, labels_rows, graded_rows = build_label_collision_fragments()
    in_dir = os.path.join(tmp, "in_collision")
    os.makedirs(in_dir, exist_ok=True)
    write_fragments_to_dir(fragments, in_dir)

    labels_csv_path = os.path.join(tmp, "labels_collision.csv")
    _write_simple_csv(labels_csv_path, ["sessionId", "label"], labels_rows)
    graded_csv_path = os.path.join(tmp, "graded_collision.csv")
    _write_simple_csv(graded_csv_path, ["slideKey", "sessionId", "correct"], graded_rows)

    out_dir = os.path.join(tmp, "out_collision")
    analyze([in_dir], out_dir, labels_csv=labels_csv_path, graded_csv=graded_csv_path)

    nav_path = os.path.join(out_dir, "nav_accuracy.csv")
    assert os.path.isfile(nav_path), "nav_accuracy.csv missing for the label-collision fixture"
    with open(nav_path, newline="", encoding="utf-8") as fh:
        nav_rows = list(csv.DictReader(fh))
    by_metric = {r["metric"]: r for r in nav_rows}
    cov = by_metric["coveragePct"]

    expected_dense = 60.0 / (GW * GH) * 100.0
    expected_sparse = 4.0 / (GW * GH) * 100.0

    assert cov["n"] == "2", f"expected both collision sessions joined (n=2), got {cov}"
    assert cov["meanCorrect"] != "", (
        "REGRESSION (Finding 1): meanCorrect is blank -- the pre-fix label-based join collapses "
        "both same-labeled sessions into the 'incorrect' group, emptying 'correct' entirely"
    )
    assert cov["meanIncorrect"] != "", "REGRESSION (Finding 1): meanIncorrect should not be blank"
    assert abs(float(cov["meanCorrect"]) - expected_dense) < 1e-6, (
        f"expected meanCorrect == collide-a's (graded correct=1) coveragePct "
        f"({expected_dense}), got {cov['meanCorrect']} -- sessionId join is misattributing groups"
    )
    assert abs(float(cov["meanIncorrect"]) - expected_sparse) < 1e-6, (
        f"expected meanIncorrect == collide-b's (graded correct=0) coveragePct "
        f"({expected_sparse}), got {cov['meanIncorrect']} -- sessionId join is misattributing groups"
    )


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

        # --- decisions.csv (Phase 3): hand-grade-only, populated for s1/s2, blank for s3/s4,
        # `correct` blank throughout since no --graded was passed to this run ---
        check_decisions(out_dir)

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
            "meanAbsTurnAngleDeg", "turnAngleEntropy", "mousePathLengthPx",
            "mouseVelocityPxPerSec", "activeFractionPct",
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

        # --- hotspots_<slug>.csv (Tier 1 A5): written for every session (grid always present),
        # rank ascending + dwellMs descending within each session ---
        hotspot_files = [f for f in os.listdir(out_dir) if f.startswith("hotspots_")]
        assert len(hotspot_files) == 1, hotspot_files
        hotspots = pd.read_csv(os.path.join(out_dir, hotspot_files[0]))
        assert list(hotspots.columns) == [
            "session", "rank", "cellRow", "cellCol", "centerImageX", "centerImageY",
            "dwellMs", "dwellFrac",
        ], hotspots.columns.tolist()
        assert set(hotspots["session"].unique()) == {"s1", "s2", "s3", "s4"}, (
            f"hotspots should cover every session (grid always present): {hotspots['session'].unique()}"
        )
        assert hotspots["dwellFrac"].between(0, 1).all(), hotspots["dwellFrac"].tolist()
        for sess, grp in hotspots.groupby("session"):
            grp_sorted = grp.sort_values("rank")
            ranks = grp_sorted["rank"].tolist()
            assert ranks == list(range(1, len(ranks) + 1)), f"{sess}: rank should be 1..N: {ranks}"
            dwells = grp_sorted["dwellMs"].tolist()
            assert all(dwells[i] >= dwells[i + 1] - 1e-9 for i in range(len(dwells) - 1)), (
                f"{sess}: hotspots not sorted descending by dwellMs: {dwells}"
            )

        # --- transitions_<slug>.csv (Tier 1 A5): path sessions only, <=15 rows/session, count desc ---
        transitions_files = [f for f in os.listdir(out_dir) if f.startswith("transitions_")]
        assert len(transitions_files) == 1, transitions_files
        transitions = pd.read_csv(os.path.join(out_dir, transitions_files[0]))
        assert list(transitions.columns) == ["session", "fromCell", "toCell", "count"], (
            transitions.columns.tolist()
        )
        assert set(transitions["session"].unique()) <= {"s1", "s2", "s4"}, (
            transitions["session"].unique()
        )
        assert "s3" not in transitions["session"].values, (
            "schema/2 (no path) session should be absent from transitions_<slug>.csv"
        )
        for sess, grp in transitions.groupby("session"):
            assert len(grp) <= 15, f"{sess}: expected <=15 transitions, got {len(grp)}"
            counts = grp["count"].tolist()
            assert counts == sorted(counts, reverse=True), (
                f"{sess}: transitions not sorted by count descending: {counts}"
            )

        # --- overlay_<slug>_scanpaths.png (Tier 1 A6): written when path sessions exist ---
        overlay_files = [f for f in os.listdir(out_dir) if f.startswith("overlay_")]
        assert len(overlay_files) == 1, overlay_files
        _assert_png(os.path.join(out_dir, overlay_files[0]))

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

        # --- Tier 1 A2 (turn-angle) / A3 (mouse kinematics) / A4 (active fraction) columns ---
        for row, label in ((row_s1, "s1"), (row_s2, "s2"), (row_s4, "s4")):
            assert not pd.isna(row["meanAbsTurnAngleDeg"]), f"{label} missing meanAbsTurnAngleDeg"
            assert 0.0 <= row["meanAbsTurnAngleDeg"] <= 180.0, (label, row["meanAbsTurnAngleDeg"])
            assert not pd.isna(row["turnAngleEntropy"]), f"{label} missing turnAngleEntropy"
            assert -1e-6 <= row["turnAngleEntropy"] <= 1.0 + 1e-6, (label, row["turnAngleEntropy"])
            assert not pd.isna(row["activeFractionPct"]), f"{label} missing activeFractionPct"
        assert pd.isna(row_s3["meanAbsTurnAngleDeg"]), "schema/2 (no path) should have blank meanAbsTurnAngleDeg"
        assert pd.isna(row_s3["turnAngleEntropy"]), "schema/2 (no path) should have blank turnAngleEntropy"
        assert pd.isna(row_s3["activeFractionPct"]), "schema/2 (no path) should have blank activeFractionPct"

        # A4 hand-derived, NOT clamped at 100%: s1's synthetic path's first sample sits at
        # tRelMs=250 (not 0), so durationMs (== n*250) exceeds the wall-clock span (== (n-1)*250)
        # by construction -- a real, not-clamped-away >100% result.
        s1_frag, s1_path_full = fragments[0], fragments[0]["path"]
        expected_active_s1 = 100.0 * s1_frag["durationMs"] / (s1_path_full[-1][0] - s1_path_full[0][0])
        assert abs(row_s1["activeFractionPct"] - expected_active_s1) < 1e-6, (
            f"expected activeFractionPct == {expected_active_s1}, got {row_s1['activeFractionPct']}"
        )
        assert row_s1["activeFractionPct"] > 100.0, (
            f"activeFractionPct should not be clamped at 100%, got {row_s1['activeFractionPct']}"
        )

        # A3 mouse kinematics: populated only for s1 (schema/5, has mouse data), blank elsewhere.
        assert not pd.isna(row_s1["mousePathLengthPx"]), "s1 (schema/5) missing mousePathLengthPx"
        assert row_s1["mousePathLengthPx"] > 0, row_s1["mousePathLengthPx"]
        assert not pd.isna(row_s1["mouseVelocityPxPerSec"]), "s1 (schema/5) missing mouseVelocityPxPerSec"
        assert row_s1["mouseVelocityPxPerSec"] > 0, row_s1["mouseVelocityPxPerSec"]
        for row, label in ((row_s2, "s2"), (row_s3, "s3"), (row_s4, "s4")):
            assert pd.isna(row["mousePathLengthPx"]), f"{label} should have blank mousePathLengthPx (no mouse data)"
            assert pd.isna(row["mouseVelocityPxPerSec"]), (
                f"{label} should have blank mouseVelocityPxPerSec (no mouse data)"
            )

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

        # --- A2 targeted assert: turn-angle metrics on a hand-derivable minimal L-shaped path ---
        # 3 points: (0,0) -> (10,0) -> (10,10) -- one segment heading east (0deg), one heading
        # south (90deg) -- exactly one interior point -> exactly one turn of 90deg.
        l_path = [
            [0, 0, 0, 400, 300],
            [100, 10, 0, 400, 300],
            [200, 10, 10, 400, 300],
        ]
        mean_turn = bf_metrics.mean_abs_turn_angle_deg(l_path)
        assert abs(mean_turn - 90.0) < 1e-6, (
            f"minimal L-shaped path should have a mean abs turn angle of 90deg, got {mean_turn}"
        )
        turn_ent = bf_metrics.turn_angle_entropy(l_path)
        assert abs(turn_ent) < 1e-6, (
            f"a single-turn path concentrates all mass in one of 8 bins -> entropy ~= 0, got {turn_ent}"
        )
        # <3 points -> blank (NaN), not a crash.
        assert math.isnan(bf_metrics.mean_abs_turn_angle_deg(l_path[:2])), (
            "turn-angle metrics should be blank (NaN) for a <3-point path"
        )
        assert math.isnan(bf_metrics.turn_angle_entropy(l_path[:2])), (
            "turn-angle metrics should be blank (NaN) for a <3-point path"
        )
        assert math.isnan(bf_metrics.mean_abs_turn_angle_deg([])), "blank for an empty path"

        # --- A2 parity-critical assert: turn angles landing exactly on a turnAngleEntropy bin
        # boundary (45-degree multiples), from exact-integer diagonal/axis-aligned pixel deltas --
        # the realistic case an R port's degree-conversion association order (`x*180/pi` vs
        # `x*(180.0/pi)`) could silently disagree on, since floor((deg+180)/45) is a discontinuous
        # step function of `deg`. Path alternates heading 0deg (dx,dy=100,0) and heading 45deg
        # (dx,dy=100,100) -> turns alternate exactly -45deg/+45deg (two distinct bins, not one, so
        # entropy is a non-trivial ~1 bit rather than the degenerate 0 the L-path above exercises).
        boundary_path = [[0, 0, 0, 400, 300]]
        bx, by, bt = 0, 0, 0
        for i in range(21):  # odd count -> 20 turns (even) -> exact 10/10 split, 2 clean bins
            bdx, bdy = (100, 0) if i % 2 == 0 else (100, 100)
            bt += 100
            bx += bdx
            by += bdy
            boundary_path.append([bt, bx, by, 400, 300])
        boundary_turns = bf_metrics._turn_angles_deg(boundary_path)
        assert all(abs(abs(t) - 45.0) < 1e-9 for t in boundary_turns), (
            f"expected every turn to be exactly +/-45deg, got {boundary_turns}"
        )
        boundary_mean = bf_metrics.mean_abs_turn_angle_deg(boundary_path)
        assert abs(boundary_mean - 45.0) < 1e-6, boundary_mean
        boundary_ent = bf_metrics.turn_angle_entropy(boundary_path)
        # 2 equally-likely bins out of 8 -> 1 bit of entropy, normalized by log2(8)=3 -> 1/3.
        assert abs(boundary_ent - (1.0 / 3.0)) < 1e-6, (
            f"expected turnAngleEntropy == 1/3 (2 equally-likely bins out of 8), got {boundary_ent}"
        )

        # --- A3 targeted assert: mouse kinematics skip segments touching the (-1,-1) sentinel,
        # not bridge across them ---
        mk_path = [
            [0, 0, 0, 400, 300, 1000, 0, 0],
            [100, 10, 0, 400, 300, 1000, 10, 0],    # on-slide segment from point0: dist 10 [counted]
            [200, 20, 0, 400, 300, 1000, -1, -1],   # sentinel -- both adjoining segments skipped
            [300, 30, 0, 400, 300, 1000, 40, 0],
            [400, 40, 0, 400, 300, 1000, 50, 0],    # on-slide segment into point4: dist 10 [counted]
        ]
        mplen = bf_metrics.mouse_path_length_px(mk_path)
        assert abs(mplen - 20.0) < 1e-6, (
            f"expected mousePathLengthPx == 20.0 (sentinel-touching segments skipped, not "
            f"bridged), got {mplen}"
        )
        mvel = bf_metrics.mouse_velocity_px_per_sec(mk_path)
        assert mvel > 0, f"mouseVelocityPxPerSec should be positive over the 2 valid segments, got {mvel}"

        # schema/3 5-element path (no mouse data at all) -> both blank.
        assert math.isnan(bf_metrics.mouse_path_length_px(s2_path)), (
            "mouse kinematics should be blank for a path with no mouse data"
        )
        assert math.isnan(bf_metrics.mouse_velocity_px_per_sec(s2_path)), (
            "mouse kinematics should be blank for a path with no mouse data"
        )

        # all-off-slide path -> blank (no valid on-slide segment at all, distinct from a "0" path
        # length -- there is nothing measurable, not a measured-and-stationary cursor).
        off_path = [
            [0, 0, 0, 400, 300, 1000, -1, -1],
            [100, 10, 0, 400, 300, 1000, -1, -1],
        ]
        assert math.isnan(bf_metrics.mouse_path_length_px(off_path)), (
            "an all-off-slide path should yield a blank mousePathLengthPx, not 0.0"
        )
        assert math.isnan(bf_metrics.mouse_velocity_px_per_sec(off_path)), (
            "an all-off-slide path should yield a blank mouseVelocityPxPerSec"
        )

        # --- A4 targeted assert: activeFractionPct, hand-derivable, NOT clamped above 100% ---
        af_path = [[0, 0, 0, 400, 300], [5000, 10, 0, 400, 300]]  # span = 5000ms
        af = bf_metrics.active_fraction_pct(af_path, 7500)  # durationMs=7500 -> 150%
        assert abs(af - 150.0) < 1e-6, f"expected activeFractionPct == 150.0 (not clamped), got {af}"
        assert math.isnan(bf_metrics.active_fraction_pct(af_path[:1], 1000)), (
            "expected blank activeFractionPct for a <2-point path"
        )
        assert math.isnan(bf_metrics.active_fraction_pct(
            [[0, 0, 0, 400, 300], [0, 1, 1, 400, 300]], 1000
        )), "expected blank activeFractionPct for a zero-span path"
        assert math.isnan(bf_metrics.active_fraction_pct(af_path, None)), (
            "expected blank activeFractionPct for a missing durationMs"
        )

        # --- A5 targeted assert: top_hotspots deterministic tie-break (value desc, flat-index asc) ---
        tie_grid = [5.0, 5.0, 3.0, 5.0]  # 2x2 grid: ties at value 5.0 in flat cells 0, 1, 3
        top3 = bf_metrics.top_hotspots(tie_grid, 2, 2, n=3)
        assert [(r, c) for r, c, v in top3] == [(0, 0), (0, 1), (1, 1)], (
            f"expected value-desc/index-asc tie-break order, got {top3}"
        )

        # --- A5 targeted assert: top_transitions deterministic tie-break (count desc, then
        # fromCell/toCell asc) ---
        seq_tie = [0, 1, 0, 2, 0, 1]  # transitions: 0->1 (x2), 1->0 (x1), 0->2 (x1), 2->0 (x1)
        tt = bf_metrics.top_transitions(seq_tie, top_n=10)
        assert tt[0] == (0, 1, 2), f"expected the count=2 transition first, got {tt}"
        assert [(f, t) for f, t, c in tt[1:]] == [(0, 2), (1, 0), (2, 0)], (
            f"expected count-1 ties broken by (fromCell,toCell) ascending, got {tt}"
        )
        assert bf_metrics.top_transitions([]) == [], "top_transitions should be [] for an empty sequence"
        assert bf_metrics.top_transitions([0]) == [], "top_transitions should be [] for a 1-element sequence"

        # --- .zip input also works ---
        zip_path = os.path.join(tmp, "fragments.zip")
        write_fragments_to_zip(fragments, zip_path)
        zip_out = os.path.join(tmp, "out_zip")
        analyze([zip_path], zip_out, reference="s1")
        zip_metrics = pd.read_csv(os.path.join(zip_out, "metrics.csv"))
        assert len(zip_metrics) == 4, f"zip input: expected 4 metrics rows, got {len(zip_metrics)}"

        # --- Phase 3: navigation<->accuracy correlation, on a dedicated graded fixture ---
        graded_fragments, graded_rows, key_rows = build_graded_fragments()
        graded_in = os.path.join(tmp, "in_graded")
        os.makedirs(graded_in, exist_ok=True)
        write_fragments_to_dir(graded_fragments, graded_in)

        graded_csv_path = os.path.join(tmp, "graded.csv")
        _write_simple_csv(graded_csv_path, ["slideKey", "sessionId", "correct"], graded_rows)
        key_csv_path = os.path.join(tmp, "key.csv")
        _write_simple_csv(key_csv_path, ["slideKey", "correctDx"], key_rows)

        graded_out = os.path.join(tmp, "out_graded")
        analyze([graded_in], graded_out, key_csv=key_csv_path, graded_csv=graded_csv_path)

        check_nav_accuracy(out_dir, graded_out)
        check_hand_grade_only(graded_out)

        # --- Finding-1 regression: two sessions sharing a --labels display label on one slide ---
        check_label_collision_regression(tmp)

        print("OK: all selftest assertions passed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    try:
        run()
    except AssertionError as exc:
        print(f"SELFTEST FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
