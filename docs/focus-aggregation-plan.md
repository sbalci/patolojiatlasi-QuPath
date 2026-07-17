# Plan: aggregating reader focus across the atlas (research & education)

Turn the per-reader focus maps produced by the QuPath extension into a **per-slide crowd attention
map** — "where do pathologists look on this slide?" — and show it back on the atlas website. Intended
for research (a human-attention/saliency dataset, comparable against AI attention) and education
(trainees see where experienced readers concentrate; instructors spot systematically missed regions).

Status: the **client side exists** (the extension writes anonymised contribution files locally);
**uploading is disabled** because the atlas website is a **static site with no receiver yet**. This
doc is the plan to close that gap.

## Data flow

```
QuPath extension                 collection                 aggregation                 website
────────────────                 ──────────                 ───────────                 ───────
Odak ısı haritası →              gather many                sum + normalise             OpenSeadragon
"Araştırmaya         ──file──►   contribution     ──►       grids per slideKey  ──►      overlay toggle
 katkıda bulun…"                 JSONs                       → aggregate grid            "attention map"
(anonymous, local)               (see Phase 1)               (+ PNG, + N readers)        on the slide
```

## The contribution format (already produced)

Each contribution is one JSON file under `~/QuPath-atlas-focus-maps/contributions/`, schema
`atlas-focus-contribution/1`:

```jsonc
{
  "schema": "atlas-focus-contribution/1",
  "slideKey": "https://images.patolojiatlasi.com/<case>/<image>.dzi", // stable → GROUP BY this
  "sessionId": "<random uuid>",   // anonymous; NOT a user identity
  "imageWidth": 98304, "imageHeight": 76800,
  "gridWidth": 256, "gridHeight": 200,
  "sampleCount": 1234,
  "date": "2026-07-17",           // date only, no time
  "grid": [ /* gridWidth*gridHeight raw dwell counts, row-major */ ]
}
```

Deliberately anonymous: **no user name**, no time-of-day, only a random per-session id (so the server
can de-duplicate/weight sessions without identifying anyone). All contributions for one slide share a
`slideKey`, and grids are directly addable once resampled to a common resolution.

## Phase 1 — collection (pick one; they can coexist)

The site is **static (GitHub Pages)**, so there is no origin server to POST to. Options, simplest first:

| Option | How | Pros | Cons |
|--------|-----|------|------|
| **A. Manual / batch** *(recommended start)* | Contributors send their `contributions/*.json` (shared folder / OSF / a PR to a data repo); a maintainer runs the Phase 2 script. | Zero infrastructure; works today; full control/consent. | Not live; manual step. |
| **B. Serverless receiver** | A tiny **Cloudflare Worker / Netlify function / Val.town** endpoint accepts `POST application/json` and appends to storage (KV / D1 / object store). Point `UPLOAD_ENDPOINT` at it and set `UPLOAD_ENABLED = true` in `FocusHeatmap`. | Live collection; still keeps the site static. | One small service to run + secure (rate-limit, size-cap, CORS, spam). |
| **C. GitHub-native** | A form or the extension opens a prefilled GitHub issue/discussion containing the JSON, or commits it to a `focus-data/` repo; a GitHub Action aggregates. | No separate server; Pages-friendly. | Clunky UX; public data by default. |

**Enabling upload (option B) is a two-line client change** — the interface is already built:
in `FocusHeatmap.java` set `UPLOAD_ENABLED = true` and `UPLOAD_ENDPOINT = "https://…"`. The endpoint
must accept a `POST` of the contribution JSON (same-origin or CORS-open), cap body size, and rate-limit.

## Phase 2 — aggregation (a script, run server-side or by a maintainer)

Per `slideKey`:

1. **Load** all contributions for the slide.
2. **Resample** each `grid` to a common resolution (e.g. 256×N to the slide aspect) if they differ.
3. **Normalise each contribution** before summing — divide by its own max (or by `sampleCount`) — so
   a single long session can't dominate the pooled map.
4. **Sum** the normalised grids; divide by the number of contributions → the aggregate grid.
5. **Threshold N.** Only publish an aggregate once **≥ N distinct sessions** (e.g. N = 5) contributed,
   so no single reader's path is identifiable. Record `readers = N` alongside.
6. **Emit static assets:** `focus/<hash(slideKey)>.json` (aggregate grid + `readers` + dims) and an
   optional `focus/<hash(slideKey)>.png` heat preview, committed to / served by the static site.

**Implemented:** [`tools/aggregate-focus.py`](../tools/aggregate-focus.py) does exactly this — pure
standard library (no numpy/Pillow), so a maintainer just runs it:

```bash
python tools/aggregate-focus.py \
    --in  ~/QuPath-atlas-focus-maps/contributions \
    --out site/focus \
    --min-readers 5
```

It groups by `slideKey`, **de-duplicates by `sessionId`** (a session that contributed a slide twice
counts once), normalises each contribution by its max, averages, enforces the min-readers threshold,
and writes `<hash>.json` + `<hash>.png` (heat preview, same colormap as the extension) + an
`index.json` mapping `slideKey → {hash, readers, dims}` for the viewer to look up. `hash =
sha1(slideKey)[:16]`. Runs in a GitHub Action (option A/C) or inside the serverless function (option B).

## Phase 3 — display on the atlas viewer

The atlas viewer is already OpenSeadragon, and the `pathologyatlas/template` viewer (`HE.html`)
already contains a heatmap **painter** (it renders a dwell grid onto a canvas over the navigator).
Reuse it, fed by the *aggregate* grid instead of the live session:

- On slide open, fetch `focus/<hash(slideKey)>.json` (404 → no attention map yet, hide the toggle).
- Add a **"Attention map"** toggle next to the existing filters/magnifier; paint the aggregate grid
  as a translucent overlay on the main image (and/or the navigator), same colormap as the extension.
- Show **`readers = N`** and a small legend so viewers know it's pooled, anonymised data.

## Privacy & governance (load-bearing)

- **Anonymous by construction:** no user name, date-only, random session id. Do not add identifying
  fields when wiring upload.
- **Opt-in per contribution:** "Araştırmaya katkıda bulun…" is an explicit action, not automatic.
- **Minimum-N before publish** (Phase 2 step 5) so an aggregate can't reveal one person's viewing path.
- **No patient data:** focus grids carry no image pixels and no PHI; slides are already the anonymised
  atlas set (same policy as the OSF package).
- Keep raw contributions access-controlled; publish only the pooled aggregate.

## Checklist to go live

- [ ] Choose Phase 1 option (start with **A. manual/batch**).
- [x] Write the Phase 2 aggregation script → [`tools/aggregate-focus.py`](../tools/aggregate-focus.py).
- [ ] Add the Phase 3 overlay toggle + fetch to the viewer template.
- [ ] (If option B) stand up the receiver, then set `UPLOAD_ENABLED = true` + `UPLOAD_ENDPOINT` in
      `FocusHeatmap.java` and cut a new extension release.
- [ ] Publish a short consent/΄how it works΄ note next to the toggle.
