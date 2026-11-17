# Pathology-CoT authoring recorder — design & verified facts (2026-10-11)

**Status:** implemented on `feat/cot-authoring-recorder` (`444b21e`, review fixes in the follow-up
commit). 56 new unit tests, 268 total, all passing; `clean build` green. **The GUI paths have not
been run yet** — see §11 for exactly what that leaves unverified.

**What it is.** A deliberate start/stop recorder for producing Pathology-CoT demonstration data:
press record, navigate the slide while reasoning, press stop, land directly in the CoT draft. It
sits beside — and is firewalled from — the pre-existing *blinded* research recorder.

---

## 1. The problem

Before this change, the only way to record navigation was to flag an entire project as a research
project. That path is correct for its purpose and wrong for authoring:

| | Blinded research recording | What a CoT author needs |
|---|---|---|
| Granularity | whole project, every open | one take, one slide |
| Visibility | silent, no indicator, by design | must see that it is running |
| Stopping | only on project change/close | on demand |
| Reader state | **unaware** (naturalistic) | **aware** (demonstration) |
| Finding the data | hand-pick a hashed fragment from `atlas-focus/` | already in hand |

Counted end to end, producing one CoT case went from **flag project → consent → navigate → close
project → menu → file-picker → slide-key confirm → save-dialog → author window** to **Shortcut+Shift+G
→ navigate → Shortcut+Shift+G → confirm → author window**. The two dialogs removed (fragment picker,
slide-key mismatch) were the bulk of the friction, because both ask the user to re-establish
something the recorder already knows.

## 2. Facts that shaped the design

Verified from source and `javap`, not assumed. Two of these **corrected earlier beliefs** of mine:

1. **A user-facing blinded start/stop already existed.** `FocusHeatmap.buildMenu()` builds the
   `CheckMenuItem` *"Gezinme kaydı (araştırma) — sessizce kaydeder, ısı haritası gösterilmez"*
   (`FocusHeatmap.java:337-343`) whose listener calls `startBlinded()`/`stopBlinded()`. So blinded
   recording has **four** start sites, not three — this class's menu item plus three in
   `AtlasExtension` (`:353`, `:386`, `:402`). That count is why the mode firewall is installed on
   `startBlinded()` itself rather than at its call sites.
2. **There is no idle detection anywhere.** The accrual gate is exactly
   `suppressCount == 0 && mainStageFocused() && viewer != null && viewer.getImageData() != null`
   (`FocusHeatmap.java:768`). The word "idle" appears only in comments. A focused, untouched viewer
   accrues dwell indefinitely.
3. **`FocusMap` caps per-tick credit at 500 ms; the scanpath does not.** `FocusMap.activeDwellMs`
   clamps `dt` to `DT_CAP_MS = 2 × SAMPLE_MS` before depositing into the dwell *grid*. The *path* is
   a bare timestamp list, and `PathologyCotDiscretizer` credited the raw gap between consecutive
   points for any gap under its 60 s idle threshold. **This is a data-quality bug that affected CoT
   output already** — see §6.
4. **`PathologyCotDiscretizer`'s dwell is timestamp-driven; its geometry is not.** `dwellMs`
   accumulates timestamp gaps, but `Run.cx/cy/w/h` and the mean downsample are **unweighted means
   over points** (`PathologyCotDiscretizer.java:33-45`). Uniform 4 Hz sampling is what makes those
   means implicitly dwell-weighted — which is why the authored recorder samples at the same fixed
   rate rather than on viewer events. (See §8 for why this rules out feeding QuPath's own
   event-driven recordings in raw.)
5. **The analysis loaders gate on an exact schema string.** `analysis/python/blinded_focus/io.py`
   accepts a file as a study fragment only when `schema ∈ SCHEMAS` (`atlas-focus-contribution/1..5`),
   mirrored in `analysis/R/blinded_focus.R`. An unknown schema is rejected with no code change.
6. **`PathologyCotIO.readFragment` ignores the schema string**, and `writeBehaviors` copies a
   fragment's `decision` map verbatim into the sidecar. Both facts are load-bearing in §5.
7. **`Alert.showAndWait()` inside a `Timeline` pulse throws**, and `AtlasExtension.onProjectChanged`
   runs synchronously inside the `projectProperty` listener, already nesting a consent
   `showAndWait`. Hence the deferred-stop and silent-stop paths in §7.

## 3. Architecture

