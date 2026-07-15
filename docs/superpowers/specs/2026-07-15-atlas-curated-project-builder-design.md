# Curated-project builder for the Patoloji Atlası extension

- **Date:** 2026-07-15
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Status:** Approved design, ready for implementation planning
- **Author:** Serdar Balcı (with Claude Code)

## 1. Background

The Patoloji Atlası extension adds a browsable catalogue of whole-slide images
published on patolojiatlasi.com / histopathologyatlas.com to QuPath. The browser
(`AtlasBrowser`) lists images grouped by category with search, a *Published only*
filter, a thumbnail preview, and an **Open in QuPath** action that streams a single
selected slide into QuPath — either added to the currently-open project or shown
read-only when no project is open.

Because the slides stream as Deep Zoom (DZI) tiles from URLs, a QuPath project built
from atlas images is tiny (it stores only DZI URL references, not pixels), which makes
such a project cheap to create and portable to share — provided the recipient has this
extension installed to resolve the `.dzi` URLs.

This feature lets an instructor **select several atlas images at once and turn them into
a QuPath project**, so the extension can be used to prepare slide sets for courses,
seminars, and exams. It is the first of two planned features; the second — an
examination / question / annotation / quiz interface — is a **separate spec** that will
build on the project produced here.

## 2. Goals

- Let a user accumulate a **persistent selection** of atlas images while browsing
  (the selection survives searching, filtering, and *Refresh list*).
- From that selection, **create a new QuPath project** on disk, or **add the selection
  to the currently-open project**.
- Reuse the extension's existing single-image add path so both flows behave identically
  per image.
- Keep the change contained to the existing **Browse Patoloji Atlası…** window (no new
  menu item).

## 3. Non-goals (deferred, not built here)

- **Set-list export/import** — sharing a curated set as a small standalone file
  (list of image IDs) that rebuilds the same basket/project on another machine.
  Explicitly deferred and co-designed with the exam/quiz layer, which will define what a
  shareable "course pack" needs.
- **The examination / quiz interface** — its own spec.
- **"Add all in this category"** bulk-add — optional stretch; include only if it is a
  trivial addition to the context menu, otherwise defer.

## 4. Decisions (settled during brainstorming)

| Question | Decision |
|----------|----------|
| Sequencing of the two features | Build the project builder first; the exam/quiz layer is a separate later spec. |
| Project target | **Both** — create a new project *and* add the selection to the current project. |
| Selection mechanism | A **persistent basket** (ordered set), independent of the tree, that survives search/filter/refresh. |
| Where the builder lives | Integrated into the existing browser window (no new menu item), driven through a **separate modal "Create project…" dialog**. |
| Distribution of a set | Out of scope for now — for this feature the user shares the created project folder; the set-list export is a deferred follow-up. |

### 4.1 Reconciling "persistent basket" + "separate dialog"

These two choices interact. The reconciliation the user approved:

- The selection **state** is a persistent basket that survives search/filter/refresh.
- The browser window itself stays uncluttered: it shows only a **"N selected"** count
  and an **Add to selection** action — **not** a permanent basket panel.
- The **full basket list** (review, remove per item, clear all) lives **inside the
  Create-project dialog**. So while browsing you see the running count; the list is one
  click away.

## 5. UX flow

### 5.1 Browser window changes (`AtlasBrowser`)

- New **"Add to selection"** button next to *Open in QuPath*, which adds the
  tree-selected image to the basket. If nothing is selected it sets a status hint
  ("Select a case first"), consistent with the existing `openSelected()` behaviour.
- A right-click **context menu** on tree image rows with **Add to selection**.
- **Double-click keeps its current meaning: Open** (unchanged). Adding to the basket is
  a deliberate, separate gesture so the primary "open a slide to look at it" action is
  never surprised.
- A **"N selected"** label and a **"Create project…"** button (disabled while the basket
  is empty), placed in the bottom action bar next to the existing About button.

### 5.2 Create-project dialog (`ProjectBuilderDialog`, modal)

