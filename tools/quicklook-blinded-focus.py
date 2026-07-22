#!/usr/bin/env python3
"""Zero-dependency quick-look for blinded focus fragments.

Renders each recorded blinded-focus fragment (schema atlas-focus-contribution/1, /2, /3) to a
heatmap PNG — and, when a scanpath is present (schema /3), overlays the ordered navigation path.
No numpy/Pillow, no pip: pure standard library, so you can eyeball a session without setting up
the advanced R/Python analysis environments (see ../analysis/).

Usage:
    python3 tools/quicklook-blinded-focus.py <input...> --out DIR [--scale N] [--labels labels.csv]
    python3 tools/quicklook-blinded-focus.py --selftest

<input...> may be JSON files, directories (searched recursively for *.json), or .zip archives
(the file a participant sends the coordinator). One PNG per (slide, session) at
<out>/<slug>/<label>.png, plus a <label>.txt sidecar with the headline numbers.
"""

import argparse
import glob
import json
import os
import struct
import sys
import zipfile
import zlib

SCHEMAS = {
    "atlas-focus-contribution/1",
    "atlas-focus-contribution/2",
    "atlas-focus-contribution/3",
}


# --- loading -------------------------------------------------------------------------------------

def _accept(d):
    return (isinstance(d, dict) and d.get("schema") in SCHEMAS and "grid" in d and "slideKey" in d
            and "gridWidth" in d and "gridHeight" in d)


def load_fragments(paths):
    """Load fragment dicts from files, directories (recursive *.json), and .zip archives."""
    frags = []
    for p in paths:
        if os.path.isdir(p):
            for f in glob.glob(os.path.join(p, "**", "*.json"), recursive=True):
                _try_file(f, frags)
        elif p.lower().endswith(".zip") and zipfile.is_zipfile(p):
            with zipfile.ZipFile(p) as z:
                for name in z.namelist():
                    if name.lower().endswith(".json"):
                        try:
                            d = json.loads(z.read(name).decode("utf-8"))
                        except Exception:
                            continue
                        if _accept(d):
                            d["_source"] = p + "!" + name
                            frags.append(d)
        else:
            _try_file(p, frags)
    return frags


def _try_file(path, out):
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
    except Exception:
        return
    if _accept(d):
        d["_source"] = path
        out.append(d)


def slug(s):
    keep = [c if (c.isalnum() or c in "._-") else "_" for c in str(s)]
    return "".join(keep)[:80] or "slide"


def load_labels(path):
    labels = {}
    if not path:
        return labels
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split(",")
                if len(parts) >= 2 and parts[0] != "sessionId":
                    labels[parts[0]] = parts[1]
    except Exception:
        pass
    return labels


# --- colormap + PNG (RGBA, hand-rolled — matches aggregate-focus.py / FocusMap.heatColor) --------

def heat_rgba(t):
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
    return [int(r * 255), int(g * 255), int(b * 255), int(60 + 180 * t)]


def _png_bytes(width, height, rgba):
    """rgba: flat list length width*height*4."""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0
        raw += bytes(rgba[y * stride:(y + 1) * stride])

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    out = bytearray(b"\x89PNG\r\n\x1a\n")
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    return bytes(out)


def _blend(rgba, w, h, x, y, color):
    """Draw an opaque dot (color=[r,g,b]) at (x,y) over the RGBA buffer."""
    if 0 <= x < w and 0 <= y < h:
        i = (y * w + x) * 4
        rgba[i:i + 4] = [color[0], color[1], color[2], 255]


def _line(rgba, w, h, x0, y0, x1, y1, color):
    """Bresenham line of opaque `color`."""
    dx = abs(x1 - x0)
    dy = -abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx + dy
    while True:
        _blend(rgba, w, h, x0, y0, color)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


# --- rendering -----------------------------------------------------------------------------------

def render_fragment(frag, scale):
    gw, gh = frag["gridWidth"], frag["gridHeight"]
    grid = frag["grid"]
    mx = max(grid) if grid else 0.0
    W, H = gw * scale, gh * scale
    rgba = [0] * (W * H * 4)
    for r in range(gh):
        for c in range(gw):
            v = grid[r * gw + c]
            t = (v / mx) ** 0.5 if mx > 0 else 0.0   # gamma lift so faint dwell shows
            col = heat_rgba(t)
            for yy in range(r * scale, (r + 1) * scale):
                base = (yy * W + c * scale) * 4
                for xx in range(scale):
                    rgba[base + xx * 4: base + xx * 4 + 4] = col

    # scanpath overlay (schema/3): draw the ordered path, graded blue(start)->red(end)
    path = frag.get("path")
    if path and frag.get("imageWidth") and frag.get("imageHeight"):
        iw, ih = frag["imageWidth"], frag["imageHeight"]
        pts = []
        for p in path:
            cx, cy = p[1], p[2]
            px = int(cx / iw * gw * scale)
            py = int(cy / ih * gh * scale)
            pts.append((px, py))
        n = len(pts)
        for k in range(1, n):
            f = k / max(1, n - 1)
            col = [int(255 * f), 30, int(255 * (1 - f))]  # blue->red over time
            _line(rgba, W, H, pts[k - 1][0], pts[k - 1][1], pts[k][0], pts[k][1], col)
        if pts:
            for dx in range(-2, 3):
                for dy in range(-2, 3):
                    _blend(rgba, W, H, pts[0][0] + dx, pts[0][1] + dy, [255, 255, 255])
    return W, H, rgba


