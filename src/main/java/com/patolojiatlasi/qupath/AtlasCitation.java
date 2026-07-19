package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Pure templating of citations / manifests / methods / figure cards for atlas slides. */
public final class AtlasCitation {

    private AtlasCitation() {}

    /** Provenance context stamped onto every export. {@code catalogCommitSha} may be null/blank. */
    public record CitationContext(LocalDate accessDate, String extensionVersion, String catalogCommitSha) {}

    /** The framing of a figure-region citation card. */
    public record Viewport(double downsample, double centerX, double centerY) {}

    private static final String[] COLS =
            {"title", "stain", "organ", "category", "reponame", "dziUrl", "viewerUrl", "mpp", "published"};

    public static String bibtex(AtlasCase c, CitationContext ctx) {
        String key = "atlas_" + slug(c.getReponame()) + "_" + slug(c.getImage());
        return "@misc{" + key + ",\n"
                + "  title = {" + bareTitle(c) + " (" + c.getImage() + ")},\n"
                + "  author = {{Patoloji Atlası}},\n"
                + "  howpublished = {\\url{" + c.getViewerUrl() + "}},\n"
                + "  year = {" + ctx.accessDate().getYear() + "},\n"
                + "  note = {" + provNote(ctx) + "}\n"
                + "}\n";
    }

    public static String ris(AtlasCase c, CitationContext ctx) {
        String n1 = "organ: " + organOrCategory(c);
        if (has(ctx.catalogCommitSha()))
            n1 += "; catalog " + ctx.catalogCommitSha();
        if (has(ctx.extensionVersion()))
            n1 += "; extension v" + ctx.extensionVersion();
        return "TY  - ELEC\n"
                + "TI  - " + bareTitle(c) + " (" + c.getImage() + ")\n"
                + "AU  - Patoloji Atlası\n"
                + "PB  - Patoloji Atlası\n"
                + "UR  - " + c.getViewerUrl() + "\n"
                + "Y2  - " + ctx.accessDate() + "\n"
                + "N1  - " + n1 + "\n"
                + "ER  - \n";
    }

    public static String plainText(AtlasCase c, CitationContext ctx) {
        StringBuilder sb = new StringBuilder("Patoloji Atlası. ")
                .append(bareTitle(c)).append(" (").append(c.getImage()).append("), ")
                .append(organOrCategory(c)).append(". ")
                .append(c.getViewerUrl()).append(" (accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append("; catalog ").append(ctx.catalogCommitSha());
        return sb.append(").").toString();
    }

    public static String manifestCsv(List<AtlasCase> cases) {
        StringBuilder sb = new StringBuilder(String.join(",", COLS)).append("\n");
        for (AtlasCase c : cases)
            sb.append(csv(bareTitle(c))).append(",").append(csv(c.getImage())).append(",")
                    .append(csv(c.getOrganEN())).append(",").append(csv(c.getCategory())).append(",")
                    .append(csv(c.getReponame())).append(",").append(csv(c.getDziUrl())).append(",")
                    .append(csv(c.getViewerUrl())).append(",").append(mpp(c)).append(",")
                    .append(c.isPublished() ? "published" : "unpublished").append("\n");
        return sb.toString();
    }

    public static String manifestMarkdown(List<AtlasCase> cases, CitationContext ctx) {
        StringBuilder sb = new StringBuilder("<!-- ").append(cases.size()).append(" slides; accessed ")
                .append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append("; catalog ").append(ctx.catalogCommitSha());
        if (has(ctx.extensionVersion()))
            sb.append("; extension v").append(ctx.extensionVersion());
        sb.append(" -->\n");
        sb.append("| ").append(String.join(" | ", COLS)).append(" |\n");
        sb.append("|").append(" --- |".repeat(COLS.length)).append("\n");
        for (AtlasCase c : cases)
            sb.append("| ").append(md(bareTitle(c))).append(" | ").append(md(c.getImage())).append(" | ")
                    .append(md(c.getOrganEN())).append(" | ").append(md(c.getCategory())).append(" | ")
                    .append(md(c.getReponame())).append(" | ").append(md(c.getDziUrl())).append(" | ")
                    .append(md(c.getViewerUrl())).append(" | ").append(mpp(c)).append(" | ")
                    .append(c.isPublished() ? "published" : "unpublished").append(" |\n");
        return sb.toString();
    }

    public static String methodsParagraph(List<AtlasCase> cases, CitationContext ctx) {
        long ncases = cases.stream().map(AtlasCase::getReponame).distinct().count();
        StringBuilder sb = new StringBuilder()
                .append(cases.size()).append(" whole-slide images (").append(ncases).append(" cases) from the ")
                .append("Patoloji Atlası (https://www.patolojiatlasi.com) were reviewed in QuPath via the ")
                .append("qupath-extension-atlas");
        if (has(ctx.extensionVersion()))
            sb.append(" v").append(ctx.extensionVersion());
        sb.append(", accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append(" (catalogue snapshot ").append(ctx.catalogCommitSha()).append(")");
        return sb.append(". Exact slide URLs are listed in the accompanying manifest.").toString();
    }

    public static String figureCitationCard(AtlasCase c, CitationContext ctx, Viewport vp,
                                            String captionStub, String roiGeoJson) {
        StringBuilder sb = new StringBuilder(plainText(c, ctx)).append("\n");
        sb.append(String.format(Locale.US, "Region: center (%.0f, %.0f) px, downsample %.2f.%n",
                vp.centerX(), vp.centerY(), vp.downsample()));
        if (has(captionStub))
            sb.append("Caption: ").append(captionStub).append("\n");
        if (has(roiGeoJson))
            sb.append("ROI (GeoJSON):\n").append(roiGeoJson).append("\n");
        return sb.toString();
    }

    // --- helpers ---
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    /**
     * {@link AtlasCase#getTitle()} already appends {@code " (image)"} when the stain/image isn't
     * already implied by the title (e.g. non-H&amp;E stains). Templates here re-add that suffix
     * explicitly (bibtex/RIS/plain-text) or carry the stain in its own column (CSV/Markdown), so
     * this strips a pre-existing suffix to avoid duplicating it as {@code "Title (CD3) (CD3)"} or
     * a redundant stain repeated in both the title and stain columns.
     */
    private static String bareTitle(AtlasCase c) {
        String t = c.getTitle();
        String suffix = " (" + c.getImage() + ")";
        return t.endsWith(suffix) ? t.substring(0, t.length() - suffix.length()) : t;
    }
    private static String organOrCategory(AtlasCase c) { return has(c.getOrganEN()) ? c.getOrganEN() : c.getCategory(); }
    private static String mpp(AtlasCase c) { return c.getMpp() > 0 ? String.format(Locale.US, "%.4f", c.getMpp()) : ""; }
    private static String provNote(CitationContext ctx) {
        StringBuilder sb = new StringBuilder("Accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha())) sb.append("; catalog ").append(ctx.catalogCommitSha());
        if (has(ctx.extensionVersion())) sb.append("; qupath-extension-atlas v").append(ctx.extensionVersion());
        return sb.toString();
    }
    private static String slug(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ""); }
    private static String csv(String s) {
        if (s == null) s = "";
        return (s.contains(",") || s.contains("\"") || s.contains("\n")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
    private static String md(String s) { return s == null ? "" : s.replace("|", "\\|").replace("\n", " "); }
}
