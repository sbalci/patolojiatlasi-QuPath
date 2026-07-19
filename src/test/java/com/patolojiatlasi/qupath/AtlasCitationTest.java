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
