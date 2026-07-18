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

- Adds **Extensions → Patoloji Atlası → Slaytlara gözat…**, opening a searchable window that lists every
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

## Quiz (self-study)

**Extensions → Sınav/quiz hazırla…** and **Extensions → Sınav/quiz çöz…** turn atlas slides into a
self-study quiz — no project and no server required. Four question types are supported:

- **Multiple-choice** — a prompt with several options, one marked correct.
- **Free-text** — a prompt with a model answer to compare your own notes against.
- **Annotation task** — a prompt that asks you to mark or outline a feature on the slide (e.g.
  "mark every mitosis"); you draw with QuPath's normal annotation tools, then click **Göster** to
  overlay the instructor's reference annotation on the slide and compare by eye.
- **Guided navigation ("find it")** — a prompt that asks you to navigate to a region (e.g. "find
  the area of highest mitotic activity"); pan/zoom there yourself, then click **Göster** to
  overlay the target region and recentre the viewer on it.

- **Author** (`Sınav/quiz hazırla…`) — with an atlas slide open, add a question of any of the four
  types; each question is bound to the slide that was open when you added it (its DZI URL is
  stored with the question), so one pack can span several slides. For an annotation or navigation
  question, draw and select the reference region on the slide yourself, then click **Referansı
  slayttan al** to capture its shape as the question's reference (annotation) or target
  (navigation) geometry. Save the finished set as a quiz-pack JSON.
- **Take** (`Sınav/quiz çöz…`) — load a quiz-pack and work through its questions in order; each
  question opens its slide, you answer it — pick an option, type notes, draw an outline, or
  navigate to a region — then click **Göster** to reveal the correct MCQ option, the free-text
  model answer, or, for annotation/navigation questions, an overlay of the reference/target region
  drawn directly on the slide.
- This is **self-study**: **there is no auto-grading** — **Göster** only overlays the reference for
  a visual self-compare, nothing is scored. Nothing is saved anywhere either: the quiz-pack is a
  single portable file you're free to email or hand out, and anything you draw while answering an
  annotation or navigation question is transient — it's cleared again as soon as you move to the
  next/previous question or close the window, so it never ends up saved in the project.

---

## Compare a case's stains

**Extensions → Bu vakanın boyalarını karşılaştır…** opens every stain of the case shown in the
active viewer — H&E plus any IHC / special stains — into QuPath's native multi-viewer grid, with
pan/zoom linked across all of them (QuPath's built-in **synchronize viewers** behavior), so you can
scroll one panel and have the others follow to the same field. The grid size (1×2 up to 2×3) is
picked to fit the number of stains; cases with more than six stains only show the first six. If the
active viewer isn't showing a cataloged atlas slide, or the slide's case has no other stains, you
get a message instead of a grid.

**Extensions → Tek görünüme dön** turns synchronization off and collapses the grid back to a
single viewer, closing the other panels (prompting to save first if any of them have unsaved
edits, exactly like QuPath's own close-viewer action).

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
4. Restart QuPath, then open **Extensions → Patoloji Atlası → Slaytlara gözat…**.

### Method B — manual (drag-and-drop)

1. Download `qupath-extension-atlas-<version>.jar` from the
   [latest release](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest).
2. Start QuPath 0.6+.
3. Drag the `.jar` onto the QuPath window (or copy it into the extensions directory:
   *Extensions → Installed extensions → open extensions directory*).
4. Restart QuPath when prompted, then open **Extensions → Patoloji Atlası → Slaytlara gözat…**.

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
- **Image type is set on open when recognised.** H&E → *Brightfield (H&E)* and a known
  special/histochemical stain → *Brightfield (other)*, so color deconvolution works without setting
  it by hand. Any other stain (including IHC markers) is left **unset** — the extension only assigns
  a type it is confident about and never guesses IHC/DAB; set those in the Image tab.
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
