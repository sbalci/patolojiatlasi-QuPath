package com.patolojiatlasi.qupath;

import java.net.URI;
import java.util.Locale;

import qupath.lib.images.ImageData;

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
    private final double mpp;         // pixel size in microns/px; 0 = unknown

    public AtlasCase(String reponame, String stainname, String image, String titleEN,
                     String titleTR, String organEN, String speciality, String type,
                     String dziUrl, String thumbUrl) {
        this(reponame, stainname, image, titleEN, titleTR, organEN, speciality, type,
                dziUrl, thumbUrl, 0.0);
    }

    public AtlasCase(String reponame, String stainname, String image, String titleEN,
                     String titleTR, String organEN, String speciality, String type,
                     String dziUrl, String thumbUrl, double mpp) {
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
        this.mpp = mpp > 0 ? mpp : 0.0;
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

    /** Pixel size in microns/px, or 0 if unknown. */
    public double getMpp() {
        return mpp;
    }

    /**
     * The DZI URI QuPath opens. When a pixel size is known, it is appended as a {@code ?mpp=}
     * query so {@link com.patolojiatlasi.qupath.dzi.DziImageServer} applies µm/px calibration
     * (the atlas's DZI descriptors don't carry pixel size). Double-to-string is locale-safe.
     */
    public URI getDziURI() {
        if (mpp > 0) {
            String sep = dziUrl.indexOf('?') >= 0 ? "&" : "?";
            return URI.create(dziUrl + sep + "mpp=" + mpp);
        }
        return URI.create(dziUrl);
    }

    /**
     * QuPath image type inferred from the stain, so recognised slides open ready for analysis.
     * H&amp;E (the atlas default) → brightfield H&amp;E; a known histochemical/special stain →
     * brightfield other. Any other stain is left <b>unset</b> — the extension only assigns a type
     * it is confident about and never guesses IHC/DAB, so an unrecognised stain is left for QuPath
     * (or the user) to type.
     */
    public ImageData.ImageType getImageType() {
        return imageTypeForImageName(this.image);
    }

    /**
     * Shared stain→type heuristic behind {@link #getImageType()}, factored out so other callers
     * (e.g. the quiz slide opener, which only has a slide URL and no {@code AtlasCase}) can infer
     * a type from an image basename without duplicating the H&amp;E / special-stain logic.
     */
    public static ImageData.ImageType imageTypeForImageName(String imageBasename) {
        String s = (imageBasename == null ? "" : imageBasename).toLowerCase(Locale.ROOT);
        if (s.matches("h[&-]?e[0-9]*") || s.contains("hematoxylin"))
            return ImageData.ImageType.BRIGHTFIELD_H_E;
        if (isSpecialStain(s))
            return ImageData.ImageType.BRIGHTFIELD_OTHER;
        return ImageData.ImageType.UNSET;
    }

    /** Histochemical / special stains (not H&amp;E). Matched on the stain basename. */
    private static boolean isSpecialStain(String s) {
        String[] keys = {
                "pas", "giemsa", "mgg", "congo", "amyloid", "crystalviolet", "trichrome",
                "masson", "reticulin", "mucicarmine", "warthin", "starry", "grocott", "gms",
                "ziehl", "afb", "verhoeff", "elastic", "gieson", "evg", "perls", "prussian",
                "alcian", "mucin", "luxol", "cresyl", "fontana", "sudan", "toluidine",
                "papanicolaou", "silver"
        };
        for (String k : keys)
            if (s.contains(k))
                return true;
        return false;
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
