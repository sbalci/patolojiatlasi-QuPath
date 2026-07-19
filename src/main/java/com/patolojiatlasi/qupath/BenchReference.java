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

    /**
     * The single active reference session's control window, or {@code null} if no reference is
     * currently open. Doubles as the "is a reference session active?" flag consulted at the top
     * of {@link #openBeside}: non-null from the moment the reference viewer's control window is
     * first shown until the session is torn down (either via "Referansı kapat" or the window
     * being closed via the window manager), at which point it is set back to {@code null}.
     */
    private static ReferenceControlWindow controlWindow;

    /**
     * True from the moment {@link #openBeside} commits to opening a second viewer (set
     * immediately before {@code addColumn}) until that open either succeeds ({@link #controlWindow}
     * gets set / shown) or fails (the empty second viewer is removed, or the open-failure handler
     * runs). Guards the async gap — the background DZI load hasn't finished, so {@link
     * #controlWindow} is still {@code null} — during which a second {@link #openBeside} call would
     * otherwise slip past the {@code controlWindow} refuse-guard and add a leaked second column.
     * Never set by the degenerate empty-active-viewer branch (that open is effectively synchronous
     * and reuses the single existing viewer, so two rapid calls there are benign).
     */
    private static boolean opening;

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
     * one new viewer either way).
     * <p>
     * Only <b>one reference session is allowed at a time</b>. If a reference is already open
     * (the control window exists and hasn't been torn down), this method does <em>not</em>
     * re-derive an anchor from {@code qupath.getViewer()} — once the user has clicked into the
     * reference viewer, that call would return the reference itself rather than the user's own
     * slide, which previously caused a wrong magnification-match anchor, a control-window rebind
     * to the wrong pair, and the user's real slide becoming an orphaned, unreachable panel.
     * Instead, the existing control window is brought to front with a hint and nothing else
     * changes; the caller must close the current reference first.
     * <p>
     * Must be called on the JavaFX application thread (e.g. from a menu action or dialog); only
     * the DZI network build for the reference slide runs off-thread.
     */
    public static void openBeside(QuPathGUI qupath, AtlasCase ref) {
        if (qupath == null || ref == null)
            return;

        if (controlWindow != null || opening) {
            if (controlWindow != null) {
                // A reference session is already active: refuse the second open rather than
                // re-deriving the anchor from qupath.getViewer(), which may now be the reference
                // viewer itself if the user clicked into it.
                controlWindow.bringToFrontWithHint("Bir referans zaten açık — önce 'Referansı kapat' deyin.");
            } else {
                // A reference session is being opened but the background DZI load hasn't
                // finished yet, so controlWindow is still null — refuse anyway rather than
                // letting a second addColumn slip through and leak a column.
                infoAlert("Referans yükleniyor, lütfen bekleyin.");
            }
            return;
        }

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
        opening = true;
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
            opening = false;
            infoAlert("İkinci bir görüntüleyici açılamadı.");
            return;
        }

        QuPathViewer rv = refViewer;
        Runnable removeEmptyViewer = () -> {
            opening = false;
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
        // The reference load that set `opening = true` has now succeeded — clear it right where
        // controlWindow gets set / the control window is shown, so a subsequent openBeside is
        // refused via controlWindow (not opening) from here on.
        opening = false;
        if (controlWindow == null)
            controlWindow = new ReferenceControlWindow();
        controlWindow.bindAndShow(qupath, yourViewer, refViewer, initialStatus);
    }

    private static void infoAlert(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }

    /**
     * The small non-modal control window shown beside a just-opened reference viewer. Exactly one
     * instance is live per reference session (see {@link #controlWindow}): a fresh instance is
     * created only when no session is currently active, and it is discarded when the session is
     * torn down. A second {@link #openBeside} call made while a session is active never reaches
     * {@link #bindAndShow} — it is refused up front and routed to {@link #bringToFrontWithHint}
     * on the existing instance instead, so this window is never rebound to a different viewer
     * pair mid-session.
     */
    private static final class ReferenceControlWindow {

        private final Stage stage;
        private final Label statusLabel = new Label();
        private final CheckBox syncCheck = new CheckBox("Kaydırmayı da eşle (tam senkron)");

        private QuPathGUI qupath;
        private QuPathViewer yourViewer;
        private QuPathViewer refViewer;

        /**
         * Set once the session has been torn down (reference viewer closed, sync turned off,
         * grid collapsed) via either {@link #closeReference()} (the "Referansı kapat" button) or
         * {@code setOnCloseRequest} (a window-manager close). Guards against running that
         * teardown twice: {@link #closeReference()} itself calls {@code stage.close()}, which
         * fires {@code setOnHidden} (not {@code setOnCloseRequest}) — and {@code setOnHidden}
         * only clears the {@link BenchReference#controlWindow} session flag, so it never re-runs
         * viewer teardown regardless of which path set {@code disposed}. Also guards
         * {@code setOnCloseRequest} itself against re-entry if it somehow fired twice.
         */
        private boolean disposed;

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
            stage.setOnCloseRequest(e -> {
                // If the window is closed via the window manager (X / Alt+F4) rather than the
                // "Referansı kapat" button, closeReference() never ran — tear the session down
                // here instead, so the reference viewer and grid don't linger orphaned.
                // setOnCloseRequest (unlike setOnHidden) fires BEFORE the stage is hidden, so
                // consuming the event here can veto the close — required because closeViewer
                // may prompt to save unsaved annotations, and a cancelled prompt must leave the
                // reference viewer open and the session intact rather than clearing controlWindow
                // out from under a viewer that never actually closed.
                if (disposed)
                    return;
                if (qupath != null && !qupath.closeViewer(refViewer)) {
                    // User cancelled the save prompt: veto the close entirely — same contract as
                    // the button path in closeReference().
                    e.consume();
                    return;
                }
                if (qupath != null) {
                    qupath.getViewerManager().setSynchronizeViewers(false);
                    syncCheck.setSelected(false);
                    qupath.getViewerManager().setGridSize(1, 1);
                }
                disposed = true;
                // Not consumed: the close proceeds, hiding the stage and firing setOnHidden next.
            });
            stage.setOnHidden(e -> {
                // By the time this fires, either setOnCloseRequest already ran the teardown above
                // (X close) or closeReference() already ran it (button close, via stage.close(),
                // which fires this handler but NOT setOnCloseRequest). Either way the reference
                // viewer is already closed and torn down — this only needs to clear the session
                // flag, and does so idempotently for both paths.
                if (controlWindow == this)
                    controlWindow = null;
            });
        }

        /** Rebind to a (possibly new) viewer pair, set the initial status, and show/focus the window. */
        void bindAndShow(QuPathGUI qupath, QuPathViewer yourViewer, QuPathViewer refViewer, String initialStatus) {
            this.qupath = qupath;
            this.yourViewer = yourViewer;
            this.refViewer = refViewer;
            this.disposed = false;
            // Reflect whatever sync state QuPath is actually in (e.g. left on from an earlier
            // reference session) rather than defaulting the checkbox to unchecked and lying.
            syncCheck.setSelected(qupath.getViewerManager().getSynchronizeViewers());
            statusLabel.setText(initialStatus);
            stage.show();
            stage.toFront();
        }

        /**
         * Brings this already-active control window to the front and shows a hint explaining why
         * a second "open reference" request was refused. Touches no viewer state.
         */
        void bringToFrontWithHint(String hint) {
            statusLabel.setText(hint);
            stage.show();
            stage.toFront();
        }

        /**
         * Closes the reference viewer first (prompting to save if it has unsaved changes); only
         * if that succeeds does it turn sync off, collapse the grid, mark the session disposed,
         * and close this window. If the user cancels the save prompt, {@code closeViewer} returns
         * {@code false} and this method returns immediately — the reference viewer stays open,
         * sync is left exactly as it was, and this window stays open too, so nothing is lost and
         * the user isn't stranded without a way to retry or resync.
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
                if (!qupath.closeViewer(refViewer)) {
                    // User cancelled the save prompt: leave the reference viewer open, the sync
                    // state untouched, and this window open so nothing is lost and they can
                    // retry (or resync) once ready.
                    return;
                }
                qupath.getViewerManager().setSynchronizeViewers(false);
                syncCheck.setSelected(false);
                if (!qupath.getViewerManager().setGridSize(1, 1)) {
                    // Only refuses if other open (servered) viewers remain beyond this pair (e.g.
                    // a pre-existing multi-viewer grid) — QuPath shows its own notification in
                    // that case; the reference viewer is already closed either way.
                    logger.warn("Bench reference: setGridSize(1, 1) did not collapse the grid "
                            + "after closing the reference viewer; leaving the grid as-is.");
                }
            }
            disposed = true;
            stage.close();
        }
    }
}
