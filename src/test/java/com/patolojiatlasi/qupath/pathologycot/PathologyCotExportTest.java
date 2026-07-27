package com.patolojiatlasi.qupath.pathologycot;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.junit.jupiter.api.Test; import com.patolojiatlasi.qupath.quiz.*;
import qupath.lib.regions.ImagePlane; import qupath.lib.roi.ROIs;

class PathologyCotExportTest {
    @Test void conversationHasTurnsAndThumbnailCoords() {
        AtlasQuiz q = new AtlasQuiz(); q.setTitle("t");
        q.setDescription("Kaydedilen tanı/karar: benign");
        QuizQuestion s = new QuizQuestion(); s.setType(QuizType.NARRATION);
        s.setPrompt("1. 10x İnceleme (inspect)"); s.setExplanation("Reaktif germinal merkez.");
        QuizQuestion.Viewport vp = new QuizQuestion.Viewport(); vp.downsample = 4; vp.centerX=5000; vp.centerY=5000;
        s.setViewport(vp);
        s.setHighlightGeoJson(QuizGeometry.toGeoJson(
                ROIs.createRectangleROI(4000,4000,2048,2048, ImagePlane.getDefaultPlane())));
        q.getQuestions().add(s);
        var turns = PathologyCotExport.buildConversation(q, 1000, 800, 100.0); // thumbDs=100 → /100
        assertTrue(turns.stream().anyMatch(t -> "user".equals(t.get("role"))));
        assertTrue(turns.stream().anyMatch(t -> "assistant".equals(t.get("role"))));
        String all = turns.toString();
        assertTrue(all.contains("<inspect>[40,40,60,60]</inspect>")); // 4000/100..6048/100 → 40..60 (rounded)
        assertTrue(all.contains("<conclusion>"));
        assertTrue(all.contains("Reaktif germinal merkez"));
    }

    @Test void narrationStopWithoutHighlightIsNotReferenced() {
        // Regression test for Finding 1: a NARRATION stop with a null/blank highlightGeoJson must
        // not leave a dangling conversation.json reference to a crop file export() never writes.
        AtlasQuiz q = new AtlasQuiz(); q.setTitle("t");
        q.setDescription("Kaydedilen tanı/karar: benign");

        QuizQuestion s1 = new QuizQuestion(); s1.setType(QuizType.NARRATION);
        s1.setPrompt("1. 10x İnceleme (inspect)"); s1.setExplanation("Reaktif germinal merkez.");
        QuizQuestion.Viewport vp1 = new QuizQuestion.Viewport(); vp1.downsample = 4; vp1.centerX=5000; vp1.centerY=5000;
        s1.setViewport(vp1);
        s1.setHighlightGeoJson(QuizGeometry.toGeoJson(
                ROIs.createRectangleROI(4000,4000,2048,2048, ImagePlane.getDefaultPlane())));
        q.getQuestions().add(s1);

        QuizQuestion s2 = new QuizQuestion(); s2.setType(QuizType.NARRATION);
        s2.setPrompt("2. 20x İnceleme (inspect)"); s2.setExplanation("İkinci alan, işaretlenmemiş.");
        QuizQuestion.Viewport vp2 = new QuizQuestion.Viewport(); vp2.downsample = 4; vp2.centerX=6000; vp2.centerY=6000;
        s2.setViewport(vp2);
        // highlightGeoJson intentionally left null (optional field) — no drawn highlight for this stop.
        q.getQuestions().add(s2);

        var turns = PathologyCotExport.buildConversation(q, 1000, 800, 100.0);
        String all = turns.toString();
        assertTrue(all.contains("box_1"));
        assertFalse(all.contains("box_2"));
        assertFalse(all.contains("cyto_box_2"));
    }
}
