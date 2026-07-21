# Blinded data collection: in-project storage, autosave, zip — design

- **Date:** 2026-07-21
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/blinded-data-collection`
- **Status:** Approved design (enhancement to the blinded focus recording feature), ready for planning

## 1. Goal

Make blinded research data (a) easy to collect back and (b) crash-safe:
1. **In-project storage** — write blinded fragments into `<projectDir>/atlas-focus/` so the data
   travels with the project the coordinator hands out and gets back.
2. **One timestamped zip** — on session end, bundle the fragments into a single
   `<projectDir>/atlas-focus_<YYYYMMDD-HHMMSS>_<sessionShort>.zip` the participant emails.
3. **Autosave / crash safety** — a periodic checkpoint + a JVM shutdown hook so a crash/force-quit
   loses at most one checkpoint interval (today the current in-progress slide is lost entirely).

Preserves the existing guarantees: **data-only** (JSON only, never a PNG) and **anonymized**
(no username, public/`sha256:` slide keys, random sessionId, date-only).

## 2. Storage location (attribution is the subtlety)

- Blinded fragments go to `<projectDir>/atlas-focus/` when a project is open; fall back to the
  current `~/QuPath-atlas-focus-maps/contributions/` when blinded recording runs with no project
  (manual toggle, no project).
- **Capture the target dir at `startBlinded()` time**, store it on the recorder, and use it for
  every save (checkpoint, per-slide fragment, stop, shutdown, zip). Rationale: when a project
  *switch* triggers `stopBlinded()` (via the project-open hook), `qupath.getProject()` is already
  the **new** project — re-resolving at save time would misattribute the finished session's data to
  the wrong project. Resolving once at start pins the session to the project that was open when
  recording began.
- Project dir = `project.getPath().getParent().toFile()` (reuse `BlindedResearch.projectDir(...)`);
  null (unsaved project / none) → the home-dir fallback.

## 3. `BlindedStore` (new helper; the testable core)

`focus/BlindedStore.java` (or `research/`) — pure/IO, unit-tested:
- `static File blindedDir(File projectDir, File homeFallback)` — `projectDir != null ?
  new File(projectDir, "atlas-focus") : homeFallback`. (The recorder supplies both.)
- `static File zipFragments(File dir, File zipTarget)` — zips every `*.json` in `dir` whose name
  starts with `focus-blinded__` OR ends with `.partial.json` (final fragments + any crash-surviving
  checkpoints) into `zipTarget` (a `java.util.zip.ZipOutputStream`, entry name = the file's base
  name). Creates `zipTarget`'s parent. Returns `zipTarget`. Best-effort per entry (skip a file that
  can't be read; never throw on one bad entry). No-op-safe on an empty/missing dir.
- `static String zipName(String tsStamp, String sessionShort)` → `"atlas-focus_" + tsStamp + "_" +
  sessionShort + ".zip"` (timestamp passed in — keep it pure/testable; the caller stamps the time).
- Tests: a temp dir with two `focus-blinded__*.json` + one `x.partial.json` + one unrelated
  `note.txt` → `zipFragments` produces a zip containing exactly the three focus files, not the txt;
  empty dir → empty/absent-entry zip, no throw; `blindedDir` resolution (projectDir → `atlas-focus`
  subdir; null → fallback); `zipName` format.

## 4. `FocusHeatmap` wiring

- **Session dir field:** `private File blindedDir;` set in `startBlinded()` =
  `BlindedStore.blindedDir(projectDirOrNull(), new File(defaultDir(), "contributions"))`, where
  `projectDirOrNull()` reads `qupath.getProject()` → path parent (null-safe). All blinded saves
  (`saveBlinded`, checkpoint, stop) write into `blindedDir` instead of the hardcoded
  `defaultDir()/contributions`.
- **Periodic checkpoint:** a tick counter; every `CHECKPOINT_EVERY_TICKS` (≈ 30 s worth of
  `SAMPLE_MS` ticks) while blinded and the current map is non-empty, write the current slide's map
  to `<blindedDir>/session-<sessionId>.partial.json` (schema/2, anonymized — reuse
  `buildBlindedJson`), overwriting each interval. This is the only in-progress-slide protection.
- **Promotion / cleanup:** in `switchTo` (blinded) and `stopBlinded`, after the final
  `saveBlinded(...)` fragment is written for the finished slide, **delete the `*.partial.json`
  checkpoint** (its data is now in the final fragment) and reset the checkpoint counter.
- **Shutdown hook:** register once (guard against double-registration) via
  `Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown))`. `flushOnShutdown`:
  best-effort, wrapped in try/catch, no throw — if `blindedRecording` and `currentMap` non-empty,
  write a final blinded fragment from a **snapshot** (clone the grid to avoid an FX-thread mutation
  race) into `blindedDir`. Covers graceful JVM exit; a hard power-loss still relies on the last
  checkpoint. (A shutdown hook can't be per-session removed, so its body self-guards on
  `blindedRecording`.)
- **Auto-zip on session end:** in `stopBlinded()`, after the final fragment + checkpoint cleanup,
  call `BlindedStore.zipFragments(blindedDir, new File(zipParent, BlindedStore.zipName(stamp,
  sessionShort)))` where `zipParent` = the project dir (the parent of `blindedDir`) when in a
  project, else `blindedDir`; `stamp` = `LocalDateTime.now()` formatted `yyyyMMdd-HHmmss` (UI-side,
  impure is fine here); `sessionShort` = first 8 chars of `sessionId`. The zip bundles ALL blinded
  fragments in `blindedDir` (all slides of the session) → one file to send. Best-effort (log on
  failure; a zip failure must not break stop).

## 5. Data-only / anonymization invariants (unchanged, must hold)

- The checkpoint, the shutdown-flush, and the zip contents are **JSON only** — never a PNG (the
  `currentMapBlinded` guard on `save()` still stands; the new paths use `buildBlindedJson`/
  `saveBlinded`, never `save()`).
- All JSON stays anonymized (no `user`, `sha256:` non-http slide keys, date-only). The zip filename
  carries only a timestamp + the random `sessionShort` — no identity. The in-project *path* reflects
  the researcher-chosen project location; that's not identity in the data.

## 6. Reuse & verified facts

- `FocusHeatmap`: `startBlinded`/`stopBlinded`/`switchTo`/`saveBlinded`/`buildBlindedJson`/
  `sessionId`/`currentMap`/`defaultDir`/the Timeline tick; `FocusMap.getGrid()` (clone for snapshot).
- `BlindedResearch.projectDir(Project)` (Task-3 helper) for the project dir.
- `qupath.getProject()` → `Project`; `Project.getPath()` → `.qpproj` Path (parent = project dir).
- `java.util.zip.ZipOutputStream`, `Runtime.getRuntime().addShutdownHook` (stdlib; no new deps).

## 7. Testing

- **Automated (JUnit):** `BlindedStore` — `zipFragments` selects the right files + round-trips
  (unzip → same JSON bytes), skips unrelated files, no-throw on empty/bad; `blindedDir` resolution;
  `zipName` format. Temp dirs only.
- **Manual (running QuPath):** fragments land in `<projectDir>/atlas-focus/`; a checkpoint file
  appears within ~30 s and is deleted on clean slide-switch/stop; force-quit mid-slide leaves a
  recoverable `*.partial.json`; stop produces the timestamped zip in the project folder containing
  all fragments; no PNG anywhere; JSON has no username.

## 8. Non-goals

- No upload/network (still local + manual send). No cross-session dedup in the zip (a later session's
  zip is a superset snapshot — newest zip is the complete one; documented). No change to the
  aggregator (it already reads the fragment JSONs; point it at the project's `atlas-focus/`).
