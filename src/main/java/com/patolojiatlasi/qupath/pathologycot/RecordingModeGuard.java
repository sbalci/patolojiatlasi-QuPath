package com.patolojiatlasi.qupath.pathologycot;

/**
 * The one place that decides whether a recording mode may start. Pure, so the whole transition
 * table is unit-testable.
 *
 * <p>The extension has two recorders that both sample the viewer, and <b>at most one may be active
 * at any instant</b>:
 * <ul>
 *   <li><b>BLINDED</b> — {@code focus.FocusHeatmap}'s silent research recording. The live study
 *       instrument: an unaware reader, no on-screen indicator, naturalistic data.</li>
 *   <li><b>AUTHORING</b> — {@link CotRecorder}'s Pathology-CoT take. A deliberate, visibly
 *       indicated demonstration by an aware author.</li>
 * </ul>
 *
 * <p>Both directions are refused, not silently preferred, because either silent outcome corrupts
 * data: starting authoring during a blinded session would make the blinded reader aware (reactivity
 * — the primary threat the blinded design names), and starting blinded recording during an
 * authored take would mix a demonstration into the study cohort. The authoring recorder also
 * refuses to start while a programmatic viewer sweep holds {@code FocusHeatmap.suppressTracking},
 * since the viewport would then be moving on its own and the "author's" path would be the sweep's.
 */
public final class RecordingModeGuard {

    private RecordingModeGuard() {
    }

    /**
     * Whether a start is permitted, plus the Turkish message to show when it is not.
     *
     * @param allowed whether the caller may proceed
     * @param message user-facing refusal reason, or {@code null} when {@code allowed}
     */
    public record Verdict(boolean allowed, String message) {

        private static final Verdict OK = new Verdict(true, null);

        static Verdict ok() {
            return OK;
        }

        static Verdict no(String message) {
            return new Verdict(false, message);
        }
    }

    /**
     * May an authored Pathology-CoT take start now?
     *
     * @param blindedActive      {@code FocusHeatmap.isBlinded()}
     * @param trackingSuppressed {@code FocusHeatmap.isTrackingSuppressed()}
     * @param imageOpen          whether a slide is open in the active viewer
     */
    public static Verdict canStartAuthoring(boolean blindedActive, boolean trackingSuppressed, boolean imageOpen) {
        if (blindedActive)
            return Verdict.no("Araştırma (gizli) gezinme kaydı çalışıyor. CoT demo kaydı için önce onu durdurun.");
        if (trackingSuppressed)
            return Verdict.no("Otomatik tarama sürüyor; görüntü kendiliğinden hareket ettiği için kayıt başlatılamaz.");
        if (!imageOpen)
            return Verdict.no("Önce bir slayt açın.");
        return Verdict.ok();
    }

    /**
     * May blinded research recording start now? Called as a veto from
     * {@code FocusHeatmap.startBlinded()} so every one of its start sites is covered by one check.
     *
     * @param authoringActive whether an authored take is in progress
     */
    public static Verdict canStartBlinded(boolean authoringActive) {
        if (authoringActive)
            return Verdict.no("CoT demo kaydı çalışıyor. Araştırma kaydı için önce onu durdurun.");
        return Verdict.ok();
    }
}
