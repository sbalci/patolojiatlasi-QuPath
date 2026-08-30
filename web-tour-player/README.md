# Web tour / quiz player

`tour-player.html` is a single, self-contained HTML file that plays a quiz-pack / guided-tour-pack
JSON file — the same portable file produced by the **Patoloji Atlası** QuPath extension's author
window (`Extensions → Patoloji Atlası → Sınav / Quiz → Hazırla…`, or the guided-tour author) — in
a plain web browser. No QuPath installation, no build step, no server component beyond the DZI
image host and wherever you put this one file.

It reads the same `AtlasQuizIO` pack format the desktop `QuizRunnerWindow` reads
(`formatVersion` 1 or 2 — a newer version still plays, with a visible notice in the title, since
unknown fields are simply ignored; `title`/`description`/`allowBack` + an ordered `questions` array of
MCQ / FREETEXT / ANNOTATION / NAVIGATION / NARRATION stops — see the header comment inside
`tour-player.html` for the full field list). The three geometry fields
(`referenceGeometryGeoJson`, `targetGeometryGeoJson`, `highlightGeoJson`) are parsed defensively:
a malformed or missing geometry simply shows no overlay, never an error dialog.

## Deploying it

There is nothing to build. Pick whichever of these fits:

1. **Point it at a hosted pack.** Copy `tour-player.html` anywhere on a web server (e.g. next to
   the atlas website itself) and open
   `https://your-host/tour-player.html?tour=https://your-host/path/to/pack.json`. The page fetches
   the pack and starts playing immediately (an intro screen with the pack's title/description
   shows first, if it has either).
2. **Open it locally and load a pack by hand.** Double-click `tour-player.html` (a `file://` URL
   works fine) and either click **"Paket yükle…"** to pick a `*.json` pack file, or drag a pack
   file onto the page. This path uses `FileReader`, not `fetch`, so it needs no server at all —
   useful for previewing a pack you just authored before publishing it anywhere.
3. **Embed it in an existing page.** The whole player lives in one `<html>` file with no external
   assets besides the OpenSeadragon CDN script, so it can be dropped into an `<iframe>` or served
   as a standalone route on the atlas website with no other change.

Loading by `?tour=<url>` (path 1) uses `fetch()`, so it is subject to normal browser rules: it
needs an `https://` (or same-scheme) URL reachable from wherever the player page itself is
hosted, and if the pack JSON is on a **different origin** than the player page, that origin must
send CORS headers permitting it (see below) — `fetch()` cannot load `file://` URLs at all in most
browsers, so use path 2 for local pack files.

## CORS finding

The player's OpenSeadragon viewer loads slides as Deep Zoom (`.dzi`) tile pyramids. Opening a
`.dzi` URL cross-origin has **two different CORS requirements** worth separating:

- The **`.dzi` descriptor** itself (the small XML/JSON file OpenSeadragon parses to learn the
  pyramid's tile layout) is fetched with an `XMLHttpRequest`/`fetch`-style request, which **is**
  subject to CORS.
- The individual **tile images** (`<slide>_files/<level>/<col>_<row>.jpeg`) are loaded through
  plain `<img>` elements by default, which display cross-origin regardless of CORS headers (CORS
  only blocks `<img>` loads when a `crossOrigin` attribute is explicitly requested, which this
  player does not set) — so tile display would still work even on a host with no CORS story at
  all.

Checked from Bash on 2026-08-23 against the atlas's live image host:

```
$ curl -sI -H "Origin: https://www.patolojiatlasi.com" "https://images.patolojiatlasi.com/myxoidliposarcoma/HE.dzi" | grep -i "access-control\|HTTP/"
HTTP/1.1 200 OK
Access-Control-Allow-Origin: *
```

**`Access-Control-Allow-Origin: *` is present** on the `.dzi` descriptor (confirmed on a tile file
too — `HE_files/0/0_0.jpeg` returns the same header). `images.patolojiatlasi.com` allows any
origin.

**Deployment consequence:** because the DZI host answers `*`, `tour-player.html` can be hosted
**anywhere** — a different domain than `images.patolojiatlasi.com`, a static-file bucket, GitHub
Pages, a teaching LMS, wherever — and slide loading will still work; no same-host deployment is
required for the atlas's own slides. (If a tour pack ever points at a *different* DZI host that
does **not** send `Access-Control-Allow-Origin`, its `.dzi` descriptor fetch will fail in the
browser; in that case either deploy the player on that same host, or ask that host to enable
CORS. The player's own error handling degrades gracefully either way — a stop whose slide fails
to open shows a persistent "Slayt açılamadı…" note instead of crashing, and the learner can still
navigate past it.)

