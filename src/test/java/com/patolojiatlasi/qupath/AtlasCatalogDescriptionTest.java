package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the optional {@code descriptionTR} / {@code descriptionEN} scalar fields that
 * {@link AtlasCatalog#parseList(String)} may pick up from {@code list.yaml}, and the
 * TR-first / EN-fallback resolution exposed by {@link AtlasCase#getDescription()}.
 */
class AtlasCatalogDescriptionTest {

    @Test
    void parsesOptionalDescriptionScalars() {
        String yaml = """
                - stainname: demo-HE
                  reponame: demo
                  titleEN: Demo case
                  descriptionTR: Kısa Türkçe açıklama.
                  descriptionEN: Short English description.
                  url: https://images.patolojiatlasi.com/demo/HE.html
                """;
        List<AtlasCase> cases = AtlasCatalog.parseList(yaml);
        assertEquals(1, cases.size());
        assertEquals("Kısa Türkçe açıklama.", cases.get(0).getDescription());
    }

    @Test
    void missingDescriptionYieldsEmptyString() {
        String yaml = """
                - stainname: demo-HE
                  reponame: demo
                  titleEN: Demo case
                  url: https://images.patolojiatlasi.com/demo/HE.html
                """;
        List<AtlasCase> cases = AtlasCatalog.parseList(yaml);
        assertEquals(1, cases.size());
        assertEquals("", cases.get(0).getDescription());
        assertTrue(cases.get(0).getDescription().isEmpty());
    }

    @Test
    void fallsBackToEnglishDescriptionWhenTurkishAbsent() {
        String yaml = """
                - stainname: demo-HE
                  reponame: demo
                  titleEN: Demo case
                  descriptionEN: Short English description.
                  url: https://images.patolojiatlasi.com/demo/HE.html
                """;
        List<AtlasCase> cases = AtlasCatalog.parseList(yaml);
        assertEquals(1, cases.size());
        assertEquals("Short English description.", cases.get(0).getDescription());
    }

    @Test
    void blankTurkishDescriptionFallsBackToEnglish() {
        // A blank descriptionTR line (key present, empty value) should not shadow a
        // non-blank descriptionEN.
        String yaml = """
                - stainname: demo-HE
                  reponame: demo
                  titleEN: Demo case
                  descriptionTR:
                  descriptionEN: Short English description.
                  url: https://images.patolojiatlasi.com/demo/HE.html
                """;
        List<AtlasCase> cases = AtlasCatalog.parseList(yaml);
        assertEquals(1, cases.size());
        assertEquals("Short English description.", cases.get(0).getDescription());
    }
}
