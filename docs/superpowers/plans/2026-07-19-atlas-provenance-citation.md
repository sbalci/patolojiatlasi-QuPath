# Research Provenance & Citation Suite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Turn an atlas slide / selection / region into citable, reproducible artifacts (BibTeX/RIS/text citation; cohort manifest CSV+MD+methods; figure-region card), all copy/save, no backend.

**Architecture:** A pure `AtlasCitation` templating class (unit-tested) + a `ProvenanceService` (context resolution + best-effort GitHub commit-SHA + clipboard/file I/O) + `CitationDialog`/`FigureCitationDialog` + a manifest button on `ProjectBuilderDialog` + a browser action + an "Atıf" menu group.

**Tech Stack:** Java 21, JavaFX (provided), QuPath 0.6 API (`compileOnly`), JUnit 5.

## Global Constraints
- QuPath 0.6.0 API only; Java 21; no new bundled deps. Build/test `--offline`. Package `com.patolojiatlasi.qupath`.
- Turkish user-facing labels; ASCII names. `String.format` pins `Locale.US`.
- Network (GitHub SHA) off the FX thread, best-effort (degrade to null); UI on the FX thread.
- Reuse: `QuizGeometry.toGeoJson(ROI)`, `AtlasCatalog.loadBundled()`, active-viewer-URL→case matching (as in `CaseCompare`/`BenchReference`), `ProjectBuilderDialog`'s basket review.

---

### Task 1: `AtlasCitation` pure templating + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/AtlasCitation.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/AtlasCitationTest.java`

**Interface (later tasks rely on):** the static fns + `record CitationContext(LocalDate accessDate, String extensionVersion, String catalogCommitSha)` + `record Viewport(double downsample, double centerX, double centerY)`.

