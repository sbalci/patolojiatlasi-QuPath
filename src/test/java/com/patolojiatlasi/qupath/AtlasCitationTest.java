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

    // DOIs from the two upstream CITATION.cff files + QuPath.
    private static final String ATLAS_DOI = "10.5281/zenodo.6382734";
    private static final String EXT_DOI = "10.5281/zenodo.21443833";
    private static final String QUPATH_DOI = "10.1038/s41598-017-17204-5";

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
    void bibtexHasImageExtensionAndQupathEntries() {
        String b = AtlasCitation.bibtex(CASE, CTX);
        assertTrue(b.contains("@misc{atlas_celiac_he,"), b);
        assertTrue(b.contains("@software{qupath_extension_atlas,"));
        assertTrue(b.contains("@article{bankhead2017qupath,"));
        assertTrue(b.contains("Patoloji Atlası — Celiac disease (HE)"));
        assertTrue(b.contains("Balcı, Serdar"));
        assertTrue(b.contains(ATLAS_DOI));
        assertTrue(b.contains(EXT_DOI));
        assertTrue(b.contains(QUPATH_DOI));
        assertTrue(b.contains("https://images.x/celiac/HE.html"));   // slide viewer URL (.dzi->.html)
        assertTrue(b.contains("Accessed 2026-07-19"));
        assertTrue(b.contains("catalog abc1234"));
        assertTrue(b.contains("version = {0.1.0}"));
    }

    @Test
    void extensionBibtexOmitsVersionWhenUnknown() {
        String withV = AtlasCitation.extensionBibtex(CTX);
        assertTrue(withV.contains("version = {0.1.0}"));
        String noV = AtlasCitation.extensionBibtex(new CitationContext(LocalDate.of(2026, 7, 19), null, null));
        assertFalse(noV.contains("version ="));
        assertTrue(noV.contains(EXT_DOI));
    }

    @Test
    void risHasThreeRecordsWithDois() {
        String r = AtlasCitation.ris(CASE, CTX);
        assertTrue(r.contains("TY  - ELEC"));   // image
        assertTrue(r.contains("TY  - COMP"));   // extension
        assertTrue(r.contains("TY  - JOUR"));   // QuPath
        assertTrue(r.contains("DO  - " + ATLAS_DOI));
        assertTrue(r.contains("DO  - " + EXT_DOI));
        assertTrue(r.contains("DO  - " + QUPATH_DOI));
        assertTrue(r.contains("UR  - https://images.x/celiac/HE.html"));
        assertTrue(r.trim().endsWith("ER  -"));
    }

    @Test
    void plainTextHasImageSoftwarePlatformLines() {
        String p = AtlasCitation.plainText(CASE, CTX);
        assertTrue(p.contains("Image: "));
        assertTrue(p.contains("Software: "));
        assertTrue(p.contains("Platform: "));
        assertTrue(p.contains("Patoloji Atlası — Celiac disease (HE)"));
        assertTrue(p.contains("https://doi.org/" + ATLAS_DOI));
        assertTrue(p.contains("https://doi.org/" + EXT_DOI));
        assertTrue(p.contains("https://doi.org/" + QUPATH_DOI));
        assertTrue(p.contains("accessed 2026-07-19"));
    }

    @Test
    void slideTextIsJustTheImageCitation() {
        String s = AtlasCitation.slideText(CASE, CTX);
        assertTrue(s.startsWith("Balcı S. Patoloji Atlası — Celiac disease (HE)."));
        assertTrue(s.contains("https://doi.org/" + ATLAS_DOI));
        assertTrue(s.contains("https://images.x/celiac/HE.html"));
        assertFalse(s.contains(EXT_DOI), "the image line alone must not carry the extension DOI");
    }

    @Test
    void nullShaOmitsCatalogLine() {
        assertFalse(AtlasCitation.slideBibtex(CASE, CTX_NOSHA).contains("catalog "));
        assertFalse(AtlasCitation.slideText(CASE, CTX_NOSHA).contains("catalog "));
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
    void manifestMarkdownIsATableWithAtlasDoi() {
        String md = AtlasCitation.manifestMarkdown(List.of(CASE), CTX);
        assertTrue(md.contains("| title | stain |"));
        assertTrue(md.contains("| --- |"));
        assertTrue(md.contains("| Celiac disease |"));
        assertTrue(md.contains(ATLAS_DOI));
    }

    @Test
    void methodsParagraphHasCountsVersionAndDois() {
        String m = AtlasCitation.methodsParagraph(List.of(CASE, CASE), CTX);
        assertTrue(m.contains("2 whole-slide images"));
        assertTrue(m.contains("1 cases"));                   // same reponame -> 1 distinct case
        assertTrue(m.contains("v0.1.0"));
        assertTrue(m.contains("2026-07-19"));
        assertTrue(m.contains(ATLAS_DOI));
        assertTrue(m.contains(EXT_DOI));
        assertTrue(m.contains(QUPATH_DOI));
    }

    @Test
    void figureCardHasImageCitationViewportAndGeoJson() {
        String card = AtlasCitation.figureCitationCard(
                CASE, CTX, new Viewport(4.0, 1000, 2000), "Villous atrophy", "{\"type\":\"Feature\"}");
        assertTrue(card.contains("Patoloji Atlası — Celiac disease (HE)"));
        assertTrue(card.contains("https://doi.org/" + ATLAS_DOI));
        assertTrue(card.contains("center (1000, 2000)"));
        assertTrue(card.contains("downsample 4.00"));
        assertTrue(card.contains("Villous atrophy"));
        assertTrue(card.contains("{\"type\":\"Feature\"}"));
    }
}
