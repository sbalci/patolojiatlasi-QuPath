#!/usr/bin/env python3
"""
Aggregate per-reader focus-map contributions into per-slide crowd attention maps.

Phase 2 of docs/focus-aggregation-plan.md. Reads the anonymised contribution JSONs
produced by the QuPath extension — schema "atlas-focus-contribution/1" (fixed-weight
sample counts, from "Araştırmaya katkıda bulun…") and schema "atlas-focus-contribution/2",
"/3", "/4", or "/5" (real dwell-milliseconds, weightUnit="ms", from blinded recording; /3+
additionally carry an ordered "path" scanpath time-series, /4 further adds a per-point
downsample (dsMilli) and a fragment-level "baseMagnification", and /5 further adds a
per-point cursor position (mouseX, mouseY) — all ignored here, this
aggregator only reads "grid") — groups them by slideKey, normalises each contribution (so one long session,
or one unit, can't dominate: each grid is divided by its own max before averaging, which
makes ms-vs-count irrelevant), averages them, and writes static assets the atlas website can
overlay on its OpenSeadragon viewer:

    <out>/<hash>.json    aggregate grid + readers + dimensions   (hash = sha1(slideKey)[:16])
    <out>/<hash>.png     heat preview (same colormap as the extension)
    <out>/index.json     { slideKey: {hash, readers, ...} }  lookup for the viewer

Only slides with at least --min-readers DISTINCT sessions are published, so a
single reader's viewing path can never be identified.

Pure standard library — no numpy/Pillow, no pip installs.

Usage:
    python tools/aggregate-focus.py \
        --in  ~/QuPath-atlas-focus-maps/contributions \
        --out site/focus \
        --min-readers 5
"""
import argparse
import glob
import hashlib
import json
import os
import struct
import sys
import zlib
from datetime import date

# schema/1: fixed-weight sample counts (visible-mode "Contribute"). schema/2, /3, /4, /5: real
# dwell-ms (blinded recording, weightUnit="ms"); /3+ additionally carries an ordered "path"
# scanpath, /4 further adds a per-point downsample (dsMilli) and a fragment-level
# "baseMagnification", and /5 further adds a per-point cursor position (mouseX, mouseY) — this
# aggregator ignores all of that (grid-only). All are accepted —
# normalise() below scales each contribution's grid by its own max before averaging, so the
# unit difference washes out.
SCHEMA_IN = "atlas-focus-contribution/1"
SCHEMA_IN_BLINDED = "atlas-focus-contribution/2"
SCHEMA_IN_BLINDED_V3 = "atlas-focus-contribution/3"
SCHEMA_IN_BLINDED_V4 = "atlas-focus-contribution/4"
SCHEMA_IN_BLINDED_V5 = "atlas-focus-contribution/5"
SCHEMA_OUT = "atlas-focus-aggregate/1"


def load_contributions(indir):
    items = []
    for path in glob.glob(os.path.join(indir, "**", "*.json"), recursive=True):
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
        except Exception as e:  # noqa: BLE001
            print(f"  skip (unreadable) {path}: {e}", file=sys.stderr)
            continue
        if d.get("schema") not in (SCHEMA_IN, SCHEMA_IN_BLINDED, SCHEMA_IN_BLINDED_V3,
                                    SCHEMA_IN_BLINDED_V4, SCHEMA_IN_BLINDED_V5) \
                or "slideKey" not in d or "grid" not in d:
            continue
        items.append(d)
    return items


