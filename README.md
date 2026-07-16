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

- **Pixel-size calibration.** `vips dzsave` does not store microns-per-pixel in the DZI, so a
  slide opens **uncalibrated** (measurements in pixels) unless a pixel size is supplied. Three
  ways to supply it, in order of precedence: (1) a per-image `"mpp"` field in the catalog, (2) a
  catalog-wide `"defaultMpp"` (see *Regenerating the bundled snapshot* below) — both are applied
  automatically on open (and enable QuPath's scale bar); (3) manually per slide after opening
  (Image tab → *Set pixel size*), or baked into a URL as `…/HE.dzi?mpp=0.25`. No pixel size is
  imposed by default, so a wrong calibration is never applied silently.
- **Image type is set on open.** Slides open with a best-guess QuPath image type from the stain —
  H&E → *Brightfield (H&E)*, a known special/histochemical stain → *Brightfield (other)*, any
  other named stain → *Brightfield (H-DAB)* (assumed IHC/DAB) — so color deconvolution works
  without setting it by hand. Change it in the Image tab if a guess is wrong.
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

**Optional calibration fields.** Each case object may carry a numeric `"mpp"` (microns per pixel),
and the catalog root may carry a `"defaultMpp"` applied to any case without its own `"mpp"`. When
present these are applied automatically on open (see *Notes & limitations*). Omit them to leave
slides uncalibrated — only add real values (a wrong pixel size makes every measurement wrong).

```jsonc
{
  "defaultMpp": 0.25,          // optional; applied to cases with no "mpp"
  "cases": [
    { "reponame": "…", "image": "HE", "dzi": "…/HE.dzi", "mpp": 0.2456 /* optional per-image */ }
  ]
}
```

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

## Citation

If you use this software, please cite it using the metadata in [`CITATION.cff`](CITATION.cff)
(GitHub renders a **"Cite this repository"** button from it).

---

## License

MIT — see [LICENSE](LICENSE).
