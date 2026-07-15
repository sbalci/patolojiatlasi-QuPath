package com.patolojiatlasi.qupath;

import java.net.URI;

/**
 * A single viewable whole-slide image (one stain of one case) in the Patoloji Atlası.
 * Backed by an entry of {@code lists/list.yaml}.
 */
public class AtlasCase {

    private final String reponame;
    private final String stainname;
    private final String image;      // image basename, e.g. "HE", "warthinstarry"
    private final String titleEN;
    private final String titleTR;
    private final String organEN;
    private final String speciality;
    private final String type;       // "published" / "unpublished"
    private final String dziUrl;
    private final String thumbUrl;

    public AtlasCase(String reponame, String stainname, String image, String titleEN,
                     String titleTR, String organEN, String speciality, String type,
                     String dziUrl, String thumbUrl) {
        this.reponame = nz(reponame);
        this.stainname = nz(stainname);
        this.image = image == null || image.isBlank() ? "HE" : image;
        this.titleEN = nz(titleEN);
        this.titleTR = nz(titleTR);
        this.organEN = nz(organEN);
        this.speciality = nz(speciality);
        this.type = nz(type);
        this.dziUrl = nz(dziUrl);
        this.thumbUrl = nz(thumbUrl);
    }

    public String getReponame() {
        return reponame;
    }

    public String getImage() {
        return image;
    }

    public String getOrganEN() {
        return organEN;
    }

    public boolean isPublished() {
        return type.equalsIgnoreCase("published");
    }

    /** Display title: English preferred, then Turkish, then the slug; stain appended if useful. */
    public String getTitle() {
        String base = !titleEN.isBlank() ? titleEN
                : (!titleTR.isBlank() ? titleTR : prettify(stainname.isBlank() ? reponame : stainname));
        if (!image.equalsIgnoreCase("HE")) {
            String a = alnum(base);
            String b = alnum(image);
            if (!b.isEmpty() && !a.contains(b))
                base = base + " (" + image + ")";
        }
        return base;
    }

    public String getCategory() {
        String cat = AtlasCatalog.normalizeCategory(speciality, organEN, titleEN, reponame);
        if (cat.equals("Uncategorized") && AtlasCatalog.looksLikeStain(image, stainname))
            return "Techniques & stains";
        return cat;
    }

    public String getDziUrl() {
        return dziUrl;
    }

    public URI getDziURI() {
        return URI.create(dziUrl);
    }

    /** Web viewer (OpenSeadragon) page. */
    public String getViewerUrl() {
        return dziUrl.endsWith(".dzi") ? dziUrl.substring(0, dziUrl.length() - 4) + ".html" : dziUrl;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String alnum(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String prettify(String slug) {
        if (slug == null || slug.isBlank())
            return "";
        String s = slug.replace('-', ' ').replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AtlasCase other))
            return false;
        return dziUrl.equals(other.dziUrl);
    }

    @Override
    public int hashCode() {
        return dziUrl.hashCode();
    }

    @Override
    public String toString() {
        return getTitle();
    }
}