def coverage_pct(grid):
    return 100.0 * sum(1 for v in grid if v > 0) / len(grid) if grid else 0.0


def run(inputs, out_dir, scale, labels_path):
    frags = load_fragments(inputs)
    if not frags:
        print("No blinded-focus fragments found.", file=sys.stderr)
        return 0
    labels = load_labels(labels_path)
    written = 0
    for frag in frags:
        sd = os.path.join(out_dir, slug(frag["slideKey"]))
        os.makedirs(sd, exist_ok=True)
        sess = frag.get("sessionId", "session")
        # slug() the label before it becomes a filename: sessionId/label come from untrusted
        # participant fragments, so a value like "../../x" or "/abs/path" must not escape out_dir.
        label = slug(labels.get(sess, sess[:8] if len(sess) >= 8 else sess))
        W, H, rgba = render_fragment(frag, scale)
        png = os.path.join(sd, label + ".png")
        with open(png, "wb") as f:
            f.write(_png_bytes(W, H, rgba))
        with open(os.path.join(sd, label + ".txt"), "w", encoding="utf-8") as f:
            f.write("slideKey: %s\nsession: %s\nschema: %s\ndurationMs: %s\ncoveragePct: %.1f\n"
                    "pathPoints: %d\n" % (
                        frag["slideKey"], sess, frag.get("schema"), frag.get("durationMs", ""),
                        coverage_pct(frag["grid"]), len(frag.get("path") or [])))
        written += 1
    print("Wrote %d heatmap(s) to %s" % (written, out_dir))
    return written


# --- selftest ------------------------------------------------------------------------------------

def _selftest():
    import tempfile
    gw, gh = 8, 6
    grid = [0.0] * (gw * gh)
    grid[2 * gw + 3] = 10.0
    grid[2 * gw + 4] = 6.0
    frag2 = {"schema": "atlas-focus-contribution/2", "slideKey": "https://x/a/HE.dzi",
             "sessionId": "sess-aaaa1111", "imageWidth": 800, "imageHeight": 600,
             "gridWidth": gw, "gridHeight": gh, "grid": grid, "durationMs": 5000.0}
    frag3 = dict(frag2, sessionId="sess-bbbb2222", schema="atlas-focus-contribution/3",
                 path=[[0, 300, 200, 100, 80], [500, 320, 210, 100, 80], [1000, 500, 400, 60, 50]])
    with tempfile.TemporaryDirectory() as d:
        # also exercise the .zip input path
        import json as _j
        zp = os.path.join(d, "session.zip")
        with zipfile.ZipFile(zp, "w") as z:
            z.writestr("focus-blinded__a__t.json", _j.dumps(frag3))
        out = os.path.join(d, "out")
        n = run([_frag_file(d, "a.json", frag2), zp], out, 6, None)
        assert n == 2, "expected 2 fragments (file + zip), got %d" % n
        pngs = glob.glob(os.path.join(out, "**", "*.png"), recursive=True)
        assert len(pngs) == 2, "expected 2 PNGs, got %d" % len(pngs)
        for p in pngs:
            with open(p, "rb") as f:
                assert f.read(8) == b"\x89PNG\r\n\x1a\n", "not a PNG: " + p
    print("OK: quicklook selftest passed")


def _frag_file(d, name, frag):
    import json as _j
    p = os.path.join(d, name)
    with open(p, "w", encoding="utf-8") as f:
        _j.dump(frag, f)
    return p


def main():
    ap = argparse.ArgumentParser(description="Zero-dep quick-look heatmaps for blinded focus data.")
    ap.add_argument("inputs", nargs="*", help="JSON files, directories, or .zip archives")
    ap.add_argument("--out", default="quicklook-out", help="output directory")
    ap.add_argument("--scale", type=int, default=6, help="pixels per grid cell (default 6)")
    ap.add_argument("--labels", help="CSV sessionId,label to name outputs")
    ap.add_argument("--selftest", action="store_true", help="run the built-in synthetic self-test")
    args = ap.parse_args()
    if args.selftest:
        _selftest()
        return
    if not args.inputs:
        ap.error("provide input files/dirs/zips, or --selftest")
    run(args.inputs, args.out, args.scale, args.labels)


if __name__ == "__main__":
    main()