The pack JSON referenced by `?tour=<url>` needs the same treatment if it is cross-origin from the
player page — see "Deploying it" above.

## Limitations

- **Geometry overlays are bounding-box outlines, not exact shapes.** The player computes the
  axis-aligned bounding box of each `referenceGeometryGeoJson` / `targetGeometryGeoJson` /
  `highlightGeoJson`'s `coordinates` and draws that as a rectangle over the slide — it does not
  render the true polygon outline the way QuPath's own `QuizRevealOverlay` does. For a rectangle
  reference region this is exact; for an irregular polygon (e.g. a hand-drawn tumour outline) it
  is only an approximate envelope.
- **MCQ is marked right/wrong; nothing else is scored.** For MCQ stops the player compares your
  pick against the correct option and marks it (green/red plus the correct option highlighted) —
  the same client-side check the desktop runner shows as "Doğru cevap / Sizin cevabınız". FREETEXT
  stops reveal the model answer for a visual self-compare. ANNOTATION/NAVIGATION stops only overlay
  the reference/target region: this web player has no drawing tools and no viewport-vs-target
  scoring (there is nothing in a plain web viewer to draw with, or to score against), whereas the
  desktop `QuizRunnerWindow` additionally computes a measurement-only IoU %/hit line for those two
  types. Nothing is persisted in either client.
- **Requires the DZI tile host to be reachable** from the learner's browser (network access,
  and, for the `.dzi` descriptor specifically, CORS — see above). Nothing is cached or mirrored by
  this player.
- **Nothing is saved.** Like the desktop runner, this is read-only self-study: no progress,
  answers, or drawings are persisted anywhere, in memory or otherwise, once the page is closed or
  reloaded.
- Requires a JavaScript-enabled, reasonably current browser (uses `fetch`, `FileReader`,
  `URLSearchParams`, `Array.prototype.forEach`/`querySelectorAll` — no transpilation or polyfills
  are bundled).

## Clean-room note & prior art

This player is an independent, from-scratch implementation, written to consume the portable
`AtlasQuizIO` pack format described above. **OpenMicroanatomy / QuPath Edu**
(Yli-Hallila et al., *Journal of Anatomy* 2025;246(5):846–856,
[doi:10.1111/joa.14172](https://doi.org/10.1111/joa.14172)) ships a comparable
[Zlib](https://opensource.org/license/zlib)-licensed web client for its own server-hosted "slide
tour" workspaces; it is cited here as prior art in the same design space — no code from that
project was viewed or copied while writing this file. This player instead needs nothing beyond
the pack file itself, this one HTML file, and read access to the slides' DZI tile host (no
workspace server, no accounts).

## License

MIT, same as the rest of this repository — see [`../LICENSE`](../LICENSE).

## Third-party code

[OpenSeadragon](https://openseadragon.github.io/) is loaded from a CDN
(`cdn.jsdelivr.net`), pinned to an exact release with a Subresource Integrity hash — see the
`<script>` tag in `tour-player.html`. OpenSeadragon is BSD-3-Clause licensed; it is not bundled or
modified, only referenced by URL.
