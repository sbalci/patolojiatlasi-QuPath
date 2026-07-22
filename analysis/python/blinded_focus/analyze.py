"""CLI orchestrator for the blinded_focus analysis toolkit.

Usage::

    python -m blinded_focus.analyze <input...> --out DIR [--reference SESSIONID]
        [--roi geojson] [--labels csv] [--figures]

``<input...>`` may be fragment JSON files, directories (recursed for ``*.json``), and/or
``.zip`` archives, in any mix. See ``../README.md`` for the full output-file contract.

Output files (written to ``--out DIR``):

- ``metrics.csv`` — one row per (slide, session).
- per slide: ``compare_<slug>.csv`` (pairwise cc/sim/iou, tidy long format — see below),
  ``consensus_<slug>.png``.
- per slide, when ``--reference``/``--roi`` given: ``reference_<slug>.csv``.
- per slide, when any session has a schema/3 ``path``: ``scanpath_<slug>.csv``.
- ``summary.md`` — counts, per-slide agreement, reference ranking.
- with ``--figures``: per-(slide,session) heatmap/scanpath/coverage-over-time PNGs under
  ``<out>/<slug>/``.

Design note on "matrices" (``compare_<slug>.csv`` / ``scanpath_<slug>.csv``): these are written
as *tidy long-format* tables (one row per ``(sessionA, sessionB)`` pair, including the diagonal
``sessionA == sessionB``) rather than 2D matrix-shaped CSVs, so a single file can carry multiple
metrics (cc/sim/iou) and stays trivial to `pivot()`/parse in either Python or R. As a compact way
to attach per-session (not per-pair) values, the *diagonal* row of each pair table also carries
that one extra column (``diffFromConsensus`` in ``compare_<slug>.csv``, ``transitionEntropy`` in
``scanpath_<slug>.csv``) — off-diagonal rows leave it blank.
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
#: spec's iou(a,b,thresh=0.1) default and the reference attended-map mask definition).
IOU_THRESH = 0.1


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


def load_roi_rings(roi_path):
    """Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon and
    return its rings (image-px coordinates) for rasterization."""
    with open(roi_path, "r", encoding="utf-8") as fh:
        d = json.load(fh)
    if d.get("type") == "FeatureCollection":
        rings = []
        for feat in d.get("features", []):
            rings.extend(_extract_rings(feat.get("geometry", {}) or {}))
        return rings
    if d.get("type") == "Feature":
        return _extract_rings(d.get("geometry", {}) or {})
    return _extract_rings(d)


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
        w = csv.DictWriter(fh, fieldnames=fieldnames)
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


# ---------------------------------------------------------------------------
# core pipeline
# ---------------------------------------------------------------------------

def analyze(inputs, out_dir, reference=None, roi=None, labels_csv=None, make_figures=False):
    """Run the full pipeline over ``inputs`` (files/dirs/zips) into ``out_dir``. Returns the list
    of metrics-row dicts written to ``metrics.csv`` (for programmatic/test use)."""
    os.makedirs(out_dir, exist_ok=True)
    fragments = bf_io.load_fragments(inputs)
    if not fragments:
        print(
            "warning: no valid fragments found (schema atlas-focus-contribution/{1,2,3})",
            file=sys.stderr,
        )
    labels = bf_io.load_labels(labels_csv)
    groups = bf_io.group_by_slide(fragments)
    roi_rings = load_roi_rings(roi) if roi else None

    metrics_rows = []
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

        for sid, f in sessions:
            gw, gh = int(f["gridWidth"]), int(f["gridHeight"])
            grid = [float(v) for v in f["grid"]]
            native_grid[sid] = (grid, gw, gh)
            resampled[sid] = m.resample_nn(grid, gw, gh, tw, th)

            comx, comy = m.center_of_mass(grid, gw, gh)
            row = {
                "slide": slide_key,
                "session": _session_label(f, labels),
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
            }

            path = f.get("path")
            if path:
                # Use the slide's common (tw, th) so metrics.csv values match the diagonal reuse
                # in scanpath_<slug>.csv (same visited-cell sequence definition either place).
                seq = m.visited_sequence(path, tw, th, f.get("imageWidth", 1), f.get("imageHeight", 1))
                path_seq[sid] = seq
                row["pathPoints"] = len(path)
                row["pathLengthPx"] = m.scanpath_length_px(path)
                row["nRevisits"] = m.n_revisits(seq)
                row["transitionEntropy"] = m.transition_entropy(seq)

            metrics_rows.append(row)

        # ------------------------------------------------------------------
        # cross-user compare (pairwise cc/sim/iou + consensus + agreement summary)
        # ------------------------------------------------------------------
        session_ids = [sid for sid, _ in sessions]
        consensus_grid = np.mean([m.normalise_max(resampled[sid]) for sid in session_ids], axis=0)

        compare_rows = []
        for a in session_ids:
            for b in session_ids:
                row = {
                    "sessionA": labels.get(a, a),
                    "sessionB": labels.get(b, b),
                    "cc": m.cc(resampled[a], resampled[b]),
                    "sim": m.sim(resampled[a], resampled[b]),
                    "iou": m.iou(resampled[a], resampled[b], IOU_THRESH),
                    "diffFromConsensus": "",
                }
                if a == b:
                    row["diffFromConsensus"] = 1.0 - m.cc(resampled[a], consensus_grid)
                compare_rows.append(row)
        _write_csv(
            os.path.join(out_dir, f"compare_{slide_slug}.csv"),
            compare_rows,
            ["sessionA", "sessionB", "cc", "sim", "iou", "diffFromConsensus"],
        )
        fig.heatmap(
            consensus_grid, tw, th, f"Consensus - {slide_key}",
            os.path.join(out_dir, f"consensus_{slide_slug}.png"),
        )

        mean_cc = m.mean_pairwise_cc([resampled[sid] for sid in session_ids])
        icc_val = m.icc([resampled[sid] for sid in session_ids])
        coverages = [m.coverage(native_grid[sid][0]) * 100.0 for sid in session_ids]
        durations = [float(f.get("durationMs") or 0) for _, f in sessions]
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
                ref_mask = rasterize_roi(roi_rings, tw, th, img_w, img_h)
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
                    return {
                        "session": labels.get(sid, sid),
                        "nss": m.nss(other, ref_mask),
                        "aucJudd": m.auc_judd(other, ref_mask),
                        "cc": m.cc(other, ref_map),
                        "iou": m.iou(other, ref_map, IOU_THRESH),
                        "refCoveragePct": ref_cov * 100.0,
                        "timeOnRefMs": time_on,
                        "timeOffRefMs": time_off,
                    }

                ref_rows = [_ref_row(sid) for sid in session_ids if sid != reference]
                if reference and reference in resampled:
                    ref_rows.insert(0, _ref_row(reference))

                ref_rows.sort(key=lambda r: r["nss"] if r["nss"] == r["nss"] else -1e9, reverse=True)
                _write_csv(
                    os.path.join(out_dir, f"reference_{slide_slug}.csv"),
                    ref_rows,
                    ["session", "nss", "aucJudd", "cc", "iou", "refCoveragePct",
                     "timeOnRefMs", "timeOffRefMs"],
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
                    }
                    if a == b:
                        row["transitionEntropy"] = m.transition_entropy(path_seq[a])
                    scan_rows.append(row)
            _write_csv(
                os.path.join(out_dir, f"scanpath_{slide_slug}.csv"),
                scan_rows,
                ["sessionA", "sessionB", "levenshteinSim", "transitionEntropy"],
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
                fig.heatmap(
                    grid, gw, gh, f"{slide_key} - {label}",
                    os.path.join(slide_out, f"{sess_slug}_heatmap.png"),
                )
                path = f.get("path")
                if path:
                    fig.scanpath_overlay(
                        grid, gw, gh, path, f.get("imageWidth", 1), f.get("imageHeight", 1),
                        f"{label} scanpath", os.path.join(slide_out, f"{sess_slug}_scanpath.png"),
                    )
                    fig.coverage_over_time(
                        path, gw, gh, f.get("imageWidth", 1), f.get("imageHeight", 1),
                        f"{label} coverage over time",
                        os.path.join(slide_out, f"{sess_slug}_coverage.png"),
                    )

    _write_csv(
        os.path.join(out_dir, "metrics.csv"),
        metrics_rows,
        ["slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
         "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
         "nRevisits", "transitionEntropy"],
    )
    _write_summary(out_dir, groups, slide_summaries, reference_summaries)
    return metrics_rows


def _write_summary(out_dir, groups, slide_summaries, reference_summaries):
    lines = ["# Blinded-focus analysis summary", "", f"- Slides analyzed: {len(groups)}", ""]
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
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="python -m blinded_focus.analyze",
        description="Analyze blinded-focus fragments (schema atlas-focus-contribution/1,2,3): "
                    "per-session metrics, cross-user agreement, reference/ROI comparison, "
                    "scanpath sequence metrics.",
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
        "--figures", action="store_true",
        help="also write per-(slide,session) heatmap/scanpath/coverage-over-time PNGs",
    )
    args = ap.parse_args(argv)
    analyze(
        args.inputs, args.out, reference=args.reference, roi=args.roi,
        labels_csv=args.labels, make_figures=args.figures,
    )


if __name__ == "__main__":
    main()