- Header/description naming what will be created.
- A **list of the selected images** (title + category) with:
  - per-item **Remove**
  - **Clear all**
- **Target choice** (radio or segmented control):
  - **New project** (default)
  - **Add to current project** — enabled only when a project is currently open;
    otherwise disabled with an explanatory tooltip.
- For **New project**: a **project name** field and a **location** folder picker
  (choose an empty/new folder for the project).
- **Create** and **Cancel** buttons.
- On **Create**: run the build on a background thread with a progress indicator; on
  success, **open the new project** (or refresh the current one), show a summary status,
  and **clear the basket**. On cancel, nothing changes and the basket is preserved.

## 6. Architecture / components

### 6.1 `AtlasProjectService` (new)

A small, UI-free helper that owns the project mechanics so both the single-open path and
the builder share one implementation. Pure enough to unit-test its logic.

- `ProjectImageEntry<BufferedImage> addCaseToProject(Project<BufferedImage> project, AtlasCase c)`
  — the sequence currently inlined in `AtlasBrowser.openSelected()`:
  build a `DziImageServer` from `c.getDziURI()`, `project.addImage(server.getBuilder())`,
  `entry.setImageName(c.getTitle())`, save `ImageData`, and **roll the entry back**
  (`project.removeImage(entry, true)`) if the save fails. It does **not** call
  `syncChanges()` — that is the caller's job (so a batch syncs once, not per image). The
  single-open flow keeps its current behaviour by calling this method and then
  `syncChanges()`.
- `BuildResult createProject(File dir, List<AtlasCase> cases)` — create the project via
  `Projects.createProject(dir, BufferedImage.class)`, then `addCaseToProject` for each,
  collecting per-image success/failure, then `project.syncChanges()` **once** at the end.
  Returns the created `Project` plus counts.
- `BuildResult addCasesToProject(Project<BufferedImage> project, List<AtlasCase> cases)`
  — for *Add to current*; **skips** cases already present (see §7 dedup), adds the rest,
  collects per-image success/failure, and calls `project.syncChanges()` **once** at the
  end.
- `BuildResult` — a small record: created/updated project, `added`, `skipped`
  (already-present), and `failed` (with the reason per failed case) for the summary
  message.

### 6.2 `ProjectBuilderDialog` (new)

The modal JavaFX dialog described in §5.2. Owns the basket-review UI and target choice;
delegates the actual build to `AtlasProjectService`. Owned by (`initOwner`) the browser
stage.

### 6.3 `AtlasBrowser` (changed)

- Holds the basket: `private final java.util.LinkedHashSet<AtlasCase> selection = new LinkedHashSet<>();`
  (insertion-ordered, deduped by `AtlasCase` identity — see §7).
- Adds the *Add to selection* / *Create project…* buttons, the *N selected* label, the
  context menu, and the "update count" glue.
- Its existing `openSelected()` is refactored to call `AtlasProjectService.addCaseToProject`
  for the project branch (the no-project read-only branch is unchanged).

## 7. Data model, identity, dedup

- **Basket identity.** Slides are identified for dedup by their **DZI URL**
  (`AtlasCase.getDziUrl()` / `getDziURI()`), which is unique per stain of a case.
  `AtlasCase` currently has no `equals`/`hashCode` (so `LinkedHashSet` would dedup by
  object identity, not URL). The chosen fix is to add value `equals`/`hashCode` based on
  `dziUrl` so the basket dedups correctly by slide. This is a small, safe addition — no
  other code relies on `AtlasCase` identity semantics.
- **Project dedup (Add to current).** For each existing entry, compare
  `entry.getServerBuilder().getURIs()` against `c.getDziURI()`; skip the case if its DZI
  URI is already present. (`ProjectImageEntry.getServerBuilder().getURIs()` returns
  `Collection<URI>` in the 0.6 API — verified; this avoids relying on a
  `getServerURIs()`-style accessor that does not exist on the entry in 0.6.)

