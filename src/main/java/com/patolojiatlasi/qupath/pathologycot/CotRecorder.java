package com.patolojiatlasi.qupath.pathologycot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;
import com.patolojiatlasi.qupath.quiz.QuizSlide;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

/**
 * The start/stop recorder for <b>authored</b> Pathology-CoT takes: the JavaFX shell around
 * {@link CotRecording}, {@link CotRecordingGate} and {@link CotRecordingIO}.
 *
 * <h2>What it is for</h2>
 * Generating chain-of-thought training data used to require flagging a whole project as a research
 * project, which starts silent blinded recording on every open and only flushes when the project
 * closes — then picking the right hashed fragment file out of {@code atlas-focus/} by hand. That is
 * the correct shape for an unaware-reader study and the wrong shape for an author who wants to
 * demonstrate their reasoning on one slide. This recorder is the deliberate counterpart: press
 * record, navigate while thinking aloud, press stop, and land directly in the CoT draft.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li><b>Never concurrent with blinded recording.</b> Both directions are refused by
 *       {@link RecordingModeGuard}; {@link FocusHeatmap#setStartVeto} covers blinded's four start
 *       sites from one place.</li>
 *   <li><b>Bound at start, not read at stop.</b> The slide URI, title, dimensions and objective
 *       power are captured once in {@link #start()}. By the time a take stops — possibly from a
 *       project-change listener or the JVM shutdown hook — the viewer may show something else
 *       entirely. (Same lesson as {@code FocusHeatmap}'s {@code blindedDir}, which is captured at
 *       start for exactly this reason.)</li>
 *   <li><b>One slide per take.</b> A slide change stops the take rather than continuing into the
 *       next slide, so a take's path always belongs to one image.</li>
 *   <li><b>Crash-safe.</b> A checkpoint is written every ~30 s and from a JVM shutdown hook, both
 *       to the take's own folder outside the project.</li>
 *   <li><b>Honest dwell.</b> Time is credited only while the gate is open and only up to
 *       {@link CotRecordingGate#MAX_CREDIT_MS} per tick; every closed interval is recorded as a
 *       pause, so "looked away" is distinguishable from "stared".</li>
 * </ul>
 *
 * <p>JavaFX thread only, except the shutdown hook, which writes synchronously on its own thread and
 * touches only {@code volatile} state (mirroring {@code FocusHeatmap}'s hook discipline).
 */
public final class CotRecorder {

    private static final Logger logger = LoggerFactory.getLogger(CotRecorder.class);

    /** Checkpoint cadence: 120 ticks × 250 ms ≈ 30 s, as blinded recording uses. */
    private static final int CHECKPOINT_EVERY_TICKS = 120;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Identifier and version of the instruction text shown to the author at start, hashed into
     * every take's provenance. Bump {@link #PROTOCOL_VERSION} whenever {@link #PROTOCOL_TEXT}
     * changes, so a later analyst can tell which prompt a take was answering.
     */
    static final String PROTOCOL_ID = "atlas-cot-demo";
    static final String PROTOCOL_VERSION = "1";

    /**
     * The exact instruction shown before recording starts. Its SHA-256 goes into the take, so this
     * string is data, not just UI copy — editing it must come with a {@link #PROTOCOL_VERSION} bump.
     */
    static final String PROTOCOL_TEXT =
            "Bu kayıt bir GÖSTERİM kaydıdır: tanıya nasıl vardığınızı göstermek için slaytta "
                    + "gezinin. Baktığınız her bölge ve oradaki büyütme kaydedilir; fare konumu da "
                    + "kaydedilir. Ekranda kırmızı bir KAYIT göstergesi görünür. Kaydı bitirdiğinizde "
                    + "her durak için gerekçenizi yazacağınız taslak açılır.\n\n"
                    + "Not: bu kayıt, farkında olmayan bir okuyucunun doğal gezinmesini ölçen "
                    + "araştırma (gizli) kaydından ayrı tutulur ve ayrı bir klasöre yazılır.";

    private final QuPathGUI qupath;
    private final FocusHeatmap focusHeatmap;
    private final SimpleBooleanProperty recording = new SimpleBooleanProperty(false);
    private final CotRecordingHud hud = new CotRecordingHud();

