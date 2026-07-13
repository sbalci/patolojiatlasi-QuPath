# QuPath Patoloji Atlası extension

Browse the whole-slide images published on **patolojiatlasi.com** from inside QuPath and open
any of them directly — the slides are streamed tile-by-tile over HTTP, with nothing to download
in advance.

The atlas serves its slides as **Deep Zoom (DZI)** pyramids (produced by `vips dzsave` and shown
on the website with OpenSeadragon). This extension adds a DZI image reader to QuPath plus a
browsable catalogue driven by the atlas's own image list.

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

## Build

This project was generated in an environment without access to the Maven repositories that host
QuPath, so it is shipped as source for you to build once on your own machine (JDK 21 required):

```bash
./gradlew build          # macOS / Linux
gradlew.bat build        # Windows
```

The extension jar is written to `build/libs/qupath-extension-atlas-0.1.0.jar`.
(`gradle build` with your own Gradle 8.x works too.)

---

## Install

1. Start QuPath 0.6.
2. Drag **`qupath-extension-atlas-0.1.0.jar`** onto the QuPath window (or copy it into the
   extensions directory: *Extensions → Installed extensions → open extensions directory*).
3. Restart QuPath when prompted.
4. Open **Extensions → Browse Patoloji Atlası…**.

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

---

## License

MIT.
