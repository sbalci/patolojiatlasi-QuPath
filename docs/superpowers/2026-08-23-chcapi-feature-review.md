# qupath-chcapi-extension (Google) — feature review for atlas viewing (2026-08-23)

**Reviewed source:** [github.com/GoogleCloudPlatform/qupath-chcapi-extension](https://github.com/GoogleCloudPlatform/qupath-chcapi-extension)
— Google's own QuPath ↔ Cloud Healthcare API DICOM-store extension (built by Quantumsoft for
Google, package `com.quantumsoft.qupathcloud`). Reviewed 2026-08-23 from the `develop`-branch
source + GitHub API + release history; every load-bearing claim below was independently
re-verified against raw files (corrections from the verify pass are already folded in).

## What it is (verified)

A QuPath GUI extension (Java 11, Maven) that streams DICOM whole-slide images from a Google
Cloud Healthcare API DICOM store **without downloading the slide**, and synchronizes QuPath
annotations back to the cloud. Three toolbar buttons (Cloud / Synchronize / Logout; no menu
items). `CloudWindow` (620×500 modal) drills Project → Dataset → DICOM Store with a breadcrumb;
`CloudImageServer` streams pyramid tiles over DICOMweb (QIDO-RS listing + WADO-RS frame fetch,
hand-rolled on Apache HttpClient); annotations round-trip as QuPath `.qpdata` blobs embedded in
a **private DICOM tag `0xff010001`** on Verification-SOP-Class instances (Modality sentinel
`QU_PATH_DATA`, name-keyed via `SOPAuthorizationComment`), with timestamp-based conflict
resolution. A bundled native `wsi2dcm` binary (dll/so/dylib) "dicomizes" local WSIs for upload.

## Project health & licensing (verified — matters for reuse)

- **Archived Feb 27, 2023; effectively dead since Dec 2021** (last push 2021-12-20 — the final
  v2.0.8 release was a Log4Shell patch). 22★, 10 forks. 17 closed issues (all 2019–2020),
  1 open (#47: OSSF Allstar flags the committed native binaries as unreviewable supply-chain
  risk — unfixable, repo is read-only).
- **QuPath compatibility: 0.3.0, and it will not load on 0.6.x.** `pom.xml` pins
  `qupath-gui-fx 0.3.0`; release history shows a code patch was needed for *every* QuPath
  version jump it tracked (0.1.2 → 0.2.0-m5 → m10 → 0.3.0), and no 0.4/0.5/0.6 adaptation ever
  happened. (Inference from API churn — untested — but strongly supported.) No
  `getQuPathVersion()` override anywhere.
- **License: GPL-3.0-or-later** (per-file headers "Copyright 2019 Google LLC … either version 3
  … or (at your option) any later version", uniform across sources; GitHub's repo-level tag
  shows the coarser "GPL-3.0"). **Copyleft: reimplement patterns clean-room, never copy code
  into our MIT repos** — same discipline as DANEELpath (GPL) and Pathology-CoT (unlicensed).
- Curiosities: the final shaded JAR is **~531 MB** (bundles dcm4che + native binaries + the
  world); `src/main/resources/client_secrets.json` contains a real-looking committed OAuth
  client-id/secret (a security smell worth *not* imitating); DICOMweb calls target the stale
  `v1beta1` endpoint.

## Verified engineering findings (the instructive ones)

**Good ideas (validated by their design):**

1. **Stream, don't download** — the whole premise matches our DZI atlas: tiles fetched on
   demand, project entries are lazy pointers, pixels never touch disk until viewed.
2. **Metadata sidecar + deferred indexing** — QIDO listing is done once and persisted to a local
   `.mtd` file (Jackson `List<Instance>`); `metadataOnly` flag defers the expensive per-frame
   tile-index build during QuPath's server→builder→server construction round-trip. The cheap
   geometry (width/height/levels/tileSize) is all the project-add UI needs.
3. **`StubImageServer` placeholder** — a zero-cost `ImageServer` exposing just name/path lets a
   project list slides without resolving them. (Our curated-project builder achieves the same
   lazily via URI-only entries.)
4. **Explicit tile-miss contract** — two clearly-commented branches: return `null` so QuPath
   re-queries later, vs. draw a debug placeholder that gets cached "semi-permanently"; plus
   opt-in debug overlays (`quPathCloud.drawDebugInfo` / `drawPlaceholderTiles` system
   properties) that paint tile boundaries + level/coords onto composited tiles.
5. **Drill-down browser as a single-window page state machine** — `clearPage()`/`showPage()`
   swapping one content pane (Projects → Datasets → Stores) with a breadcrumb enum + a
   controller-owned filter over dumb table factories. (Same family as our modul-06-style
   single-window wizard pattern.)
6. **Sentinel-based mixed-content filtering** — non-image payloads share the store but are
   excluded from image listings by a well-known field value, not naming convention.

**Mistakes (equally instructive — we already do these right, keep it that way):**

- **Server can't round-trip from its own URI**: the URI points at the local `.mtd` sidecar and
  `buildServer()` pulls the actual store from a process-wide `Repository` singleton — one store
  per QuPath process, servers unreconstructable standalone. Our `DziImageServer` encodes the
  full endpoint in the URI (`…/HE.dzi?mpp=…`) — the correct inversion.