- [ ] **Step 1: Failing test** — `AtlasCitationTest.java`:
```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.patolojiatlasi.qupath.AtlasCitation.CitationContext;
import com.patolojiatlasi.qupath.AtlasCitation.Viewport;

class AtlasCitationTest {

    private static AtlasCase c(String repo, String stain, String title, String organ, String dziUrl, double mpp) {
        return new AtlasCase(repo, stain, stain, title, "", organ, "GI", "published", dziUrl, "", mpp);
    }

    private static final AtlasCase CASE =
            c("celiac", "HE", "Celiac disease", "Gastrointestinal", "https://images.x/celiac/HE.dzi", 0.26);
    private static final CitationContext CTX =
            new CitationContext(LocalDate.of(2026, 7, 19), "0.1.0", "abc1234");
    private static final CitationContext CTX_NOSHA =
            new CitationContext(LocalDate.of(2026, 7, 19), "0.1.0", null);

    @Test
    void bibtexHasKeyTitleUrlNote() {
        String b = AtlasCitation.bibtex(CASE, CTX);
        assertTrue(b.startsWith("@misc{atlas_celiac_he,"), b);
        assertTrue(b.contains("Celiac disease (HE)"));
        assertTrue(b.contains("https://images.x/celiac/HE.html"));   // viewer URL (.dzi -> .html)
        assertTrue(b.contains("Accessed 2026-07-19"));
        assertTrue(b.contains("catalog abc1234"));
        assertTrue(b.contains("v0.1.0"));
        assertTrue(b.trim().endsWith("}"));
    }

    @Test
    void risHasRequiredTags() {
        String r = AtlasCitation.ris(CASE, CTX);
        assertTrue(r.contains("TY  - ELEC"));
        assertTrue(r.contains("TI  - Celiac disease (HE)"));
        assertTrue(r.contains("UR  - https://images.x/celiac/HE.html"));
        assertTrue(r.trim().endsWith("ER  -"));
    }

    @Test
    void plainTextHasTitleUrlAccessed() {
        String p = AtlasCitation.plainText(CASE, CTX);
        assertTrue(p.contains("Celiac disease (HE)"));
        assertTrue(p.contains("Gastrointestinal"));
        assertTrue(p.contains("https://images.x/celiac/HE.html"));
        assertTrue(p.contains("accessed 2026-07-19"));
    }

    @Test
    void nullShaOmitsProvenanceLine() {
        assertFalse(AtlasCitation.bibtex(CASE, CTX_NOSHA).contains("catalog "));
        assertFalse(AtlasCitation.plainText(CASE, CTX_NOSHA).contains("catalog "));
    }

    @Test
    void manifestCsvHeaderRowsAndEscaping() {
        AtlasCase comma = c("x", "CD3", "Tumor, grade 2", "Colon", "https://images.x/x/CD3.dzi", 0);
        String csv = AtlasCitation.manifestCsv(List.of(CASE, comma));
        String[] lines = csv.strip().split("\n");
        assertTrue(lines[0].startsWith("title,stain,organ,"));
        assertEquals(3, lines.length);                       // header + 2 rows
        assertTrue(csv.contains("\"Tumor, grade 2\""));      // comma-containing title quoted
        assertTrue(csv.contains("0.2600"));                  // mpp formatted
    }

    @Test
    void manifestMarkdownIsATable() {
        String md = AtlasCitation.manifestMarkdown(List.of(CASE), CTX);
        assertTrue(md.contains("| title | stain |"));
        assertTrue(md.contains("| --- |"));
        assertTrue(md.contains("| Celiac disease |"));
    }

    @Test
    void methodsParagraphHasCountsAndVersion() {
        String m = AtlasCitation.methodsParagraph(List.of(CASE, CASE), CTX);
        assertTrue(m.contains("2 whole-slide images"));
        assertTrue(m.contains("1 cases"));                   // same reponame → 1 distinct case
        assertTrue(m.contains("v0.1.0"));
        assertTrue(m.contains("2026-07-19"));
    }

    @Test
    void figureCardHasCitationViewportAndGeoJson() {
        String card = AtlasCitation.figureCitationCard(
                CASE, CTX, new Viewport(4.0, 1000, 2000), "Villous atrophy", "{\"type\":\"Feature\"}");
        assertTrue(card.contains("Celiac disease (HE)"));
        assertTrue(card.contains("center (1000, 2000)"));
        assertTrue(card.contains("downsample 4.00"));
        assertTrue(card.contains("Villous atrophy"));
        assertTrue(card.contains("{\"type\":\"Feature\"}"));
    }
}
```

- [ ] **Step 2:** Run → FAIL (class missing): `./gradlew --offline test --tests "com.patolojiatlasi.qupath.AtlasCitationTest"`

