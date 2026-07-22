# Research menu + any-slide blinded research — design & plan

- **Date:** 2026-07-22
- **Repo:** `patolojiatlasi-QuPath` (`qupath-extension-atlas`)
- **Branch:** `feat/research-menu`
- **Status:** Approved (user); contained menu-reorg + one action. Lean combined spec/plan.

## Goal

1. **Menu reorg** — surface the atlas-**independent** tools as general research tools: a new
   top-level **"Araştırma"** menu (sibling of "Patoloji Atlası" under Extensions) holding the focus
   heatmap / blinded recording / scanpath and rotation (the current "Görünüm" submenu, dissolved out
   of the atlas menu). Atlas-specific features stay under "Patoloji Atlası".
2. **Any-slide research** — let a researcher flag **their own** (non-atlas) project for blinded
   recording, so local/server/other-web SVS work first-class for double-blind spatial-temporal-
   directional studies. (Recording itself is already slide-source-agnostic — verified no atlas
   dependency in `FocusHeatmap`; `slideKey` is `sha256:`-anonymized for non-http.)

## Menu mapping (in `AtlasExtension.installExtension`)

- **"Patoloji Atlası"** (atlas-only) = `browseItem` (Slaytlara gözat…), `coverageItem` (Katalog
  kapsamı ve QC…), separator, `compareMenu` (Karşılaştır), `referenceMenu` (Referans), `relatedItem`
  (İlgili içerik…), `citationMenu` (Atıf), `quizMenu` (Sınav / Quiz). **Remove `viewMenu`.**
- **New top-level "Araştırma"** = `focusHeatmap.buildMenu()` (the Odak ısı haritası / blinded-record
  submenu), `rotationItem` (Görüntüyü döndür…), a `SeparatorMenuItem`, `flagProjectItem`
  ("Mevcut projeyi araştırma projesi yap (kör kayıt)…").
- Register both: `qupath.getMenu("Extensions", true).getItems().addAll(atlas, research);`
  (Both built/added inside the same `installExtension` FX-thread block.)

## New action — flag the current project

`flagCurrentProjectAsResearch(qupath)` (on the FX thread, from `flagProjectItem`):
- `Project<?> p = qupath.getProject();` — if null → info Alert "Önce bir proje açın veya oluşturun." return.
- `File dir = BlindedResearch.projectDir(p);` — if null → info Alert "Proje klasörünü bulmak için projeyi kaydedin." return.
- If `BlindedResearch.isBlindedProject(p)` already → info "Bu zaten bir araştırma projesi." return.
- Confirm (the consent, since the researcher is opting in now): a CONFIRMATION Alert —
  "Bu proje bir araştırma projesi yapılacak: bu oturumda ve her açılışta **anonim, gizli (kör) odak
  kaydı** tutulur — ekranda ısı haritası gösterilmez. Devam edilsin mi?" (`.initOwner(qupath.getStage())`).
- On OK: `BlindedResearch.writeFlag(dir, true); BlindedResearch.markConsented(p); focusHeatmap.startBlinded();`
  then info "Proje işaretlendi; kör kayıt başladı ve her açılışta sürecek." (Starting now + marking
  consented means this session records too, and the project-open hook auto-starts on future opens
  without re-asking — consistent with the atlas builder flow, but immediate since the project is
  already open.)
- On Cancel: nothing (flag not written; recording not started).

Reuses (all public, from the blinded feature): `BlindedResearch.projectDir(Project)`,
`isBlindedProject(Project)`, `writeFlag(File,boolean)`, `markConsented(Project)`; the retained
`AtlasExtension.focusHeatmap` instance (`startBlinded()`).

## Constraints / invariants

- Java 21; QuPath 0.6; no new deps. Turkish labels; menu-path strings English.
- Data-only + anonymization unchanged (this only relocates menus + writes the existing sidecar +
  starts the existing recorder). No new recording/write path.
- One retained `focusHeatmap` instance still drives everything (menu + project hook + this action) —
  do NOT create a second instance.
- The project-open hook (`onProjectChanged`) is unchanged; it already auto-starts on a flagged
  project's next open. Flagging an already-open project won't re-fire the hook, which is why this
  action starts recording directly + marks consented.

## Testing

- No new automated unit test (menu wiring + one FX action, per repo convention); existing build +
  tests stay green.
- Manual (running QuPath): two top-level menus appear correctly split; the atlas menu no longer
  shows Görünüm; blinded recording / focus / rotation live under Araştırma and work on a **non-atlas**
  slide (open a local SVS, run Kör kayıt, confirm a schema/3 fragment with a `path` is written to
  `<project>/atlas-focus/`); "Mevcut projeyi araştırma projesi yap…" on a local-slide project writes
  the sidecar, starts recording, and auto-starts (with consent already given) on reopen.

## Task (single, SDD + review)

- [ ] Reorg the menu in `AtlasExtension.installExtension` per the mapping (remove `viewMenu` from
      atlas; new top-level `research` Menu with focus submenu + rotation + separator + flag item;
      register both under Extensions).
- [ ] Add `flagCurrentProjectAsResearch(qupath)` (+ the `flagProjectItem`), reusing `BlindedResearch`
      + the retained `focusHeatmap`.
- [ ] Update README (Araştırma menu; blinded recording works on any slide; flag-your-own-project for
      double-blind studies on local/server/web slides).
- [ ] `./gradlew --offline build` green. Commit `feat(menu): top-level Araştırma menu + flag-any-project blinded research`.