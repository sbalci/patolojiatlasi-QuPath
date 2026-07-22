# Blinded Evaluation Tooling (scanpath + render + analysis) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Record the ordered viewport scanpath (extension), then render per-slide/per-user heatmap + scanpath PNGs and run cross-user + reference comparison analysis — all offline, stdlib-only.

**Architecture:** Task 1 adds `path` to the blinded JSON (schema/3) in `FocusHeatmap`. Tasks 2–3 are pure-stdlib Python tools sharing `blinded_focus_common.py`.

**Tech Stack:** Java 21 / QuPath 0.6 (Task 1); Python 3 **stdlib only** — no numpy/matplotlib/pip (Tasks 2–3), matching `tools/aggregate-focus.py`.

## Global Constraints

- **Data-only + anonymized preserved (Task 1):** the `path` is written only via `buildBlindedJson` (the existing anonymized JSON path) — never a PNG, no username; `slideKey` stays `sha256:`-anonymized; the `currentMapBlinded` guard on `save()` is untouched.
- **Additive schema:** `atlas-focus-contribution/3` = `/2` + `"path"`; keep `grid`/`durationMs`/`weightUnit`/dims. The aggregator accepts `/1,/2,/3`.
- **Stdlib-only Python** (Tasks 2–3): no third-party imports. PNGs via the aggregator's hand-rolled `heat_rgba`/`write_png`. Each tool has a numpy-free `--selftest`.
- **Path capped** at `MAX_PATH_POINTS = 20000`; integers (image px); `tRelMs` relative to slide start.
- Best-effort: path append/serialize never throws into `tick()`.

---

