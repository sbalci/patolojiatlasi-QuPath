# Compare a case's stains (synchronized viewers) — design

- **Date:** 2026-07-17
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/case-compare`
- **Status:** Approved design, ready for planning

## 1. Background & goal

Atlas cases are multi-stain: one case (`reponame`) has an H&E plus IHC / special stains, each a
separate `AtlasCase`. A pathologist researcher frequently wants to view those stains **side by side
with linked pan/zoom** (e.g., compare H&E morphology against a CD3 IHC of the same region). QuPath
already provides a **native synchronized multi-viewer grid**; the atlas uniquely knows which slides
are stains of the same case. This feature wires the two together: from an open atlas slide, one
click opens all of that case's stains into a synchronized grid.

## 2. Non-goals

- **No curtain/blend** (a slider dissolving between two stains) — QuPath has no native curtain; that
  is a separate custom build. Side-by-side synchronized is the native, low-risk win.
- No cross-case comparison and no "pick arbitrary images" mode in v1 (the compare set is exactly one
  case's stains). Those are possible later.
- No registration/alignment of the stains (they are shown as-is; the atlas slides are not
  co-registered).

## 3. Decisions

| Question | Decision |
|----------|----------|
| Compare set | **All stains of the current case** (same `reponame`), auto-detected from the open slide. |
| Trigger | Extensions menu item acting on the active viewer's slide. |
| Sync | Native `ViewerManager.setSynchronizeViewers(true)` (linked pan/zoom). |
| Scope for v1 | Side-by-side synced only (curtain deferred). |

## 4. Behaviour

**"Bu vakanın boyalarını karşılaştır…"** (Compare this case's stains):
1. Read the active viewer's server URI → its DZI URL (strip any `?mpp=` query).
2. Match it against `AtlasCatalog.loadBundled()` to find the open `AtlasCase` → its `reponame`.
3. Collect all catalog entries with that `reponame` (all stains), deduped by DZI URL, **the open
   slide first**, the rest in catalog order.
4. Size the native grid to N stains via `ViewerManager.setGridSize(rows, cols)` — `1×N` for N≤3,
   else a near-square grid; **cap at 6** panels (if a case has more, open the first 6 and log/inform
   which were dropped — never silently truncate).
5. Open each stain into its own viewer (`QuPathViewer.setImageData(...)` per viewer from
   `getAllViewers()`), reusing the atlas open path (`DziImageServer` + `new ImageData<>(server,
   imageType)`; the DZI URL carries `?mpp=` calibration).
6. `ViewerManager.setSynchronizeViewers(true)`.

**"Tek görünüme dön"** (Back to single view): `ViewerManager.resetGridSize()` + sync off.

## 5. Guards (graceful, no surprises)

- Active viewer empty / slide **not** matchable to a catalog case → info alert
  ("Bu bir atlas slaytı değil ya da katalogda bulunamadı.").
- Case has **only one** stain → info alert ("Bu vakada karşılaştırılacak başka boya yok.").
- The active image `isChanged()` → one-time **confirmation** before rearranging the grid (building
  the grid replaces viewer contents), reusing the pattern from the quiz runner. Cancel → no change.

## 6. Components

- **`CaseCompare`** (new, `com.patolojiatlasi.qupath`): the orchestration + the pure helpers:
  - `static List<AtlasCase> siblingStains(List<AtlasCase> catalog, String openDziUrl)` — **pure,
    unit-tested**: strips `?mpp=`/query from `openDziUrl`, finds its case, returns that case's stains
    (open slide first, deduped, order stable), or empty if not found.
  - `static int[] gridFor(int n)` — **pure, unit-tested**: `{rows, cols}` for N panels (1→1×1,
    2→1×2, 3→1×3, 4→2×2, 5..6→2×3), capped at 6.
  - `void compareCurrentCase(QuPathGUI)` — the orchestration (match → collect → guards → grid →
    open each on a background thread with `Platform.runLater` hand-off → sync). Manual-verified.
  - `void backToSingle(QuPathGUI)` — reset grid + sync off.
- **`AtlasExtension`** (changed): two menu items.

## 7. Threading & reuse

Slide opening does network I/O (DZI descriptor) → open each stain on a background daemon thread,
hand the `ImageData` to its viewer inside `Platform.runLater`, mirroring `AtlasBrowser.openSelected`
/ the quiz `QuizSlide`. Grid sizing / sync toggling happen on the FX thread. Consider extracting or
reusing a shared "open a DZI URL into a given viewer" helper rather than duplicating the server/
ImageData construction (the quiz's `QuizSlide.openAsync` opens into the *active* viewer only; this
needs a *specific* viewer, so a small shared helper `openInto(viewer, dziUrl, imageType, onDone,
onError)` is the clean move).

## 8. Verified QuPath 0.6 API

- `QuPathGUI.getViewerManager() : ViewerManager`; `getAllViewers() : ObservableList<QuPathViewer>`.
- `ViewerManager.setGridSize(int,int)`, `resetGridSize()`, `setSynchronizeViewers(boolean)`,
  `getAllViewers()`, `getActiveViewer()`.
- `QuPathViewer.setImageData(ImageData)`, `getImageData()`, server `getURIs()`.
- `DziImageServer(URI)`, `new ImageData<>(server, ImageType)`, `AtlasCase.getImageType()`,
  `ImageData.isChanged()`, `AtlasCatalog.loadBundled()`, `AtlasCase.getReponame()/getDziUrl()`.

## 9. Testing

- **Automated (JUnit):** `siblingStains` (matches by DZI URL ignoring `?mpp=`; open slide first;
  dedup; not-found → empty; single-stain case → size 1) and `gridFor` (the N→grid mapping incl. the
  cap). No network/JavaFX.
- **Manual (stated):** the multi-viewer open + synchronization + guards need a running QuPath.

## 10. Open questions

None blocking. Curtain mode and arbitrary-image compare are explicit future options.
