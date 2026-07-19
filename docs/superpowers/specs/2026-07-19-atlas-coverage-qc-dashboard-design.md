# Catalogue Coverage & QC Dashboard — design

- **Date:** 2026-07-19
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/coverage-qc-dashboard`
- **Status:** Approved design (feature #3 of the research backlog), ready for planning

## 1. Goal

Give the researcher a **catalogue-wide, read-only overview**: how many slides exist per
category and stain type, what fraction are published, what fraction have a known pixel size
(mpp), and whether any slide's DZI URL is dead. It answers "what does the atlas actually cover,
and is it healthy?" — useful for a data-availability statement, for spotting gaps before
building a teaching set, and for catching broken links. All output is on-screen + copy/save
(no writable backend). Data source is the **offline bundled catalogue** (`AtlasCatalog.loadBundled()`);
the only network call is the opt-in link check.

## 2. Terminology (consistency with feature #2)

An `AtlasCase` is **one stain-image of one case** (`reponame` + `image`), so a single case
(`reponame`) spans several `AtlasCase` rows. Feature #2's methods paragraph already says
"N whole-slide images (M cases)". To avoid contradicting it in the same manuscript:

- Matrix cells and per-category/grand **Total** count **slides** (= `AtlasCase` count) — column
  labelled **"Slayt"** (Slides).
- The header and grand-total line ALSO report **distinct cases** = distinct `reponame` count,
  labelled **"Vaka"** (Cases).
- The CSV/MD export header carries both counts explicitly.

## 3. Stain bucketing — a NEW 4-way classifier (do NOT reuse `looksLikeStain`)

`AtlasCatalog.looksLikeStain(image, stainname)` returns a single boolean and **cannot** separate
IHC from special stains; its keyword list also omits the CD markers (`CD3`, `CD20` are absent).
Reusing it would drop CD3/CD20/Ki67 into "Other" — a domain error. Instead add a pure classifier
`CoverageStats.stainBucket(String image, String stainname) -> StainBucket` with
`enum StainBucket { HE, IHC, SPECIAL, OTHER }`, evaluated **in this order** (first match wins) on
`hay = (image + " " + stainname).toLowerCase(Locale.ROOT)`:

1. **HE** — `hay` is/starts-with an H&E name: matches any of `"he"` (as a whole token — guard so
   it doesn't match "the"/"hem…"; use exact-token or `image`-equals check), `"h&e"`, `"hande"`,
   `"h and e"`, `"hematox"`, `"haematox"`, `"h e"`.
2. **SPECIAL** — histochemical special stains (checked before IHC because some special-stain
   names could otherwise be misread): contains any of `"pas"`, `"pasd"`, `"giemsa"`, `"mgg"`,
   `"congo"`, `"amyloid"`, `"crystal"`, `"trichrome"`, `"masson"`, `"reticulin"`, `"mucicarmine"`,
   `"warthin"`, `"grocott"`, `"gms"`, `"ziehl"`, `"afb"`, `"verhoeff"`, `"vvg"`, `"elastic"`,
   `"perls"`, `"prussian"`, `"iron"`, `"alcian"`, `"silver"`, `"pap"` (Papanicolaou),
   `"trypsin"`, `"fontana"`.
3. **IHC** — immunohistochemistry markers: `hay` matches the regex `\bcd\d+\b` (CD3, CD20,
   CD117, …) OR contains any of `"ihc"`, `"immuno"`, `"ki67"`, `"ki-67"`, `"p53"`, `"p63"`,
   `"p40"`, `"p16"`, `"ttf"`, `"napsin"`, `"chromogranin"`, `"synaptophysin"`, `"syn"`,
   `"s100"`, `"sox"`, `"melan"`, `"hmb"`, `"desmin"`, `"sma"`, `"actin"`, `"vimentin"`,
   `"panck"`, `"ck7"`, `"ck20"`, `"ck5"`, `"cytokeratin"`, `"keratin"`, `"her2"`, `"estrogen"`,
   `"progesterone"`, `"\ber\b"`, `"\bpr\b"`, `"gata"`, `"pax"`, `"wt1"`, `"calretinin"`,
   `"inhibin"`, `"dog1"`, `"ckit"`, `"c-kit"`, `"mib"`, `"bcl"`, `"alk"`, `"pdl1"`, `"pd-l1"`,
   `"mart"`, `"cea"`, `"psa"`, `"tdt"`, `"mpo"`.
4. **OTHER** — everything unmatched (honest catch-all: unusual/blank/unknown stain names).

The exact keyword lists above are **load-bearing** — enumerate them verbatim in the plan and pin
them with unit tests. Short, ambiguous tokens (`"er"`, `"pr"`, `"syn"`) use word-boundary /
whole-token matching so they don't shadow ("**syn**ovial", "prope**r**ty"). Provide a small
`hasToken`/boundary helper in `CoverageStats` (or reuse the pattern from `AtlasCatalog`).

## 4. Pure core — `CoverageStats` (no UI/network; unit-tested)

`enum StainBucket { HE, IHC, SPECIAL, OTHER }` with a Turkish display label
(`"H&E"`, `"IHK"`, `"Özel boya"`, `"Diğer"`).

`stainBucket(String image, String stainname) -> StainBucket` — §3.

`record CategoryRow(String category, int[] counts /*len 4, indexed by StainBucket.ordinal*/,
int slides, int cases, int published, int mppKnown)` with derived `publishedPct()`,
`mppKnownPct()` (int 0–100, guard divide-by-zero → 0).

`record CoverageMatrix(List<CategoryRow> rows, int[] colTotals, int totalSlides, int totalCases,
int totalPublished, int totalMppKnown)` with the same derived pct accessors on the totals.

`static CoverageMatrix compute(List<AtlasCase> cases)`:
- Group by `getCategory()`; rows sorted by slide-count descending, **"Uncategorized" always last**.
- Per row: bucket each case via `stainBucket(getImage(), <stainname>)` (a `stainname` getter is
  added to `AtlasCase` if not already public — see §8), increment `counts[bucket]`, `slides`,
  `published` (if `isPublished()`), `mppKnown` (if `getMpp() > 0`); `cases` = distinct `reponame`
  in that category.
- Totals: `totalCases` = distinct `reponame` across the whole catalogue.

Renderers (Locale.US, mirroring `AtlasCitation`):
- `static String toCsv(CoverageMatrix)` — header `category,HE,IHC,special,other,slides,cases,published_pct,mpp_known_pct` + one row/category + a `TOTAL` row. CSV-escaped.
- `static String toMarkdown(CoverageMatrix)` — a `| … |` table (same columns) + a provenance header line: total slides, total cases, N categories, generated date (date passed in, since `LocalDate.now()` is impure — accept a `LocalDate` param).

## 5. Link check — `LinkCheck` (network; off the FX thread; best-effort)

- `static Map<String, Boolean> checkAll(List<AtlasCase> cases, java.util.function.IntConsumer progress)`
  — HEAD-request each **distinct** `getDziUrl()`; `true` = reachable (2xx/3xx), `false` = dead
  (non-2xx, timeout, exception). Bounded concurrency (small fixed pool, e.g. 6). Overall
  try/catch so it **never throws** — an unreachable host just yields `false`. `progress` fires
  0..N as URLs complete. **Runs off the FX thread** (caller supplies the thread); short connect/read
  timeouts (~5 s). Mirrors the best-effort ethos of `ProvenanceService`'s SHA lookup.
- HEAD semantics: use `HttpURLConnection.setRequestMethod("HEAD")` (or the `java.net.http.HttpClient`
  the repo already uses — match `ProvenanceService`/`AtlasCatalog`'s HTTP style). Some servers reject
  HEAD; treat a 405 as reachable (the URL resolves) rather than dead.

## 6. UI — `CoverageDashboard` (modal Stage)

- `static void show(QuPathGUI qupath)`:
  - Loads `AtlasCatalog.loadBundled()` (offline, fast), computes `CoverageStats.compute(...)`.
  - Header line: "Katalog kapsamı — {totalSlides} slayt, {totalCases} vaka, {N} kategori".
  - **Matrix table** (`TableView<CategoryRow>` or `GridPane`): a Category column, 4 stain-bucket
    count columns (H&E / IHK / Özel boya / Diğer), then Slayt / Vaka / Yayın % / mpp % summary
    columns; a bold TOTAL row at the bottom.
  - **Drill-down:** clicking a category row (or a non-zero bucket cell) opens
    `ProjectBuilderDialog.show(qupath, dashboardStage, seededBasket, () -> {})` where
    `seededBasket` is a **fresh** `LinkedHashSet<AtlasCase>` of that slice (whole category, or
    category∩bucket for a cell). The dashboard's own `Stage` is the owner (valid even when launched
    standalone from the menu with no browser open — verified in §8).
  - **"Bağlantıları denetle" (Check links)** button → disables itself, runs `LinkCheck.checkAll`
    on a background daemon thread with a `ProgressBar` (updated via `Platform.runLater`); on
    completion, categories/cells containing a dead URL are flagged (red count + tooltip) and a
    footer lists the failed slide titles + URLs. Re-enable the button. Cancellable-on-close
    (guard the completion callback with `stage.isShowing()`, per the #2 close-guard lesson).
  - **"Kopyala" / "Kaydet…"** → `ProvenanceService.copyToClipboard(CoverageStats.toCsv/​toMarkdown)`
    and `saveTextFile` (default names `atlas-coverage.csv` / `atlas-coverage.md`). A small
    format toggle (CSV / Markdown) like `CitationDialog`.
  - All alerts `.initOwner(stage)` (repo convention).

## 7. Menu wiring — `AtlasExtension`

A new **top-level** item under Extensions → Patoloji Atlası: **"Katalog kapsamı ve QC…"** →
`CoverageDashboard.show(qupath)`. Not under the Atıf group. Place it near the catalogue/browser
items (e.g., after "Atlas'ı Aç…"/browser launch), grouped logically.

## 8. Reuse & verified facts

- `AtlasCatalog.loadBundled() : List<AtlasCase>`, `groupByCategory(...)`, `normalizeCategory(...)`.
- `AtlasCase` getters: `getCategory()`, `getImage()`, `isPublished()`, `getMpp()` (0 = unknown),
  `getDziUrl()`, `getReponame()`, `getTitle()`. **`stainname`** is currently a private field with no
  public getter — add a `getStainname()` getter (trivial, no behavior change) for the classifier.
- `ProjectBuilderDialog.show(QuPathGUI, Stage owner, LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged)`
  — **verify** it runs correctly with a fresh throwaway basket + `() -> {}` callback + the dashboard
  stage as owner (standalone launch, no browser open). The callback only syncs browser stars, so a
  no-op is safe.
- `ProvenanceService.copyToClipboard(String)`, `saveTextFile(String defaultName, String content, Window owner)`.
- HTTP style: match `ProvenanceService`/`AtlasCatalog` (`java.net.http.HttpClient` vs
  `HttpURLConnection`) — check before writing `LinkCheck`.
- `QuPathGUI.getStage()` for a main-window owner where needed (confirm accessor name in this repo).

## 9. Testing

- **Automated (JUnit):** `CoverageStats` — `stainBucket` for each bucket incl. the advisor's cases
  (`HE→HE`, `CD3→IHC`, `CD20→IHC`, `Ki67→IHC`, `p63→IHC`, `PAS→SPECIAL`, `masson→SPECIAL`,
  `warthinstarry→SPECIAL`, `congo→SPECIAL`, an unknown basename `→OTHER`, and the boundary guards
  `"synovial"` must NOT be IHC via `syn`, an H&E token must not match inside "the"); `compute`
  matrix counts, distinct-case counting (two stains of one `reponame` → 1 case, 2 slides),
  published%/mpp% math incl. divide-by-zero, "Uncategorized" sorted last; `toCsv` header + TOTAL
  row + escaping; `toMarkdown` table shape + both counts in the provenance line. No network/JavaFX.
- **Manual (running QuPath):** the link check, the dashboard table, drill-down into the builder,
  clipboard/file export.

## 10. Non-goals / notes

- No writable backend; catalogue is read-only. The link check is advisory (best-effort).
- Classification (category + stain bucket) is a keyword heuristic — "Other"/"Uncategorized" are
  honest catch-alls, not failures. The dashboard states this.
- Not a refresh tool: uses the bundled offline catalogue. A future "Refresh from list.yaml" is
  out of scope for v1 (the coverage of the shipped bundle is what the participant has).
