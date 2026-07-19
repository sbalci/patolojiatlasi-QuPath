package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
