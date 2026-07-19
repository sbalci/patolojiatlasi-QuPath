# Bench-Side Atlas Reference — design

- **Date:** 2026-07-19
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/bench-reference`
- **Status:** Approved design (feature #1 of the research backlog), ready for planning

## 1. Goal

Let a researcher, while looking at their **own** slide in QuPath, open any **atlas** case in a
second viewer **beside** it at **matched magnification** (same µm/px on screen), to eyeball an
unusual finding against a public reference case. This turns the atlas from a standalone browser into
an active reference consulted during the researcher's own casework — only possible because the atlas
is public, pre-calibrated (mpp), and always addressable.

## 2. Decisions (from brainstorming)

| Question | Decision |
|----------|----------|
| Viewer relationship | **Match magnification, pan independently** by default (the two are different tissue), with a **full pan+zoom sync toggle** (native `setSynchronizeViewers`). |
| Launch | **Both** — a browser right-click action AND a menu picker. |
| Adding the 2nd viewer | `ViewerManager.addColumn(activeViewer)` → a **new empty** viewer; the user's slide is never replaced (no unsaved-data risk, unlike Case-Compare). |

## 3. Non-goals

- No registration/alignment of the two slides (independent tissue; magnification only).
- No quantification/measurement (workshop territory). This is a viewing aid.
- Not the same as Case-Compare (that syncs a case's *own* stains in a grid; this pairs *your* slide with an *arbitrary* atlas reference, mag-matched, independent pan by default).

## 4. Behaviour

**`BenchReference.openBeside(qupath, AtlasCase ref)`:**
1. `active = qupath.getViewer()`. If `active` has no image → just open `ref` in `active` (degenerate but useful) and return (no second viewer).
2. Else record the current viewers, `qupath.getViewerManager().addColumn(active)`, then find the **new** viewer (in `getAllViewers()` but not in the recorded set) = `refViewer`.
3. Open `ref` into `refViewer` on a background daemon thread (`new DziImageServer(ref.getDziURI())` + `new ImageData<>(server, ref.getImageType())`), `Platform.runLater(() -> refViewer.setImageData(imageData))`, mirroring `AtlasBrowser.openSelected`. On failure → log + info alert; leave the empty viewer or remove it.
4. After the ref is loaded (in the `onDone`), **match magnification** (see §5). Remember `active` (yours) and `refViewer` for later re-matching.
5. Show the small **Referans** control window (§6). Set `refViewer` (or keep `active`) as active per taste.

## 5. Magnification match (pure, testable)

`matchedDownsample(double yourDs, double mppYours, double mppRef)`:
- If `mppYours <= 0 || mppRef <= 0` → return `Double.NaN` (unknown → caller skips matching, zoom-to-fit, status note).
- Else `yourDs * mppYours / mppRef` (so `refViewer` shows the same µm/px as `active`).

`matchMagnification()` (control action): read `yourDs = active.getDownsampleFactor()`,
`mppYours = active.getImageData().getServer().getPixelCalibration()` (µm/px, guarded by
`hasPixelSizeMicrons()`), `mppRef` likewise from `refViewer`'s server; `double ds =
matchedDownsample(...)`; if `!NaN` → `refViewer.setDownsampleFactor(ds)`; else status "kalibrasyon
bilinmiyor — büyütme eşlenemedi". All on the FX thread.

## 6. Controls — a small floating "Referans" window (like `RotationControl`)

- **Büyütmeyi eşle** button → `matchMagnification()`.
- **Kaydırmayı da eşle (tam senkron)** `CheckBox` → `qupath.getViewerManager().setSynchronizeViewers(selected)` (default unchecked = independent pan; the magnification match is a one-shot, sync is continuous).
- **Referansı kapat** button → close the reference viewer and return to a single view (reuse the Case-Compare "back to single" approach: `setSynchronizeViewers(false)` + close the non-anchor viewer(s) via `QuPathGUI.closeViewer`, which prompts-to-save; then normalize the grid).
- A status label (calibration/failure notes). Window is non-modal; single instance (focus if open).

## 7. Launch (both)

- **Browser** (`AtlasBrowser`): a right-click menu item **"Referans olarak yanında aç"** on tree image rows → `BenchReference.openBeside(qupath, selectedCase)`.
- **Menu** (`AtlasExtension`, under Patoloji Atlası → a new **Referans** group): **"Referans slayt aç…"** → a small picker dialog (a search `TextField` + a `ListView<AtlasCase>` over `AtlasCatalog.loadBundled()` filtered by title/organ/stain) → on choose, `openBeside`.

## 8. Components

- **`BenchReference`** (new, `com.patolojiatlasi.qupath`): `openBeside`, `matchMagnification`, the floating control window, `close`, and the **pure** `matchedDownsample(...)` helper.
- **`ReferencePickerDialog`** (new, small) — the menu-launch search/pick dialog.
- **`AtlasBrowser`** (changed) — one context-menu item.
- **`AtlasExtension`** (changed) — the Referans menu group.

## 9. Threading & safety

DZI open on a background daemon thread, viewer touched only in `Platform.runLater` (mirror
`AtlasBrowser.openSelected`). Adding a new empty viewer means **no** existing viewer content is
replaced → no unsaved-changes guard needed for the open itself. `close()` routes through
`QuPathGUI.closeViewer` (which prompts to save) — so closing never silently discards work. All
viewer/grid/sync/UI mutation on the FX thread.

## 10. Verified QuPath 0.6 API

- `ImageServer.getPixelCalibration() : PixelCalibration`; `PixelCalibration.hasPixelSizeMicrons()`, `getAveragedPixelSizeMicrons()`.
- `ViewerManager.addColumn(QuPathViewer)`, `getAllViewers()`, `setActiveViewer(...)`, `setSynchronizeViewers(boolean)`; `QuPathGUI.getViewerManager()`, `getViewer()`, `closeViewer(QuPathViewer)`.
- `QuPathViewer.getDownsampleFactor()`, `setDownsampleFactor(double)`, `getImageData()`, `setImageData(...)`.
- `DziImageServer(URI)`, `new ImageData<>(server, ImageType)`, `AtlasCase.getImageType()/getDziURI()`, `AtlasCatalog.loadBundled()`.

## 11. Testing

- **Automated (JUnit):** `matchedDownsample` — same-mpp → yourDs; ref twice-as-fine (smaller mpp) → half the downsample; unknown mpp (≤0) → NaN.
- **Manual (stated):** the add-viewer + open + magnification-visual-match + sync toggle + close need a running QuPath.

## 12. Open questions

None blocking. (Multi-viewer-already-open edge cases: `addColumn` still adds one viewer; the "find the new viewer" set-difference handles it.)
