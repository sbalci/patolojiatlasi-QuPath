package com.patolojiatlasi.qupath.pathologycot;

import com.patolojiatlasi.qupath.quiz.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.util.*;

/** Builds a reviewable guided-tour {@link AtlasQuiz} from discretized {@link Behavior}s. Each stop
 *  is a NARRATION with the ROI captured as a viewport + highlight box; the pathologist fills the
 *  per-ROI rationale (the stop's {@code explanation}) during review in the existing tour author. */
public final class PathologyCotDraft {
    private PathologyCotDraft() {}

    public static AtlasQuiz buildDraft(List<Behavior> behaviors, String slideUrl, String slideTitle,
                                       Double baseMag, Map<String,Object> decision) {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setTitle("Pathology-CoT taslağı — " + (slideTitle == null ? "" : slideTitle));
        String dx = decision == null ? null : Objects.toString(decision.get("diagnosis"), null);
        quiz.setDescription(dx == null || dx.isBlank()
                ? "Kaydedilen gezinmeden üretilen inceleme/yakın-bakış dizisi. Her durak için gerekçe ekleyin."
                : "Kaydedilen tanı/karar: " + dx + "\n\nHer durak için 'neden baktım / ne gördüm' gerekçesini ekleyin.");
        quiz.setAllowBack(true);
        List<QuizQuestion> qs = quiz.getQuestions();
        int i = 1;
        for (Behavior b : behaviors) {
            QuizQuestion q = new QuizQuestion();
            q.setId("cot" + System.nanoTime() + "_" + i);
            q.setType(QuizType.NARRATION);
            q.setSlideUrl(slideUrl); q.setSlideTitle(slideTitle);
            q.setPrompt(label(b, i));
            q.setExplanation("");   // manual: filled during review
            QuizQuestion.Viewport vp = new QuizQuestion.Viewport();
            vp.centerX = b.centerX(); vp.centerY = b.centerY();
            vp.downsample = downsampleFor(b, baseMag);
            q.setViewport(vp);
            ROI box = ROIs.createRectangleROI(b.x(), b.y(), b.w(), b.h(), ImagePlane.getDefaultPlane());
            q.setHighlightGeoJson(QuizGeometry.toGeoJson(box));
            qs.add(q); i++;
        }
        return quiz;
    }

    private static String label(Behavior b, int i) {
        String kind = b.type() == Behavior.Type.PEEK ? "Yakın bakış (peek)" : "İnceleme (inspect)";
        String mag = b.magBin() == null ? "" : b.magBin() + " ";
        return i + ". " + mag + kind;
    }
    /** downsample the runner should fly to: baseMag/bin (e.g. 10x on 40x → 4.0); fallback 1.0/4.0. */
    private static double downsampleFor(Behavior b, Double baseMag) {
        if (b.type() == Behavior.Type.PEEK) return 1.0;   // peek = near-native res; magBin is a label, not a resize instruction
        if (baseMag != null && b.magBin() != null) {
            int m = PathologyCotDiscretizer.magIntSafe(b.magBin());
            if (m > 0) return baseMag / m;
        }
        return 4.0;
    }
}
