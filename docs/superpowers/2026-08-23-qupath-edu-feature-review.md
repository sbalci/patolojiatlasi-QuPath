# QuPath Edu / OpenMicroanatomy — feature review & inspiration map (2026-08-23)

**Reviewed sources (all three cross-checked, 2026-08-23):**
1. **Paper:** Yli-Hallila A, Bankhead P, Arends MJ, Lehenkari P, Palosaari S. *QuPath Edu and
   OpenMicroanatomy: Open-source virtual microscopy tools for medical education.* **Journal of
   Anatomy** 2025;246(5):846–856. doi:[10.1111/joa.14172](https://doi.org/10.1111/joa.14172)
   (online 2024-11-18; CC BY). PMID 39555994.
2. **Repos:** [github.com/openmicroanatomy](https://github.com/openmicroanatomy) —
   `qupath-edu-extension` (Java), `server` (Java + tiler), `web` (React/TS, Zlib),
   `docs` (AsciiDoc handbook at openmicroanatomy.github.io), `qupath-edu-ckeditor`, `hosting`.
3. **Shipped JAR:** `qupath-edu-extension-1.0.3.jar` (manifest `QuPath-Version: 0.6.0`;
   69 own classes + ~2,700 shaded OAuth/MSAL classes; full class + UI-string inventory taken).

## What QuPath Edu is (verified)

A server-backed **LMS-style teaching layer on QuPath**: an extension + a self-hosted
**OpenMicroanatomy Server** (stores lessons, tiles uploaded slides server-side — up to ~30 min/slide,
Docker deploy) + a read-only **browser/mobile web client**. Content hierarchy
*organization → workspace → course → lesson (a QuPath project) → slides*. Auth = guest /
credentials / Microsoft SSO (the msal4j+Nimbus OIDC stack is >90 % of the JAR's bulk). Three
user modes (Study / Analysis / Editing) gate how much of QuPath's UI is visible. Teaching
primitives: **per-annotation hidden answers and multiple-choice quizzes** (`SimpleAnnotationPane`:
"Show answer" / "Show quiz" / "Right answer!"), **slide tours** (`SlideTour`: frames = viewer
position + annotations + text, Previous/Next), **rich-text lesson info** (CKEditor in a WebView),
collaborative sync with personal copies (12-month retention) and lesson backups.

**Evaluation reported in the paper** (Oulu, 200-student class, n=75, 37.5 % response): SUS **84.8**
(top 4 %); feature usefulness (−50…+50): **hidden-answer questions 47.5 ± 5.3 (best)**, slide
descriptions 43.5, MCQ 39.5, descriptive annotations 39.5, slide tours 20 (underexposed — used in
one final lesson only), **answer-less open questions −12.7 (only negative; students called them
"frustrating")**. 86.7 % preferred concealed-then-revealed answers; **76 % preferred the web client**
over the desktop app. Pilots: Stellenbosch (South Africa), University of Namibia.

## Project health & licensing (verified 2026-08-23 — matters for reuse)

- **Version lag:** v1.0.3 (released 2025-08-09) supports **QuPath 0.6.0 only**; no 0.7 support.
  Desktop extension + server untouched since 2025-08; web client + docs active (pushed 2026-08).
  Tiny community (6★ extension). Not in any extension catalog (manual JAR install).
- **License gap:** the paper states GPL-3.0, but the **extension and server repos contain NO
  LICENSE file** (GitHub API `license: null`); only the `web` repo is licensed (Zlib).
  → **Treat extension/server code as all-rights-reserved: clean-room inspiration only, no
  porting** (same discipline as the Pathology-CoT engine). The paper's ideas + published survey
  numbers are citable facts.

## Feature-by-feature vs `qupath-extension-atlas`

| Area | QuPath Edu | Atlas extension | Verdict |
|---|---|---|---|
| Slide delivery | Self-hosted server, server-side tiling, Docker + IT staff (**their stated #1 adoption barrier**) | Static public DZI (`images.patolojiatlasi.com`), zero server ops | Ours deliberately avoids their main barrier — keep serverless |
| Auth / roles | Guest, credentials, Microsoft SSO; 5-role admin console | None (public read-only atlas) | Out of scope for us (no writable backend) |
| Content structure | Org→workspace→course→lesson | Catalog categories→case→stain + curated project builder | Parity by different means |
| Hidden-answer annotations | Per-annotation "Show answer" while freely browsing — **their top-rated feature** | Only inside the sequential quiz/tour flow | **GAP → candidate C2** |
| MCQ | Per-annotation quiz pane | MCQ stops in portable quiz packs | Parity (different anchoring) |
| Slide tours | Frames (viewport+annotations+text), Prev/Next | Guided Tour: NARRATION+question stops, per-stop highlight ROI, forward-only mode, intro | **Ours richer** — cite theirs as prior art |
| Rich lesson info | CKEditor WebView, embedded media | Tour intro (title+description) only | Partial gap → candidate C3 |
| UI simplification | Study/Analysis/Editing modes (reflection into QuPath internals) | None | Candidate C4 (low priority, fragile technique) |
| Exam auto-grading | **Proof-of-concept only** (script-checks student annotations vs teacher ground truth; "yet to be implemented in practice") | We already ship reference geometries (ANNOTATION/NAVIGATION stops) + IoU machinery (Pathology-CoT hit criterion) | **Leapfrog opportunity → candidate C1** |
| Sync / personal copies / backups | Server-side | Portable local JSON packs (quiz/tour/collections) by design | Keep ours |
| Web/mobile client | React viewer, tours in browser (76 % preferred) | patolojiatlasi.com website (separate) | Candidate C5 (cross-repo) |
| Research instrumentation | None | Focus heatmap, blinded nav recording, decisions, Pathology-CoT engine | Our unique territory |

## Inspiration candidates (unranked — user decides ordering; sizes S/M/L)

- **C1 — Annotation auto-scoring on reveal (S).** In the quiz runner's ANNOTATION (and NAVIGATION)
  reveal, score the learner's drawn annotation against the stored reference geometry — IoU %,
  plus the Pathology-CoT hit criterion (IoU > 0.3 or containment) as a pass/miss line. Pure
  measurement (no clinical language), reuses existing reveal + geometry code. Implements what the
  paper leaves as future work ("basic examination tools… automated grading"; working
  proof-of-concept only) — evidence: paper §Discussion.
- **C2 — Hidden-answer browse mode (M).** Load a quiz pack as **clickable pins/regions on a freely
  browsed slide** (non-sequential): click an annotation → prompt + "Cevabı göster" reveal.
  Their single best-rated feature (47.5 ± 5.3; 86.7 % prefer concealed-then-reveal). Reuses
  QuizQuestion/reveal; new thin overlay+pane interaction. Complements backlog #10
  (Cover-the-Diagnosis) and #11 (Self-Assessment).
- **C3 — Per-case description pane (S).** Surface a rich (Markdown) per-case/per-slide description
  in the atlas browser and/or on slide open — their "slide descriptions" scored 43.5 (2nd best).
  Feed from catalog metadata; no CKEditor/WebView heaviness.
- **C4 — Basit görünüm / study mode (M, deprioritized).** UI simplification for learners. Their
  implementation reflects into QuPath's private UI internals (fragile across versions); our
  audience (pathologists learning analysis) usually *wants* the analysis tools visible. Park it.
- **C5 — Web tour player (M–L, cross-repo).** Our tour packs are portable JSON; a small JS player
  on patolojiatlasi.com could play them in-browser (their web client — Zlib-licensed — is a
  reference implementation; 76 % of their students preferred web). Belongs to the website repo,
  not this extension.

**No-build design validations (already aligned):**
- Our FREETEXT stops **require** a model answer (`AtlasQuizIO.validate`) — directly vindicated by
  their −12.7 score for answer-less open questions.
- Serverless/portable-files architecture avoids their stated #1 adoption barrier (server ops).
- Public read-only atlas ≈ their "public hosts / guest mode" model.
- Their tours scored low **because under-deployed** ("only in the final lesson") — lesson for us:
  author enough tour content before judging the feature.

## References to carry when any candidate ships

Cite in the shipping docs/commit: Yli-Hallila et al., *J Anat* 2025;246(5):846–856,
doi:10.1111/joa.14172 (CC BY) + github.com/openmicroanatomy. Clean-room note required for anything
inspired by extension/server behavior (no LICENSE in those repos).

Workshop-side references were updated in `qupath-workshop` (this same review):
`references.bib` (`@article{ylihallila2025}`), enriched ecosystem row in
`ekler/literatur.qmd` § "Eğitim ve segmentasyon kalitesi", year fix in `kaynaklar.qmd` § literature
table.
