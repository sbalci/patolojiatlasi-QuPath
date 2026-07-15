package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashSet;

import org.junit.jupiter.api.Test;

class AtlasCaseTest {

    private static AtlasCase sample(String dziUrl) {
        return new AtlasCase("repo", "stain", "HE", "Title EN", "Başlık TR",
                "Colon", "GI", "published", dziUrl, "");
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
}
