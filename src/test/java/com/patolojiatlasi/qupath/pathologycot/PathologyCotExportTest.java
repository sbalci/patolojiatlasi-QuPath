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
}