Pure (no JavaFX, no QuPath imports — unit-testable off the FX thread, mirroring `focus.FocusMap`):

| File | Responsibility |
|---|---|
| `CotRecording` | the take: path, pauses, provenance, caps, `toFragment()` |
| `CotRecordingGate` | one tick's verdict: emit? credit how much? paused why? |
| `CotPauseLedger` | when a pause opens/closes and what it is called (see §7) |
| `RecordingModeGuard` | the two-direction firewall transition table |

FX shell:

| File | Responsibility |
|---|---|
| `CotRecorder` | 4 Hz `Timeline`, viewer binding, lifecycle, persistence, stop→draft |
| `CotRecordingHud` | the in-viewer `KAYIT` indicator |

Touched: `PathologyCotDiscretizer` (dwell cap), `PathologyCotActions` (`draftFromTake`),
`AtlasExtension` (menu + lifecycle + veto install), `FocusHeatmap` (**a `setStartVeto` hook only** —
no change to blinded logic, anonymisation, or fragment bytes).

The path point is the **same 8-element layout** blinded recording writes
(`[tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY]`), so the existing discretizer, draft builder and
export consume an authored take with no branch of their own.

## 4. The observer effect — honest framing

A visible record button makes the author aware, and awareness changes behaviour. The honest
assessment, and it is not symmetric across the data:

- **The behaviour half is compromised** — timing, order, coverage, magnification sequence and cursor
  use of an aware demonstrator are not those of a working diagnostician. Authored takes must
  therefore **never** enter the Abe-2026 screening-efficiency or `nav_accuracy` analyses, which
  assume unaware readers.
- **The content half survives** — *which* regions an expert chooses to show, at *what*
  magnification, with *what* explanation, is exactly what a demonstration is for, and is what
  Pathology-CoT consumes.

Correct label: **demonstration**, not naturalistic. Every take therefore carries
`recordingMode: "authored"`, `captureIntent: "demonstration"`,
`recordingIndicatorVisible: true`, `endReason`, `creditedMs`, `pausedMs`, `pointCount`,
`pathCapped`, and `protocolId/protocolVersion/protocolSha256` — the SHA-256 of the exact
instruction text shown at start, so a later analyst can tell which prompt a take was answering.
Editing `CotRecorder.PROTOCOL_TEXT` therefore requires a `PROTOCOL_VERSION` bump.

## 5. Three isolations from blinded study data

1. **Schema.** `atlas-cot-authored/1` — a **new family**, not an `atlas-focus-contribution/6` bump.
   Every already-deployed reader rejects it with no code change, i.e. it **fails closed**. A mode
   flag inside `/5`, or a `/6` bump, would fail *open* for any reader later extended to accept it.
   No Python or R change was needed, and none should be added.
2. **Location.** `~/QuPath-atlas-cot/<slide-slug>__<yyyyMMdd-HHmmss>/recording.json`. Outside the
   project entirely: `SHARING.md` tells study participants to send their whole `atlas-focus/`
   folder, and the analysis toolkits are pointed at a parent directory and glob `*.json`
   recursively. Physical separation means a participant hand-off cannot sweep one up.
3. **Identity.** `auth-<uuid>` session ids can never be confused with blinded ones.

**Provenance is written twice, deliberately** — as a top-level `provenance` object *and* mirrored
under `decision.cotProvenance`. Because `readFragment` ignores the schema (§2.6), a user can open an
authored take through the *old* file-picker menu action; because `writeBehaviors` copies `decision`
verbatim, the mirror is what makes the authored marker survive that route into `behaviors.json`.
Without it, that one path would emit a sidecar indistinguishable from naturalistic data. Pinned by
`CotRecordingIOTest.provenanceIsMirroredUnderDecisionSoTheLegacyDraftPathKeepsIt`.

**The raw slide URI.** Unlike a blinded fragment (anonymised `slideKey` only; the JSON-only,
no-PNG, no-username contract is a public promise in `README.md`), an authored take stores the **raw**
`slideUrl`, because the author is demonstrating on a known slide and the draft must reopen it. For a
local file that string can carry an OS user name and an accession number. Mitigations: the file stays
out of the project (above), and `recording.json` is not an input to `PathologyCotExport`, which
writes only crops + `conversation.json` + `README.txt`.

## 6. The dwell cap (a real bug, fixed)