- [ ] **Step 3:** Create `AtlasCitation.java`:
```java
package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Pure templating of citations / manifests / methods / figure cards for atlas slides. */
public final class AtlasCitation {

    private AtlasCitation() {}

    /** Provenance context stamped onto every export. {@code catalogCommitSha} may be null/blank. */
    public record CitationContext(LocalDate accessDate, String extensionVersion, String catalogCommitSha) {}

    /** The framing of a figure-region citation card. */
    public record Viewport(double downsample, double centerX, double centerY) {}

    private static final String[] COLS =
            {"title", "stain", "organ", "category", "reponame", "dziUrl", "viewerUrl", "mpp", "published"};

    public static String bibtex(AtlasCase c, CitationContext ctx) {
        String key = "atlas_" + slug(c.getReponame()) + "_" + slug(c.getImage());
        return "@misc{" + key + ",\n"
                + "  title = {" + c.getTitle() + " (" + c.getImage() + ")},\n"
                + "  author = {{Patoloji Atlası}},\n"
                + "  howpublished = {\\url{" + c.getViewerUrl() + "}},\n"
                + "  year = {" + ctx.accessDate().getYear() + "},\n"
                + "  note = {" + provNote(ctx) + "}\n"
                + "}\n";
    }

    public static String ris(AtlasCase c, CitationContext ctx) {
        String n1 = "organ: " + organOrCategory(c);
        if (has(ctx.catalogCommitSha()))
            n1 += "; catalog " + ctx.catalogCommitSha();
        if (has(ctx.extensionVersion()))
            n1 += "; extension v" + ctx.extensionVersion();
        return "TY  - ELEC\n"
                + "TI  - " + c.getTitle() + " (" + c.getImage() + ")\n"
                + "AU  - Patoloji Atlası\n"
                + "PB  - Patoloji Atlası\n"
                + "UR  - " + c.getViewerUrl() + "\n"
                + "Y2  - " + ctx.accessDate() + "\n"
                + "N1  - " + n1 + "\n"
                + "ER  - \n";
    }

    public static String plainText(AtlasCase c, CitationContext ctx) {
        StringBuilder sb = new StringBuilder("Patoloji Atlası. ")
                .append(c.getTitle()).append(" (").append(c.getImage()).append("), ")
                .append(organOrCategory(c)).append(". ")
                .append(c.getViewerUrl()).append(" (accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append("; catalog ").append(ctx.catalogCommitSha());
        return sb.append(").").toString();
    }

    public static String manifestCsv(List<AtlasCase> cases) {
        StringBuilder sb = new StringBuilder(String.join(",", COLS)).append("\n");
        for (AtlasCase c : cases)
            sb.append(csv(c.getTitle())).append(",").append(csv(c.getImage())).append(",")
                    .append(csv(c.getOrganEN())).append(",").append(csv(c.getCategory())).append(",")
                    .append(csv(c.getReponame())).append(",").append(csv(c.getDziUrl())).append(",")
                    .append(csv(c.getViewerUrl())).append(",").append(mpp(c)).append(",")
                    .append(c.isPublished() ? "published" : "unpublished").append("\n");
        return sb.toString();
    }

    public static String manifestMarkdown(List<AtlasCase> cases, CitationContext ctx) {
        StringBuilder sb = new StringBuilder("<!-- ").append(cases.size()).append(" slides; accessed ")
                .append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append("; catalog ").append(ctx.catalogCommitSha());
        if (has(ctx.extensionVersion()))
            sb.append("; extension v").append(ctx.extensionVersion());
        sb.append(" -->\n");
        sb.append("| ").append(String.join(" | ", COLS)).append(" |\n");
        sb.append("|").append(" --- |".repeat(COLS.length)).append("\n");
        for (AtlasCase c : cases)
            sb.append("| ").append(md(c.getTitle())).append(" | ").append(md(c.getImage())).append(" | ")
                    .append(md(c.getOrganEN())).append(" | ").append(md(c.getCategory())).append(" | ")
                    .append(md(c.getReponame())).append(" | ").append(md(c.getDziUrl())).append(" | ")
                    .append(md(c.getViewerUrl())).append(" | ").append(mpp(c)).append(" | ")
                    .append(c.isPublished() ? "published" : "unpublished").append(" |\n");
        return sb.toString();
    }

    public static String methodsParagraph(List<AtlasCase> cases, CitationContext ctx) {
        long ncases = cases.stream().map(AtlasCase::getReponame).distinct().count();
        StringBuilder sb = new StringBuilder()
                .append(cases.size()).append(" whole-slide images (").append(ncases).append(" cases) from the ")
                .append("Patoloji Atlası (https://www.patolojiatlasi.com) were reviewed in QuPath via the ")
                .append("qupath-extension-atlas");
        if (has(ctx.extensionVersion()))
            sb.append(" v").append(ctx.extensionVersion());
        sb.append(", accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append(" (catalogue snapshot ").append(ctx.catalogCommitSha()).append(")");
        return sb.append(". Exact slide URLs are listed in the accompanying manifest.").toString();
    }

    public static String figureCitationCard(AtlasCase c, CitationContext ctx, Viewport vp,
                                            String captionStub, String roiGeoJson) {
        StringBuilder sb = new StringBuilder(plainText(c, ctx)).append("\n");
        sb.append(String.format(Locale.US, "Region: center (%.0f, %.0f) px, downsample %.2f.%n",
                vp.centerX(), vp.centerY(), vp.downsample()));
        if (has(captionStub))
            sb.append("Caption: ").append(captionStub).append("\n");
        if (has(roiGeoJson))
            sb.append("ROI (GeoJSON):\n").append(roiGeoJson).append("\n");
        return sb.toString();
    }

    // --- helpers ---
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    private static String organOrCategory(AtlasCase c) { return has(c.getOrganEN()) ? c.getOrganEN() : c.getCategory(); }
    private static String mpp(AtlasCase c) { return c.getMpp() > 0 ? String.format(Locale.US, "%.4f", c.getMpp()) : ""; }
    private static String provNote(CitationContext ctx) {
        StringBuilder sb = new StringBuilder("Accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha())) sb.append("; catalog ").append(ctx.catalogCommitSha());
        if (has(ctx.extensionVersion())) sb.append("; qupath-extension-atlas v").append(ctx.extensionVersion());
        return sb.toString();
    }
    private static String slug(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ""); }
    private static String csv(String s) {
        if (s == null) s = "";
        return (s.contains(",") || s.contains("\"") || s.contains("\n")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
    private static String md(String s) { return s == null ? "" : s.replace("|", "\\|").replace("\n", " "); }
}
```

