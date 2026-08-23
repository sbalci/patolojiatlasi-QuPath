# Atlas — research/researcher feature backlog (ranked)

> Origin: a 6-lens ideation workflow (2026-07-18) over "what a pathologist-*researcher*
> needs from the atlas that the workshop 'Atölye' extension does NOT already give them."
> Ranked by value ÷ effort. Sizes: **S** small / **M** medium / **L** large.
> The atlas is public, **read-only** (no writable backend) — every feature ships as
> local-file + clipboard output, never a server write.
>
> **Workflow per feature:** brainstorm → design spec → **user approves/adjusts** → plan →
> subagent-driven build → review → merge. The user asked to be consulted on each feature's
> plan before building ("ask me to adjust plan for features"). The user drives push/release.

## Status

| # | Feature | Size | Status |
|---|---------|------|--------|
| 1 | **Bench-Side Atlas Reference** | S | ✅ shipped (merged to master) |
| 2 | **Research Provenance & Citation Suite** | M | ✅ shipped (merged f65bb7e, unpushed) |
| 3 | **Catalogue Coverage & QC Dashboard** | M | ✅ shipped (merged to master, unpushed) |
| 4 | **Related-Content Navigator** | S | ✅ shipped (merged to master, unpushed) |
| 5 | **Portable Collections / Bookmarks-History** | S | ✅ shipped (merged to master, unpushed) |
| 6 | **Guided Teaching Tour** | M | ✅ shipped (merged to master 3bcf68c, unpushed) |

