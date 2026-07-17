package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opens all stains of one atlas case into QuPath's synchronized multi-viewer grid. */
public final class CaseCompare {

    private CaseCompare() {}

    /**
     * All stains of the case that owns {@code openDziUrl} (matched by DZI URL, ignoring any
     * {@code ?mpp=} query), with the open slide first, deduped, order stable. Empty if the URL
     * isn't a catalogue slide; a single-element list if its case has only that stain (or a blank
     * reponame, which can't be grouped).
     */
    public static List<AtlasCase> siblingStains(List<AtlasCase> catalog, String openDziUrl) {
        if (catalog == null || openDziUrl == null || openDziUrl.isBlank())
            return List.of();
        String openBase = stripQuery(openDziUrl);
        AtlasCase open = null;
        for (AtlasCase a : catalog) {
            if (stripQuery(a.getDziUrl()).equals(openBase)) {
                open = a;
                break;
            }
        }
        if (open == null)
            return List.of();
        String repo = open.getReponame();
        if (repo == null || repo.isBlank())
            return List.of(open);
        List<AtlasCase> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        result.add(open);
        seen.add(stripQuery(open.getDziUrl()));
        for (AtlasCase a : catalog) {
            if (!repo.equals(a.getReponame()))
                continue;
            if (seen.add(stripQuery(a.getDziUrl())))
                result.add(a);
        }
        return result;
    }

    /** {rows, cols} for {@code n} panels: 1×1, 1×2, 1×3, 2×2, else 2×3; n clamped to [1,6]. */
    public static int[] gridFor(int n) {
        int c = Math.max(1, Math.min(6, n));
        return switch (c) {
            case 1 -> new int[]{1, 1};
            case 2 -> new int[]{1, 2};
            case 3 -> new int[]{1, 3};
            case 4 -> new int[]{2, 2};
            default -> new int[]{2, 3};
        };
    }

    static String stripQuery(String url) {
        if (url == null)
            return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
