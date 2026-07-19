package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;

/** Opens an atlas case in a second viewer beside the user's own slide, at matched magnification. */
public final class BenchReference {

    private static final Logger logger = LoggerFactory.getLogger(BenchReference.class);

    /** Single floating control window, lazily built and rebound on every {@link #openBeside}. */
    private static ReferenceControlWindow controlWindow;

    private BenchReference() {}

    /**
     * Downsample for the reference viewer so it shows the same µm/px on screen as your viewer:
     * {@code yourDs * mppYours / mppRef}. {@link Double#NaN} if any input is non-positive (unknown
     * calibration → caller skips matching).
     */
    public static double matchedDownsample(double yourDs, double mppYours, double mppRef) {
        if (yourDs <= 0 || mppYours <= 0 || mppRef <= 0)
            return Double.NaN;
        return yourDs * mppYours / mppRef;
    }

    /**
     * Opens {@code ref} beside whatever is showing in {@code qupath}'s active viewer.
     * <p>
     * If there is no active viewer, shows an info alert and does nothing. If the active viewer is
     * empty (no image open yet), there is nothing to be "beside" — {@code ref} is opened directly
     * into that one viewer (no second viewer, no magnification match, no control window).
     * Otherwise a new viewer is added beside the active one via
     * {@link qupath.lib.gui.viewer.ViewerManager#addColumn}, {@code ref} is streamed into it on a
     * background thread, its magnification is matched to the active viewer's, and a small
     * floating "Referans" control window is shown bound to the two viewers.
     * <p>
     * The new viewer is found by diffing {@code getAllViewers()} before/after {@code addColumn}.
     * This is reliable for the common case (a single active viewer, growing 1x1 → 1x2), but if
     * the grid was already multi-<b>row</b> (e.g. left over from a prior multi-viewer session),
     * {@code addColumn} adds one new viewer per existing row — only the first of those is picked
     * up as {@code refViewer}, the rest are added to the grid but left unmanaged by this control
     * window. A pre-existing multi-<b>column</b>, single-row grid is unaffected (there is exactly
     * one new viewer either way). Re-running {@link #openBeside} while a reference is already
     * open similarly rebinds the single control window to the newest pair, leaving any earlier
     * reference viewer open but no longer reachable from a "Referansı kapat" button.
     * <p>
     * Must be called on the JavaFX application thread (e.g. from a menu action or dialog); only
     * the DZI network build for the reference slide runs off-thread.
     */
    public static void openBeside(QuPathGUI qupath, AtlasCase ref) {
        if (qupath == null || ref == null)
            return;

        QuPathViewer active = qupath.getViewer();
        if (active == null) {
            infoAlert("Açık bir görüntüleyici yok.");
            return;
        }

        if (active.getImageData() == null) {
            // Degenerate case: nothing of yours is open yet, so there is no "beside" to match
            // against. Just show the reference in the one viewer that exists.
            openInto(active, ref, null, null);
            return;
        }

        Set<QuPathViewer> before = new HashSet<>(qupath.getViewerManager().getAllViewers());
        qupath.getViewerManager().addColumn(active);
        QuPathViewer refViewer = null;
        for (QuPathViewer v : qupath.getViewerManager().getAllViewers()) {
            if (!before.contains(v)) {
                refViewer = v;
                break;
            }
        }
        if (refViewer == null) {
            // addColumn refused (or the manager didn't grow) — nothing to open into.
            infoAlert("İkinci bir görüntüleyici açılamadı.");
            return;
        }

        QuPathViewer rv = refViewer;
        Runnable removeEmptyViewer = () -> {
            try {
                qupath.closeViewer(rv);
            } catch (Exception ignore) {
                // best-effort removal of the now-empty second viewer
            }
        };
        openInto(rv, ref, () -> {
            String status = matchMagnification(active, rv);
            showControlWindow(qupath, active, rv, status);
        }, removeEmptyViewer);
    }

