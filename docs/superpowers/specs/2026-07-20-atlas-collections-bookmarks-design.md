# Portable Collections & Bookmarks — design

- **Date:** 2026-07-20
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/collections-bookmarks`
- **Status:** Approved design (feature #5 of the research backlog), ready for planning

## 1. Goal

Save a curated set of atlas slides to a small, **shareable** JSON file and load it back; plus a
per-slide **★ favorite** toggle auto-persisted as a "Favorites" collection. This is the
previously-deferred "set-list export/import" (share a curated set as a tiny file). A collection is
**portable**: saved against one catalogue snapshot, it reloads correctly against a different one,
reporting anything no longer present. Loading a collection or favorites populates the browser's
selection **basket** — it never opens or swaps a viewer.

## 2. Scope (v1 = Collections + Stars; Resume deferred)

- **Collections** — save the browser basket → JSON; load a JSON → re-resolve into the basket with a
  found/missing summary. Inherently shareable.
- **Stars / Favorites** — a ★ toggle per slide (browser context menu + an in-tree marker),
  auto-persisted to a fixed-path "Favorites" collection; a one-click "load favorites into basket".
- **Deferred:** resume-where-you-left-off / recents (needs a viewer open-tracking hook).

## 3. Persistence model — reuse ONE schema

Favorites is just an `AtlasCollection` stored at a fixed path — do NOT fork a second format.

- Fixed directory: `~/QuPath-atlas-collections/` (`new File(System.getProperty("user.home"),
  "QuPath-atlas-collections")`), mirroring `FocusHeatmap`'s `~/QuPath-atlas-focus-maps/`. `mkdirs()`
  on first save.
- Favorites file: `<fixedDir>/favorites.json` (an `AtlasCollection` named "Favorites").
- User collections: saved via a `FileChooser` (initial dir = the fixed dir) but savable anywhere;
  loadable from anywhere → **shareable**.

## 4. Pure / IO core (unit-tested)

### `AtlasCollection` (pure)
`record AtlasCollection(int formatVersion, String name, List<Entry> entries)` where
`record Entry(String dziUrl, String reponame, String image, String title)`. `FORMAT_VERSION = 1`.
- Each `Entry` stores the **stable key** (`dziUrl`) **plus display fields** (reponame/image/title)
  so a `missing` entry is still nameable in the load summary.
- `static AtlasCollection fromCases(String name, Collection<AtlasCase> cases)` — builds entries from
  `getDziUrl/getReponame/getImage/getTitle`.
- `record Resolution(List<AtlasCase> found, List<Entry> missing)`.
- `static Resolution resolve(AtlasCollection coll, List<AtlasCase> catalog)` — for each entry, match
  against `catalog` by **query-stripped** DZI URL. **CRITICAL:** strip the `?…` query on BOTH the
  stored entry key AND the catalogue's `getDziUrl()` (reuse `CaseCompare.stripQuery`), so a stored
  `…/HE.dzi?mpp=0.26` matches a catalogue `…/HE.dzi` and vice-versa. `found` preserves the
  collection's entry order (deduped); `missing` = entries with no catalogue match.

### `AtlasCollectionIO` (I/O; mirrors `AtlasQuizIO`)
- `static void save(AtlasCollection, File)` — Gson pretty-print, `Files.writeString` UTF-8. `mkdirs`
  the parent.
- `static AtlasCollection load(File)` — `Files.readString` + Gson. **Fail-soft on a bad/old file:**
  `formatVersion` guarded; on version mismatch, parse failure, or absent file → return `null` (or an
  empty collection — pick one and document), never throw. Caller treats `null`/empty as "nothing
  loaded" + a status message. (User-facing "load this file" errors may surface a friendly message,
  but must not crash the browser.)

### `Favorites` (pure key-set + fixed-path store)
- In-memory `LinkedHashSet<String>` of **query-stripped** DZI URLs (O(1) `contains` for the cell
  factory). Pure, testable: `boolean contains(AtlasCase)`, `boolean toggle(AtlasCase)` (returns new
  state), `add/remove`, `List<AtlasCase> resolve(List<AtlasCase> catalog)` (via `AtlasCollection`).
- Persistence: backed by `AtlasCollectionIO` at `<fixedDir>/favorites.json`. `load()` is
  **corruption-tolerant** — a truncated/absent/old-version file degrades to **empty favorites**,
  logged, never throwing (mutable read-modify-write surface, unlike the write-once focus files).
  `save()` writes the current set as an `AtlasCollection("Favorites", …)`; `mkdirs` first. Each
  `toggle` from the UI persists immediately (small file, synchronous — see Threading).

## 5. UI — `AtlasBrowser` integration

- **Bottom bar buttons** (near "Create project…"):
  - **"Koleksiyonu kaydet…"** — if the basket is empty → status hint; else `FileChooser` (initial
    dir = fixed dir, default name `koleksiyon.json`, `*.json` filter) → `AtlasCollectionIO.save(
    AtlasCollection.fromCases(name, selection), file)`. Name = the file's base name (or prompt).
  - **"Koleksiyon yükle…"** — `FileChooser` → `AtlasCollectionIO.load` → `AtlasCollection.resolve(
    coll, allCases)` → add every `found` to the `selection` basket (dedup is the set's job) →
    `updateSelectionCount()` → status "N yüklendi, M artık katalogda yok" (list missing titles if
    small). A `null`/empty load → a friendly status, no crash.
  - **"Favorileri yükle"** — add `Favorites.resolve(allCases)` to the basket + status.
- **Context menu** (add to the existing `ContextMenu`): **"★ Favori"** toggle — on the tree-selected
  `AtlasCase`, `favorites.toggle(c)` → `favorites.save()` → `tree.refresh()` → status. (Label may
  stay static "★ Favori (aç/kapat)"; toggling is by current state.)
- **★ in-tree marker — a NEW `tree.setCellFactory` (the tree currently has none). Explicit
  requirements (or stale-cell bugs on scroll):**
  1. In `updateItem(Object item, boolean empty)`: if `empty || item == null` → `setText(null);
     setGraphic(null); return;` (TreeCells are recycled — reset BOTH).
  2. Branch on type: a `String` category → `setText(item.toString())` (categories must NOT go blank);
     an `AtlasCase c` → `setText((favorites.contains(c) ? "★ " : "") + c.toString())`.
  3. After any favorite toggle, call `tree.refresh()` so `updateItem` re-runs across visible cells.
  `favorites.contains` is O(1) (the `Set<String>` of stripped URLs), safe to call per cell.

- **No `setImageData` save-guard here.** Loading a collection/favorites only mutates the in-memory
  `selection` basket — it never opens or swaps a viewer, so feature #4's unsaved-changes swap guard
  is intentionally **not** applicable. (Called out so the implementer neither adds nor hunts for it.)

## 6. Threading

Save/load run **synchronously on the FX thread** after a `FileChooser` (or on a context-menu
toggle) — matching `AtlasQuizIO`/`QuizAuthorWindow.saveQuiz`'s established convention for small,
interactive, single-file I/O in this repo. No network; files are tiny. No `Platform.runLater`
dance needed.

## 7. Reuse & verified facts

- `AtlasBrowser.selection` (`LinkedHashSet<AtlasCase>`), `updateSelectionCount()`, the `TreeView<Object>`
  (nodes are category `String`s or `AtlasCase`), the existing `ContextMenu`, `allCases`
  (`List<AtlasCase>` = `loadBundled()`).
- `AtlasCase.getDziUrl/getReponame/getImage/getTitle/toString` (toString exists → tree display).
- `CaseCompare.stripQuery(String)` (public) for the query-stripped match on both sides.
- `AtlasQuizIO` (the Gson save/load + version-guard pattern to mirror).
- `com.google.gson` (already a dependency via the quiz).
- Home-dir persistence convention: `FocusHeatmap` uses `new File(System.getProperty("user.home"),
  "QuPath-atlas-focus-maps")`.

## 8. Testing

- **Automated (JUnit):**
  - `AtlasCollection`: `fromCases` builds entries with all fields; `resolve` matches by
    query-stripped URL **including the `?mpp=` asymmetry** (stored key has `?mpp=`, catalogue doesn't,
    → found; and the reverse), preserves order, dedups, and reports genuinely-absent entries in
    `missing` (nameable via stored display fields).
  - `AtlasCollectionIO`: save→load round-trip equality (temp file); a wrong-`formatVersion` file and
    a garbage/truncated file both fail **soft** (null/empty, no throw); a non-existent file too.
  - `Favorites`: `toggle` flips membership and is keyed by query-stripped URL (so `…?mpp=` and the
    bare URL are the same favorite); `resolve` returns the catalogue cases; a corrupt favorites file
    loads as empty.
- **Manual (running QuPath):** the buttons, the `FileChooser` round-trip, the ★ context toggle +
  in-tree marker refresh on scroll, load-summary counts, sharing a file between two machines.

## 9. Non-goals / notes

- No cloud sync / server — files are local + shared manually (the whole point of "portable").
- Not tied to a QuPath project — a collection is a *pick list* that seeds the basket; the user then
  opens or builds a project from it as today.
- Resume/recents deferred (open-tracking hook out of scope for v1).
- The collection format is intentionally the SAME as favorites (`AtlasCollection`) — one schema.