    /** Cursor position over the bound viewer, in image pixels; {@code (-1,-1)} when off-viewer. */
    private int lastMouseImageX = -1;
    private int lastMouseImageY = -1;

    /** Viewer the cursor filters are currently attached to, or {@code null}. FX thread only. */
    private QuPathViewer mouseViewer;

    private final javafx.event.EventHandler<javafx.scene.input.MouseEvent> mouseMovedHandler = e -> {
        QuPathViewer v = mouseViewer;
        if (v == null) {
            resetMousePosition();
            return;
        }
        try {
            java.awt.geom.Point2D p = v.componentPointToImagePoint(e.getX(), e.getY(), null, true);
            lastMouseImageX = (int) Math.round(p.getX());
            lastMouseImageY = (int) Math.round(p.getY());
        } catch (Exception ex) {
            resetMousePosition();
        }
    };
    private final javafx.event.EventHandler<javafx.scene.input.MouseEvent> mouseExitedHandler =
            e -> resetMousePosition();

    private Timeline timer;
    private QuPathViewer boundViewer;
    private ImageData<BufferedImage> boundImageData;
    private long startWallMs;
    private long lastTickMs;
    private int ticks;

    /** Pause bookkeeping, extracted so the state machine is testable. FX thread only. */
    private final CotPauseLedger pauses = new CotPauseLedger();

    // volatile: the JVM shutdown hook thread reads these.
    private volatile CotRecording current;
    private volatile File currentFile;
    private boolean shutdownHookAdded;

    public CotRecorder(QuPathGUI qupath, FocusHeatmap focusHeatmap) {
        this.qupath = qupath;
        this.focusHeatmap = focusHeatmap;
    }

    /** True while an authored take is in progress. Bind a {@code CheckMenuItem} to this. */
    public ReadOnlyBooleanProperty recordingProperty() {
        return recording;
    }

    public boolean isRecording() {
        return recording.get();
    }

    /** The refusal message blinded recording should show, or {@code null} when it may start. */
    public String blindedStartVeto() {
        return RecordingModeGuard.canStartBlinded(isRecording()).message();
    }

    // --- start ---------------------------------------------------------------

    /**
     * Shows the protocol notice and, on confirmation, begins a take on the active viewer.
     * No-op (beyond a message) when the mode firewall refuses.
     */
    public void start() {
        if (isRecording())
            return;

        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> imageData = viewer == null ? null : viewer.getImageData();
        RecordingModeGuard.Verdict verdict = RecordingModeGuard.canStartAuthoring(
                focusHeatmap.isBlinded(), focusHeatmap.isTrackingSuppressed(), imageData != null);
        if (!verdict.allowed()) {
            info("Kayıt başlatılamadı", verdict.message());
            return;
        }

        Alert notice = new Alert(Alert.AlertType.CONFIRMATION, PROTOCOL_TEXT);
        notice.setTitle("CoT demo kaydı");
        notice.setHeaderText("Gösterim kaydı başlatılsın mı?");
        notice.getDialogPane().setMinWidth(560);
        if (qupath.getStage() != null)
            notice.initOwner(qupath.getStage());
        var choice = notice.showAndWait();
        if (choice.isEmpty() || choice.get() != javafx.scene.control.ButtonType.OK)
            return;

        // Re-check after the modal: the world may have changed while the dialog was open.
        viewer = qupath.getViewer();
        imageData = viewer == null ? null : viewer.getImageData();
        verdict = RecordingModeGuard.canStartAuthoring(
                focusHeatmap.isBlinded(), focusHeatmap.isTrackingSuppressed(), imageData != null);
        if (!verdict.allowed()) {
            info("Kayıt başlatılamadı", verdict.message());
            return;
        }

        String slideUrl = QuizSlide.currentSlideUrl(viewer);
        String slideTitle = PathologyCotActions.slideTitleOf(imageData, slideUrl);
        int width = 0;
        int height = 0;
        Double baseMag = null;
        try {
            var server = imageData.getServer();
            width = server.getWidth();
            height = server.getHeight();
            double mag = server.getMetadata().getMagnification();
            if (!Double.isNaN(mag) && mag > 0)
                baseMag = mag;
        } catch (Exception e) {
            logger.debug("Could not read slide geometry/magnification: {}", e.getMessage());
        }

        CotRecording rec = new CotRecording(CotRecordingIO.newSessionId(), slideUrl, slideTitle,
                PathologyCotIO.anonymizedKeyForOpenSlide(slideUrl), width, height, baseMag,
                CotRecordingIO.today(), PROTOCOL_ID, PROTOCOL_VERSION, protocolSha256());

        File dir = CotRecordingIO.takeDir(CotRecordingIO.defaultRoot(), slideTitle,
                LocalDateTime.now().format(STAMP));
        try {
            CotRecordingIO.ensureDir(dir);
        } catch (Exception e) {
            error("Kayıt klasörü oluşturulamadı:\n" + dir + "\n\n" + e.getMessage());
            return;
        }

        current = rec;
        currentFile = CotRecordingIO.recordingFile(dir);
        boundViewer = viewer;
        boundImageData = imageData;
        startWallMs = System.currentTimeMillis();
        lastTickMs = startWallMs;
        ticks = 0;

        attachMouseTracking(viewer);
        hud.attach(viewer);
        hud.update(0L, 0, false);

        timer = new Timeline(new KeyFrame(Duration.millis(CotRecordingGate.SAMPLE_MS), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();

        if (!shutdownHookAdded) {
            shutdownHookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown, "atlas-cot-shutdown"));
        }

        recording.set(true);
        logger.info("Authored CoT take started: {} -> {}", rec.sessionId(), currentFile);
    }

