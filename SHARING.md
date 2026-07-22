# Sharing a blinded-research project

How to hand out a **QuPath blinded-research project** (silent, anonymized focus recording) to
participating researchers and get their data back — for spatial · temporal · directional viewing
research. Two parts: a **researcher quick guide** (give this to whoever opens the project) and a
**coordinator guide** (for whoever prepares and sends it).

> **Short answer to "is QuPath enough, or do they need the extension?"** — **Both.** Plain QuPath
> alone is *not* enough. The `qupath-extension-atlas` extension provides (1) the Deep-Zoom reader
> that opens atlas slides and (2) the entire recording + consent mechanism. Without it, the atlas
> slides won't display and the recording flag does nothing.

---

## For the researcher (receiving the project)

### 1. Install QuPath + the extension — **before** opening the project

1. **QuPath 0.6 or newer** — download from [qupath.github.io](https://qupath.github.io) (runs on Java 21).
2. **The extension** (required — opens the slides *and* runs the recording). Easiest if the
   coordinator included the JAR in the package:
   - **Bundled JAR (simplest):** drag **`qupath-extension-atlas-<version>.jar`** (in the package)
     onto the QuPath window → confirm the install → **restart QuPath**. No internet or catalog
     needed for this step.
   - *Or* **Catalog:** QuPath → `Extensions ▸ Manage extension catalogs ▸ Add` →
     `https://github.com/sbalci/patolojiatlasi-QuPath` → then `Extensions ▸ Manage extensions ▸
     Install` → restart.
   - *Or* **Download the JAR** from the project's GitHub *Releases* and drag it on as above.
3. **Internet** — only if the project uses **atlas** slides (they stream from
   `images.patolojiatlasi.com`); nothing to download in advance. Local-slide projects don't need it.

> ⚠️ **Install the extension first.** If you open the project without it, nothing is damaged — but
> the slides won't display (an "unable to build server" message), the recording won't run, and the
> session is wasted. Just install the extension, reopen, and it works.

### 2. Open the project

1. Unzip the project folder somewhere stable (e.g. Documents). Keep it intact — don't move files
   out of it (`project.qpproj`, `data/`, `atlas-research.json`, …).
2. QuPath → `File ▸ Project… ▸ Open project…` → choose **`project.qpproj`**.
3. You may see a one-time **"Araştırma kaydı"** notice explaining that, if you accept, your viewed
   regions and time are recorded **anonymously and silently** (no identity, nothing shown on screen).
   Click **OK** to take part. *(Some studies obtain consent separately at enrollment — in that case
   no notice appears; ask your coordinator.)*
4. **If a slide won't open** and the project uses **your own local slides:** QuPath will offer to
   locate/update the file — point it at the folder where you saved the slide files the coordinator
   sent **separately** (`File ▸ Project… ▸ Import images`/the URI-update prompt). Atlas slides need
   no relinking (they stream from a URL).

### 3. Just read the slides — normally

Pan, zoom, and move between slides exactly as you normally would. **Everything is recorded
automatically and silently** — there are no buttons to press and no heatmap is shown (that's
deliberate: seeing where you'd looked would change how you look). Work naturally.

### 4. Send your data back

When you finish (close the project, or quit QuPath), the extension writes **one file** into the
project folder:

```
<your project folder>/atlas-focus_<date-time>_<id>.zip
```

**Email that one zip to the coordinator.** (If unsure, send the whole `atlas-focus/` sub-folder — it
always contains everything.) It's **anonymized** — no name, a random session id, and a date only —
so it's safe to send. This *data* zip is a **different file** from the *project* zip you received.

### FAQ

- **Do I really need the extension?** Yes — see the top. QuPath alone can't open the slides or record.
- **A slide won't load.** Atlas slide → check your internet. Your own slide → relink it (step 2.4).
- **Where's my data?** In the project folder: `atlas-focus/` and the `atlas-focus_*.zip`.
- **Is it anonymous?** Yes — no name, random id, date only.
- **Nothing happens when I "record".** Correct — it's blinded by design; the recording is invisible.
- **Can I check or toggle recording myself?** Normally you shouldn't need to — the project starts it
  for you. The optional manual toggle lives at **Extensions ▸ Araştırma ▸ Odak ısı haritası ▸ Kör
  kayıt (araştırma)** if you ever need it (e.g. to confirm it's on).

---

## For the coordinator (preparing & sending the project)

### Package to send

Put these in the zip you distribute (keep it small — see slide notes below):

- The **project folder** (`project.qpproj`, `data/`, `atlas-research.json`).
- The **extension JAR** `qupath-extension-atlas-<version>.jar` (so recipients just drag-drop it —
  no catalog/internet install, and the version is guaranteed to match). MIT-licensed, fine to
  redistribute.
- A short **`README-first.txt`** (template at the bottom of this file).

### Slides — keep the project zip small

- **Atlas slides (works today):** build the project from the atlas browser
  (`Extensions ▸ Patoloji Atlası ▸ Slaytlara gözat… ▸ Create project…`) with the
  **"Araştırma projesi — kör odak kaydı (blinded)"** box checked. Entries are **DZI URLs**, so the
  project zip is tiny and slides stream on the recipient's machine (they need internet + the
  extension).
- **Your own local slides (SVS, etc.):** the project builder is atlas-only, so build a **normal
  QuPath project** from your slides, then flag it as a research project one of two ways:
  - **One-click (recommended):** with the project open, **Extensions ▸ Araştırma ▸ "Mevcut projeyi
    araştırma projesi yap (kör kayıt)…"** writes the sidecar for you, confirms once, and starts
    recording immediately in this session too.
  - **Manual:** add an `atlas-research.json` file yourself in the project folder:
    ```json
    { "schema": "atlas-research/1", "blindedTracking": true, "consented": false }
    ```
  Either way, the extension's project-open hook reads the sidecar regardless of slide source, so
  blinded recording turns on for every future open. **Share the slide files separately** (they're
  large — zipping them into the project is impractical); recipients relink them on open.

### Consent model — and the observer-effect tradeoff (read this)

Telling a reader, in the moment, that their viewing is being recorded can **change how they look**
(reactivity / Hawthorne effect) — which undermines the unbiased design. Choose deliberately:

- **(A) Enrollment consent + silent recording — recommended for validity.** Obtain written informed
  consent **once at enrollment** (IRB-approved; general wording, e.g. "your use of the slide software
  may be recorded anonymously"), then ship with `atlas-research.json` **`"consented": true`** so **no
  in-app notice appears** and behavior stays natural. Reduce reactivity further with: general framing
  (you needn't name gaze/dwell as the measure — IRBs permit incomplete, non-deceptive disclosure with
  a debrief), a **natural task** (real diagnostic/teaching work), and **habituation** (discard the
  first few warm-up sessions). Debrief participants afterward and offer data withdrawal.
- **(B) In-app per-session consent.** Ship with **`"consented": false`**; each reader sees the
  one-time notice on first open. Simplest as a digital consent record, but the most priming.
- **Useful fact:** for **comparative** questions (expert vs trainee, condition A vs B) the observer
  effect is a shared constant across groups and **largely cancels out** — comparisons stay valid even
  with some reactivity.

> ⚠️ **Consent footgun.** When *you* build/test the project, the consent prompt fires in **your own**
> QuPath. If you click **OK**, `consented:true` is written into `atlas-research.json` and kept. So:
> for model (B), **Cancel** it (or set `"consented": false`) **before zipping**; for model (A),
> leaving `true` is intentional. Ship the wrong value and every recipient either records with no
> notice (B intended) or is prompted when you didn't want it (A intended).

> **This is an ethics/IRB decision.** The incomplete-disclosure-plus-debrief design must be approved
> by your ethics board. This guide describes standard methodology; it doesn't prescribe your choice.

### Getting data back & analyzing it

- Recipients return `atlas-focus_*.zip` files (anonymized). Keep your **`sessionId → participant`
  map out-of-band** (a separate file held by a third party) — that's what makes the study
  **double-blind**: the analyst works blind to identity (and, if you use condition codes, to group).
- Analyze the returned zips with the tools in **[`analysis/`](analysis/README.md)**:
  - `analysis/python/` (numpy/pandas/matplotlib/scipy) or `analysis/R/` (jsonlite/ggplot2/irr) —
    per-user metrics, cross-user agreement/consensus, reference (expert/ROI) comparison, scanpath.
  - `tools/quicklook-blinded-focus.py` — zero-dependency heatmap PNGs for a fast look.
  - Use condition-coded `--labels sessionId,label` (never names).
- The recording captures the three axes: **spatial** (dwell grid), **temporal** (dwell-ms + total
  duration), and **directional** (the ordered scanpath, schema/3).

---

## `README-first.txt` — drop this in each zip

```
QuPath blinded-research project — quick start

1. Install QuPath 0.6+  (https://qupath.github.io).
2. Install this extension: drag qupath-extension-atlas-<version>.jar onto the
   QuPath window, confirm, and RESTART QuPath.  (Do this BEFORE step 3.)
3. In QuPath: File > Project... > Open project... > project.qpproj
   - Accept the one-time "Araştırma kaydı" notice if it appears.
   - Atlas slides stream over the internet; for local slides, relink when asked.
4. Just review the slides normally. Recording is automatic, silent, anonymous.
5. When done, email the file  atlas-focus_<date>_<id>.zip  (in the project
   folder) back to the study coordinator.

Questions: see SHARING.md.
```
