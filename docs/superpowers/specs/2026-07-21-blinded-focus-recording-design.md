# Blinded temporal focus recording — design

- **Date:** 2026-07-21
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/blinded-focus-recording`
- **Status:** Approved design (user-requested enhancement to the `focus/` feature; not part of the ranked #1–#12 backlog), ready for planning

## 1. Goal

Let a researcher record **which regions a viewer looked at and for how long**, WITHOUT the viewer
ever seeing a heatmap — because seeing your own dwell map biases where you look next. The data is
spatial (grid) **and** temporal (per-cell milliseconds of active viewing). To make studies easy to
run, a researcher can **prepare a project with blinded recording on by default**: a participant just
opens it and views slides while anonymized data is collected silently.

## 2. Approved decisions (from the user)

1. **Data-only blinding:** during AND after a blinded recording, QuPath never renders the map (no
   overlay, no monitor window, no coverage status). Data is written silently; visualization happens
   only in the offline `tools/aggregate-focus.py` pipeline.
2. **Pause when unfocused/idle:** dwell accumulates only while the QuPath window is focused and a
   slide is open; unfocused / app-switched / suspend-resume gaps do not add time.
3. **Project-default-on:** a project can carry a "blinded tracking" flag; opening it auto-starts
   blinded recording. Set at project-build time via the curated-project builder.
4. **Temporal via Δt-weighting:** deposit the measured elapsed time per tick (not a fixed count), so
   each grid cell accumulates real milliseconds. The **visible** heatmap keeps its current fixed-weight
   look — only blinded recording is ms-weighted.
5. **Consent:** the first time a participant opens a blinded-research project, a one-time notice
   states anonymized viewing (regions + time, no identity) is recorded for research; shown once per
   project, then silent.

## 3. Temporal core — `FocusMap` (pure; extend + unit-test)

Today `deposit(x,y,w,h)` adds a fixed total weight of 1 per sample and bumps `sampleCount`.

- Add `boolean deposit(double x, double y, double w, double h, double weight)` — spreads `weight`
  (instead of 1) over the covered cells. The existing `deposit(x,y,w,h)` delegates with `weight=1.0`
  (unchanged behavior for the visible heatmap).
- Track total accumulated weight: add `double getTotalWeight()` (sum of deposits) — for a blinded
  (ms-weighted) map this is the total dwell-milliseconds; keep `sampleCount` too.
- `getGrid()` values are now "sample count" for the visible path and "milliseconds" for the blinded
  path — the SAVED JSON records which (a `weightUnit` field), so the grid is never ambiguous.
- Pure/testable: weighted deposit distributes proportionally; `getTotalWeight` sums; a zero/negative
  weight is a no-op (guarded).

## 4. Blinded recording mode — `FocusHeatmap` (extend)

Today sampling runs while `track = overlayVisible || windowVisible`; `tick()` samples the viewport
and refreshes the overlay/window when visible; auto-saves on slide-change/stop.

- Add a third state `blindedRecording`. `track = overlayVisible || windowVisible || blindedRecording`.
- **`tick()` for blinded:** compute `now = System.currentTimeMillis()`, `dt = now - lastTickMs`,
  update `lastTickMs`. Deposit ONLY when **active** = (the QuPath stage is focused) AND (a slide is
  open). When active, deposit `min(dt, DT_CAP_MS)` ms (cap ≈ 2× `SAMPLE_MS`, guards suspend/resume
  spikes) via the weighted `deposit`. When inactive, skip the deposit (no idle time added) but still
  advance `lastTickMs`. **No overlay/window/status refresh in the blinded path** — nothing visible.
- **Mutual exclusion / no peeking:** while `blindedRecording` is on, DISABLE the overlay + window
  menu toggles (and refuse to enable them) so the map cannot be revealed mid-session. Starting
  blinded recording clears any existing visible overlay/window.
- **Menu:** a new toggle **"Kör kayıt (araştırma)"** — start/stop blinded recording manually. Start
  → fresh `FocusMap`, `lastTickMs = now`, disable visible toggles; Stop → save + re-enable toggles.
- **Save (temporal):** on stop / slide-change / project-close, write the anonymized contribution
  with the ms grid. Extend the schema: bump `CONTRIBUTION_SCHEMA` to `atlas-focus-contribution/2`
  (or add fields) carrying `weightUnit:"ms"`, `durationMs` (= `getTotalWeight()`), plus the existing
  slideKey/sessionId/dims/grid/date. Keep the anonymized machinery (no username; random sessionId;
  slideKey = DZI URL sans query; date-only). **Aggregator note:** `tools/aggregate-focus.py` reads
  the current schema — update it to accept v2 (ms grid) OR keep the field additive so old readers
  still work; decide in the plan.

## 5. Blinded-research project — flag, auto-start, consent, builder

### 5.1 Sidecar flag — `research/BlindedResearch` (small helper; testable read/write)
- A per-project sidecar JSON in the project directory: `<projectDir>/atlas-research.json`,
  `{ "schema":"atlas-research/1", "blindedTracking":true, "consented":false }`. (Project dir =
  `project.getPath().getParent()` — `getPath()` is the `.qpproj` path, verified in 0.6.)
- Helper API (pure-ish, file I/O fail-soft like `AtlasCollectionIO`): `isBlindedProject(Project)`,
  `hasConsented(Project)`, `markConsented(Project)`, `writeFlag(File projectDir, boolean blinded)`.
  A missing/corrupt sidecar → not a research project (never throws).

### 5.2 Auto-start hook — in `AtlasExtension` (or a small `research/` controller)
- Register a `ChangeListener` on `qupath.projectProperty()` (verified: `ReadOnlyObjectProperty<
  Project<BufferedImage>>`; mirror `RotationControl`'s `viewerProperty()` listener style).
- On a project becoming active: if `BlindedResearch.isBlindedProject(p)` →
  - if `!hasConsented(p)` → show the **one-time consent notice** (Turkish, §2.5); on OK
    `markConsented(p)`; (if the notice is declined, do not start — respect refusal).
  - start blinded recording (via `FocusHeatmap`).
- On the project changing away / closing → stop + save the blinded recording.
- Removed cleanly on extension shutdown; guard re-entrancy (don't double-start).

### 5.3 Builder integration — `ProjectBuilderDialog` / `AtlasProjectService`
- Add a checkbox **"Araştırma projesi — kör odak kaydı (blinded)"** to the create-project dialog.
- When checked, after the project is created/opened, `BlindedResearch.writeFlag(projectDir, true)`
  writes the sidecar so the handed-out project auto-records on open.

## 6. Reuse & verified facts

- `focus/FocusMap` (grid float[], deposit, sampleCount), `focus/FocusHeatmap` (Timeline sampling,
  `overlayVisible`/`windowVisible`, `defaultDir()` = `~/QuPath-atlas-focus-maps/`, contribution
  machinery + `slideKey`/`sessionId` anonymization).
- `QuPathGUI.projectProperty()` / `getProject()` (verified 0.6); `Project.getPath()` → `Path` of the
  `.qpproj` (verified 0.6); `Stage.focusedProperty()`/`isFocused()` for the active check.
- `System.currentTimeMillis()` for Δt (extension is real Java — no sandbox clock limits).
- `ProjectBuilderDialog` / `AtlasProjectService` (the curated-project builder).
- Gson (already a dep). Home-dir persistence convention (`FocusHeatmap`).

## 7. Testing

- **Automated (JUnit):**
  - `FocusMap`: weighted `deposit` distributes `weight` proportionally over covered cells;
    `getTotalWeight` sums deposits; `deposit(...,1.0)` == the legacy `deposit(...)`; zero/negative
    weight no-ops.
  - `BlindedResearch`: `writeFlag`→`isBlindedProject` round-trip; `markConsented`→`hasConsented`;
    a missing/corrupt sidecar → not blinded, not consented, no throw. (Use a temp dir; do not touch
    a real project.)
- **Manual (running QuPath):** blinded start/stop shows NO overlay/window/status; the visible
  toggles are disabled while blinded; dwell accumulates only while focused (unfocus → time stops);
  the saved JSON carries ms + durationMs; a project with the flag auto-starts recording and shows the
  one-time consent once; the builder checkbox writes the sidecar.

## 8. Non-goals / notes

- No in-app visualization of blinded data — ever (decision #1). Offline tools only.
- The visible heatmap's look is unchanged (only its data path stays fixed-weight; blinded is ms).
- Covert-by-design for consented participants; the one-time notice + anonymization + opt-in build
  flag are the governance surface (aligns OSF: anonymous-by-construction, no PHI).
- Upload stays gated off (as today); contributions are written locally.