    // --- sampling ------------------------------------------------------------

    private void tick() {
        CotRecording rec = current;
        if (rec == null)
            return;

        QuPathViewer v = boundViewer;
        // A slide change (or a closed viewer) ends the take rather than silently continuing into
        // whatever is open now: one take must describe one image. Deferred out of the Timeline pulse
        // because stop() can show a modal, and showAndWait() inside a pulse throws. The timer is
        // stopped right here, before scheduling, so no further tick can queue a second stop; and the
        // deferred lambda re-checks that THIS take is still the current one, so a late stop can
        // never terminate a take the user has already restarted.
        if (v == null || v.getImageData() != boundImageData) {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            CotRecording stopping = rec;
            CotRecording.EndReason why = v == null
                    ? CotRecording.EndReason.VIEWER_CLOSED : CotRecording.EndReason.SLIDE_CHANGED;
            Platform.runLater(() -> {
                if (current == stopping)
                    stop(why, true);
            });
            return;
        }

        long now = System.currentTimeMillis();
        CotRecordingGate.Decision d = CotRecordingGate.decide(now, lastTickMs,
                mainStageFocused(), focusHeatmap.isTrackingSuppressed(), v.getImageData() != null);

        // Pause bookkeeping. Both edges are pinned to tick boundaries by CotPauseLedger so that
        // pause intervals and the gate's credited windows tile the take without overlapping — see
        // that class's javadoc for why "now" is the wrong edge on both sides.
        boolean gateOpen = d.pauseReason() == null || d.pauseReason() == CotRecording.PauseReason.GAP;
        if (!gateOpen) {
            record(rec, pauses.closedTick(lastTickMs, d.pauseReason()));
        } else {
            record(rec, pauses.openTick(lastTickMs));
            if (d.pauseReason() == CotRecording.PauseReason.GAP)
                record(rec, pauses.gap(CotRecordingGate.gapPauseStartMs(lastTickMs), now));
        }
        lastTickMs = now;

        if (d.emit()) {
            try {
                java.awt.Shape shape = v.getDisplayedRegionShape();
                if (shape != null) {
                    java.awt.Rectangle b = shape.getBounds();
                    int dsMilli = (int) Math.round(v.getDownsampleFactor() * 1000);
                    boolean added = rec.addPoint(relative(now),
                            (int) Math.round(b.getX() + b.getWidth() / 2.0),
                            (int) Math.round(b.getY() + b.getHeight() / 2.0),
                            (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()),
                            dsMilli, lastMouseImageX, lastMouseImageY, d.creditedMs());
                    if (!added) {
                        logger.info("Authored CoT take hit the {}-point cap; stopping.",
                                CotRecording.MAX_PATH_POINTS);
                        Platform.runLater(() -> stop(CotRecording.EndReason.POINT_CAP, true));
                        return;
                    }
                }
            } catch (Exception e) {
                logger.debug("Skipped a CoT sample: {}", e.getMessage());
            }
        }

        hud.update(rec.creditedMs(), rec.pointCount(), pauses.isPaused());

        ticks++;
        if (ticks % CHECKPOINT_EVERY_TICKS == 0)
            writeQuietly(rec);
    }

