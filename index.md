---
title: QuPath Patoloji Atlası extension
---

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/sbalci/patolojiatlasi-QuPath/blob/master/LICENSE)
[![Latest release](https://img.shields.io/github/v/release/sbalci/patolojiatlasi-QuPath)](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)
[![Build](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml/badge.svg)](https://github.com/sbalci/patolojiatlasi-QuPath/actions/workflows/build.yml)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21443833.svg)](https://doi.org/10.5281/zenodo.21443833)

Browse the whole-slide images published on **[patolojiatlasi.com](https://www.patolojiatlasi.com/)**
from inside **QuPath** and open any of them directly — the slides are streamed tile-by-tile over
HTTP as **Deep Zoom (DZI)** pyramids, with nothing to download in advance.

> **Türkçe:** patolojiatlasi.com koleksiyonundaki bütün-slayt görüntülerini QuPath içinden, önceden
> indirmeye gerek kalmadan açar. Menü: **Extensions → Patoloji Atlası → Slaytlara gözat…**

---

## Install

Both methods need only **QuPath 0.6 or newer**. Method A is recommended — add the catalog once and
QuPath installs (and later updates) the extension for you.

### Method A — add the catalog to QuPath (recommended)

1. In QuPath: **Extensions → Manage extension catalogs → Add**.
2. Paste this **repository URL** and confirm:

   ```
   https://github.com/sbalci/patolojiatlasi-QuPath
   ```

   QuPath finds the catalog in the repository for you.
3. Open **Extensions → Manage extensions**, find **QuPath Patoloji Atlası extension**, and click
   **Install**. QuPath downloads the matching release JAR automatically.
4. **Restart QuPath.** The extension appears under **Extensions → Patoloji Atlası**
   (start with **Slaytlara gözat…**).

*Updating later:* **Extensions → Manage extensions → Update**. If the update doesn't seem to take,
fully quit and reopen QuPath so it re-reads the catalog.

### Method B — download the JAR from Releases (manual)

1. Download `qupath-extension-atlas-<version>.jar` from the
   **[latest release](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)** (under *Assets*).
2. Start QuPath 0.6+ and **drag the `.jar` onto the QuPath window** (confirm the install prompt).
3. **Restart QuPath** — the extension appears under **Extensions → Patoloji Atlası → Slaytlara gözat…**.

---

## What it does

- **Streams slides, nothing to download.** A DZI image reader fetches tiles on demand into QuPath's
  tile cache as you pan and zoom.
- **Browsable, category-grouped catalogue** driven by the atlas's own image list — search and open
  any published slide.
- **Registers `.dzi` URLs** as an openable image type, so a Deep Zoom URL opens like any local slide.
- **Sensible defaults on open** — recognised H&E / special stains get the right image type for color
  deconvolution; optional pixel-size (MPP) calibration is applied when the catalog provides it.

---

## Requirements

- **QuPath 0.6.x or newer** (built against the 0.6 API; runs on Java 21).
- **Internet access at runtime** — slides stream from `images.patolojiatlasi.com`.

---

## The atlas

The **Patoloji Atlası** is an openly published whole-slide teaching collection curated by
pathologists. This extension brings that collection into QuPath for study and analysis.

- **Türkçe:** [patolojiatlasi.com](https://www.patolojiatlasi.com/)
- **English:** [histopathologyatlas.com](https://www.histopathologyatlas.com/)

---

## Links

- **Source & releases:** [github.com/sbalci/patolojiatlasi-QuPath](https://github.com/sbalci/patolojiatlasi-QuPath)
- **Latest release JAR:** [releases/latest](https://github.com/sbalci/patolojiatlasi-QuPath/releases/latest)
- **Report an issue:** [issues](https://github.com/sbalci/patolojiatlasi-QuPath/issues)

---

## Citation

If you use this software, please cite it using the metadata in
[`CITATION.cff`](https://github.com/sbalci/patolojiatlasi-QuPath/blob/master/CITATION.cff)
(GitHub renders a *"Cite this repository"* button from it). Archived on Zenodo under a **concept DOI
that always resolves to the latest release** (cite this — it stays the same across versions):
**[10.5281/zenodo.21443833](https://doi.org/10.5281/zenodo.21443833)**

Built on **QuPath** (Bankhead et al., *Sci Rep* 2017, [doi:10.1038/s41598-017-17204-5](https://doi.org/10.1038/s41598-017-17204-5)).

## License

Released under the **MIT License** — see
[LICENSE](https://github.com/sbalci/patolojiatlasi-QuPath/blob/master/LICENSE).
