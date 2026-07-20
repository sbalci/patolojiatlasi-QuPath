# Blinded temporal focus recording — captured requirements (spec to be written)

> User-requested enhancement to the existing `focus/` heatmap (NOT part of the ranked #1–#12
> research backlog). Requested 2026-07-20; to be spec'd + built AFTER feature #5 (Collections)
> merges. Motivation: seeing your own dwell heatmap **biases** where you look next — so record
> viewed areas silently for unbiased spatial + temporal research.

## Approved decisions (from the user)

1. **Blinding = data-only, NEVER shown in-app.** During AND after a blinded recording, QuPath never
   renders the map. Recording writes data silently to `~/QuPath-atlas-focus-maps/`. Visualization
   happens ONLY in the separate offline tools (`tools/aggregate-focus.py` pipeline). Strongest bias
   guarantee. (So: no overlay, no monitor window, no coverage %/status while blinded; and no
   "reveal after stop" either.)
2. **Idle handling = pause when unfocused/idle.** Accumulate dwell only while the QuPath window is
   focused and the view is active. A gap (window unfocused, app switched, or Δt > ~3× the sample
   interval) does NOT add time. Measures "time studying this region", not "time present".
3. **Sequencing = finish #5 first**, then spec + build this.

## Feasibility / approach (grounded in the current code)

- `focus/FocusHeatmap.java`: sampling runs while `track = overlayVisible || windowVisible` (Timeline
  at `SAMPLE_MS`, `tick()` → `getDisplayedRegionShape().getBounds()` → `currentMap.deposit(...)`,
  refreshes overlay/window only when visible; auto-saves on slide-change/stop to `defaultDir()`).
  → Add a THIRD state `blindedRecording`: `track = overlay || window || blinded`; in `tick()`, when
  blinded, deposit but do NO visual refresh. While `blindedRecording` is on, DISABLE the
  overlay/window menu items so the map can't be revealed mid-session. Menu: a new
  "Kör kayıt (araştırma)" start/stop toggle.
- `focus/FocusMap.java`: `deposit(x,y,w,h)` currently adds a FIXED total weight of 1 per sample
  (`sampleCount++`). → For TRUE temporal data, deposit the actual elapsed time as the weight:
  add `deposit(x,y,w,h, double weightMs)` (or a weighted overload), where `weightMs = min(Δt, cap)`
  and `Δt = now − lastTickMs` (`System.currentTimeMillis`/`nanoTime`). Cap a single tick's Δt to
  ~3× `SAMPLE_MS` so an idle gap can't dump minutes into one cell. Track per-cell accumulated ms +
  total session duration.
- Idle pause: skip the deposit (and reset `lastTickMs`) when the QuPath stage is not focused
  (`stage.focusedProperty()`), or when Δt exceeds the cap (treat as a resumed session, not dwell).
- Persistence: extend the saved JSON schema (bump to `atlas-focus-contribution/2` or add fields) to
  carry per-cell **dwell-milliseconds** + total duration + (keep) sampleCount, so downstream gets
  both spatial (which cells) and temporal (seconds per cell). Reuse the existing anonymized
  contribution machinery (no username; random sessionId; slideKey = DZI URL sans query).

## Refinement (user, 2026-07-20): project-level "blinded tracking on by default"

Rather than (only) a manual start/stop toggle, let a researcher **prepare a project where blinded
focus recording is ON by default**, so a participant just opens the project and views slides while
recording runs automatically — no per-session action, easiest study workflow.

Design implications (to spec after #5):
- **Project flag.** Persist a per-project setting "blinded focus recording = on". Store it where the
  extension controls it — a sidecar file in the project dir (e.g. `atlas-research.json`,
  `{schema, blindedTracking:true, studyId?}`) is simplest and avoids depending on QuPath project
  metadata internals. (Confirm whether QuPath 0.6 `Project` exposes a usable key-value store; else
  sidecar.)
- **Auto-start hook.** The extension watches for a project opening — `QuPathGUI.projectProperty()`
  ChangeListener (mirror how `RotationControl` listens to `viewerProperty()`); on a project with the
  flag, silently start blinded recording (no overlay/window/status, per decision #1). Remove the
  listener/stop cleanly on project close.
- **Builder integration.** The curated-project builder (`ProjectBuilderDialog` / `AtlasProjectService`,
  the About+builder feature) gets an option when creating a project:
  "Araştırma projesi — kör odak kaydı (blinded) açık". Checking it writes the sidecar flag so the
  handed-out project auto-records.
- **Study workflow:** researcher builds a research project (blinded on) → hands out the tiny project
  folder → participants open + view → anonymized spatial+temporal data collected automatically,
  unbiased.

## Open items to resolve when writing the spec

- **Consent/ethics of covert auto-on.** "Never show in-app" + "auto-on via project" = the participant
  sees no indication recording is happening. For consented study participants this is the point
  (unbiased), but consent must be handled — decide: a one-time "Bu proje araştırma için anonim
  görüntüleme kaydı tutar" notice on first open of a research project, vs fully silent when consent
  is obtained out-of-band. Align with OSF governance (opt-in, anonymous-by-construction, no PHI).
  This is the researcher's/ethics call — surface it, don't hard-code.

- Backward-compat of the JSON schema vs `tools/aggregate-focus.py` (Phase-2 aggregator reads the
  current schema) — decide: additive field (safe) vs version bump (update aggregator too).
- Whether the weighted `deposit` REPLACES the fixed-1 deposit for the visible modes too, or blinded
  uses the weighted path while overlay/window keep the fixed-1 heat aesthetic. (Likely: weighted ms
  is the stored/research quantity; the on-screen heat render can normalize either way. Keep the
  visible heatmap's look unchanged; add ms as a parallel accumulation.)
- Consent/anonymization copy for a research-recording toggle (align with OSF governance:
  anonymous-by-construction, opt-in, no PHI).