    /** Forwards a ledger-emitted interval (or nothing) to the take, converting to its time base. */
    private void record(CotRecording rec, long[] interval) {
        if (rec == null || interval == null)
            return;
        rec.addPause(relative(interval[0]), relative(interval[1]),
                CotRecording.PauseReason.values()[(int) interval[2]]);
    }

    /** Milliseconds since the take began — the path's own time base, never wall clock. */
    private long relative(long wallMs) {
        return wallMs - startWallMs;
    }

    // --- stop ----------------------------------------------------------------

    /**
     * Ends the take, writes it, and — when {@code offerDraft} — offers to build the CoT draft
     * straight from memory, with no file picker and no slide-key confirmation.
     *
     * <p>Must not be called with {@code offerDraft == true} from a context that cannot nest a modal
     * dialog (a {@link Timeline} pulse, or the {@code projectProperty} listener, which already runs
     * {@code showAndWait} of its own). Those callers pass {@code false} or wrap in
     * {@link Platform#runLater}.
     */
    public void stop(CotRecording.EndReason reason, boolean offerDraft) {
        if (!isRecording())
            return;

        if (timer != null) {
            timer.stop();
            timer = null;
        }
        long now = System.currentTimeMillis();
        CotRecording rec = current;
        record(rec, pauses.stop(now));
        hud.detach();
        attachMouseTracking(null);
        recording.set(false);
        boundViewer = null;
        boundImageData = null;

        if (rec == null)
            return;
        rec.setEndReason(reason == null ? CotRecording.EndReason.USER : reason);

        File file = currentFile;
        File takeDir = file == null ? null : file.getParentFile();
        // An empty take is not written at all, and its folder is removed again: a zero-point
        // recording.json is not data, and leaving one behind would litter the take root with
        // folders for every mis-click. The check therefore precedes the write.
        boolean written = false;
        if (!rec.isEmpty())
            written = writeQuietly(rec);
        else
            discardEmptyTakeDir(takeDir);
        current = null;
        currentFile = null;

        logger.info("Authored CoT take stopped ({}): {} points, {} ms credited",
                rec.endReason(), rec.pointCount(), rec.creditedMs());

        if (rec.isEmpty()) {
            info("Kayıt boş", "Bu kayıtta hiç örnek toplanmadı "
                    + "(pencere odakta değilken ya da slayt kapalıyken süre işlenmez).");
            return;
        }
        if (!offerDraft)
            return;

        String summary = String.format(Locale.US,
                "Kayıt bitti: %d nokta, %d sn işlenmiş süre (%d sn duraklama).%s\n\nTaslak şimdi oluşturulsun mu?",
                rec.pointCount(), rec.creditedMs() / 1000L, rec.pausedMs() / 1000L,
                written && file != null ? "\n\nKayıt dosyası:\n" + file : "");
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, summary);
        ask.setTitle("CoT demo kaydı");
        ask.setHeaderText("Kayıt tamamlandı");
        ask.getDialogPane().setMinWidth(560);
        if (qupath.getStage() != null)
            ask.initOwner(qupath.getStage());
        var choice = ask.showAndWait();
        if (choice.isPresent() && choice.get() == javafx.scene.control.ButtonType.OK)
            PathologyCotActions.draftFromTake(qupath, rec, takeDir);
    }

    /** Stops a take without any dialog — for the non-interactive project-change path. */
    public void stopSilently(CotRecording.EndReason reason) {
        if (!isRecording())
            return;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        long now = System.currentTimeMillis();
        CotRecording rec = current;
        record(rec, pauses.stop(now));
        hud.detach();
        attachMouseTracking(null);
        recording.set(false);
        boundViewer = null;
        boundImageData = null;
        if (rec != null) {
            rec.setEndReason(reason == null ? CotRecording.EndReason.PROJECT_CHANGED : reason);
            writeQuietly(rec);
            logger.info("Authored CoT take flushed silently ({}): {} points", rec.endReason(), rec.pointCount());
        }
        current = null;
        currentFile = null;
    }

    /**
     * Project-change hook. Called from {@code AtlasExtension.onProjectChanged} <b>before</b> any
     * {@code startBlinded()}, so an authored take is always flushed before blinded recording can
     * claim the viewer. Silent by contract: that listener runs synchronously around a consent
     * {@code showAndWait}, and nesting another modal inside it would re-enter the event loop
     * mid-transition.
     */
    public void onProjectChanged() {
        stopSilently(CotRecording.EndReason.PROJECT_CHANGED);
    }

    // --- persistence ---------------------------------------------------------

    /** Deletes a take folder that never received a recording. Only ever called when empty. */
    private void discardEmptyTakeDir(File dir) {
        if (dir == null)
            return;
        try {
            String[] names = dir.list();
            if (names != null && names.length == 0 && !dir.delete())
                logger.debug("Could not remove empty take folder {}", dir);
        } catch (Exception e) {
            logger.debug("Could not remove empty take folder {}: {}", dir, e.getMessage());
        }
    }

    private boolean writeQuietly(CotRecording rec) {
        File file = currentFile;
        if (rec == null || file == null)
            return false;
        try {
            CotRecordingIO.write(file, rec);
            return true;
        } catch (Exception e) {
            logger.warn("Could not write authored CoT take to {}: {}", file, e.getMessage());
            return false;
        }
    }

    /**
     * Shutdown-hook body: writes synchronously on the hook thread, reading only {@code volatile}
     * fields. No FX calls — the toolkit may already be gone.
     */
    private void flushOnShutdown() {
        CotRecording rec = current;
        File file = currentFile;
        if (rec == null || file == null || rec.isEmpty())
            return;
        try {
            // Only claim SHUTDOWN if nothing else already ended the take: stop() sets its own reason
            // before clearing `current`, so an unconditional set here could overwrite USER with
            // SHUTDOWN in that window and misreport a complete take as a truncated one.
            if (rec.endReason() == null)
                rec.setEndReason(CotRecording.EndReason.SHUTDOWN);
            CotRecordingIO.write(file, rec);
            logger.info("Authored CoT take flushed on shutdown: {}", file);
        } catch (Exception e) {
            logger.warn("Could not flush authored CoT take on shutdown: {}", e.getMessage());
        }
    }

    // --- small helpers -------------------------------------------------------

    /**
     * Moves the cursor event filters onto {@code v} (or detaches them for {@code null}), mirroring
     * {@code FocusHeatmap.attachMouseTracking} exactly — including its own {@code mouseViewer} field,
     * so detaching never depends on the order in which {@code boundViewer} is cleared.
     */
    private void attachMouseTracking(QuPathViewer v) {
        if (v == mouseViewer)
            return;
        if (mouseViewer != null) {
            try {
                javafx.scene.Node node = mouseViewer.getView();
                node.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, mouseMovedHandler);
                node.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, mouseMovedHandler);
                node.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, mouseExitedHandler);
            } catch (Exception e) {
                logger.debug("Could not detach CoT cursor tracking: {}", e.getMessage());
            }
        }
        mouseViewer = v;
        resetMousePosition();
        if (v == null)
            return;
        try {
            javafx.scene.Node node = v.getView();
            // Filters, not handlers, so this never competes with the viewer's own pan/zoom/tool
            // handlers for event consumption. MOUSE_DRAGGED keeps the position live while panning.
            node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, mouseMovedHandler);
            node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, mouseMovedHandler);
            node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, mouseExitedHandler);
        } catch (Exception e) {
            logger.debug("Could not attach CoT cursor tracking: {}", e.getMessage());
        }
    }

    private void resetMousePosition() {
        lastMouseImageX = -1;
        lastMouseImageY = -1;
    }

    private boolean mainStageFocused() {
        var stage = qupath.getStage();
        return stage != null && stage.isFocused();
    }

    static String protocolSha256() {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(PROTOCOL_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private void info(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setTitle(header);
        alert.setHeaderText(header);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle("Hata");
        alert.setHeaderText("Hata");
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }
}
