package com.patolojiatlasi.qupath.quiz;

import java.util.Collection;
import java.util.Locale;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.objects.PathObject;

/**
 * Measurement-only scoring of a learner's drawn geometry against a stop's reference geometry.
 * Hit criterion: IoU > {@link #HIT_IOU} OR either geometry covers the other (the same inclusive
 * criterion used by the Pathology-CoT exporter's behaviour matching). Inspired by the examination
 * proof-of-concept described (but not shipped) in Yli-Hallila et al., J Anat 2025;246(5):846-856,
 * doi:10.1111/joa.14172 (QuPath Edu). Clean-room: no code from that project is used.
 */
public final class QuizScoring {

    public static final double HIT_IOU = 0.3;

    public record Score(double iou, boolean containment, boolean hit) {}

    private QuizScoring() {}

    /** IoU + containment + hit for two JTS geometries; null when either is null/empty/invalid. */
    public static Score score(Geometry learner, Geometry reference) {
        if (learner == null || reference == null || learner.isEmpty() || reference.isEmpty())
            return null;
        try {
            double inter = learner.intersection(reference).getArea();
            double union = learner.union(reference).getArea();
            double iou = union <= 0 ? 0.0 : inter / union;
            boolean containment = learner.covers(reference) || reference.covers(learner);
            return new Score(iou, containment, iou > HIT_IOU || containment);
        } catch (Exception ex) {          // TopologyException etc. -> no score rather than a crash
            return null;
        }
    }

    /** Union of the annotation ROI geometries of {@code objects}; null when none usable. */
    public static Geometry unionOf(Collection<PathObject> objects) {
        Geometry union = null;
        if (objects == null) return null;
        for (PathObject po : objects) {
            try {
                if (po == null || po.getROI() == null) continue;
                Geometry g = po.getROI().getGeometry();
                if (g == null || g.isEmpty()) continue;
                union = union == null ? g : union.union(g);
            } catch (Exception ignore) { /* skip unusable geometry */ }
        }
        return union;
    }

    /** Turkish, measurement-only score line; Locale.US numerals. */
    public static String formatScoreLine(Score s) {
        if (s == null) return "";
        String pct = String.format(Locale.US, "%.1f", s.iou() * 100.0);
        if (s.hit()) {
            String why = s.containment() && !(s.iou() > HIT_IOU)
                    ? "kapsama" : "IoU > " + String.format(Locale.US, "%.1f", HIT_IOU);
            return "Çiziminiz: IoU %" + pct + " — isabet (" + why + ").";
        }
        return "Çiziminiz: IoU %" + pct + " — isabet yok (eşik IoU "
                + String.format(Locale.US, "%.1f", HIT_IOU) + " ya da kapsama).";
    }
}
