package com.patolojiatlasi.qupath.pathologycot;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.viewer.QuPathViewer;

import java.util.Locale;

/**
 * The on-screen "recording" indicator for an authored Pathology-CoT take: a red dot plus elapsed
 * credited time and point count, pinned to the top-left of the viewer.
 *
 * <p><b>Why an indicator is mandatory here and forbidden next door.</b> Blinded research recording
 * promises the opposite — {@code README.md} states there is "no on-screen indication of any kind",
 * because an awareness cue is the primary threat to that design's validity. An authored take is the
 * mirror image: the author must be able to see at a glance that a take is running, so they never
 * narrate into a recording that stopped, or demonstrate while believing nothing is being captured.
 *
 * <p>The HUD can never appear during blinded recording for a structural reason, not a conditional
 * one: it is created only by {@link CotRecorder#start()}, and {@link RecordingModeGuard} refuses to
 * let an authored take begin while blinded recording is active. There is no code path on which a
 * blinded session owns a HUD instance.
 *
 * <p>Attached to {@link QuPathViewer#getView()} (a {@link StackPane}) and {@linkplain
 * javafx.scene.Node#setMouseTransparent(boolean) mouse-transparent}, so it overlays the slide
 * without intercepting a single pan, zoom or drawing event. It is a child of the viewer's own pane
 * rather than a separate window on purpose: a floating control window that takes focus would trip
 * the main-stage focus gate the recorder shares with {@code FocusHeatmap}, silently pausing the very
 * recording it is reporting on.
 */
final class CotRecordingHud {

    private static final Logger logger = LoggerFactory.getLogger(CotRecordingHud.class);

    private final HBox box;
    private final Label label;
    private StackPane host;

    CotRecordingHud() {
        Circle dot = new Circle(5, Color.web("#d64545"));
        label = new Label();
        label.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
        box = new HBox(6, dot, label);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 9, 4, 9));
        box.setStyle("-fx-background-color: rgba(20,20,20,0.78); -fx-background-radius: 11;");
        box.setMouseTransparent(true);
        update(0L, 0, false);
    }

    /** Adds the HUD to {@code viewer}'s view pane. Best-effort: a failure must never stop a take. */
    void attach(QuPathViewer viewer) {
        detach();
        if (viewer == null)
            return;
        try {
            javafx.scene.Node view = viewer.getView();
            if (view instanceof StackPane pane) {
                pane.getChildren().add(box);
                StackPane.setAlignment(box, Pos.TOP_LEFT);
                StackPane.setMargin(box, new Insets(10, 0, 0, 10));
                host = pane;
            } else {
                logger.debug("Viewer view is not a StackPane ({}); recording HUD not shown.",
                        view == null ? "null" : view.getClass().getName());
            }
        } catch (Exception e) {
            logger.debug("Could not attach recording HUD: {}", e.getMessage());
        }
    }

    /** Removes the HUD. Idempotent, so both the normal stop and a failure path may call it. */
    void detach() {
        if (host == null)
            return;
        try {
            host.getChildren().remove(box);
        } catch (Exception e) {
            logger.debug("Could not detach recording HUD: {}", e.getMessage());
        } finally {
            host = null;
        }
    }

    /**
     * Refreshes the readout.
     *
     * @param creditedMs credited (not wall-clock) milliseconds so far — the same number that ends up
     *                   in the take, so what the author sees is what was recorded
     * @param points     scanpath points captured so far
     * @param paused     whether the gate is currently closed (window unfocused, no image, or a
     *                   programmatic sweep in progress)
     */
    void update(long creditedMs, int points, boolean paused) {
        long totalSeconds = Math.max(0L, creditedMs) / 1000L;
        String text = String.format(Locale.US, "%s  %02d:%02d  ·  %d nokta",
                paused ? "DURAKLADI" : "KAYIT", totalSeconds / 60L, totalSeconds % 60L, points);
        label.setText(text);
    }
}
