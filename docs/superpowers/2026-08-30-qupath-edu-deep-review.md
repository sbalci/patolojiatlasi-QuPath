# QuPath Edu / OpenMicroanatomy — exhaustive second-pass review (2026-08-30)

**Scope.** Follow-up to `2026-08-23-qupath-edu-feature-review.md` after its five candidates (C1–C5)
shipped (merged `2b7ca10`, polished `7a2a269`). Question: *what is left to learn from QuPath Edu /
OpenMicroanatomy and the platforms it positions itself against, given our extension as it exists
today?* Every candidate below survived three independent refuters (absence-in-our-code /
provenance-in-theirs / serverless-feasibility); refuter corrections are folded in.

**Sources (all re-read this pass).** Paper: Yli-Hallila A, Bankhead P, Arends MJ, Lehenkari P,
Palosaari S. *QuPath Edu and OpenMicroanatomy: Open-source virtual microscopy tools for medical
education.* J Anat 2025;246(5):846–856, doi:[10.1111/joa.14172](https://doi.org/10.1111/joa.14172)
(CC BY; 44 items extracted). Extension source `github.com/openmicroanatomy/qupath-edu-extension`
(42 behaviours, read class-by-class). Docs handbook + `server` + `web` + `hosting` repos
(51 items; note: their default branch is `master`, not `main`). Our own codebase (17-feature
inventory, cross-checked inline — see "Inline verification"). Competitor platforms named by the
paper — HistoViewer, Leeds Virtual Pathology, Michigan Histology/SecondLook, PathScribe — plus
Cytomine, PathPresenter, Digital Slide Archive, Virtual Slidebox (28 items, marketing/docs depth only).

**Method.** 61-agent workflow: 5-angle sweep → 3-lens gap panel (pedagogy / engineering-fit /
research) → merge to 17 → 3 refuters per candidate (drop on ≥2 refutations) → completeness critic.
Result: 16 survive, 1 refuted (D7). **Clean-room stands:** the extension/server repos carry no
LICENSE; everything here is behavioural description, nothing is ported.

## Corrections this pass established (fix before reusing any earlier text)

1. **Our web player already grades MCQ** (`tour-player.html` `markMcqOptions()` — green/red).
   `web-tour-player/README.md`'s "No auto-grading … for any stop type" bullet was wrong (corrected
   in this commit). The **desktop** runner is the one with no explicit verdict: it shows
   "Doğru cevap" + "Sizin cevabınız" but never says right/wrong (→ D1).
2. **Masked image names are core QuPath**, not a QuPath Edu invention: `PathPrefs.maskImageNamesProperty()`
   (javap-verified, 0.6.0) = Edit → Preferences → "Mask image names". D17 was re-scoped accordingly.
