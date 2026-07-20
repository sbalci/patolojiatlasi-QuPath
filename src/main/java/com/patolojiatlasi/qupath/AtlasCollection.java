package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A portable, shareable pick-list of atlas slides. Pure model + resolve; no UI/IO. */
public record AtlasCollection(int formatVersion, String name, List<Entry> entries) {

    public static final int FORMAT_VERSION = 1;

    public record Entry(String dziUrl, String reponame, String image, String title) {}

    public record Resolution(List<AtlasCase> found, List<Entry> missing) {}

    public static AtlasCollection fromCases(String name, Collection<AtlasCase> cases) {
        List<Entry> entries = new ArrayList<>();
        for (AtlasCase c : cases)
            entries.add(new Entry(c.getDziUrl(), c.getReponame(), c.getImage(), c.getTitle()));
        return new AtlasCollection(FORMAT_VERSION, name, entries);
    }

    /** Re-match each entry against {@code catalog} by query-stripped DZI URL (both sides). */
    public static Resolution resolve(AtlasCollection coll, List<AtlasCase> catalog) {
        Map<String, AtlasCase> byKey = new LinkedHashMap<>();
        for (AtlasCase c : catalog)
            byKey.putIfAbsent(CaseCompare.stripQuery(c.getDziUrl()), c);
        List<AtlasCase> found = new ArrayList<>();
        List<Entry> missing = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry e : coll.entries()) {
            String key = CaseCompare.stripQuery(e.dziUrl());
            AtlasCase c = byKey.get(key);
            if (c != null && seen.add(key))
                found.add(c);
            else if (c == null)
                missing.add(e);
        }
        return new Resolution(found, missing);
    }
}