- [ ] **Step 4:** Run → PASS (8 tests). `./gradlew --offline compileJava` → SUCCESSFUL.
- [ ] **Step 5:** Commit: `git commit -m "feat(citation): AtlasCitation pure templating + tests"`

---

### Task 2: `ProvenanceService` + `CitationDialog` + cite-a-slide launch

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/ProvenanceService.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/CitationDialog.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` (tree context menu)
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` ("Atıf" menu group)

**Read for patterns:** `FocusHeatmap` (a background `HttpClient` GET + JSON parse via Gson); `CaseCompare` (resolve the active viewer's DZI URL → catalogue case: first of `server.getURIs()`, strip `?…`, match `AtlasCatalog.loadBundled()` by `getDziUrl()`); `ProjectBuilderDialog` (modal `Stage` with buttons); `AtlasBrowser.getSelectedCase()`.

**`ProvenanceService`:**
- `static CitationContext resolveContext()` — `LocalDate.now()`; version = `AtlasExtension.class.getPackage().getImplementationVersion()` (fallback `"0.1.0"` then null-safe); `catalogCommitSha` = **best-effort** GET `https://api.github.com/repos/patolojiatlasi/patolojiatlasi.github.io/commits?path=lists/list.yaml&per_page=1` (2–3s timeout), parse first element's `sha` (Gson), take the first 7 chars; ANY failure → null. Meant to be called OFF the FX thread.
- `static void copyToClipboard(String text)` (FX thread — `Clipboard.getSystemClipboard()`).
- `static void saveTextFile(String suggestedName, String content, javafx.stage.Window owner)` — `FileChooser` → write UTF-8; on IOException show an Alert.
- `static void saveManifest(java.io.File dir, List<AtlasCase> cases, CitationContext ctx)` — writes `atlas-manifest.csv`, `atlas-manifest.md`, `atlas-methods.txt` from `AtlasCitation`.

**`CitationDialog`:** `static void show(QuPathGUI qupath, AtlasCase c)` — a modal `Stage`: a `ComboBox<String>` (BibTeX / RIS / Düz metin), a read-only wrapping `TextArea`, **"Panoya kopyala"** + **"Kaydet…"** + Cancel. Resolve the `CitationContext` on a background thread (show it immediately with a placeholder SHA note, then re-render when the SHA arrives via `Platform.runLater`); switching the ComboBox re-renders from the cached context. Copy/save act on the current TextArea text.

**`AtlasBrowser`:** add a **"Bu slaytı alıntıla…"** item to the tree `ContextMenu` (alongside the existing items) → `CitationDialog.show(qupath, getSelectedCase())` guarded by the null-selection status hint.

**`AtlasExtension`:** add an **Atıf** `Menu` under "Patoloji Atlası" (after Referans) with **"Açık slaytı alıntıla…"** → resolve the active viewer's DZI URL → catalogue case (reuse the matching helper; if not an atlas slide → info Alert) → `CitationDialog.show`.

- [ ] **Step 1:** Implement `ProvenanceService`, `CitationDialog`, the browser item, the menu group.
- [ ] **Step 2:** `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → PASS (Task-1 tests; no new automated tests — UI/network manual).
- [ ] **Step 3:** Commit: `git commit -m "feat(citation): ProvenanceService + CitationDialog + cite-a-slide launch"`

**Manual verification (record):** right-click a case → "Bu slaytı alıntıla…" → dialog shows BibTeX/RIS/text, copy works, the catalog SHA appears (or is omitted offline); the menu "Açık slaytı alıntıla…" cites the open atlas slide (info if not an atlas slide).

---

### Task 3: cohort manifest + figure-region card + README

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java` (manifest button)
- Create: `src/main/java/com/patolojiatlasi/qupath/FigureCitationDialog.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` ("Bu bölgeyi alıntıla…")
- Modify: `README.md`

**`ProjectBuilderDialog`:** add a **"Künye / manifest dışa aktar…"** button (near Create) → a `DirectoryChooser` → `ProvenanceService.saveManifest(dir, <the dialog's selected cases>, ProvenanceService.resolveContext())` on a background thread (SHA lookup) with a status/Alert on done. Uses the same case list the dialog already holds. Does NOT require creating a project.

**`FigureCitationDialog`:** `static void show(QuPathGUI qupath)` — resolve the active viewer's atlas case (as in Task 2; not-atlas → info); get the selected annotation `qupath.getViewer().getImageData().getHierarchy().getSelectionModel().getSelectedObject()`; if null or no ROI → info "Önce bir bölge (anotasyon) seçin"; else build `AtlasCitation.Viewport` from the viewer (`getDownsampleFactor()`, and the center — use the displayed-region-shape bounds center, or `getCenterPixelX/Y` if available; read `QuPathViewer` for the accessor) and `QuizGeometry.toGeoJson(roi)`; a modal dialog showing `AtlasCitation.figureCitationCard(...)` with an editable **caption** field (re-renders the card) + Copy/Save. Context resolved off-thread as in `CitationDialog`.

**`AtlasExtension`:** add **"Bu bölgeyi alıntıla…"** to the Atıf menu → `FigureCitationDialog.show(qupath)`.

**README:** a short "Provenance & citation" note (cite a slide from the browser right-click or Atıf menu; export a cohort manifest from the Create-project dialog; cite a region via Atıf → Bu bölgeyi alıntıla…).

- [ ] **Step 1:** Implement the manifest button, `FigureCitationDialog`, the menu item, README. (Verify the `QuPathViewer` viewport-center accessor via a quick check before use.)
- [ ] **Step 2:** `./gradlew --offline compileJava` → SUCCESSFUL; `./gradlew --offline test` → PASS; `./gradlew --offline build` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -m "feat(citation): cohort manifest export + figure-region card + README"`

## Self-Review
- Spec coverage: all templating (T1, tested); context/SHA/clipboard/save + cite-a-slide dialog + browser/menu (T2); manifest export + figure card + region-cite menu + README (T3). ✅
- Placeholders: T1 full code+tests; T2/T3 precise interface + behavior + named reuse anchors + the one viewport-center VERIFY note. ✅
- Types consistent: `AtlasCitation.*`, `CitationContext`, `Viewport`, `ProvenanceService.{resolveContext,copyToClipboard,saveTextFile,saveManifest}`, `CitationDialog.show`, `FigureCitationDialog.show` used identically across tasks. ✅