> **Out-of-band (user-requested 2026-07-20, ahead of #6): ✅ SHIPPED** — blinded temporal focus
> recording: records viewed areas + dwell time silently (no in-app heatmap, data-only) for unbiased
> research, with a project-default "hidden tracking on" mode + one-time consent. Merged to master
> (unpushed). Spec `2026-07-21-blinded-focus-recording-design.md`. Data-only guarantee + slideKey
> anonymization verified airtight by review.
| 7 | **Manual Stain-Alignment Curtain/Overlay** | M | queued |
| 8 | **Multi-Panel Figure Export** | S | queued |
| 9 | **Shareable View Links** | M | queued (VERIFY: 0.6 viewer settable center/downsample) |
| 10 | **Unknown-Case / Cover-the-Diagnosis Mode** | M | queued |
| 11 | **Self-Assessment log** | M | queued |
| 12 | **Agreement Map** | L | queued |

## Descriptions

1. **Bench-Side Atlas Reference** (S) — open any atlas case as a 2nd viewer beside the user's OWN
   slide, one-click **magnification-match** via each side's mpp. Reuses Case-Compare's `ViewerManager`.
2. **Research Provenance & Citation Suite** (M) — cite-a-slide (BibTeX/RIS/text; image+extension+QuPath),
   cohort manifest (CSV+MD+catalogue commit-SHA), methods paragraph + slide table, mpp provenance,
   figure-region citation card. Honors both upstream CITATION.cff files.
3. **Catalogue Coverage & QC Dashboard** (M) — category×stain matrix (counts / published% / mpp-known%)
   + link-integrity HEAD-check; drill-down seeds the project builder. Classification is a keyword heuristic.
4. **Related-Content Navigator** (S) — docked filmstrip of the case's other stains + cross-case siblings;
   one click swaps/opens. (Absorbs the "sibling stains" idea; not a standalone stain-align feature.)
5. **Portable Collections / Bookmarks-History** (S) — save/load/share small case-pick JSON files
   (stable key), stars, resume-where-you-left-off.
6. **Guided Teaching Tour** (M) — author ordered stops (slide + viewport + caption), learner steps
   Next/Prev. Reuses `QuizQuestion.Viewport` + `AtlasQuizIO` Gson I/O.
7. **Manual Stain-Alignment Curtain/Overlay** (M) — offset/rotate/scale sliders (rotation-control pattern)
   composite a 2nd stain over H&E via `TransformedServerBuilder` + `getCustomOverlayLayers()`.
8. **Multi-Panel Figure Export** (S) — flatten the synced compare view → labeled composite PNG/SVG
   (scale bar + citation) via `Node.snapshot()`; optional OMERO.figure JSON sidecar.
9. **Shareable View Links** (M) — copy/paste viewport permalink (case+stain+region+zoom+rotation),
   IIIF Content-State-style. **VERIFY** QuPath 0.6 viewer exposes settable center/downsample.
10. **Unknown-Case / Cover-the-Diagnosis Mode** (M) — open a slide blind, guess, reveal verified dx
    from catalogue metadata. GOTCHA: `DziImageServer.deriveName()` derives the tab name from the DZI URL.
11. **Self-Assessment log** (M) — rate case → anonymized file; local review log feeds a progress view.
12. **Agreement Map** (L) — draw/label ROI → anonymized GeoJSON (focus-contribution pattern);
    accumulate votes → per-pixel consensus overlay (reuses `FocusMap` grid). No writable backend.

> **Also shipped out-of-band (2026-07-26): Pathology-CoT data engine** — clean-room AI Session
> Recorder (Wang et al., *Nat Biomed Eng* 2026): recorded navigation → inspect/peek discretizer →
> `behaviors.json` → reviewable tour draft → case-folder export. Merged to master `9914785`,
> unpushed. See `docs/superpowers/plans/2026-07-26-pathology-cot-data-engine.md`.

## Candidates from the QuPath Edu review (2026-08-23 — UNRANKED, user decides ordering)

Source: `docs/superpowers/2026-08-23-qupath-edu-feature-review.md` (Yli-Hallila et al., *J Anat*
2025;246(5):846–856, doi:10.1111/joa.14172 — survey-backed evidence per candidate; **no LICENSE on
their extension/server repos → clean-room only**).

- **C1 Annotation auto-scoring on reveal** (S) — IoU % + hit/miss (IoU > 0.3 or containment, the
  Pathology-CoT criterion) of the learner's drawn annotation vs the stop's reference geometry, in
  the quiz runner's ANNOTATION/NAVIGATION reveal. Leapfrogs QuPath Edu's unshipped exam
  proof-of-concept; measurement-only output.
- **C2 Hidden-answer browse mode** (M) — quiz pack rendered as clickable regions on a freely
  browsed slide; click → prompt + "Cevabı göster". Their best-rated feature (47.5/50; 86.7 %
  prefer concealed-then-reveal). Complements #10/#11.
- **C3 Per-case description pane** (S) — Markdown case/slide description in the atlas browser /
  on open (their 2nd-best feature, 43.5/50); catalog-metadata-fed, no WebView.
- **C4 Basit görünüm / study mode** (M, deprioritized) — UI simplification; their approach
  reflects into QuPath private internals (fragile), and our audience usually wants analysis tools.
- **C5 Web tour player** (M–L, cross-repo) — play portable tour JSON on patolojiatlasi.com
  (their Zlib-licensed React viewer is a reference impl; 76 % preferred web).

## Candidates from the CHCAPI review (2026-08-23 — UNRANKED, user decides ordering)

Source: `docs/superpowers/2026-08-23-chcapi-feature-review.md` (Google's archived
`qupath-chcapi-extension`, GPL-3.0-or-later → clean-room only; QuPath 0.3.0-era, dead since
2021 — reviewed for its remote-slide-streaming patterns, not for use).

- **G1 Persist parsed DZI descriptor per case** (S) — cache width/height/tileSize/overlap/
  format/mpp in curated-project entries so slides open without the initial `.dzi` fetch; with a
  refresh path (their `.mtd` cache had none and went silently stale).
- **G2 Debug tile overlay** (S) — opt-in system property paints tile boundaries + level/col/row
  on composited tiles in `DziImageServer`; cheap grid-bug diagnostic.
- **G3 Deliberate tile-miss contract** (M) — today any non-200 → blank white, so a transient
  network blip can be cached as a permanently white tile; split transient (re-query) from
  definitive 404 (sparse-white). VERIFY QuPath 0.6 `AbstractTileableImageServer` null-vs-throw
  caching semantics first.

## Resource watchlist — webcam gaze channel (2026-08-23, user-requested; UNRANKED)

Source: `brainstorming/webcam-eye-tracking-tools.md` (licenses + integration feasibility verified
2026-08-23). Motivation: viewport logs show what was on screen, not where the eye was; mouse↔gaze
coupling is only moderate (Raghunath 2012). Webcam gaze = zero-hardware middle tier between our
viewport logging and Tobii-grade trackers, feeding the Pathology-CoT engine / blinded focus
recording with a true gaze channel. Needs its own consent opt-in (webcam ≠ blinded viewport).

- **W1 EyeTrax gaze sidecar for QuPath** (M) — MIT Python webcam gaze estimator
  (github.com/ck-zhang/eyetrax): sidecar venv streams primary-screen (x,y) → JVM maps via the
  existing mouse pipeline (`componentPointToImagePoint`) → gaze channel in the focus fragment
  (schema `/5` → `/6`). Modal fullscreen calibration = explicit pre-session step. **No published
  accuracy — in-house validation is a prerequisite for any scientific claim.**
- **W2 WebGazer.js for the web atlas** (M, cross-repo, **GPL-3.0-or-later**) — browser-native
  self-calibrating gaze (github.com/brownhci/WebGazer, Papoutsaki et al. IJCAI-16, ~4.17°/
  100–250 px); pairs with C5 web tour player via OpenSeadragon coordinate APIs. Load as an
  unmodified external script only — never bundle into MIT code. Upstream maintenance officially
  ended 2026-02-24 (v3.5.3 final). NOT viable inside QuPath (JavaFX WebView lacks getUserMedia).
