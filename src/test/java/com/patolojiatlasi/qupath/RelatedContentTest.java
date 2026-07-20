package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelatedContentTest {

    // reponame, stainname, image, titleEN, titleTR, organEN, speciality, type, dziUrl, thumbUrl, mpp
    private static AtlasCase mk(String repo, String image, String organ, String dzi) {
        return new AtlasCase(repo, image, image, "T " + repo, "", organ, "", "published", dzi, "", 0.0);
    }

    @Test
    void otherStainsDropsTheOpenSlide() {
        AtlasCase he  = mk("repoA", "HE",  "Colon", "https://x/repoA/HE.dzi");
        AtlasCase cd3 = mk("repoA", "CD3", "Colon", "https://x/repoA/CD3.dzi");
        AtlasCase cd20= mk("repoA", "CD20","Colon", "https://x/repoA/CD20.dzi");
        AtlasCase other = mk("repoB", "HE", "Colon", "https://x/repoB/HE.dzi");
        List<AtlasCase> cat = List.of(he, cd3, cd20, other);
        List<AtlasCase> res = RelatedContent.otherStains(cat, he);
        assertEquals(2, res.size());                         // CD3, CD20 — not HE (the open one)
        assertTrue(res.stream().noneMatch(c -> c.getDziUrl().equals(he.getDziUrl())));
        assertTrue(res.stream().anyMatch(c -> c.getImage().equals("CD3")));
    }

    @Test
    void otherStainsEmptyForSingleStainCase() {
        AtlasCase only = mk("solo", "HE", "Colon", "https://x/solo/HE.dzi");
        assertEquals(0, RelatedContent.otherStains(List.of(only), only).size());
    }

    @Test
    void sameCategoryReturnsOneRepresentativePerOtherCase() {
        AtlasCase openHe = mk("repoA", "HE", "Colon", "https://x/repoA/HE.dzi");     // Gastrointestinal
        AtlasCase bHe  = mk("repoB", "HE",  "Colon", "https://x/repoB/HE.dzi");
        AtlasCase bCd3 = mk("repoB", "CD3", "Colon", "https://x/repoB/CD3.dzi");
        AtlasCase cCd  = mk("repoC", "CD20","Colon", "https://x/repoC/CD20.dzi");    // no HE -> first stain
        AtlasCase breast = mk("repoD", "HE", "Breast", "https://x/repoD/HE.dzi");    // different category
        List<AtlasCase> cat = List.of(openHe, bHe, bCd3, cCd, breast);
        List<AtlasCase> res = RelatedContent.sameCategoryCases(cat, openHe);
        // repoB (represented by its HE) + repoC (represented by CD20). Not repoA (self), not repoD (other cat).
        assertEquals(2, res.size());
        assertTrue(res.stream().noneMatch(c -> c.getReponame().equals("repoA")));
        assertTrue(res.stream().noneMatch(c -> c.getReponame().equals("repoD")));
        AtlasCase repB = res.stream().filter(c -> c.getReponame().equals("repoB")).findFirst().orElseThrow();
        assertEquals("HE", repB.getImage());                 // prefers the H&E stain
        AtlasCase repC = res.stream().filter(c -> c.getReponame().equals("repoC")).findFirst().orElseThrow();
        assertEquals("CD20", repC.getImage());               // no HE -> first (only) stain
    }
}
