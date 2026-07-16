package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;

import org.junit.jupiter.api.Test;

import qupath.lib.images.ImageData.ImageType;

class AtlasCaseTest {

    private static AtlasCase sample(String dziUrl) {
        return new AtlasCase("repo", "stain", "HE", "Title EN", "Başlık TR",
                "Colon", "GI", "published", dziUrl, "");
    }

    /** A case with a given stain basename and pixel size. */
    private static AtlasCase withStain(String image, double mpp) {
        return new AtlasCase("repo", "stain", image, "Title EN", "Başlık TR",
                "Colon", "GI", "published", "https://images.patolojiatlasi.com/c/" + image + ".dzi", "", mpp);
    }

    @Test
    void equalWhenSameDziUrl() {
        AtlasCase a = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        AtlasCase b = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferentDziUrl() {
        AtlasCase a = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        AtlasCase b = sample("https://images.patolojiatlasi.com/case2/HE.dzi");
        assertNotEquals(a, b);
    }

    @Test
    void basketDedupsBySlide() {
        LinkedHashSet<AtlasCase> basket = new LinkedHashSet<>();
        basket.add(sample("https://images.patolojiatlasi.com/case1/HE.dzi"));
        basket.add(sample("https://images.patolojiatlasi.com/case1/HE.dzi"));
        assertEquals(1, basket.size());
    }

    @Test
    void imageTypeHandEForHematoxylinEosin() {
        assertEquals(ImageType.BRIGHTFIELD_H_E, withStain("HE", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_H_E, withStain("HE1", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_H_E, withStain("H&E", 0).getImageType());
        // Blank stain defaults to "HE" in the constructor.
        assertEquals(ImageType.BRIGHTFIELD_H_E, withStain("", 0).getImageType());
    }

    @Test
    void imageTypeUnsetForUnrecognizedStains() {
        // IHC markers and anything else not clearly H&E or a special stain are left unset —
        // the extension never guesses IHC/DAB.
        assertEquals(ImageType.UNSET, withStain("cerbB2", 0).getImageType());
        assertEquals(ImageType.UNSET, withStain("ki67", 0).getImageType());
        assertEquals(ImageType.UNSET, withStain("her2", 0).getImageType());
        assertEquals(ImageType.UNSET, withStain("somethingUnknown", 0).getImageType());
    }

    @Test
    void imageTypeOtherForSpecialStains() {
        assertEquals(ImageType.BRIGHTFIELD_OTHER, withStain("pas", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_OTHER, withStain("congored", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_OTHER, withStain("warthinstarry", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_OTHER, withStain("crystalviolet", 0).getImageType());
        assertEquals(ImageType.BRIGHTFIELD_OTHER, withStain("giemsa", 0).getImageType());
    }

    @Test
    void dziUriAppendsMppWhenKnown() {
        AtlasCase c = withStain("HE", 0.25);
        assertTrue(c.getDziURI().toString().endsWith("HE.dzi?mpp=0.25"),
                "expected ?mpp= appended, got " + c.getDziURI());
    }

    @Test
    void dziUriPlainWhenNoMpp() {
        AtlasCase c = withStain("HE", 0);
        assertEquals("https://images.patolojiatlasi.com/c/HE.dzi", c.getDziURI().toString());
        assertEquals(0.0, c.getMpp());
    }

    @Test
    void dziUriUsesAmpersandWhenQueryAlreadyPresent() {
        AtlasCase c = new AtlasCase("repo", "stain", "HE", "t", "",
                "Colon", "GI", "published", "https://x/HE.dzi?v=2", "", 0.5);
        assertEquals("https://x/HE.dzi?v=2&mpp=0.5", c.getDziURI().toString());
    }
}
