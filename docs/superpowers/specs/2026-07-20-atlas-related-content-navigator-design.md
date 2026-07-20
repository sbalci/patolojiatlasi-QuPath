# Related-Content Navigator — design

- **Date:** 2026-07-20
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/related-content-navigator`
- **Status:** Approved design (feature #4 of the research backlog), ready for planning

## 1. Goal

A small companion window that, for the atlas slide currently open in the active viewer, shows
**related content as thumbnail filmstrips** and lets one click jump the active viewer to a related
slide. Two relations: (1) the **same case's other stains**, (2) **other cases in the same
category**. It **auto-follows** the active viewer — when you open/swap a slide, the filmstrips
rebuild for the new case. Complementary to Case-Compare (that opens a synced side-by-side grid of
the case's stains; this is a lightweight single-viewer jump-to-related browser). Read-only: it only
reads the catalogue and swaps the viewer's image.

## 2. Pieces

1. **`RelatedContent`** (pure, unit-tested) — builds the two related lists from the catalogue.
2. **`RelatedContentNavigator`** (JavaFX companion `Stage`) — the filmstrips, thumbnails, auto-follow
   listener, single-instance guard, and the guarded click-to-swap.
3. Widen two `CaseCompare` helpers to package-private for reuse (no behavior change).
4. One menu item.

## 3. Pure core — `RelatedContent` (no UI/network; unit-tested)

Reuses `CaseCompare.siblingStains` and `CoverageStats.stainBucket` (from #3).

- `static List<AtlasCase> otherStains(List<AtlasCase> catalog, AtlasCase open)` — the open case's
  OTHER stains. `siblingStains` returns the open slide **first** then the rest; this returns
  `siblingStains(catalog, open.getDziUrl())` with the first element (the open slide itself) dropped.
  Empty if the case has only the one stain.
- `static List<AtlasCase> sameCategoryCases(List<AtlasCase> catalog, AtlasCase open)` — one
  **representative** `AtlasCase` per OTHER case (distinct `reponame`) in the same
  `getCategory()`, excluding the open case's own `reponame`. Order: stable by first appearance in
  the catalogue. Representative pick per case: prefer the case's **H&E** stain
  (`CoverageStats.stainBucket(image, stainname) == StainBucket.HE`); if none, the first stain of
  that case in catalogue order. (Reusing #3's classifier keeps "which is the overview slide"
  consistent with the coverage dashboard's H&E bucket.)
- No cap — the UI strip is horizontally scrollable (advisor: don't gold-plate an S feature).

Both are pure `List<AtlasCase>` transforms → unit-testable with synthetic `AtlasCase`s.

## 4. `RelatedContentNavigator` (companion Stage)

`static void show(QuPathGUI qupath)`:

- **Single instance** (mirror `BenchReference`'s refuse-second-open): a `private static
  RelatedContentNavigator instance` (or a static `Stage`). If one is already showing, `toFront()`
  it and return — never a second window (a second window = a second `imageDataProperty` listener).
- A non-modal, resizable `Stage`, `.initOwner(qupath.getStage())` when non-null, title
  **"İlgili içerik"**. Two labelled sections stacked vertically, each a horizontally-scrollable
  `HBox` of thumbnail tiles inside a `ScrollPane`:
  1. **"Bu vakanın diğer boyaları"** ← `RelatedContent.otherStains`.
  2. **"Aynı kategoriden vakalar"** ← `RelatedContent.sameCategoryCases`.
  A header line naming the current case; a manual **"Yenile"** button (belt-and-suspenders next to
  auto-follow).
- **Thumbnail tile:** a `Button`/`VBox` with an `ImageView` fed by
  `new Image(c.getThumbUrl(), THUMB_W, 0, true, true, true)` — JavaFX **background loading**
  (`backgroundLoading=true`), exactly as `AtlasBrowser` builds its preview (no custom threads). If
  `getThumbUrl()` is blank, show a text-only tile (title + stain). Tooltip = full title. The tile's
  caption is the stain (this-case strip) or the case title (same-category strip).
- **Auto-follow:** register a `ChangeListener` on `qupath.imageDataProperty()`
  (`ReadOnlyObjectProperty<ImageData<BufferedImage>>` — verified present in 0.6; GUI-level, so it
  fires on active-image change across viewers). On change → `refresh()`. Mirrors
  `RotationControl`'s existing `qupath.viewerProperty().addListener(...)` style. **Remove the
  listener on window close** (`stage.setOnHidden` / `setOnCloseRequest`) and clear the static
  instance — otherwise a closed navigator keeps firing and leaks the stage.
- **`refresh()`** (FX thread): `AtlasCase open = AtlasExtension.resolveOpenCase(qupath)`. If `null`
  (no image, or not a catalogue slide) → clear both strips, show
  **"İlgili içerik için bir atlas slaytı açın."**. Else rebuild both strips via `RelatedContent`.
  Guard against the case with no other stains / no same-category cases with a per-strip
  "—" placeholder.
- **Click-to-swap (the guarded action):** clicking a tile swaps the **active viewer** to that
  slide. **This must dirty-check the outgoing image first** — `CaseCompare.openInto` is
  intentionally UNGUARDED (its compare-grid caller does the guarding), so reusing it bare would
  regress the repo's save-protection when the current atlas slide carries unsaved annotations.
  Sequence on the FX thread:
  ```
  QuPathViewer viewer = qupath.getViewer();
  ImageData<BufferedImage> outgoing = viewer == null ? null : viewer.getImageData();
  if (outgoing != null && CaseCompare.isChangedSafe(outgoing) && !confirmSwap()) return;
  CaseCompare.openInto(viewer, target);   // off-thread build + Platform.runLater setImageData
  ```
  `confirmSwap()` = a `Dialogs.showConfirm`/yes-no with a navigator-worded message
  ("Açık slaytta kaydedilmemiş değişiklikler var. Başka bir slaytla değiştirilsin mi?"). After the
  swap, `imageDataProperty` fires → `refresh()` auto-rebuilds for the newly-opened case (no manual
  refresh needed; no loop, since refresh doesn't change the image). `setImageData`'s save-prompt
  bypass is acceptable ONLY because we dirty-check + confirm first.

## 5. Reuse & widen

- **Reuse as-is:** `AtlasExtension.resolveOpenCase(QuPathGUI)` (package-private, from #2),
  `CaseCompare.siblingStains(List, String)` (public), `CaseCompare.stripQuery`,
  `AtlasCatalog.loadBundled()`, `CoverageStats.stainBucket(String, String)` (package-private, #3),
  `AtlasCase.getThumbUrl/getTitle/getStainname/getImage/getReponame/getCategory/getDziURI`,
  `AtlasBrowser`'s `new Image(url, w, 0, true, true, true)` thumbnail idiom.
- **Widen to package-private `static` (no behavior change, mirroring how #2 widened
  `resolveOpenCase`):**
  - `CaseCompare.openInto(QuPathViewer, AtlasCase)` — currently `private static`.
  - `CaseCompare.isChangedSafe(ImageData<BufferedImage>)` — currently `private static`; the shared
    dirty-check so the navigator's guard matches the compare grid's exactly (`ImageData.isChanged()`
    verified present in 0.6, wrapped to swallow exceptions → false).
- **Verify before writing** (all javap-confirmed at design time, re-confirm in code):
  `qupath.imageDataProperty()` → `ReadOnlyObjectProperty<ImageData<BufferedImage>>`;
  `QuPathViewer.getImageData()`; `ImageData.isChanged()`; `CaseCompare.openInto`/`isChangedSafe`
  signatures after widening.

## 6. Menu wiring — `AtlasExtension`

A new top-level item under Extensions → Patoloji Atlası: **"İlgili içerik…"** →
`RelatedContentNavigator.show(qupath)`. Group it near the browser / Case-Compare items (it's a
navigation aid), NOT under Atıf.

## 7. Testing

- **Automated (JUnit):** `RelatedContent` — `otherStains` drops the open slide and returns the
  case's remaining stains (and empty when the case is single-stain); `sameCategoryCases` returns one
  representative per OTHER case in the same category, excludes the open case's `reponame`, dedups by
  `reponame`, and the representative is the case's H&E stain when present (else first-in-order).
  Synthetic `AtlasCase`s; no network/JavaFX.
- **Manual (running QuPath):** the companion window, thumbnail loading, auto-follow on
  open/swap, click-to-swap incl. the unsaved-changes confirm, single-instance refuse-second-open,
  and listener cleanup on close.

## 8. Non-goals / notes

- Not a docked pane (QuPath 0.6 exposes no extension dock API) — a small companion `Stage` IS the
  "docked filmstrip".
- Not a second synced viewer — that's Case-Compare. Clicking swaps the single active viewer.
- No thumbnail disk cache / eviction — JavaFX background loading + a scrollable strip is enough for
  an S feature; revisit only if a huge category proves janky.
- Read-only: the only mutation is swapping the active viewer's image, always behind the
  unsaved-changes guard.
