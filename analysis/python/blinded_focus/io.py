"""Fragment loading for blinded-focus contribution JSON.

Fragment schema (produced by the QuPath extension's blinded-focus recording feature):
``atlas-focus-contribution/{1,2,3}``. Common fields: ``slideKey``, ``sessionId``,
``imageWidth``, ``imageHeight``, ``gridWidth``, ``gridHeight``, ``grid`` (row-major, length
``gridWidth*gridHeight``; milliseconds for schema /2 and /3, fixed-weight sample counts for
schema /1), ``durationMs`` (/2, /3), ``sampleCount``, ``date``. Schema /3 additionally carries an
ordered ``path``: a list of ``[tRelMs, cx, cy, w, h]`` integers (viewport center + visible extent
in image pixels, time relative to the start of that slide's blinded recording).

"User" = ``sessionId`` (one recording sitting); identity mapping to a human label is out-of-band
(see ``load_labels``) — the fragment data itself stays anonymous.
"""
import csv
import glob
import hashlib
import json
import os
import re
import zipfile

#: Accepted fragment schemas. /1 = fixed-weight sample counts (visible "Contribute" mode).
#: /2, /3 = real dwell-ms (blinded recording, weightUnit="ms"). /3 additionally has "path".
SCHEMAS = {
    "atlas-focus-contribution/1",
    "atlas-focus-contribution/2",
    "atlas-focus-contribution/3",
}


def _is_valid_fragment(d):
    return (
        isinstance(d, dict)
        and d.get("schema") in SCHEMAS
        and "slideKey" in d
        and "grid" in d
        and "gridWidth" in d
        and "gridHeight" in d
    )


def _parse_fragment(raw_text, source):
    """Parse one JSON document; return the fragment dict (tagged with "_source") or None."""
    try:
        d = json.loads(raw_text)
    except (json.JSONDecodeError, ValueError):
        return None
    if not _is_valid_fragment(d):
        return None
    d = dict(d)
    d["_source"] = source
    return d


def load_fragments(paths):
    """Load blinded-focus fragment JSONs from files, directories, or ``.zip`` archives.

    ``paths`` may mix any of: a single fragment ``.json`` file, a directory (recursively globbed
    for ``*.json``), or a ``.zip`` archive (its ``*.json`` entries are read directly, no
    extraction to disk). Only dicts whose ``schema`` is in :data:`SCHEMAS` and that carry
    ``slideKey``/``grid``/``gridWidth``/``gridHeight`` are kept; anything else (unreadable JSON,
    unrelated files, wrong schema) is silently skipped.

    Returns a flat list of fragment dicts (each with an added ``"_source"`` string used only for
    diagnostics/error messages).
    """
    fragments = []
    for p in paths:
        p = str(p)
        if os.path.isdir(p):
            for jf in sorted(glob.glob(os.path.join(p, "**", "*.json"), recursive=True)):
                with open(jf, "r", encoding="utf-8") as fh:
                    frag = _parse_fragment(fh.read(), jf)
                if frag is not None:
                    fragments.append(frag)
        elif os.path.isfile(p) and zipfile.is_zipfile(p):
            with zipfile.ZipFile(p) as zf:
                for name in sorted(zf.namelist()):
                    if name.lower().endswith(".json"):
                        with zf.open(name) as fh:
                            text = fh.read().decode("utf-8")
                        frag = _parse_fragment(text, f"{p}!{name}")
                        if frag is not None:
                            fragments.append(frag)
        elif os.path.isfile(p):
            with open(p, "r", encoding="utf-8") as fh:
                frag = _parse_fragment(fh.read(), p)
            if frag is not None:
                fragments.append(frag)
        else:
            raise FileNotFoundError(f"input path not found: {p}")
    return fragments


def group_by_slide(fragments):
    """Group fragment dicts by ``slideKey`` -> ``{slideKey: [fragment, ...]}`` (insertion order)."""
    groups = {}
    for f in fragments:
        groups.setdefault(f["slideKey"], []).append(f)
    return groups


def slug(text, maxlen=40):
    """Filesystem-safe slug for a slideKey/sessionId: ascii alnum + '-', hash-suffixed for
    uniqueness (so two different keys that collapse to the same readable prefix never collide)."""
    text = str(text)
    base = re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-").lower()
    base = base[:maxlen] if base else "x"
    h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:8]
    return f"{base}-{h}"


def load_labels(csv_path):
    """Load a ``sessionId,label`` CSV (optional header row) into a ``{sessionId: label}`` dict.

    Returns ``{}`` if ``csv_path`` is falsy. This is the out-of-band identity mapping: the
    fragment data stays anonymous (sessionId only); a coordinator supplies this CSV separately to
    render human-readable output.
    """
    if not csv_path:
        return {}
    with open(csv_path, newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))
    start = 0
    if rows and rows[0][0].strip().lower() in ("sessionid", "session_id", "session"):
        start = 1
    labels = {}
    for row in rows[start:]:
        if len(row) >= 2 and row[0].strip():
            labels[row[0].strip()] = row[1].strip()
    return labels
