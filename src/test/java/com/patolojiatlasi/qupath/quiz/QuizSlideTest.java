package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QuizSlideTest {
    @Test void dziUrlsClassifyAtlas() {
        assertTrue(QuizSlide.isAtlasDziUrl("https://images.patolojiatlasi.com/x/y.dzi"));
        assertTrue(QuizSlide.isAtlasDziUrl("https://h/x/y.dzi?mpp=0.25"));      // query stripped
        assertTrue(QuizSlide.isAtlasDziUrl("HTTPS://H/Y.DZI"));                 // case-insensitive
    }
    @Test void localAndOtherUrisClassifyNonAtlas() {
        assertFalse(QuizSlide.isAtlasDziUrl("file:/C:/slides/case1.svs"));
        assertFalse(QuizSlide.isAtlasDziUrl("file:///home/u/case1.ndpi"));
        assertFalse(QuizSlide.isAtlasDziUrl(null));
        assertFalse(QuizSlide.isAtlasDziUrl(""));
        assertFalse(QuizSlide.isAtlasDziUrl("https://h/x/y.tiff"));
    }
}
