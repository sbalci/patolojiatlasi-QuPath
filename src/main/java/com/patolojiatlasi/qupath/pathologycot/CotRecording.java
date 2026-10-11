package com.patolojiatlasi.qupath.pathologycot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One <b>authored</b> Pathology-CoT take: the pathologist deliberately pressed record, navigated a
 * slide to demonstrate their reasoning, then pressed stop.
 *
 * <p><b>This is not the blinded study instrument.</b> {@code focus.FocusHeatmap}'s blinded
 * recording captures an <i>unaware</i> reader naturalistically; this class captures an <i>aware</i>
 * author producing a demonstration. The two must never be mixed in one analysis, so an authored
 * take carries its own schema string ({@link CotRecordingIO#SCHEMA}), its own session-id namespace
 * ({@code auth-<uuid>}), its own folder, and the provenance block below. See
 * {@code docs/superpowers/2026-10-11-cot-authoring-recorder.md} § "Observer effect".
 *
 * <p>Deliberately free of JavaFX and QuPath imports so the whole model is unit-testable off the FX
 * thread (same discipline as {@code focus.FocusMap}). The FX shell that drives it is
 * {@link CotRecorder}.
 *
 * <p><b>Threading.</b> The sampling {@code Timeline} on the JavaFX thread is the only writer, but
 * {@link CotRecorder}'s JVM shutdown hook serializes a take from its own thread while that timer may
 * still be running — so every mutator and every accessor here is {@code synchronized}. Without it,
 * {@link #pathSnapshot()} could hit a {@code ConcurrentModificationException} mid-append and the
 * hook would lose everything since the last checkpoint. The snapshots are also defensive copies, so
 * a serialization pass never shares an array with the live take.
 */
public final class CotRecording {

    /** Why sampling was not credited for an interval. */
    public enum PauseReason {
        /** QuPath's main window did not have focus (same gate blinded recording uses). */
        UNFOCUSED,
        /** A programmatic viewer sweep held {@code FocusHeatmap.suppressTracking(...)}. */
        SUPPRESSED,
        /**
         * No image was open in the bound viewer. Part of {@link CotRecordingGate}'s contract rather
         * than a state {@link CotRecorder} can currently reach: that shell ends the take outright
         * when the bound viewer's image changes or disappears, so this reason only arises if the
         * gate is ever driven by a shell that keeps recording across an empty viewer.
         */
        NO_IMAGE,
        /** The sampling timer itself stalled (a tick arrived far later than the sample interval). */
        GAP
    }

    /** How the take ended — recorded so a short or truncated take is never read as a complete one. */
    public enum EndReason {
        /** The author pressed stop. */
        USER,
        /** The bound viewer's image changed. */
        SLIDE_CHANGED,
        /** The viewer was closed. */
        VIEWER_CLOSED,
        /** The active project changed or closed. */
        PROJECT_CHANGED,
        /** The JVM shut down with a take in progress (flushed by the shutdown hook). */
        SHUTDOWN,
        /** {@link #MAX_PATH_POINTS} was reached. */
        POINT_CAP,
        /** Blinded research recording was requested, so authoring yielded to it. */
        YIELDED_TO_BLINDED
    }

    /**
     * Hard cap on recorded scanpath points for one take — a safety bound against an unbounded list,
     * not an expected ceiling. At the 250 ms sample interval this is ~83 minutes of credited
     * sampling. Mirrors {@code FocusHeatmap.MAX_PATH_POINTS} so an authored path can never be
     * larger than a blinded one.
     */
    public static final int MAX_PATH_POINTS = 20_000;

    private final String sessionId;
    private final String slideUrl;
    private final String slideTitle;
    private final String slideKey;
    private final int imageWidth;
    private final int imageHeight;
    private final Double baseMagnification;
    private final String startedDate;
    private final String protocolId;
    private final String protocolVersion;
    private final String protocolSha256;

    private final List<int[]> path = new ArrayList<>();
    private final List<long[]> pauses = new ArrayList<>();

    private long creditedMs;
    private EndReason endReason;
    private boolean pathCapped;

    /**
     * @param sessionId          anonymous id for this take; {@link CotRecordingIO#newSessionId()}
     *                           namespaces it as {@code auth-<uuid>} so it can never collide with a
     *                           blinded session id
     * @param slideUrl           the <b>raw</b> slide URI, captured once at start (never re-read at
     *                           stop, when the viewer may already show something else). An authored
     *                           take is explicitly non-anonymous about its own slide — but see
     *                           {@link CotRecordingIO} for where that file may and may not go
     * @param slideTitle         human-readable slide name for the draft's title
     * @param slideKey           the same anonymized key a blinded fragment carries
     *                           ({@link PathologyCotIO#anonymizedKeyForOpenSlide}), so an authored
     *                           take can still be matched to an open slide without the raw URI
     * @param imageWidth         slide width in pixels at full resolution
     * @param imageHeight        slide height in pixels at full resolution
     * @param baseMagnification  objective power, or {@code null} when the server reports none (every
     *                           DZI atlas slide reports none — see {@code dzi.DziImageServer})
     * @param startedDate        ISO date (day precision, no wall-clock time) the take was made
     * @param protocolId         identifier of the instruction text shown to the author at start
     * @param protocolVersion    version of that instruction text
     * @param protocolSha256     SHA-256 of that exact instruction text, so a later analyst can tell
     *                           which prompt the author was answering
     */
    public CotRecording(String sessionId, String slideUrl, String slideTitle, String slideKey,
                        int imageWidth, int imageHeight, Double baseMagnification, String startedDate,
                        String protocolId, String protocolVersion, String protocolSha256) {
        this.sessionId = sessionId;
        this.slideUrl = slideUrl;
        this.slideTitle = slideTitle;
        this.slideKey = slideKey;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.baseMagnification = baseMagnification;
        this.startedDate = startedDate;
        this.protocolId = protocolId;
        this.protocolVersion = protocolVersion;
        this.protocolSha256 = protocolSha256;
    }

    public String sessionId() { return sessionId; }
    public String slideUrl() { return slideUrl; }
    public String slideTitle() { return slideTitle; }
    public String slideKey() { return slideKey; }
    public int imageWidth() { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    public Double baseMagnification() { return baseMagnification; }
    public String startedDate() { return startedDate; }
    public String protocolId() { return protocolId; }
    public String protocolVersion() { return protocolVersion; }
    public String protocolSha256() { return protocolSha256; }

    /** Total credited (gate-open, dt-capped) milliseconds — never wall-clock elapsed time. */
    public synchronized long creditedMs() { return creditedMs; }
    public synchronized EndReason endReason() { return endReason; }
    public synchronized void setEndReason(EndReason endReason) { this.endReason = endReason; }
    public synchronized int pointCount() { return path.size(); }
    public synchronized boolean isPathCapped() { return pathCapped; }
    public synchronized boolean isEmpty() { return path.isEmpty(); }

    /**
     * Appends one scanpath point, in the <b>exact</b> 8-element layout
     * {@code [tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY]} that
     * {@code FocusHeatmap.tickBlinded} writes for schema {@code atlas-focus-contribution/5}.
     * Keeping the layout identical is what lets {@link PathologyCotDiscretizer} consume an authored
     * take with no branch of its own.
     *
     * @return {@code false} once {@link #MAX_PATH_POINTS} is reached (the point is dropped)
     */
    public synchronized boolean addPoint(long tRelMs, int centerX, int centerY, int width, int height,
                            int dsMilli, int mouseX, int mouseY, long creditedMs) {
        if (path.size() >= MAX_PATH_POINTS) {
            pathCapped = true;
            return false;
        }
        path.add(new int[]{(int) tRelMs, centerX, centerY, width, height, dsMilli, mouseX, mouseY});
        this.creditedMs += Math.max(0L, creditedMs);
        return true;
    }

    /**
     * Records a closed interval during which sampling was not credited. Stored (rather than merely
     * omitted) so an analyst can distinguish "the author looked away" from "the author stared" —
     * the distinction the blinded recorder cannot make, because its path simply has a hole.
     */
    public synchronized void addPause(long startMs, long endMs, PauseReason reason) {
        if (endMs <= startMs || reason == null)
            return;
        pauses.add(new long[]{startMs, endMs, reason.ordinal()});
    }

    /** Defensive copy of the scanpath, safe to serialize while sampling continues. */
    public synchronized List<int[]> pathSnapshot() {
        List<int[]> copy = new ArrayList<>(path.size());
        for (int[] p : path)
            copy.add(p.clone());
        return copy;
    }

    /** Defensive copy of the pause intervals. */
    public synchronized List<long[]> pauseSnapshot() {
        List<long[]> copy = new ArrayList<>(pauses.size());
        for (long[] p : pauses)
            copy.add(p.clone());
        return copy;
    }

    /** Summed duration of all recorded pause intervals, in milliseconds. */
    public synchronized long pausedMs() {
        long total = 0L;
        for (long[] p : pauses)
            total += p[1] - p[0];
        return total;
    }

    /**
     * The provenance block written into every authored file and propagated into the behaviors
     * sidecar. These fields exist so an authored take can never be mistaken for naturalistic
     * reading data: {@code captureIntent=demonstration} is the honest framing of what an aware
     * author produces (see the design doc's "Observer effect" section).
     */
    public synchronized Map<String, Object> provenance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordingMode", "authored");
        m.put("captureIntent", "demonstration");
        m.put("recordingIndicatorVisible", Boolean.TRUE);
        m.put("protocolId", protocolId);
        m.put("protocolVersion", protocolVersion);
        m.put("protocolSha256", protocolSha256);
        m.put("endReason", endReason == null ? null : endReason.name().toLowerCase(java.util.Locale.ROOT));
        m.put("creditedMs", creditedMs);
        m.put("pausedMs", pausedMs());
        m.put("pointCount", path.size());
        m.put("pathCapped", pathCapped);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Adapts this take to the {@link CotFragment} the discretizer and draft builder already consume,
     * so the authored path reuses the whole existing pipeline unchanged.
     *
     * <p>The fragment's {@code decision} map carries the provenance block under a
     * {@code cotProvenance} key rather than a diagnosis, because an authored take has no blinded
     * decision record; {@link PathologyCotDraft} reads only {@code diagnosis} from that map, so a
     * provenance-only map yields the neutral draft description.
     */
    public synchronized CotFragment toFragment() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("cotProvenance", provenance());
        return new CotFragment(slideKey, sessionId, imageWidth, imageHeight, baseMagnification,
                pathSnapshot(), decision);
    }
}