`PathologyCotDiscretizer.MAX_CREDIT_MS = 500` now clamps each point's contribution, matching
`FocusHeatmap.DT_CAP_MS` and `CotRecordingGate.MAX_CREDIT_MS` so all three agree what one sample is
worth. A gap in the recorded path is **not** viewing time: the recorder appends a point only while
the main window is focused, so looking away leaves a hole the next point used to absorb.

Measured on the real pipeline before the fix: 7 points at 250 ms, a **30-second** gap, 7 more points
over the same region → one `INSPECT` with **`dwellMs = 33000`** for **3000 ms** of actual viewing.
After: **3500 ms** (12 intra-run gaps × 250 + one capped 500).

**On a gap-free path the cap is a no-op** — consecutive points are ~250 ms apart and
`min(250, 500) = 250` — so it changes a derived `Behavior` only where the old value was inflated.
The pre-existing `> 60 s ⇒ zero credit + split the run` rule is untouched.

> **One assumption stated explicitly, for the user to overrule:** this cap applies to drafts derived
> from **blinded** fragments too, not just authored takes. Two dwell semantics inside one
> `pathology-cot-behaviors/1` schema seemed worse than one corrected semantics. No fragment bytes
> change and no blinded artifact is rewritten — only newly derived drafts/sidecars differ, and only
> where a gap existed. Say so if you would rather the old behaviour were preserved for blinded
> sources.

## 7. Firewall, lifecycle, threading

**Mode firewall.** At most one recorder is active. `FocusHeatmap.setStartVeto(Supplier<String>)` is
consulted at the top of `startBlinded()` — one check covering all four start sites — and returns
`null` to allow or a Turkish refusal to block; a refusal also un-ticks `blindedItem`, because that
method is reached *from* the item's listener and the tick would otherwise claim a session that never
began. Symmetrically, `CotRecorder.start()` refuses while blinded is recording, while a programmatic
sweep holds `suppressTracking`, or with no slide open. `flagCurrentProjectAsResearch` refuses up
front, because it writes the research flag and marks consent *before* calling `startBlinded()` and
then reports success. Both directions **refuse** rather than silently preferring one recorder.

**Bound at start, never re-read at stop.** Slide URI, title, dimensions, objective power and the
take folder are captured in `start()`. By the time a take stops — possibly from a project-change
listener or the shutdown hook — the viewer may show something else. (Same lesson as
`FocusHeatmap.blindedDir`.)

**One slide per take.** A slide change or closed viewer ends the take rather than continuing into the
next image.

**Dialog-safety.** Stops originating in a `Timeline` pulse are deferred through `Platform.runLater`;
the project-change path uses `stopSilently(...)`, which shows nothing at all.

**Crash safety.** Checkpoint every ~30 s; a JVM shutdown hook writes synchronously on its own
thread, reading only `volatile` fields and making no FX calls.

**Why a menu item and a HUD, not a control window.** A floating always-on-top control would hold
window focus, and the gate requires QuPath's **main** stage to be focused — so the control would
silently pause the take it controls. The HUD is a mouse-transparent child of the viewer's own
`StackPane` and shows **credited** time, so what the author sees is what was recorded.

## 8. QuPath's own view recording — interop status

Not built. The findings, so nobody re-derives them:

- **Usable:** `ViewTrackerControlPane` is public with a public constructor and `run()`; QuPath ships
  the feature at `View → Show view tracker` (record/stop, play, export TSV, analysis window with
  frame table, time slider, downsample + time-displayed `RangeSlider` filters, data maps).
- **Not usable:** `ViewTracker` (package-private ctor), `ViewRecordingFrame` (the **class** is
  package-private, so the type cannot be named), `ViewTrackerTools`, `ViewTrackerPlayback`,
  `ViewTrackerAnalysisCommand`, `ViewTrackerDataMaps`. No programmatic construction, playback or
  gaze injection without reflection into package-private internals — rejected here as it was for
  `SimpleViewMode`.
- **The open door is the file format.** `getRecordingsFromDirectory` resolves
  `ProjectImageEntry.getEntryPath()/recordings`, filters `*.tsv`, and imports each. Column order,
  recovered from `getSummaryHeadings` bytecode: `Timestamp, X, Y, Width, Height, Canvas width,
  Canvas height, Downsample factor, Rotation, [Cursor X, Cursor Y], [Active tool], [Eye X, Eye Y,
  Eye fixated], [Z, T]` — four optional groups gated in that order. Line 1 holds
  `ImageServer.getPath()`; pretty timestamps are `%02d:%02d:%02d`; the delimiter is auto-detected.