- **No pixel calibration, ever**: the QIDO includefield list never requests PixelSpacing and
  `pyramid.getMetadata()` never sets µm/px — every measurement is pixel-only. (Our `?mpp=`
  param + catalog mpp provenance is exactly the fix; see also the pixel-size-mpp doc.)
- **A fresh `CloseableHttpClient` (new TCP+TLS handshake) per tile fetch**, 16 threads at once,
  and **no `close()`** — the per-server 16-thread pool leaks on every opened image. Ours: one
  shared `java.net.http.HttpClient`, no per-server threads (QuPath's tile machinery drives).
- **Cache with no invalidation**: `.mtd` sidecars are never refreshed once written — remote
  changes go stale silently.
- **Real bugs found in-source**: the includefield constant for NumberOfFrames duplicates the
  wrong tag (0020,9228 twice; 0028,0008 never requested — works only because the server
  returns it anyway); instance listing is unpaginated (silent truncation risk) while
  project/dataset listing paginates; multipart parsing scans for the first CRLFCRLF and never
  strips the closing boundary (works only because JPEG self-terminates at EOI); frame indexing
  mixes 1-based DICOM and 0-based grid coordinates with `+1` scattered at lookup sites.
- **Error handling**: one generic `QuPathCloudException("Failed HTTP! Status code: N")` for
  everything, no retry/backoff; UI states are spinner → content or spinner → stack-trace dump.
- **Annotation identity keyed by display name** (`SOPAuthorizationComment`) — collides when two
  images share a name. (Validates our UID-style `slideKey` choice.)

## Feature-by-feature vs `qupath-extension-atlas`

| Area | CHCAPI | Atlas extension | Verdict |
|---|---|---|---|
| Remote tile serving | DICOMweb WADO-RS frames, manual `readBufferedImage` compositing (0.3.0-era), per-call HTTP client, blocking 16-thread fan-out | DZI over HTTPS via `AbstractTileableImageServer.readTile` — QuPath core does grid math + caching; shared HttpClient | Ours is the modern shape; theirs validates the streaming premise and catalogs the pitfalls |
| Pixel calibration | Never set (µm/px absent everywhere) | `?mpp=` → `pixelSizeMicrons` + catalog provenance | Ours strictly better — their gap is a warning |
| Server identity | Local sidecar URI + process singleton (can't round-trip) | Full endpoint in URI | Ours correct by their counterexample |
| Catalog browsing | Breadcrumb drill-down tables, live filter, no thumbnails | Category tree + search + published filter + async thumbnails | Ours richer (thumbnails); their page-state-machine is the same pattern family as our single-window wizards |
| Lazy project entries | `.mtd` sidecar + `metadataOnly` + `StubImageServer` | Curated-project builder writes URI-only entries; descriptor fetched on open | Parity; their *persisted descriptor* idea → candidate G1 |
| Tile-miss behavior | Explicit null-retry vs debug-placeholder contract + debug overlays | Any non-200 → blank white tile (sparse-pyramid assumption) | **Their contract is better → candidates G2/G3** |
| Annotation round-trip | Private-tag DICOM container, timestamp conflicts, name-keyed | Read-only atlas + portable JSON packs (quiz/tour/collections), UID-style slideKey | Keep ours; their name-key fragility validates slideKey |
| Auth | OAuth installed-app flow, committed client secret, single account | None (public read-only) | Out of scope for us — and their committed secret is the anti-pattern to avoid |
| DICOM/DICOMweb | Full QIDO/WADO client + native wsi2dcm dicomizer | None (DZI) | Not an atlas need. Historical reference implementation for DICOMweb WSI streaming into QuPath — citable context for the workshop repo's PACS/format chapters (archived, 0.3.0-only, don't recommend for use) |

## Inspiration candidates (unranked — user decides; sizes S/M/L)

- **G1 — Persist the parsed DZI descriptor per case (S).** Their `.mtd` pattern, done right:
  cache the descriptor tuple (width/height/tileSize/overlap/format/mpp) in the curated-project
  entry (or a local sidecar) so project slides open without the initial `.dzi` network fetch,
  and cold-opens survive a flaky connection. Unlike theirs, include a refresh path (re-fetch on
  open failure or explicit refresh) so the cache can't go silently stale.
- **G2 — Debug tile overlay via opt-in system properties (S).** Tile boundaries + DZI
  level/col/row text painted on composited tiles, gated by a system property read once into a
  `static final boolean`. Cheap, GUI-free, invaluable when a grid/overlap bug appears. Pattern
  reimplemented clean-room (trivial anyway).
- **G3 — Deliberate tile-miss contract in `DziImageServer` (M).** Today every non-200 tile
  fetch returns blank white — a transient network blip is indistinguishable from a genuinely
  absent sparse tile, and QuPath may cache the white tile until the slide is reopened.
  Adopt their explicit two-branch design: transient failure (timeout/5xx) → propagate so QuPath
  re-queries later; definitive 404 → white (sparse pyramid). **Before building: verify QuPath
  0.6 core's actual caching semantics for `readTile` null-vs-throw** — the right mechanism
  depends on `AbstractTileableImageServer` behavior, not on their 0.3.0-era code.

**Explicitly not taking:** OAuth machinery, write/sync/conflict stack, DICOMweb client, the
dicomizer, breadcrumb UI as a replacement for the tree (our tree + thumbnails fits a
category→case catalog better; the page-state-machine idea is already our wizard convention).