### Task 1: Scanpath recording in `FocusHeatmap` (+ aggregator schema/3)

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java`
- Modify: `tools/aggregate-focus.py` (accept schema/3)

**Pattern references (read first):** `FocusHeatmap.java` FULL — the blinded branch of `tick()` (where `getDisplayedRegionShape().getBounds()` + `deposit(...)` happen), `startBlinded`/`switchTo`/`buildBlindedJson`/`CONTRIBUTION_SCHEMA`/`sessionId`; `focus/FocusMap.java`.

- [ ] **Step 1: Path state + append**

Add fields: `private final java.util.List<int[]> blindedPath = new java.util.ArrayList<>();`, `private long blindedSlideStartMs;`, `private static final int MAX_PATH_POINTS = 20000;`, `private boolean blindedPathCapped;`.
In the blinded branch of `tick()`, WHEN a deposit actually happens (active, `ms > 0`), also append a path point — inside the same `if (ms > 0 && currentMap != null)` block, after the deposit:
```java
if (blindedPath.size() < MAX_PATH_POINTS) {
    long tRel = now - blindedSlideStartMs;
    blindedPath.add(new int[]{(int) tRel, (int) Math.round(b.getX() + b.getWidth() / 2.0),
            (int) Math.round(b.getY() + b.getHeight() / 2.0),
            (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight())});
} else if (!blindedPathCapped) {
    blindedPathCapped = true;
    logger.info("Blinded scanpath reached {} points; further points dropped.", MAX_PATH_POINTS);
}
```
(`now`, `b` are already computed in that branch for the dwell math — reuse them.)

- [ ] **Step 2: Reset on start + slide switch**

In `startBlinded()`: `blindedPath.clear(); blindedPathCapped = false; blindedSlideStartMs = System.currentTimeMillis();`. In `switchTo()` when a fresh blinded slide map is created (same spot `blindedTicks` is reset): `blindedPath.clear(); blindedPathCapped = false; blindedSlideStartMs = System.currentTimeMillis();`.

- [ ] **Step 3: Serialize into `buildBlindedJson` + bump schema**

Change `CONTRIBUTION_SCHEMA` (the blinded one) to `"atlas-focus-contribution/3"`. In `buildBlindedJson(...)`, after the existing fields, add the path as a nested array-of-arrays: `m.put("path", <snapshot of blindedPath as List<int[]>>);` (Gson serializes `int[]` as a JSON array, so a `List<int[]>` → `[[t,cx,cy,w,h],...]`). Snapshot defensively (`new ArrayList<>(blindedPath)`) so a concurrent append can't corrupt serialization. Everything else in `buildBlindedJson` is unchanged (still anonymized `slideKey`, `sessionId`, ms grid, `durationMs`, date). No new write path — every blinded save/checkpoint/shutdown already calls `buildBlindedJson`.

- [ ] **Step 4: Aggregator accepts schema/3**

In `tools/aggregate-focus.py`, add `SCHEMA_IN_BLINDED_V3 = "atlas-focus-contribution/3"` (or a set) and include it in the accepted-schema check in `load_contributions` (it reads `grid`; `path` is ignored). Update the module docstring's schema note.

- [ ] **Step 5: Build + commit**

`./gradlew --offline build` → SUCCESSFUL (existing tests green). Sanity-run the aggregator selftest if it has one, else a quick `python3 tools/aggregate-focus.py --help`.
```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java tools/aggregate-focus.py
git commit -m "feat(focus): record ordered scanpath in blinded JSON (schema/3); aggregator accepts /3"
```

---

### Task 2: `blinded_focus_common.py` + `render-blinded-focus.py` (stdlib)

**Files:**
- Create: `tools/blinded_focus_common.py`
- Create: `tools/render-blinded-focus.py`

**`blinded_focus_common.py` — produces (all pure-stdlib):**
- `load_fragments(paths) -> list[dict]` — each `paths` entry may be a file, dir (recurse `*.json`), or `.zip` (read `*.json` entries via `zipfile`). Keep dicts with `schema` in `{"atlas-focus-contribution/1","/2","/3"}` (define the set) and both `slideKey` + `grid`. Tag each with `_source`.
- `group_by_slide(frags) -> dict[str, list[dict]]`; `slug(slideKey) -> str` (filesystem-safe: keep `[A-Za-z0-9._-]`, else `_`, cap length; used for output subdirs).
- `normalise(grid) -> list[float]` (÷max, 0 if empty); `resample(grid, gw, gh, tw, th)` (nearest, copy from aggregator); `coverage(grid) -> float` (nonzero/len); `entropy(grid) -> float` (Shannon of the ÷sum distribution, 0 if empty); `center_of_mass(grid, gw, gh) -> (fx, fy)` in [0,1]; `top_hotspots(grid, gw, gh, n) -> list[(x,y,val)]`.
- `cosine(a,b)`, `pearson(a,b)`, `iou(a,b,thresh=0.0)` (Jaccard of >thresh cells) — callers resample to a common (gw,gh) first via `resample`.
- `heat_rgba(t)` + `write_png(path,w,h,grid)` — copy from `aggregate-focus.py` (same colormap). Add `write_rgba_png(path,w,h,rgba_bytes)` for overlays/diff (a general RGBA writer).
- `load_labels(path) -> dict` (sessionId→label from a 2-col CSV; empty if None).

- [ ] **Step 1: Implement `blinded_focus_common.py`** with the above. Keep it importable AND
  self-contained (no third-party imports).

- [ ] **Step 2: Implement `render-blinded-focus.py`**

CLI: `<input...> --out DIR [--labels csv] [--scale N=6] [--min-sessions N=1]`.
- Load + group by slide. For each (slide, session) fragment: upscale the grid ×scale and write
  `<out>/<slug>/<label>.png` via `write_png` (normalized grid). If `path` present, draw the ordered
  scanpath onto the RGBA raster (map each point's `cx,cy` image→grid cell→scaled px; draw integer
  line segments between consecutive points; color start→end blue→red; mark first point) and write
  via `write_rgba_png`. Write a sidecar `<label>.txt` with slide/session/durationMs/coverage/points.
- Per slide: `_consensus.png` = mean of per-session normalized grids resampled to the max (gw,gh)
  among sessions (skip if `< min-sessions`). Per session: `_diff_<label>.png` = (session norm −
  consensus) mapped through a diverging colormap (blue neg / white 0 / red pos).
- `--selftest`: synthesize 2 sessions for one slide (one with a `path`), run render to a temp dir,
  assert the two session PNGs + `_consensus.png` + a `_diff_*.png` exist and start with the PNG
  magic bytes; assert the pathful one differs from the pathless render. Exit non-zero on failure.

- [ ] **Step 3: Run selftest + a real render**

`python3 tools/render-blinded-focus.py --selftest` → OK. Then render the real sample:
`python3 tools/render-blinded-focus.py "$HOME/QuPath-atlas-focus-maps/contributions" --out /tmp/render-out` and confirm PNGs are produced (best-effort; the sample is schema/2, no path — scanpath simply skipped).

- [ ] **Step 4: Commit**

```bash
git add tools/blinded_focus_common.py tools/render-blinded-focus.py
git commit -m "feat(tools): render-blinded-focus.py — per-slide/per-user heatmap + scanpath + consensus/diff PNGs"
```

---

### Task 3: `analyze-blinded-focus.py` (stdlib)

**Files:**
- Create: `tools/analyze-blinded-focus.py`
- (uses `blinded_focus_common.py` from Task 2)

- [ ] **Step 1: Implement per-session metrics + cross-user + reference + scanpath**

CLI: `<input...> --out DIR [--reference SESSIONID] [--roi geojson] [--labels csv] [--iou-thresh 0.1]`.
- **`metrics.csv`** — header `slide,session,durationMs,sampleCount,coveragePct,entropy,comX,comY,peakDwell,nHotspots,pathPoints,pathLengthPx,nRevisits`; one row per (slide, session). `pathLengthPx` = sum of consecutive-center distances (0 if no path); `nRevisits` = count of path steps entering a grid cell already visited earlier (0 if no path).
- **Per slide `compare_<slug>.csv` + `_consensus.png`:** sessions resampled to a common grid; write
  the pairwise `cosine` and `iou` matrices (labeled rows/cols), a `consensus` (mean of normalized)
  PNG via common `write_png`, and each session's `diffFromConsensus = 1 - cosine(session,consensus)`.
  A `spread` line: min/median/max of durationMs + coveragePct across sessions.
- **Reference (`--reference SESSIONID` and/or `--roi geojson`):** build the reference region — from
  the reference session's above-`iou-thresh` normalized cells, OR from a GeoJSON polygon rasterized
  to the grid (pure-Python ray-casting point-in-polygon; map polygon image coords → grid cells using
  the fragment dims). For each other session on that slide write `reference_<slug>.csv`:
  `cosineToRef, iouToRef, refCoveragePct` (fraction of reference cells the session also dwelt on),
  `timeOnRefMs, timeOffRefMs` (session dwell-ms inside vs outside the reference region), ranked.
- **Scanpath sequence (schema/3):** for sessions with a `path`, map the path to its ordered visited
  grid-cell sequence (run-length dedup consecutive repeats), compute normalized Levenshtein distance
  between each pair (pure Python), write `scanpath_<slug>.csv` (a similarity = 1 − normDist matrix).
  Skip cleanly when a session has no path.
- **`summary.md`:** N fragments, N slides, per-slide session/label list, headline cross-user
  agreement (median pairwise cosine per slide), and the reference ranking if given.

- [ ] **Step 2: `--selftest`**

Synthesize 3 sessions on one slide (2 similar, 1 different; give ≥1 a `path`) + designate one as
reference. Assert: `metrics.csv` has 3 rows with the expected columns; `compare_<slug>.csv` matrix
is symmetric with 1.0 on the diagonal and the two similar sessions score higher than the dissimilar
pair; `reference_<slug>.csv` shows the reference-vs-itself overlap ≈ 1.0; `scanpath_<slug>.csv`
diagonal = 1.0; `summary.md` exists. Exit non-zero on any failure.

- [ ] **Step 3: Run selftest + real sample**

`python3 tools/analyze-blinded-focus.py --selftest` → OK. Then
`python3 tools/analyze-blinded-focus.py "$HOME/QuPath-atlas-focus-maps/contributions" --out /tmp/analyze-out` → confirm `metrics.csv` + `summary.md` produced on the real (schema/2) sample.

- [ ] **Step 4: README/tools note + commit**

Add a short note (tools/ or README) describing the offline evaluation pipeline: record (blinded,
schema/3 carries scanpath) → `render-blinded-focus.py` (per-slide/user heatmaps + scanpath +
consensus/diff) → `analyze-blinded-focus.py` (metrics + cross-user agreement + reference comparison
+ scanpath sequence). Note stdlib-only + the sessionId=user, out-of-band identity mapping.
```bash
git add tools/analyze-blinded-focus.py README.md
git commit -m "feat(tools): analyze-blinded-focus.py — metrics + cross-user consensus + reference + scanpath compare"
```

---

## Self-Review (author)

- **Spec coverage:** §2 scanpath → Task 1 (append/reset/serialize/cap + aggregator /3). §3 shared loader → Task 2 Step 1. §4 render → Task 2. §5 analyze (cross-user + reference + scanpath) → Task 3. §7 selftests → Tasks 2–3. ✅
- **Guarantees:** path serialized only via `buildBlindedJson` (data-only + anon preserved); schema additive; stdlib-only Python (numpy-free selftests run in this env); path capped.
- **Type/interface consistency:** `blinded_focus_common` functions consumed identically by render + analyze; schema set `{/1,/2,/3}` shared; PNG writer reused.
- **Placeholder scan:** Task 1 gives exact append/serialize code; Tasks 2–3 give exact CLI, function contracts, formulas, and selftest assertions (Python bodies filled by the implementer, validated by the numpy-free selftests — the stdlib analog of this repo's "dialogs are manual, logic is tested").