- **Importing would require resampling, not a straight read.** Native capture is purely
  event-driven: frames are appended only from `visibleRegionChanged(...)` plus one final frame in
  `doStopRecording()` — no timer, no mouse handler, no focus gate. A stationary 10-second inspect
  emits **zero** intermediate frames. Dwell survives that (timestamp-driven, §2.4) but **geometry
  does not**: a 1 s pan emitting ~60 frames followed by a 20 s stare emitting 1 gives an unweighted
  centroid of ≈3033 instead of ≈4905 for a pan from x=1000 to x=5000 — the box lands mid-pan,
  ~2000 px from where the author actually looked. The contract for whoever builds it: **piecewise-
  constant hold onto the 250 ms grid, then discretize**, plus a per-gap dwell cap and a documented
  caveat that native recordings cannot distinguish idling from staring (no focus gate upstream).
- Worth noting for the webcam-gaze watchlist (W1/W2): the native schema already carries
  `Eye X / Eye Y / Eye fixated` and `Active tool`.

## 9. Deliberately not built

From the design sweep, left for the user to order. None is a prerequisite for the above.

- **Pins** — an in-recording hotkey to mark "this stop matters" + a note, giving the discretizer
  ground truth instead of inference. Widens scope past the agreed manual-rationale model.
- **Review pane** — a purpose-built per-stop rationale editor. Today the draft opens in
  `QuizAuthorWindow`, which has a private constructor, a static singleton and no "load this pack"
  API, so the user is told the path to open. Changing the quiz package was out of scope ("reuse, do
  not modify").
- **Native TSV import/export** — §8.
- **`cot-recover-last`** — a menu action to rebuild a draft from a crashed take.
  `CotRecordingIO.read(...)` already exists and is tested; only the menu wiring is missing.
- **Stay-vs-pan segmentation** (`PAN_INSPECT_MS` is still reserved, unused).
- **Export de-identification gate** — a pre-existing issue, not introduced here: the draft quiz pack
  stores the raw slide URI on every stop (`PathologyCotDraft.java:30`), so a shared draft leaks the
  same path `recording.json` is kept out of the project to protect. Worth a separate pass.
- **Observer-effect paired study** — recording the same reader blinded and authored to quantify the
  gap. A study design, not a feature.

## 10. Review pass — what it found

An adversarial review of the first commit produced four real defects. All are fixed; the fixes are
what §3's `CotPauseLedger` and several notes above describe.

1. **Pause intervals overlapped the credited windows, and each pause lost its leading tick.**
   The inline bookkeeping opened a pause at the *first closed* tick and closed it at `now`, so the
   window between the last open tick and the first closed one was in neither account, and the
   reopening tick's credit `[lastTick, now]` sat *inside* the pause it had just closed —
   `creditedMs + pausedMs` was not bounded by elapsed time. Fixed by pinning both edges to tick
   boundaries in the extracted `CotPauseLedger`, which also splits a pause when its cause changes
   and no longer loses a stall that is immediately followed by a closed gate.
   Regression: `CotPauseLedgerTest.pauseAndCreditTileTheTakeWithoutGapsOrOverlaps`.
2. **The menu tick desynced whenever `start()` did not record.** A `CheckMenuItem` flips `selected`
   *before* `onAction` fires, so a firewall refusal, a cancelled protocol notice, a failed
   post-modal re-check or an unwritable folder left a check mark with no take running (the property
   never changed, so the listener never fired). Fixed by re-syncing from
   `cotRecorder.isRecording()` at the end of the handler.
3. **A queued deferred stop could kill the *next* take.** `tick()` scheduled
   `Platform.runLater(stop(...))` while leaving the timer running, and nothing stopped a late
   `SLIDE_CHANGED`/`VIEWER_CLOSED`/`POINT_CAP` stop from terminating a take the user had already
   restarted. Fixed by stopping the timer before scheduling and having the lambda bail unless the
   take it was scheduled for is still current.
4. **The shutdown hook raced the FX thread.** `CotRecording`'s `path`/`pauses` were plain
   `ArrayList`s, so the hook's `pathSnapshot()` could throw `ConcurrentModificationException`
   mid-append — caught, losing everything since the last 30 s checkpoint. Every mutator and
   accessor is now `synchronized`. The hook also overwrote `endReason`; it now claims `SHUTDOWN`
   only when nothing else has ended the take.

Minor, also fixed: an empty take no longer writes a `recording.json` or leaves its folder behind.

**Accepted as-is, documented rather than changed:**

- `PauseReason.NO_IMAGE` is unreachable from this shell, which ends the take when the bound viewer's
  image disappears. It stays part of the gate's contract; the javadoc now says so.
- **A take stays bound to the viewer it started on.** In a multi-viewer grid the author could
  demonstrate in viewer B while viewer A is recorded. This is intended ("one take, one slide, one
  viewer") and is the same binding blinded recording uses — but it is a real foot-gun worth a GUI
  check.
- **An open pause is not flushed if the JVM is killed mid-pause.** Bounded loss: the pause interval,
  never the path.

**Recording-origin follow-up (October 14):** quiz drafts now carry optional `cotProvenance`
metadata, which the existing quiz editor retains when loading, reviewing, and saving the same pack.
Both the stop-to-draft and file-import paths carry recording-schema evidence. Export writes
`provenance.json` (`pathology-cot-case-provenance/1`) alongside the unchanged conversation/crops.
`recordingOrigin` is `authored_demonstration` for `atlas-cot-authored/1`,
`blinded_observation` for the verified `atlas-focus-contribution/5` writer contract, and `unknown`
for missing, unsupported, or conflicting origin evidence. Legacy `/3` and `/4` recordings and old
quizzes are not retroactively classified. This is evidence from recording metadata, not a digital
signature. The sidecar includes `anonymized=false`; export consent does not establish origin or
anonymization. Raw recording files/slide URIs are not copied into the provenance sidecar.

Manual QuPath validation still required (automated suites intentionally not run):
- Record and stop an authored take; create a draft, open it in the author window, edit rationale,
  reorder stops, save, close, and reopen. Check the provenance block survives unchanged.
- Import that same authored `recording.json` through the legacy file-picker action; repeat review
  and export. Both exports must say `authored_demonstration`, with protocol/end/cap metadata retained.
- Import a real `/5` blinded fragment and export its reviewed draft; expect `blinded_observation`.
- Open/export an old quiz with no provenance and draft from metadata-free or `/3`/`/4` fragments;
  expect `unknown`. Conflicting mode/intent metadata must also remain `unknown`.
- Compare conversation turn/image references and crop ordering with the previous export. Confirm
  README still warns ANONİMLEŞTİRİLMEMİŞ and provenance contains no raw slide URL/local path.
- New/ordinary quizzes must still open and save without a provenance field; “Yeni” starts a fresh
  pack and must not inherit the previous recording's origin.

## 11. Testing

56 new tests (268 total). Pure layers are covered directly: the gate's precedence/cap/stall
boundaries, the pause ledger's full tick sequence (open, stay closed, reopen, reason change, stall,
stop-while-paused) plus the tiling invariant that pause and credit never overlap and never lose a
millisecond, the firewall's full transition table (including "every refusal carries a message"), the
model's point layout/caps/pauses/defensive copies, IO round-trip + schema refusal + atomic-write
leftovers + the provenance mirror, and the dwell cap's boundaries with the measured 30-second-gap
scenario.

Found while testing: `slugify()` lowercased before folding Turkish letters, but
`'İ'.toLowerCase(ROOT)` is **two** code points (`i` + U+0307 COMBINING DOT ABOVE), so the orphaned
mark became a separator — `"İşlenmiş örnek"` slugged to `i-slenmis-ornek`. Fixed by folding first
and stripping combining marks (`Normalizer` NFD + `\p{Mn}`).

**Not covered by tests, and needing a GUI pass** — everything in the FX shell, since JavaFX cannot
be driven headlessly here:

- record → navigate → stop → draft → author window, end to end;
- the `CheckMenuItem` tick in all four refusal paths and after a programmatic stop, plus the
  accelerator;
- the veto's un-tick of the blinded menu item, and `flagCurrentProjectAsResearch`'s refusal;
- the HUD appearing, updating, and leaving no residue after a stop or a slide change;
- mouse-filter attach/detach across start → slide change → stop → restart on a different viewer;
- a slide change, a project change, and a force-quit mid-take (checkpoint and hook);
- multi-viewer behaviour (§10, "accepted as-is").
