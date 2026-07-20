# QuPath Patoloji Atlası extension

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/sbalci/patolojiatlasi-QuPath)](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)
[![Build](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml/badge.svg)](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21443833.svg)](https://doi.org/10.5281/zenodo.21443833)

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

## Bench-side reference

Open an atlas case in a **second viewer beside your own slide** — handy for comparing your own
case against a known reference while you work, without losing what's already open. Two ways to
launch it:

- **Browser right-click** — in **Slaytlara gözat…**, right-click a case and choose **Referans
  olarak yanında aç**.
- **Extensions → Patoloji Atlası → Referans → Referans slayt aç…** — a search-and-pick dialog
  (filter by title, organ, or stain) with a **Yanında aç** button (or double-click a row).

Either way, the reference slide streams into a new viewer added next to your active one; if
nothing is open yet, it opens directly into that single viewer instead (there's nothing to be
"beside" yet). When both slides carry a known pixel size (µm/px), the reference viewer's zoom is
matched automatically to show the same on-screen scale as yours. A small floating **Referans**
window appears alongside it with:

- **Büyütmeyi eşle** — re-match magnification on demand.
- **Kaydırmayı da eşle (tam senkron)** — toggle full pan/zoom sync between the two viewers
  (QuPath's built-in synchronize-viewers behavior).
- **Referansı kapat** — closes the reference viewer (prompting to save first if it has unsaved
  edits) and collapses the grid back to a single viewer.

---

## Related-content navigator

**Extensions → Patoloji Atlası → İlgili içerik…** opens a companion window that, for the atlas
slide currently open in the active viewer, shows two thumbnail filmstrips:

- **Bu vakanın diğer boyaları** — the same case's other stains (H&E plus any IHC / special
  stains), captioned by stain name.
- **Aynı kategoriden vakalar** — other cases from the same category, one representative thumbnail
  per case (its H&E stain if it has one, otherwise its first stain).

The window **auto-follows the active viewer** — switch slides, open a different one, or close it,
and both strips rebuild for whatever's now open, with no need to reopen the window. If the active
viewer isn't showing a cataloged atlas slide, it shows a hint instead of strips. A **Yenile**
button rebuilds on demand.

**Click a thumbnail to swap the active viewer to that slide.** Because
`QuPathViewer.setImageData` bypasses QuPath's own save prompt, the navigator checks first: if the
slide you're leaving has unsaved changes, you're asked to confirm before it's replaced.

The swap targets the **active** viewer. If you have a Case-Compare grid open, only the active panel
changes — use the navigator with a single viewer for the intended jump-to-related experience.

---

## Requirements

- **QuPath 0.6.x** (built against the 0.6 API; runs on Java 21).
- Internet access at runtime (slides stream from `images.patolojiatlasi.com`).

---

## Install

Two ways to install, both needing only **QuPath 0.6 or newer**. **Method A is recommended** — you
add the catalog once and QuPath installs the extension and offers future updates for you. Method B
is a one-time manual download if you'd rather not add a catalog.

### Method A — add the catalog to QuPath (recommended)

You add the catalog **once**; afterwards QuPath installs and updates the extension from it.

1. In QuPath, open **Extensions → Manage extension catalogs**.
2. Click **Add**, paste this repository URL, and confirm:
   ```
   https://github.com/sbalci/patolojiatlasi-QuPath
   ```
   QuPath finds the `catalog.json` in the repository for you.
3. Open **Extensions → Manage extensions**. Under the catalog you just added you'll see
   **QuPath Patoloji Atlası extension** — click **Install**. QuPath downloads the matching
   release JAR for you (no manual file handling).
4. When prompted, **restart QuPath**. After it reopens, the extension lives under
   **Extensions → Patoloji Atlası** (start with **Slaytlara gözat…** to browse the slides).

**Updating later.** When a newer version is released, open **Extensions → Manage extensions** again
and click **Update** next to the extension. If the update doesn't seem to take effect, fully quit
and reopen QuPath — QuPath re-reads the catalog and loads the new JAR on the next launch.

### Method B — download the JAR from Releases (manual)

1. Go to the **[latest release](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)**
   and download the attached `qupath-extension-atlas-<version>.jar` (under **Assets**).
2. Start QuPath 0.6+.
3. **Drag the `.jar` onto the QuPath window** and confirm when asked to install it. (Alternatively,
   copy it into QuPath's extensions directory: **Extensions → Installed extensions →
   open extensions directory**, drop the JAR there.)
4. **Restart QuPath** when prompted. The extension then appears under
   **Extensions → Patoloji Atlası → Slaytlara gözat…**.

> To update with Method B, download the newer JAR and repeat — replace the old JAR in the
> extensions directory (or just drag the new one on and let QuPath overwrite it).

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
| Bench-side reference (second viewer + magnification match + control window) | `BenchReference.java` |
| Reference picker dialog | `ReferencePickerDialog.java` |
| Coverage & QC matrix (pure) + best-effort DZI link check | `CoverageStats.java`, `LinkCheck.java` |
| Coverage & QC dashboard | `CoverageDashboard.java` |
| Case-stain compare (multi-viewer grid) | `CaseCompare.java` |
| Related-content list building (pure) + navigator window | `RelatedContent.java`, `RelatedContentNavigator.java` |
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

## Coverage & QC dashboard

**Extensions → Patoloji Atlası → Katalog kapsamı ve QC…** opens a read-only **category x stain**
matrix computed from the bundled catalogue snapshot — for every category, how many slides and
distinct cases fall into each of the four stain buckets (H&E / IHC / special stain / other), plus
per-category **published%** and **mpp-known%** (pixel-size calibration coverage). A bold TOTAL
strip under the table sums the whole catalogue.

- **Bağlantıları denetle** — an opt-in, best-effort reachability check of every distinct DZI URL in
  the catalogue (a `HEAD` request per URL, run off the UI thread with a progress bar). Any
  unreachable slide is listed by title and URL underneath — nothing is checked automatically, since
  this makes a batch of network calls.
- **Drill down.** Double-click a category row to seed the project builder (the same dialog the
  browser's selection basket uses) with every case in that category, ready to review and turn into
  a project.
- **Export.** Copy or save the matrix as **CSV** or **Markdown** — handy for pasting a
  data-availability statement or tracking catalogue gaps over time.

The classification (which stain bucket a slide falls into) is keyword-based, so the **Diğer**
("other") / **Uncategorized** buckets are honest catch-alls rather than a guarantee of correctness
— see `stainBucket()`/`normalizeCategory()` if you need to extend the keyword lists.

---

## Provenance & citation

Beyond citing the extension itself (see **Citation** below), the atlas menu can generate
ready-to-use citations and export files for the slides you actually used — image + extension +
QuPath references together, so a figure or methods section always carries full provenance.

- **Cite a slide.** Right-click a case in **Slaytlara gözat…** and choose **Bu slaytı alıntıla…**,
  or use **Extensions → Patoloji Atlası → Atıf → Açık slaytı alıntıla…** with the slide open in the
  active viewer. Produces BibTeX / RIS / plain-text citations for the atlas image, this extension,
  and QuPath (Bankhead et al. 2017), with copy-to-clipboard and save-to-file actions.
- **Export a cohort manifest.** In the project-builder dialog (from the browser's selection
  basket), click **Künye / manifest dışa aktar…** and pick a folder — writes `atlas-manifest.csv`,
  `atlas-manifest.md`, and `atlas-methods.txt` for every slide in the current selection. This does
  **not** create a project; it only records provenance for the slides you've gathered.
- **Cite a region.** Select an annotation on an open atlas slide, then use
  **Extensions → Patoloji Atlası → Atıf → Bu bölgeyi alıntıla…**. Produces a figure-citation card —
  the slide citation, the viewport framing (downsample + center), an editable caption, and the
  region's geometry as GeoJSON — with the same copy/save actions.

All three add a best-effort catalogue commit SHA (from a lightweight, unauthenticated GitHub API
call) and this extension's version to the exported provenance, without blocking the UI while that
network lookup runs.

---

## Citation

If you use this software, please cite it — GitHub renders a **"Cite this repository"** button from
[`CITATION.cff`](CITATION.cff). The archive is on Zenodo under a **concept DOI that always resolves
to the latest release** (cite this — it stays the same across versions):

**[10.5281/zenodo.21443833](https://doi.org/10.5281/zenodo.21443833)**

> Balcı, S. *QuPath Patoloji Atlası Extension (qupath-extension-atlas)* [Software]. Zenodo.
> https://doi.org/10.5281/zenodo.21443833

---

## License

MIT — see [LICENSE](LICENSE).
