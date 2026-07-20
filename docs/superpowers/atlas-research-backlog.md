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
| 6 | **Guided Teaching Tour** | M | queued |

> **Out-of-band (user-requested 2026-07-20, ahead of #6):** blinded temporal focus recording —
> record viewed areas + dwell time silently (no in-app heatmap) for unbiased research, with a
> project-default "hidden tracking on" mode. Requirements captured in
> `blinded-focus-recording-notes.md`; spec/build next.
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