    /**
     * Opens {@code ref} into {@code viewer} on a background daemon thread, mirroring
     * {@code AtlasBrowser}'s no-project open branch: builds the {@link DziImageServer} +
     * {@link ImageData} off the JavaFX thread, then hands the result to {@code viewer} inside
     * {@link Platform#runLater}, followed by {@code onSuccess} (also on the FX thread, run only
     * after {@code setImageData} succeeds). A failure at either stage is logged, reported via an
     * info alert on the FX thread, and — if given — {@code onFailure} is run so the caller can
     * clean up (e.g. remove an empty second viewer). Either callback may be {@code null}.
     */
    private static void openInto(QuPathViewer viewer, AtlasCase ref, Runnable onSuccess, Runnable onFailure) {
        Thread t = new Thread(() -> {
            try {
                DziImageServer server = new DziImageServer(ref.getDziURI());
                ImageData<BufferedImage> imageData = new ImageData<>(server, ref.getImageType());
                Platform.runLater(() -> {
                    try {
                        viewer.setImageData(imageData);
                        if (onSuccess != null)
                            onSuccess.run();
                    } catch (Exception ex) {
                        reportOpenFailure(ref, ex, onFailure);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> reportOpenFailure(ref, ex, onFailure));
            }
        }, "atlas-reference-open-" + ref.getReponame());
        t.setDaemon(true);
        t.start();
    }

    /** Logs + alerts a failed reference open and runs the caller's cleanup. Runs on the FX thread. */
    private static void reportOpenFailure(AtlasCase ref, Exception ex, Runnable onFailure) {
        logger.error("Bench reference: failed to open {} ({}): {}", ref.getTitle(), ref.getReponame(), ex.getMessage(), ex);
        infoAlert("Referans slayt açılamadı: " + ref.getTitle() + "\n\n" + ex.getMessage());
        if (onFailure != null)
            onFailure.run();
    }

    /**
     * Matches {@code refViewer}'s downsample so it shows the same on-screen µm/px as
     * {@code yourViewer}, when both are calibrated; otherwise leaves it untouched. Returns the
     * Turkish status text describing what happened, for a caller to display. FX thread.
     */
    private static String matchMagnification(QuPathViewer yourViewer, QuPathViewer refViewer) {
        double yourDs = yourViewer.getDownsampleFactor();
        double mppYours = averagedMpp(yourViewer);
        double mppRef = averagedMpp(refViewer);
        double ds = matchedDownsample(yourDs, mppYours, mppRef);
        if (!Double.isNaN(ds)) {
            refViewer.setDownsampleFactor(ds);
            return "Büyütme eşlendi";
        }
        return "Kalibrasyon bilinmiyor — büyütme eşlenemedi";
    }

    /**
     * The viewer's server's averaged pixel size in microns, or {@code 0} if the viewer, its image
     * data, its server, its pixel calibration, or a known pixel size is unavailable. Never throws.
     */
    private static double averagedMpp(QuPathViewer viewer) {
        try {
            ImageData<BufferedImage> data = viewer.getImageData();
            if (data == null)
                return 0;
            ImageServer<BufferedImage> server = data.getServer();
            if (server == null)
                return 0;
            PixelCalibration cal = server.getPixelCalibration();
            return cal != null && cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Shows (or rebinds + refocuses) the single floating "Referans" control window. FX thread. */
    private static void showControlWindow(QuPathGUI qupath, QuPathViewer yourViewer, QuPathViewer refViewer,
            String initialStatus) {
        if (controlWindow == null)
            controlWindow = new ReferenceControlWindow();
        controlWindow.bindAndShow(qupath, yourViewer, refViewer, initialStatus);
    }

    private static void infoAlert(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }

    /**
     * The small non-modal control window shown beside a just-opened reference viewer. A single
     * instance is reused across calls (see {@link #controlWindow}): each {@link #bindAndShow}
     * rebinds it to whichever (your, reference) viewer pair the latest {@link #openBeside} call
     * produced, so re-opening a reference reuses the same window rather than stacking new ones.
     */
    private static final class ReferenceControlWindow {

        private final Stage stage;
        private final Label statusLabel = new Label();
        private final CheckBox syncCheck = new CheckBox("Kaydırmayı da eşle (tam senkron)");

        private QuPathGUI qupath;
        private QuPathViewer yourViewer;
        private QuPathViewer refViewer;

        ReferenceControlWindow() {
            Button matchButton = new Button("Büyütmeyi eşle");
            matchButton.setOnAction(e -> statusLabel.setText(matchMagnification(yourViewer, refViewer)));

            syncCheck.selectedProperty().addListener((obs, was, now) -> {
                if (qupath != null)
                    qupath.getViewerManager().setSynchronizeViewers(now);
            });

            Button closeButton = new Button("Referansı kapat");
            closeButton.setOnAction(e -> closeReference());

            VBox root = new VBox(8, matchButton, syncCheck, closeButton, statusLabel);
            root.setPadding(new Insets(12));

            stage = new Stage();
            stage.setTitle("Referans");
            stage.setResizable(false);
            stage.setScene(new Scene(root));
            stage.setOnHidden(e -> {
                if (controlWindow == this)
                    controlWindow = null;
            });
        }

        /** Rebind to a (possibly new) viewer pair, set the initial status, and show/focus the window. */
        void bindAndShow(QuPathGUI qupath, QuPathViewer yourViewer, QuPathViewer refViewer, String initialStatus) {
            this.qupath = qupath;
            this.yourViewer = yourViewer;
            this.refViewer = refViewer;
            // Reflect whatever sync state QuPath is actually in (e.g. left on from an earlier
            // reference session) rather than defaulting the checkbox to unchecked and lying.
            syncCheck.setSelected(qupath.getViewerManager().getSynchronizeViewers());
            statusLabel.setText(initialStatus);
            stage.show();
            stage.toFront();
        }

        /**
         * Turns sync off, closes the reference viewer (prompting to save if it has unsaved
         * changes), then closes this window — unless the user cancels the save prompt, in which
         * case the reference viewer stays open and this window is left open too, so the user
         * isn't stranded without a way to retry or resync.
         * <p>
         * {@link QuPathGUI#closeViewer} only clears the viewer's image data (it calls
         * {@code viewer.resetImageData()}) — it does not remove the now-empty panel from the
         * grid, confirmed against the 0.6.0 source (the same finding {@code CaseCompare
         * .backToSingle} documents for its own close path). Collapsing back to a single viewer
         * via {@code setGridSize(1, 1)} afterwards is what actually returns the layout to how it
         * looked before {@link #openBeside} added the second panel; without it, "Referansı kapat"
         * would leave an empty grey panel behind instead of restoring the single-viewer view.
         */
        private void closeReference() {
            if (qupath != null) {
                qupath.getViewerManager().setSynchronizeViewers(false);
                syncCheck.setSelected(false);
                if (!qupath.closeViewer(refViewer))
                    return;
                if (!qupath.getViewerManager().setGridSize(1, 1)) {
                    // Only refuses if other open (servered) viewers remain beyond this pair (e.g.
                    // a pre-existing multi-viewer grid) — QuPath shows its own notification in
                    // that case; the reference viewer is already closed either way.
                    logger.warn("Bench reference: setGridSize(1, 1) did not collapse the grid "
                            + "after closing the reference viewer; leaving the grid as-is.");
                }
            }
            stage.close();
        }
    }
}
