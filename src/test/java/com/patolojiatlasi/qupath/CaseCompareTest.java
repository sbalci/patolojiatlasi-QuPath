package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CaseCompareTest {

    private static AtlasCase c(String repo, String stain, String dziUrl) {
        return new AtlasCase(repo, stain, stain, stain + " title", "", "Colon", "GI",
                "published", dziUrl, "");
    }

    private static final List<AtlasCase> CATALOG = List.of(
            c("caseA", "HE",  "https://x/caseA/HE.dzi"),
            c("caseA", "CD3", "https://x/caseA/CD3.dzi"),
            c("caseA", "CD8", "https://x/caseA/CD8.dzi"),
            c("caseB", "HE",  "https://x/caseB/HE.dzi"));

    @Test
    void groupsByReponameWithOpenSlideFirst() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseA/CD3.dzi");
        assertEquals(3, r.size());
        assertEquals("https://x/caseA/CD3.dzi", r.get(0).getDziUrl(), "open slide must be first");
        assertTrue(r.stream().allMatch(a -> a.getReponame().equals("caseA")));
    }

    @Test
    void ignoresMppQueryWhenMatching() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseA/HE.dzi?mpp=0.26");
        assertEquals(3, r.size());
        assertEquals("https://x/caseA/HE.dzi", r.get(0).getDziUrl());
    }

    @Test
    void notInCatalogReturnsEmpty() {
        assertTrue(CaseCompare.siblingStains(CATALOG, "https://x/unknown/HE.dzi").isEmpty());
        assertTrue(CaseCompare.siblingStains(CATALOG, "").isEmpty());
    }

    @Test
    void singleStainCaseReturnsJustIt() {
        List<AtlasCase> r = CaseCompare.siblingStains(CATALOG, "https://x/caseB/HE.dzi");
        assertEquals(1, r.size());
    }

    @Test
    void gridForMapsAndCapsAtSix() {
        assertArrayEquals(new int[]{1, 1}, CaseCompare.gridFor(1));
        assertArrayEquals(new int[]{1, 2}, CaseCompare.gridFor(2));
        assertArrayEquals(new int[]{1, 3}, CaseCompare.gridFor(3));
        assertArrayEquals(new int[]{2, 2}, CaseCompare.gridFor(4));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(5));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(6));
        assertArrayEquals(new int[]{2, 3}, CaseCompare.gridFor(9)); // capped
        assertArrayEquals(new int[]{1, 1}, CaseCompare.gridFor(0)); // floor
    }
}
