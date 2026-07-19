package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Pure templating of citations / manifests / methods / figure cards for atlas slides.
 * <p>
 * Citations honour the two upstream {@code CITATION.cff} files: the atlas IMAGE dataset
 * (<a href="https://github.com/patolojiatlasi/patolojiatlasi.github.io">patolojiatlasi.github.io</a>
 * — Balcı, "Patoloji Atlası", DOI 10.5281/zenodo.6382734) and this EXTENSION
 * (<a href="https://github.com/sbalci/patolojiatlasi-QuPath">sbalci/patolojiatlasi-QuPath</a> —
 * DOI 10.5281/zenodo.21443833), plus the QuPath platform the extension's own CFF requires citing
 * (Bankhead et al. 2017). Every {@code bibtex}/{@code ris}/{@code plainText} therefore emits three
 * entries: the image, the extension, and QuPath.
 */
public final class AtlasCitation {

    private AtlasCitation() {}

    /** Provenance context stamped onto every export. {@code catalogCommitSha} may be null/blank. */
    public record CitationContext(LocalDate accessDate, String extensionVersion, String catalogCommitSha) {}

    /** The framing of a figure-region citation card. */
    public record Viewport(double downsample, double centerX, double centerY) {}

    // --- upstream CITATION.cff metadata (kept in sync with the two .cff files) ---------------------
    // Atlas image dataset — patolojiatlasi/patolojiatlasi.github.io/CITATION.cff
    private static final String ATLAS_TITLE = "Patoloji Atlası";
    private static final String ATLAS_AUTHOR_BIB = "Balcı, Serdar";
    private static final String ATLAS_AUTHOR_TXT = "Balcı S";
    private static final String ATLAS_DOI = "10.5281/zenodo.6382734";
    // This extension — sbalci/patolojiatlasi-QuPath/CITATION.cff (concept DOI, all versions)
    private static final String EXT_TITLE = "QuPath Patoloji Atlası Extension (qupath-extension-atlas)";
    private static final String EXT_AUTHOR_BIB = "Balcı, Serdar";
    private static final String EXT_AUTHOR_TXT = "Balcı S";
    private static final String EXT_DOI = "10.5281/zenodo.21443833";
    private static final String EXT_URL = "https://github.com/sbalci/patolojiatlasi-QuPath";
    // QuPath platform — required by the extension's CFF references
    private static final String QUPATH_AUTHOR_BIB = "Bankhead, Peter and others";
    private static final String QUPATH_AUTHOR_TXT = "Bankhead P, et al";
    private static final String QUPATH_TITLE = "QuPath: Open source software for digital pathology image analysis";
    private static final String QUPATH_JOURNAL = "Scientific Reports";
    private static final String QUPATH_YEAR = "2017";
    private static final String QUPATH_DOI = "10.1038/s41598-017-17204-5";

    private static final String[] COLS =
            {"title", "stain", "organ", "category", "reponame", "dziUrl", "viewerUrl", "mpp", "published"};

    // --- BibTeX (image + extension + QuPath) ------------------------------------------------------

    public static String bibtex(AtlasCase c, CitationContext ctx) {
        return slideBibtex(c, ctx) + "\n" + extensionBibtex(ctx) + "\n" + qupathBibtex();
    }

    public static String slideBibtex(AtlasCase c, CitationContext ctx) {
        String key = "atlas_" + slug(c.getReponame()) + "_" + slug(c.getImage());
        return "@misc{" + key + ",\n"
                + "  author = {" + ATLAS_AUTHOR_BIB + "},\n"
                + "  title = {" + ATLAS_TITLE + " — " + bareTitle(c) + " (" + c.getImage() + ")},\n"
                + "  year = {" + ctx.accessDate().getYear() + "},\n"
                + "  doi = {" + ATLAS_DOI + "},\n"
                + "  howpublished = {\\url{" + c.getViewerUrl() + "}},\n"
                + "  note = {" + provNote(ctx) + "}\n"
                + "}\n";
    }

    public static String extensionBibtex(CitationContext ctx) {
        StringBuilder sb = new StringBuilder("@software{qupath_extension_atlas,\n")
                .append("  author = {").append(EXT_AUTHOR_BIB).append("},\n")
                .append("  title = {").append(EXT_TITLE).append("},\n");
        if (has(ctx.extensionVersion()))
            sb.append("  version = {").append(ctx.extensionVersion()).append("},\n");
        return sb.append("  doi = {").append(EXT_DOI).append("},\n")
                .append("  url = {").append(EXT_URL).append("}\n")
                .append("}\n").toString();
    }

    public static String qupathBibtex() {
        return "@article{bankhead2017qupath,\n"
                + "  author = {" + QUPATH_AUTHOR_BIB + "},\n"
                + "  title = {" + QUPATH_TITLE + "},\n"
                + "  journal = {" + QUPATH_JOURNAL + "},\n"
                + "  year = {" + QUPATH_YEAR + "},\n"
                + "  doi = {" + QUPATH_DOI + "}\n"
                + "}\n";
    }

    // --- RIS (image + extension + QuPath) ---------------------------------------------------------

    public static String ris(AtlasCase c, CitationContext ctx) {
        return slideRis(c, ctx) + extensionRis(ctx) + qupathRis();
    }

