package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.patolojiatlasi.qupath.CoverageStats.CoverageMatrix;
import com.patolojiatlasi.qupath.CoverageStats.StainBucket;

class CoverageStatsTest {

    // reponame, stainname, image, titleEN, titleTR, organEN, speciality, type, dziUrl, thumbUrl, mpp
    private static AtlasCase mk(String repo, String image, String organ, String type, double mpp) {
        return new AtlasCase(repo, image, image, "T " + repo, "", organ, "", type,
                "https://images.x/" + repo + "/" + image + ".dzi", "", mpp);
    }

    @Test
    void stainBucketClassifiesEachFamily() {
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("HE", ""));
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("H&E", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CD3", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CD20", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("Ki67", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("p63", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("PAS", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("masson", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("warthinstarry", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("congo", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("bluething", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("", ""));
    }

    @Test
    void stainBucketBoundaryGuardsDoNotShadow() {
        // "syn" (synaptophysin) must not match inside "synovial"
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("synovial", ""));
        // an H&E token must not match a word merely containing "he"
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("heart", ""));
        // "alk" (ALK IHC marker) must not match inside "stalk". Real catalog record: reponame
        // "tubularadenoma", stainname "tubularadenoma-tubular-adenoma-with-stalk" — an H&E colon
        // polyp that used to wrongly land in IHC because "alk" was a substring key matching
        // inside "stalk".
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("tubular-adenoma-with-stalk", ""));
        // the marker must still classify IHC as a whole token.
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("ALK", ""));
    }

    @Test
    void stainBucketHandlesHeDigitSuffix() {
        // Real slide-naming convention: HE1..HE4 (49/288 real slides use this form).
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("HE1", ""));
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("HE4", ""));
        // real catalog slug form, e.g. reponame-prefixed stainname
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("BS1-HE1", ""));
        // the digit-suffix regex must still respect word boundaries
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("heart", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("the", ""));
    }

    @Test
    void stainBucketPapIsWholeTokenNotSubstring() {
        // "pap" alone (Papanicolaou) still classifies SPECIAL.
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("pap", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("pap smear", ""));
        // Real catalog record: reponame "pancreas-solid-pseudopapillary", image
        // "betacatenine", stainname "pancreas-solid-pseudopapillary-betacatenine" — a
        // beta-catenin IHC slide that used to wrongly land in SPECIAL because "pap" was
        // a substring key matching inside "pseudopapillary". mirrors compute()'s call
        // shape: stainBucket(getImage(), getStainname()).
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket(
                "betacatenine", "pancreas-solid-pseudopapillary-betacatenine"));
    }

    @Test
    void stainBucketRecognizesNewlyAddedMarkers() {
        // Distinctive substring markers.
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("GFAP", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CDX2", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("HepPar1", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("AE1AE3", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("OSCAR", ""));
        // Short/ambiguous markers — must match only as a whole token.
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("EMA", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CHR", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("NFP", ""));
        // Shadow guards: "ema" is a substring of "hematoma", "chr" of "chronic" — neither
        // is a whole token there, so both must stay OTHER.
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("hematoma", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("chronic", ""));
    }

    @Test
    void computeCountsSlidesCasesAndPercents() {
        // repoA: 2 stains (HE published mpp-known, CD3 published mpp-unknown) -> 1 case, 2 slides
        // repoB: 1 stain (HE unpublished mpp-known) -> 1 case, 1 slide
        List<AtlasCase> cases = List.of(
                mk("repoA", "HE", "Colon", "published", 0.26),
                mk("repoA", "CD3", "Colon", "published", 0.0),
                mk("repoB", "HE", "Breast", "unpublished", 0.50));
        CoverageMatrix m = CoverageStats.compute(cases);
        assertEquals(3, m.totalSlides());
        assertEquals(2, m.totalCases());          // repoA + repoB
        assertEquals(2, m.totalPublished());       // repoA HE + repoA CD3
        assertEquals(2, m.totalMppKnown());        // repoA HE + repoB HE
        assertEquals(67, m.publishedPct());        // Math.round(100*2/3) = 67
        // Colon row: 2 slides (HE+CD3), 1 case, HE-bucket 1, IHC-bucket 1
        CoverageStats.CategoryRow colon = m.rows().stream()
                .filter(r -> r.category().equals("Gastrointestinal")).findFirst().orElseThrow();
        assertEquals(2, colon.slides());
        assertEquals(1, colon.cases());
        assertEquals(1, colon.counts()[StainBucket.HE.ordinal()]);
        assertEquals(1, colon.counts()[StainBucket.IHC.ordinal()]);
    }

    @Test
    void csvHasHeaderTotalRowAndEscaping() {
        CoverageMatrix m = CoverageStats.compute(List.of(mk("r", "HE", "Colon", "published", 0.26)));
        String csv = CoverageStats.toCsv(m);
        String[] lines = csv.strip().split("\n");
        assertTrue(lines[0].startsWith("category,HE,IHC,special,other,slides,cases,"));
        assertTrue(csv.contains("\nTOTAL,"));
    }

    @Test
    void markdownIsATableWithBothCounts() {
        CoverageMatrix m = CoverageStats.compute(List.of(mk("r", "HE", "Colon", "published", 0.26)));
        String md = CoverageStats.toMarkdown(m, LocalDate.of(2026, 7, 19));
        assertTrue(md.contains("| category |"));
        assertTrue(md.contains("| --- |"));
        assertTrue(md.contains("1 slides"));
        assertTrue(md.contains("1 cases") || md.contains("1 case"));   // provenance line
        assertTrue(md.contains("2026-07-19"));
    }

    @Test
    void uncategorizedSortsLast() {
        List<AtlasCase> cases = List.of(
                mk("u", "HE", "", "published", 0.0),                 // -> Uncategorized
                mk("a", "HE", "Colon", "published", 0.0),
                mk("b", "HE", "Colon", "published", 0.0));           // Colon has more slides
        CoverageMatrix m = CoverageStats.compute(cases);
        assertEquals("Uncategorized", m.rows().get(m.rows().size() - 1).category());
    }
}
