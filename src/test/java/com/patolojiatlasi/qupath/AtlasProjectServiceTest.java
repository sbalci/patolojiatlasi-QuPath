package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AtlasProjectServiceTest {

    private static AtlasCase sample(String dziUrl) {
        return new AtlasCase("repo", "stain", "HE", dziUrl, "",
                "Colon", "GI", "published", dziUrl, "");
    }

    @Test
    void selectNewSkipsPresentUrisAndKeepsOrder() {
        AtlasCase present = sample("https://x/HE.dzi");
        AtlasCase fresh1 = sample("https://x/CD3.dzi");
        AtlasCase fresh2 = sample("https://x/CD8.dzi");
        Set<URI> existing = Set.of(URI.create("https://x/HE.dzi"));

        List<AtlasCase> result = AtlasProjectService.selectNew(
                List.of(present, fresh1, fresh2), existing);

        assertEquals(List.of(fresh1, fresh2), result);
    }

    @Test
    void selectNewReturnsAllWhenNothingPresent() {
        AtlasCase a = sample("https://x/HE.dzi");
        AtlasCase b = sample("https://x/CD3.dzi");
        List<AtlasCase> result = AtlasProjectService.selectNew(List.of(a, b), Set.of());
        assertEquals(List.of(a, b), result);
    }

    @Test
    void buildResultSummaryReportsCounts() {
        AtlasProjectService.BuildResult r = new AtlasProjectService.BuildResult(
                3, 2, List.of(new AtlasProjectService.BuildResult.Failure(
                        sample("https://x/E.dzi"), "boom")));
        assertEquals("added 3, skipped 2, failed 1", r.summary());
        assertTrue(r.hasFailures());
    }

    @Test
    void buildResultNoFailures() {
        AtlasProjectService.BuildResult r =
                new AtlasProjectService.BuildResult(5, 0, List.of());
        assertEquals("added 5, skipped 0, failed 0", r.summary());
        assertFalse(r.hasFailures());
    }
}