    private static String slideRis(AtlasCase c, CitationContext ctx) {
        String n1 = "organ: " + organOrCategory(c) + "; accessed " + ctx.accessDate();
        if (has(ctx.catalogCommitSha()))
            n1 += "; catalog " + ctx.catalogCommitSha();
        return "TY  - ELEC\n"
                + "TI  - " + ATLAS_TITLE + " — " + bareTitle(c) + " (" + c.getImage() + ")\n"
                + "AU  - Balcı, Serdar\n"
                + "PB  - Patoloji Atlası\n"
                + "DO  - " + ATLAS_DOI + "\n"
                + "UR  - " + c.getViewerUrl() + "\n"
                + "Y2  - " + ctx.accessDate() + "\n"
                + "N1  - " + n1 + "\n"
                + "ER  - \n";
    }

    private static String extensionRis(CitationContext ctx) {
        StringBuilder sb = new StringBuilder("TY  - COMP\n")
                .append("TI  - ").append(EXT_TITLE).append("\n")
                .append("AU  - Balcı, Serdar\n");
        if (has(ctx.extensionVersion()))
            sb.append("ET  - ").append(ctx.extensionVersion()).append("\n");
        return sb.append("DO  - ").append(EXT_DOI).append("\n")
                .append("UR  - ").append(EXT_URL).append("\n")
                .append("ER  - \n").toString();
    }

    private static String qupathRis() {
        return "TY  - JOUR\n"
                + "TI  - " + QUPATH_TITLE + "\n"
                + "AU  - Bankhead, Peter\n"
                + "JO  - " + QUPATH_JOURNAL + "\n"
                + "PY  - " + QUPATH_YEAR + "\n"
                + "DO  - " + QUPATH_DOI + "\n"
                + "ER  - \n";
    }

    // --- plain text (image + extension + QuPath) --------------------------------------------------

    public static String plainText(AtlasCase c, CitationContext ctx) {
        return "Image: " + slideText(c, ctx) + "\n"
                + "Software: " + extensionText(ctx) + "\n"
                + "Platform: " + qupathText();
    }

    /** Just the image citation line (used by the figure card). */
    public static String slideText(AtlasCase c, CitationContext ctx) {
        StringBuilder sb = new StringBuilder(ATLAS_AUTHOR_TXT).append(". ").append(ATLAS_TITLE)
                .append(" — ").append(bareTitle(c)).append(" (").append(c.getImage()).append("). ")
                .append("https://doi.org/").append(ATLAS_DOI).append(", ")
                .append(c.getViewerUrl()).append(" (accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append("; catalog ").append(ctx.catalogCommitSha());
        return sb.append(").").toString();
    }

    private static String extensionText(CitationContext ctx) {
        StringBuilder sb = new StringBuilder(EXT_AUTHOR_TXT).append(". ").append(EXT_TITLE);
        if (has(ctx.extensionVersion()))
            sb.append(" v").append(ctx.extensionVersion());
        return sb.append(". https://doi.org/").append(EXT_DOI).append(".").toString();
    }

    private static String qupathText() {
        return QUPATH_AUTHOR_TXT + ". " + QUPATH_TITLE + ". " + QUPATH_JOURNAL + ". "
                + QUPATH_YEAR + ". https://doi.org/" + QUPATH_DOI + ".";
    }

    // --- manifest / methods -----------------------------------------------------------------------

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
        sb.append("; atlas DOI ").append(ATLAS_DOI).append(" -->\n");
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
                .append("Patoloji Atlası (Balcı S, https://doi.org/").append(ATLAS_DOI)
                .append(") were reviewed in QuPath (Bankhead et al. 2017, https://doi.org/").append(QUPATH_DOI)
                .append(") via the qupath-extension-atlas");
        if (has(ctx.extensionVersion()))
            sb.append(" v").append(ctx.extensionVersion());
        sb.append(" (https://doi.org/").append(EXT_DOI).append("), accessed ").append(ctx.accessDate());
        if (has(ctx.catalogCommitSha()))
            sb.append(" (catalogue snapshot ").append(ctx.catalogCommitSha()).append(")");
        return sb.append(". Exact slide URLs are listed in the accompanying manifest.").toString();
    }

    public static String figureCitationCard(AtlasCase c, CitationContext ctx, Viewport vp,
                                            String captionStub, String roiGeoJson) {
        StringBuilder sb = new StringBuilder(slideText(c, ctx)).append("\n");
        sb.append(String.format(Locale.US, "Region: center (%.0f, %.0f) px, downsample %.2f.%n",
                vp.centerX(), vp.centerY(), vp.downsample()));
        if (has(captionStub))
            sb.append("Caption: ").append(captionStub).append("\n");
        if (has(roiGeoJson))
            sb.append("ROI (GeoJSON):\n").append(roiGeoJson).append("\n");
        return sb.toString();
    }

    // --- helpers ----------------------------------------------------------------------------------
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    /**
     * {@link AtlasCase#getTitle()} already appends {@code " (image)"} when the stain isn't implied by
     * the title (non-H&amp;E stains). Templates here re-add that suffix explicitly, so this strips a
     * pre-existing one to avoid {@code "Title (CD3) (CD3)"}.
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