def nearest_resample(grid, gw, gh, tw, th):
    """Nearest-neighbour resample a row-major grid from gw x gh to tw x th."""
    if gw == tw and gh == th:
        return grid
    out = [0.0] * (tw * th)
    for y in range(th):
        sy = min(gh - 1, y * gh // th)
        for x in range(tw):
            sx = min(gw - 1, x * gw // tw)
            out[y * tw + x] = grid[sy * gw + sx]
    return out


def normalise(grid):
    m = max(grid) if grid else 0.0
    if m <= 0:
        return [0.0] * len(grid)
    return [v / m for v in grid]


def heat_rgba(t):
    """Match FocusMap.heatColor: transparent->blue->cyan->green->yellow->red; alpha grows with t."""
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    if t <= 0:
        return (0, 0, 0, 0)
    if t < 0.25:
        r, g, b = 0.0, t / 0.25, 1.0
    elif t < 0.5:
        r, g, b = 0.0, 1.0, 1 - (t - 0.25) / 0.25
    elif t < 0.75:
        r, g, b = (t - 0.5) / 0.25, 1.0, 0.0
    else:
        r, g, b = 1.0, 1 - (t - 0.75) / 0.25, 0.0
    return (int(r * 255), int(g * 255), int(b * 255), int(60 + 180 * t))


def write_png(path, width, height, grid):
    """Write an 8-bit RGBA PNG of the grid (values 0..1) using the heat colormap."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for this scanline
        for x in range(width):
            raw += bytes(heat_rgba(grid[y * width + x]))

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def slide_hash(slide_key):
    return hashlib.sha1(slide_key.encode("utf-8")).hexdigest()[:16]


def aggregate(items, min_readers, today):
    """Group by slideKey, dedupe sessions, average normalised grids. Returns (index, results)."""
    groups = {}
    for d in items:
        groups.setdefault(d["slideKey"], []).append(d)

    index = {}
    results = []
    for slide_key, contribs in sorted(groups.items()):
        # One session may contribute a slide more than once — keep its richest map.
        by_session = {}
        for c in contribs:
            sid = c.get("sessionId") or f"anon-{len(by_session)}"
            prev = by_session.get(sid)
            if prev is None or c.get("sampleCount", 0) > prev.get("sampleCount", 0):
                by_session[sid] = c
        sessions = list(by_session.values())
        readers = len(sessions)
        if readers < min_readers:
            print(f"  skip {slide_key}: {readers} reader(s) < min {min_readers}")
            continue

        target = max(sessions, key=lambda c: c.get("sampleCount", 0))
        tw, th = int(target["gridWidth"]), int(target["gridHeight"])
        acc = [0.0] * (tw * th)
        for c in sessions:
            g = normalise(nearest_resample(
                [float(v) for v in c["grid"]],
                int(c["gridWidth"]), int(c["gridHeight"]), tw, th))
            for i in range(tw * th):
                acc[i] += g[i]
        agg = [v / readers for v in acc]   # mean of normalised maps → 0..1

        h = slide_hash(slide_key)
        meta = {
            "hash": h, "readers": readers,
            "gridWidth": tw, "gridHeight": th,
            "imageWidth": int(target.get("imageWidth", 0)),
            "imageHeight": int(target.get("imageHeight", 0)),
        }
        index[slide_key] = meta
        results.append((slide_key, h, tw, th, agg, meta, today))
        print(f"  publish {slide_key}: {readers} readers -> {h}")
    return index, results


def main():
    ap = argparse.ArgumentParser(description="Aggregate focus-map contributions into per-slide overlays.")
    ap.add_argument("--in", dest="indir",
                    default=os.path.expanduser("~/QuPath-atlas-focus-maps/contributions"),
                    help="folder of contribution JSONs (searched recursively)")
    ap.add_argument("--out", dest="outdir", default="site-focus",
                    help="output folder for the website's focus/ assets")
    ap.add_argument("--min-readers", type=int, default=5,
                    help="minimum distinct sessions before a slide is published (privacy)")
    args = ap.parse_args()

    today = date.today().isoformat()
    items = load_contributions(args.indir)
    print(f"Loaded {len(items)} contribution(s) from {args.indir}")
    index, results = aggregate(items, args.min_readers, today)

    os.makedirs(args.outdir, exist_ok=True)
    for slide_key, h, tw, th, agg, meta, day in results:
        out = {"schema": SCHEMA_OUT, "slideKey": slide_key, "generatedAt": day,
               **meta, "grid": [round(v, 5) for v in agg]}
        with open(os.path.join(args.outdir, f"{h}.json"), "w", encoding="utf-8") as f:
            json.dump(out, f)
        write_png(os.path.join(args.outdir, f"{h}.png"), tw, th, agg)

    with open(os.path.join(args.outdir, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"schema": SCHEMA_OUT, "generatedAt": today, "slides": index}, f, indent=2)

    print(f"Published {len(results)} slide(s) to {args.outdir}/ (index.json + per-slide .json/.png)")


if __name__ == "__main__":
    main()
