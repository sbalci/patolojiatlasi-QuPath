package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.patolojiatlasi.qupath.CoverageStats.StainBucket;

/** Pure related-slide list building for the navigator. No UI, no network. */
final class RelatedContent {

    private RelatedContent() {}

    /** The open case's OTHER stains: {@code siblingStains} returns the open slide first; drop it. */
    static List<AtlasCase> otherStains(List<AtlasCase> catalog, AtlasCase open) {
        if (catalog == null || open == null)
            return List.of();
        List<AtlasCase> siblings = CaseCompare.siblingStains(catalog, open.getDziUrl());
        if (siblings.size() <= 1)
            return List.of();
        return List.copyOf(siblings.subList(1, siblings.size()));
    }

    /**
     * One representative {@link AtlasCase} per OTHER case (distinct {@code reponame}) sharing the
     * open case's {@link AtlasCase#getCategory()} — excluding the open case's own reponame. Stable
     * by first catalogue appearance; representative = the case's H&E stain if present, else its
     * first stain in catalogue order.
     */
    static List<AtlasCase> sameCategoryCases(List<AtlasCase> catalog, AtlasCase open) {
        if (catalog == null || open == null)
            return List.of();
        String category = open.getCategory();
        // "Uncategorized" is a catch-all (~half the catalogue), not a meaningful shared category —
        // treating it as one would dump most of the atlas into the strip. Show no same-category set.
        if (category == null || category.isBlank() || "Uncategorized".equals(category))
            return List.of();
        String openRepo = open.getReponame();
        // Group same-category, other-case slides by reponame, preserving first-seen order.
        Map<String, List<AtlasCase>> byRepo = new LinkedHashMap<>();
        for (AtlasCase c : catalog) {
            if (!category.equals(c.getCategory()))
                continue;
            String repo = c.getReponame();
            if (repo == null || repo.isBlank() || repo.equals(openRepo))
                continue;
            byRepo.computeIfAbsent(repo, k -> new ArrayList<>()).add(c);
        }
        List<AtlasCase> result = new ArrayList<>();
        for (List<AtlasCase> stains : byRepo.values())
            result.add(representative(stains));
        return List.copyOf(result);
    }

    /**
     * The H&E stain of a case if present (via {@link CoverageStats#stainBucket}), else the first.
     * When a case has more than one H&E-bucketed stain, the first in catalogue order is chosen.
     */
    private static AtlasCase representative(List<AtlasCase> stains) {
        for (AtlasCase c : stains)
            if (CoverageStats.stainBucket(c.getImage(), c.getStainname()) == StainBucket.HE)
                return c;
        return stains.get(0);
    }
}
