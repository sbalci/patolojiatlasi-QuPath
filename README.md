# QuPath Patoloji Atlası extension

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/sbalci/patolojiatlasi-QuPath)](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)
[![Build](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml/badge.svg)](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml)

Browse the whole-slide images published on **patolojiatlasi.com** from inside QuPath and open
any of them directly — the slides are streamed tile-by-tile over HTTP, with nothing to download
in advance.

The atlas serves its slides as **Deep Zoom (DZI)** pyramids (produced by `vips dzsave` and shown
on the website with OpenSeadragon). This extension adds a DZI image reader to QuPath plus a
browsable catalogue driven by the atlas's own image list.

---

## About

The **Patoloji Atlası** is a whole-slide image teaching collection curated by pathologists and
published openly on the web. This extension brings that same collection into QuPath so the slides
can be browsed and opened for study and analysis without leaving the application. An **About**
button in the browser window (and the extension's entry in QuPath's extension manager) links back
to these sites:

- **Türkçe:** [patolojiatlasi.com](https://www.patolojiatlasi.com/)
- **English:** [histopathologyatlas.com](https://www.histopathologyatlas.com/)

---

## What it does

- Adds **Extensions → Browse Patoloji Atlası…**, opening a searchable window that lists every
  image grouped by category (Gastrointestinal, Pancreatobiliary, Neuropathology, …), with a
  thumbnail preview and a **Published only** filter.
- Each entry is a single stain of a case, so multi-stain cases (H&E plus IHC / special stains
  such as Warthin-Starry, Giemsa, CISH, PAS…) each appear as their own openable image.
- Double-click an image (or select it and press **Open in QuPath**) to open it.
  - With a **project** open, the slide is added to the project and opened as an entry, so your
    annotations, detections and measurements are saved with the project.
  - With no project open, it opens in the current viewer for read-only viewing.
- **Curate a project from several images.** Use **Add to selection** (button or the tree
  right-click menu) to build up a set as you browse and search — the selection persists across
  searches and *Refresh list*. Then **Create project…** opens a dialog where you review the set
  and either create a **new project** on disk or **add the selection to the current project**.
  Because slides stream from URLs, the resulting project is tiny and portable — hand the project
  folder to students (they need this extension installed) to run a course, seminar, or exam set.
- **Refresh list** pulls the latest `lists/list.yaml` live from the
  `patolojiatlasi/patolojiatlasi.github.io` repository, so new cases appear without updating the
  extension. A snapshot of the list (288 images) is bundled so the browser works offline out of
  the box.
- Registers `.dzi` URLs as an openable image type, so **File → Open URI…** with any
  `https://images.patolojiatlasi.com/<case>/<stain>.dzi` link works too.

---

## Requirements

- **QuPath 0.6.x** (built against the 0.6 API; runs on Java 21).
- Internet access at runtime (slides stream from `images.patolojiatlasi.com`).

---

## Install

### Method A — QuPath extension manager (recommended, no manual download)

QuPath 0.6+ can install and update this extension from its catalog:

1. **Extensions → Manage extension catalogs → Add**
2. Paste this catalog URL:
   ```
   https://raw.githubusercontent.com/sbalci/patolojiatlasi-QuPath/master/catalog.json
   ```
3. Open **Extensions → Manage extensions**, find **QuPath Patoloji Atlası extension**, and click
   **Install**. QuPath downloads the latest release JAR for you.
4. Restart QuPath, then open **Extensions → Browse Patoloji Atlası…**.

### Method B — manual (drag-and-drop)

1. Download `qupath-extension-atlas-<version>.jar` from the
   [latest release](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest).
2. Start QuPath 0.6+.
3. Drag the `.jar` onto the QuPath window (or copy it into the extensions directory:
   *Extensions → Installed extensions → open extensions directory*).
4. Restart QuPath when prompted, then open **Extensions → Browse Patoloji Atlası…**.

---

## Build (optional)

Pre-built JARs are attached to every [GitHub release](https://github.com/sbalci/patolojiatlasi-QuPath/releases),
so most users never need to build. To build from source (JDK 21 required):

```bash
./gradlew build          # macOS / Linux
gradlew.bat build        # Windows
```

The extension jar is written to `build/libs/qupath-extension-atlas-<version>.jar`.
QuPath's own APIs are resolved from the SciJava Maven repository at build time, so an internet
connection is needed for the first build.

---

## Notes & limitations

- **No pixel-size calibration.** `vips dzsave` does not store microns-per-pixel in the DZI, so
  opened slides have no µm/px value and measurements are in **pixels** until you set the pixel
  size manually (Image tab → *Set pixel size*). You can also bake it into the URL,
  e.g. `…/HE.dzi?mpp=0.25`, and the server will use it.
- **Streaming, not downloaded.** Tiles are fetched on demand into QuPath's tile cache; a live
  connection is needed while panning into new regions.
- **Category coverage depends on the list metadata.** Images are grouped from the `speciality`
  and `organEN` fields, falling back to the title/slug. Entries whose list record has none of
  these (currently a fair number, many with blank titles) land under **Uncategorized** — filling
  in `organEN`/`titleEN` in `list.yaml` will automatically improve grouping on the next refresh.
- **Respecting the source.** The images belong to patolojiatlasi.com; this tool is for viewing
  and study. Avoid bulk-downloading the tile pyramids.

---

## How it works (for developers)

| Piece | File |
|-------|------|
| DZI image reader (`AbstractTileableImageServer`) | `dzi/DziImageServer.java` |
| Registers `.dzi` URLs with QuPath | `dzi/DziImageServerBuilder.java` + `META-INF/services/qupath.lib.images.servers.ImageServerBuilder` |
| Catalogue (bundled snapshot + live `list.yaml`) | `AtlasCatalog.java`, `AtlasCase.java`, `catalog.json` |
| Browser window | `AtlasBrowser.java` |
| Menu entry point | `AtlasExtension.java` |

**Catalogue source.** The atlas maintains `lists/list.yaml`, one record per stain, with fields
`reponame`, `stainname`, `titleEN/TR`, `organEN`, `speciality`, `type`, `url` and `screenshot`.
The DZI URL is derived from `url` by swapping `.html → .dzi`; the viewer page is the `.html`.
`AtlasCatalog` reads the bundled JSON snapshot instantly and can re-fetch and parse the live YAML
(a small purpose-built parser, no extra dependencies) via **Refresh list**. Records are deduped by
`reponame/stain`, preferring `published`.

**DZI tiling.** The `.dzi` descriptor gives full size, `TileSize` (254 here), `Overlap` (1) and
tile `Format` (jpeg). QuPath resolution level *i* (downsample 2^*i*) maps to DZI level
`maxLevel − i`; tiles live at `<base>_files/<level>/<col>_<row>.<format>`, and the 1-pixel overlap
on interior tiles is cropped so each tile lands exactly on QuPath's grid.

### Regenerating the bundled snapshot

`src/main/resources/catalog.json` is a snapshot of `list.yaml` (deduped). To refresh it, re-export
from the current `list.yaml`. The live **Refresh list** button always overrides it at runtime.

> **Two files named `catalog.json` — don't confuse them.** The one at
> `src/main/resources/catalog.json` is the bundled **image list** described above (it ships inside
> the JAR). The one at the **repo root** (`catalog.json`) is the **QuPath extension catalog** that
> the extension manager reads (Method A above) — it lists the extension's releases, not images.

---

## Releasing a new version

1. Bump `version` in [`build.gradle`](build.gradle) (and `version` / `date-released` in
   [`CITATION.cff`](CITATION.cff)).
2. Prepend a new release entry to the root [`catalog.json`](catalog.json) — copy the newest block,
   set `name` to `vX.Y.Z`, and point `main_url` at the matching release asset
   (`.../releases/download/vX.Y.Z/qupath-extension-atlas-X.Y.Z.jar`). Keep older entries so prior
   versions stay installable. Use `vX.Y.Z` or `vX.Y.Z-rcN` only — QuPath silently drops other
   suffixes.
3. Commit, then tag and push:
   ```bash
   git tag vX.Y.Z
   git push origin master
   git push origin vX.Y.Z
   ```
   The [Release workflow](.github/workflows/release.yml) builds the JAR and publishes a GitHub
   release with it attached. `-rc`/`-alpha`/`-beta` tags are marked as pre-releases, so only a
   clean `vX.Y.Z` becomes the "Latest" that the catalog's install URL resolves to.

---

## Focus heatmap

**Extensions → Odak ısı haritası** adds a dwell/attention heatmap that records where you look on a
slide. While tracking is on, the active viewer's visible region is sampled a few times a second and
accumulated into a per-slide grid shown as a translucent overlay — focused high-magnification viewing
heats an area far faster than a zoomed-out browse, so the map doubles as a *"did I review the whole
slide?"* check and as a way to study where readers focus.

Menu items:

- **Görünür — izlemeyi aç/kapat** — start/stop tracking and show/hide the overlay.
- **Temizle** — reset the current slide's map.
- **Kaydet…** — save the current map now to a folder you pick.
- **Araştırmaya katkıda bulun…** — save an **anonymised** contribution (no user name; a random
  session id + stable slide key + date) under `~/QuPath-atlas-focus-maps/contributions/`, for the
  planned per-slide *crowd attention map*. **Uploading is disabled** until the atlas website has a
  receiver — until then the file is only written locally and can be shared manually.
- **Oturumdan sonra sakla (kalıcı)** — when **off** (default), maps live only for the session and
  are discarded; when **on**, each slide's map is auto-saved (on slide change, close, or stopping) to
  `~/QuPath-atlas-focus-maps/` so it can be analysed later.

Each saved map is a `<slide>__<user>__<timestamp>.json` (plus a `.png` preview). The JSON carries the
slide name/URI, the user (OS login), image and grid dimensions, sample count, and the row-major
`grid` of dwell values — enough to aggregate focus across readers offline. The plan for pooling
contributions into a website overlay is in [docs/focus-aggregation-plan.md](docs/focus-aggregation-plan.md).

---

## Citation

If you use this software, please cite it using the metadata in [`CITATION.cff`](CITATION.cff)
(GitHub renders a **"Cite this repository"** button from it).

---

## License

MIT — see [LICENSE](LICENSE).
