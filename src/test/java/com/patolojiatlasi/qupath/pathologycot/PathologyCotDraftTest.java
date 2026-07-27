package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.junit.jupiter.api.Test;
import com.patolojiatlasi.qupath.quiz.*;

class PathologyCotDraftTest {
    @Test void oneNarrationStopPerBehaviour() {
        List<Behavior> bs = List.of(
            new Behavior(Behavior.Type.INSPECT,"10x", 3000,3000, 4096,4096, 0,1500,1500),
            new Behavior(Behavior.Type.PEEK,"40x", 6500,6500, 1024,1024, 3000,3250,250));
        AtlasQuiz q = PathologyCotDraft.buildDraft(bs, "https://x/y.dzi", "y",
                40.0, Map.of("diagnosis","benign"));
        assertEquals(2, q.getQuestions().size());
        QuizQuestion q0 = q.getQuestions().get(0);
        assertEquals(QuizType.NARRATION, q0.getType());
        assertEquals("", q0.getExplanation());            // blank for manual rationale
        assertNotNull(q0.getViewport());
        assertEquals(4.0, q0.getViewport().downsample, 1e-9);  // 40/10
        assertNotNull(q0.getHighlightGeoJson());
        assertTrue(q.getDescription().contains("benign"));
        assertTrue(q.isAllowBack());
    }

    @Test void peekAlwaysReconstructsToNativeDownsample() {
        // Regression for C1: a PEEK whose observed ds rounded to a magBin like "20x" must NOT have
        // its viewport downsample reconstructed from that bin (baseMag/20 = 2.0 on a 40x slide,
        // which PathologyCotExport.isPeek — downsample <= 1.5 — would then reclassify as inspect).
        // Peek is near-native res by definition; magBin is a display label only.
        List<Behavior> bs = List.of(
            new Behavior(Behavior.Type.PEEK, "20x", 6000,6000, 1024,1024, 0,300,300));
        AtlasQuiz q = PathologyCotDraft.buildDraft(bs, "https://x/y.dzi", "y", 40.0, Map.of());
        assertEquals(1, q.getQuestions().size());
        QuizQuestion.Viewport vp = q.getQuestions().get(0).getViewport();
        assertNotNull(vp);
        assertTrue(vp.downsample <= 1.5, "peek downsample must stay <= PEEK_DS_MAX, was " + vp.downsample);
        assertEquals(1.0, vp.downsample, 1e-9);
    }
}
