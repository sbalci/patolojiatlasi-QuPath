# Research Provenance & Citation Suite — design

- **Date:** 2026-07-19
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/provenance-citation`
- **Status:** Approved design (feature #2 of the research backlog), ready for planning

## 1. Goal

Turn any atlas slide / selection / region into a **citable, reproducible record** for a manuscript,
grant appendix, or teaching note — because the atlas is a public, git-versioned catalogue with stable
per-slide URLs and known µm/px, unlike private local slides. All outputs are **copy-to-clipboard +
save-to-file** (no writable backend).

## 2. Pieces (v1 — all four approved)

1. **Cite this slide** — BibTeX / RIS / plain-text for one `AtlasCase`.
2. **Cohort manifest** — CSV + Markdown table of a selection of slides, version-pinned.
3. **Methods paragraph + slide table** — paste-ready "Materials & Methods" for a selection.
4. **Figure-region citation card** — cite a specific field: citation + viewport + caption stub + the selected ROI as GeoJSON.

## 3. Pure core — `AtlasCitation` (no UI/network; unit-tested)

`record CitationContext(java.time.LocalDate accessDate, String extensionVersion, String catalogCommitSha)`
(`catalogCommitSha` may be null/blank → that provenance line is omitted).

Static templating fns (all return Strings; each pinned by tests that assert the key fields/structure):
- `bibtex(AtlasCase, CitationContext)` — a `@misc`/`@online` entry: stable key `atlas_<reponame>_<image>`, `title = {<title> (<stain>)}`, org author `{{Patoloji Atlası}}`, `url`/`howpublished` = `getViewerUrl()`, `year` = accessDate year, `note` = "Accessed <date>; catalog <sha>; qupath-extension-atlas v<version>".
- `ris(AtlasCase, CitationContext)` — `TY - ELEC` … `TI`,`AU`,`UR`,`PB`,`Y2`,`N1`(organ/category/sha/version)… `ER -`.
- `plainText(AtlasCase, CitationContext)` — "Patoloji Atlası. <title> (<stain>), <organ|category>. <viewerUrl> (accessed <date>; catalog <sha>)."
- `manifestCsv(List<AtlasCase>)` — header + one row/slide: title, stain, organ, category, reponame, dziUrl, viewerUrl, mpp, published. CSV-escaped (quotes/commas).
- `manifestMarkdown(List<AtlasCase>, CitationContext)` — a `| … |` table (same columns) + a provenance header line (count, date, sha, version).
- `methodsParagraph(List<AtlasCase>, CitationContext)` — "N whole-slide images (M cases) from the Patoloji Atlası (https://www.patolojiatlasi.com) were reviewed in QuPath via qupath-extension-atlas v<version>, accessed <date> (catalogue snapshot <sha>). …" + a short stain/organ breakdown.
- `figureCitationCard(AtlasCase, CitationContext, Viewport vp, String captionStub, String roiGeoJson)` — the plainText citation + "Region: center (cx, cy) px, downsample ds" + caption stub + the GeoJSON (fenced). `record Viewport(double downsample, double centerX, double centerY)`.

All `String.format` uses `Locale.US` (repo convention).

## 4. `ProvenanceService` (context + I/O; not pure)

- `CitationContext resolve()` — `accessDate = LocalDate.now()`; `extensionVersion` = `AtlasExtension.class.getPackage().getImplementationVersion()` (from the JAR manifest `Implementation-Version`, set by the build) with a `"0.1.0"`/`"?"` fallback; `catalogCommitSha` = **best-effort** unauthenticated GET `https://api.github.com/repos/patolojiatlasi/patolojiatlasi.github.io/commits?path=lists/list.yaml&per_page=1` → first commit's short SHA; on any failure/timeout/rate-limit → null (the SHA line is simply omitted). The network call runs off the FX thread.
- Clipboard + save helpers: `copyToClipboard(String)`, `saveToFile(String defaultName, String content, Stage owner)` (FileChooser); a `saveManifest(dir, List<AtlasCase>, ctx)` that writes `atlas-manifest.csv` + `atlas-manifest.md` + `atlas-methods.txt`.

## 5. UI + launch

- **`CitationDialog`** (single slide): a `ComboBox`/tabs for BibTeX/RIS/Text, a read-only `TextArea` of the formatted citation, **Panoya kopyala** + **Kaydet…** buttons. Resolves the context (SHA off-thread; renders "…" until it arrives, then re-renders).
  - Launch A — browser: a **"Bu slaytı alıntıla…"** item in `AtlasBrowser`'s tree context menu (tree-selected case).
  - Launch B — menu: **"Açık slaytı alıntıla…"** — resolve the active viewer's DZI URL → catalogue case (strip `?mpp=`; reuse the CaseCompare/BenchReference matching); if not an atlas slide → info.
- **Cohort manifest + methods:** a **"Künye / manifest dışa aktar…"** button added to `ProjectBuilderDialog` (which already reviews the selection basket) → `saveManifest(...)` for the listed cases (CSV + MD + methods).
- **`FigureCitationDialog`** (ROI card): menu **"Bu bölgeyi alıntıla…"** — resolve the active atlas slide + the selected annotation's ROI (`hierarchy.getSelectionModel().getSelectedObject().getROI()`) → GeoJSON via the existing `QuizGeometry.toGeoJson` + the current viewport (downsample + center) → the card; copy/save. Guards: not-an-atlas-slide / no-ROI-selected → info.
- **Menu group:** Patoloji Atlası → new **Atıf** `Menu`: "Açık slaytı alıntıla…", "Bu bölgeyi alıntıla…".

## 6. Reuse

- `QuizGeometry.toGeoJson(ROI)` (from the quiz) for the figure card's ROI.
- Active-viewer-URL → catalogue-case matching (as in CaseCompare/BenchReference).
- `AtlasCatalog.loadBundled()`, `AtlasCase` getters (title/image/organEN/category/dziUrl/viewerUrl/mpp).
- `ProjectBuilderDialog`'s existing basket/selection review for the manifest button.

## 7. Testing

- **Automated (JUnit):** `AtlasCitation` — for each format, assert the key fields/structure are present and correctly filled (BibTeX has the stable key + title + url + note; RIS has TY/TI/UR/ER; CSV header + row count + escaping; Markdown table shape; methods count/date/version; figure card contains the citation + viewport + GeoJSON); SHA-null omission; CSV escaping of a title with a comma/quote. No network/JavaFX.
- **Manual (stated):** the GitHub SHA lookup, dialogs, clipboard/file I/O, ROI/viewport capture need a running QuPath.

## 8. Verified facts

- list.yaml repo: `patolojiatlasi/patolojiatlasi.github.io`, `lists/list.yaml`, branch `main` (from `AtlasCatalog`); GitHub commits API is unauthenticated + rate-limited → best-effort.
- Extension version `0.1.0` (build.gradle) → JAR `Implementation-Version`.
- `AtlasCase` getters: `getTitle/getImage/getOrganEN/getCategory/getReponame/getDziUrl/getViewerUrl/getMpp`.

## 9. Non-goals / notes

- No writable backend — everything is local files/clipboard. The commit-SHA is advisory (best-effort).
- Not a reference manager; we emit standard formats the user pastes into theirs.
- DOI: the extension has a Zenodo DOI (software); slides themselves have no per-slide DOI, so citations use the stable public viewer URL + catalogue commit as the version pin.