## 8. Threading & concurrency

- Building a project (new or append) touches `project.addImage` / `saveImageData` /
  `syncChanges`, which are **not thread-safe**, exactly like the existing single-open
  path that already runs on a background daemon thread guarded by the `opening` flag.
- The builder uses the **same discipline**: run the build on a background daemon thread,
  show the progress indicator, and use a re-entrancy guard so only **one** build runs at a
  time. The guard is unified with the existing single-open guard so a build and a single
  open cannot run concurrently (both mutate the shared project). All guard/flag reads and
  writes stay on the JavaFX thread, as they already do in `openSelected()`.
- UI updates (open project, refresh, status, re-enable buttons, clear basket) happen via
  `Platform.runLater`.

## 9. Error handling

- **Partial failure** does not abort the build: a case that fails to add is recorded in
  `BuildResult.failed` and the build continues. The resulting project keeps every image
  that succeeded.
- The completion summary reports, in one status line, e.g.
  `Created "Course A" — added 8, skipped 2 (already present), failed 1`. If any failed, a
  follow-up `Alert` lists which ones and why (mirrors the existing `fail()` dialog style).
- New-project creation failures (e.g., non-empty/invalid folder, IO error) are reported
  and leave no half-made project selected in QuPath.
- The dialog validates before enabling **Create**: New requires a name and a chosen
  location; Add-to-current requires an open project.

## 10. Menu / discoverability

No new menu item. The single entry point remains **Extensions → Browse Patoloji
Atlası…**; the builder is reached from the browser window's new buttons.

## 11. Testing

- **Automated (JUnit, already configured in `build.gradle`):** unit-test the UI-free
  logic — `AtlasCase` `equals`/`hashCode` by DZI URL; basket dedup (adding the same case
  twice keeps one); `BuildResult` aggregation; and the dedup predicate that decides
  "already present" given a set of existing URIs. These need no network and no JavaFX.
- **Not automated (stated honestly, not faked):** `addCaseToProject` /
  `createProject` actually add images, which requires a live `DziImageServer` (network)
  and a QuPath runtime; and `ProjectBuilderDialog` is JavaFX UI. These are verified by a
  **manual click-test** in a running QuPath: select several images across categories
  (with searches in between to prove persistence), create a new project, confirm all
  entries open and stream; then add a further selection to that project and confirm
  duplicates are skipped.
- Report verification truthfully: "service logic unit-tested; image-add and dialog
  verified manually in QuPath."

## 12. Files touched

- `src/main/java/com/patolojiatlasi/qupath/AtlasProjectService.java` — **new**
- `src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java` — **new**
- `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` — **changed** (basket,
  buttons, context menu, refactor `openSelected` to use the service)
- `src/main/java/com/patolojiatlasi/qupath/AtlasCase.java` — **changed** (`equals` /
  `hashCode` on `dziUrl`)
- `src/test/java/com/patolojiatlasi/qupath/AtlasProjectServiceTest.java` (+ an
  `AtlasCaseTest` if useful) — **new**
- `README.md` — **changed** (document the "select images → create project" workflow)

## 13. Verified QuPath 0.6 API (for implementation)

- `qupath.lib.projects.Projects.createProject(File, Class<T>) : Project<T>`
- `Project.addImage(ServerBuilder<T>) : ProjectImageEntry<T>` (throws `IOException`)
- `Project.removeImage(ProjectImageEntry<?>, boolean)`, `Project.syncChanges()`,
  `Project.getImageList()`, `Project.getPath()`, `Project.getName()`
- `ProjectImageEntry.getServerBuilder().getURIs() : Collection<URI>` (dedup)
- `ProjectImageEntry.setImageName(String)`, `saveImageData(ImageData)`
- Existing per-image logic already proven in `AtlasBrowser.openSelected()` and
  `DziImageServer.getBuilder()`.

## 14. Open questions

None blocking. The one place two answers interacted (persistent basket vs. separate
dialog, §4.1) is resolved and approved.