3. **One "absence" refuter searched the wrong repo** (the workshop's `_includes/quiz.html`) and
   declared `QuizAuthorWindow` non-existent. It exists; its `"Yeni"` Javadoc literally says
   *"Does not ask about unsaved changes"* and `setOnHidden` has no guard — D4 stands.
4. Survey-derived claims are out of **n = 75 respondents** (200-student class, 37.5 % response);
   the "seven surveyed features" count could not be confirmed (six are verifiable) — say "among the
   surveyed features".

## Inline verification (controller, against HEAD `4dfaef5`)

- `QuizAuthorWindow`: no dirty flag / close guard (D4 ✔ absent).
- `SimpleViewMode`: menu visibility only, no active-tool reset (D5 ✔ absent).
- `QuizRunnerWindow` MCQ reveal: no verdict line (D1-desktop ✔ absent); web `markMcqOptions` ✔ present.
- `PathPrefs.maskImageNamesProperty()` ✔ public API (D17b feasible with zero new UI).
- Multi-session decision aggregation: the Python toolkit's `analyze.py` already emits
  `decisions.csv` (+ answer-key grading) across sessions — D10 is *partial* (analysis exists,
  no in-app concordance/kappa view).

## Verified candidates (D-numbers keep the workflow's ids; D7 refuted)

### Quick wins (S)
- **D1 — Explicit MCQ verdict on the desktop reveal.** Prepend "Doğru!" / "Yanlış — doğru cevap: …"
  to the existing reveal text (web already does this). Critic addition: make it **colour-blind
  safe** — text + icon, never colour alone (applies to D12/D16 too). Evidence: MCQ usefulness
  39.5 ± 17.2 (paper §3.1).
- **D3 — Attempt before reveal (MCQ/FREETEXT).** "Göster" disabled until an option is picked / text
  typed, with an explicit "Bilmiyorum, göster" escape hatch. Rationale: the 86.7 % (n = 65/75)
  preference for concealed-then-revealed answers is a retrieval-practice effect that a skip-to-reveal
  path bypasses. ANNOTATION/NAVIGATION already need a drawing/viewport.
- **D4 — Dirty-state guard in `QuizAuthorWindow`.** One `dirty` flag set on any edit, cleared on
  Kaydet; a shared three-way confirm (Kaydet / Kaydetmeden devam / İptal) on Yeni, Aç…, and window
  close. Design lesson from OpenMicroanatomy's own admin docs, which admit "QuPath Edu gives no
  warning if forgetting to save" (docs `administrative.adoc` § Permissions).
- **D5 — Basit görünüm resets the active tool to MOVE.** QuPath Edu's STUDYING mode forces MOVE so a
  learner can never browse with a drawing tool armed (`util/UserModeManager`). Snapshot
  `getToolManager().getSelectedTool()` beside the menu-visibility map, force `PathTools.MOVE` on
  enable, restore on disable. (The BenchReference/CaseCompare half proposed by the panel was
  dropped — it conflicts with those features' purpose.)
- **D11 — Resume where you left off, no accounts.** Last stop index keyed by a hash of the pack's
  JSON content (survives renames), written on Sonraki/Önceki/close; "Kaldığınız yerden devam:
  Durak 7/12?" on load. Local analogue of their personal-copy resume (paper §1.1.3, paraphrased —
  not a quotation).
- **D12 — End-of-run summary.** Non-blocking Alert at the last stop / early close: stops viewed,
  MCQ correct (needs D1), ANNOTATION/NAVIGATION hits above IoU 0.3. In-memory only.
- **D13 — Session-only shuffle toggle** (questions + MCQ option order, `correctIndex` remapped;
  never mutates the pack; greyed in tour mode). Desirable-difficulty principle for repeated
  self-review packs.
- **D14 — Tags on `AtlasCase`** (optional `String[] tags`, same forward-compatible pattern as
  `descriptionTR/EN`) + search match + a tag-chip filter row in the browser. PathScribe evidence:
  free-text description + tags used to filter large collections (pathscribe.ru; MSU since 2022).
- **D17b — Blinded research: use QuPath's own "Mask image names".** When a project is flagged
  via `atlas-research.json`, offer/auto-enable `PathPrefs.maskImageNamesProperty()` for the session
  (restore after) so a rater cannot read the real case identity on screen even though the exported
  recording is already anonymized. Zero new UI.
- **D2 — Anki export** ("Anki destesi olarak dışa aktar…" in the author): MCQ/FREETEXT stops → an
  Anki-importable tab-separated file (front = prompt [+ options], back = answer + explanation);
  slide-bound stops skipped with a note. Evidence: Michigan SecondLook's histology Anki deck
  (sites.google.com/umich.edu/secondlook-anki/home, CC BY-NC-SA 4.0).

### Medium (M)
- **D8 — Descriptions actually populated: local overrides overlay.** A portable
  `catalog-descriptions-overrides.json` (keyed by DZI URL / case id) merged over the bundled
  `catalog.json` + live `list.yaml` at load, plus a minimal "Vaka açıklaması düzenle…" dialog —
  so the (currently dormant) description pane lights up without waiting for upstream fields.
  Evidence: slide descriptions 43.5 ± 12.5, second-highest among surveyed features.
- **D9 — Project-level intro text.** Optional "Bu projeye giriş / bağlam" in `ProjectBuilderDialog`,
  saved as a `project-info.json` sidecar (same pattern as `atlas-research.json`), shown once as a
  dismissible "Proje hakkında" panel on open. Their lesson-information pane is *the* first-contact
  scaffold (paper §1.1.2, Fig. 4; "open by default" is stated by the paper only).
- **D15 — Annotation-embedded Q&A on any slide/project.** Store an explicitly-typed metadata block
  on the selected `PathObject` (`atlas.qtype`/prompt/options/answer — explicit type, *not* their
  "sniff the format from a leading `[{`" approach) via a context-menu item; a pane reuses the
  browse-mode reveal UI. ourStatus = partial (browse UI exists; storage/local-project support absent).
- **D17a — Exam-integrity pseudonyms.** Optional per-pack `maskCaseTitles` (author-set): the runner
  and browse windows show "Vaka 1, Vaka 2…" (deterministic per pack) while opening the real slide.
- **D10 — In-app multi-rater decision concordance.** "Araştırma → Vaka konsensusu…": point at a
  folder of anonymized per-slide fragment files (the `decision` objects live in
  `focus-blinded__*.json`, **not** `atlas-research.json`), group by slide key, show each rater's
  diagnosis + confidence, compute exact-match % and Fleiss'/Cohen's κ once coded. Add an optional
  `studyId` to `atlas-research.json` so sessions group by study, not folder name. Diagnosis-level
  sibling of backlog #12 Agreement Map (ROI-level). Leeds EQA evidence: hundreds of pathologists
  review the same case for consensus/CPD.

### Large (L) / programme-level
- **D6 + D16 — Learner session record → results export → offline cohort aggregation.**
  Phase 0 (D6, S): opt-in, consent-gated (reuse the `BlindedResearch` Alert pattern; off by
  default) anonymized per-session JSON — stop enter/exit times, reveal presses, completion vs
  abandonment. Phase 1 (M): "Sınavı bitir / Sonuçları kaydet" writes a portable JSON+CSV of
  per-question results (all 5 types) with an anonymous session id — learner-saved, never uploaded.
  Phase 2 (M–L): a cohort aggregator, ideally as a module of the existing `analysis/python`
  toolkit (parity-tested like `blinded_focus`). Ships what the paper only prototyped ("working
  proof-of-concept … yet to be implemented in practice", §4.2). PathPresenter's "assessments +
  progress tracking" is two separate marketing bullets, not one verified feature. Critic pairing:
  a lightweight **completion attestation export** (PDF/MD "certificate") is a natural, cheap add-on
  for a practising-pathologist audience.

### Cross-cutting requirement (critic)
- **One schema-version convention.** D10/D14/D15/D16/D17 each add fields to overlapping files
  (`AtlasCase`, `AtlasQuiz`, `atlas-research.json`, annotation metadata). Adopt the existing
  `formatVersion` / `schema: "…/N"` pattern uniformly (as `AtlasQuizIO` and the focus fragments
  already do) before any two of them ship.
- **Design lesson — never expose listings unauthenticated.** The live OpenMicroanatomy demo host
  returns full `/api/v0/organizations` and `/workspaces` data with no auth. Our aggregation
  features (D10/D16) stay local-files-only; if a share channel is ever added, it is opt-in and
  authenticated.

## Refuted
- **D7 — time-to-decision / dwell report.** Already shipped: `FocusHeatmap.Decision`
  (`decisionMs`, `promptShownMs` relative to slide open) inside the blinded fragment, and
  `analysis/python … decisions.csv` / `metrics.csv` (decision latency + response latency columns).

## What this pass still did NOT cover (critic)
Paper supplementary / the survey instrument itself; QuPath Edu's admin-side classes
(Organization/Permission/Slide/User managers, `EduAPI` skimmed only); the server's Swagger spec
(reachable, unread); the `qupath-edu-ckeditor` repo; competitors at source depth (docs/marketing
only). None of the surviving candidates depends on those; D16's PathPresenter evidence is the
weakest link and is flagged above.

## Suggested order (value ÷ effort)
D1 → D3 → D4 → D5 → D17b → D11 → D12 → D13 → D14 → D2 → D8 → D9 → D15 → D17a → D10 → D6/D16.
Consult the user on ordering before building (backlog convention).
