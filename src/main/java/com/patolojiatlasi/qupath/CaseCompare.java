package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

/** Opens all stains of one atlas case into QuPath's synchronized multi-viewer grid. */
public final class CaseCompare {

    private static final Logger logger = LoggerFactory.getLogger(CaseCompare.class);

    /** Grid tops out at 2x3 ({@link #gridFor(int)}), so at most this many stains are opened. */
    private static final int MAX_PANELS = 6;

    private CaseCompare() {}

    /**
     * All stains of the case that owns {@code openDziUrl} (matched by DZI URL, ignoring any
     * {@code ?mpp=} query), with the open slide first, deduped, order stable. Empty if the URL
     * isn't a catalogue slide; a single-element list if its case has only that stain (or a blank
     * reponame, which can't be grouped).
     */
    public static List<AtlasCase> siblingStains(List<AtlasCase> catalog, String openDziUrl) {
        if (catalog == null || openDziUrl == null || openDziUrl.isBlank())
            return List.of();
        String openBase = stripQuery(openDziUrl);
        AtlasCase open = null;
        for (AtlasCase a : catalog) {
            if (stripQuery(a.getDziUrl()).equals(openBase)) {
                open = a;
                break;
            }
        }
        if (open == null)
            return List.of();
        String repo = open.getReponame();
        if (repo == null || repo.isBlank())
            return List.of(open);
        List<AtlasCase> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        result.add(open);
        seen.add(stripQuery(open.getDziUrl()));
        for (AtlasCase a : catalog) {
            if (!repo.equals(a.getReponame()))
                continue;
            if (seen.add(stripQuery(a.getDziUrl())))
                result.add(a);
        }
        return result;
    }

    /** {rows, cols} for {@code n} panels: 1×1, 1×2, 1×3, 2×2, else 2×3; n clamped to [1,6]. */
    public static int[] gridFor(int n) {
        int c = Math.max(1, Math.min(6, n));
        return switch (c) {
            case 1 -> new int[]{1, 1};
            case 2 -> new int[]{1, 2};
            case 3 -> new int[]{1, 3};
            case 4 -> new int[]{2, 2};
            default -> new int[]{2, 3};
        };
    }

    static String stripQuery(String url) {
        if (url == null)
            return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    /**
     * Opens every stain of the atlas case currently shown in {@code qupath}'s active viewer into
     * QuPath's synchronized multi-viewer grid — one viewer per stain, pan/zoom linked. Must be
     * called on the JavaFX application thread (e.g. from a menu action); only the DZI network
     * build for each stain runs off-thread.
     * <p>
     * No-ops (with an informational alert) if the active viewer isn't showing a cataloged atlas
     * slide, or if that slide's case has only one stain. If <b>any</b> currently-open viewer holds
     * an image with unsaved changes, asks for confirmation first, since the compare grid replaces
     * the content of every viewer in it. Stains beyond {@link #MAX_PANELS} are dropped (logged how
     * many); a per-stain open failure is logged, but does not abort the other panels.
     * <p>
     * Viewer fill order follows {@link qupath.lib.gui.viewer.ViewerManager#getAllViewers()}, which
     * grows column-major as the grid is resized (e.g. a 2x2 fills top-left, bottom-left, top-right,
     * bottom-right) — so the open slide (always first from {@link #siblingStains}) reliably lands
     * in the first viewer, but sibling stains do not necessarily read left-to-right, top-to-bottom.
     */
    public static void compareCurrentCase(QuPathGUI qupath) {
        if (qupath == null)
            return;

        QuPathViewer active = qupath.getViewer();
        ImageData<BufferedImage> activeData = active == null ? null : active.getImageData();
        if (activeData == null) {
            infoAlert("Bu bir atlas slaytı değil ya da katalogda bulunamadı.");
            return;
        }

        String openUrl = firstUri(activeData);
        if (openUrl == null || openUrl.isBlank()) {
            infoAlert("Bu bir atlas slaytı değil ya da katalogda bulunamadı.");
            return;
        }

        List<AtlasCase> stains = siblingStains(AtlasCatalog.loadBundled(), openUrl);
        if (stains.isEmpty()) {
            infoAlert("Bu bir atlas slaytı değil ya da katalogda bulunamadı.");
            return;
        }
        if (stains.size() == 1) {
            infoAlert("Bu vakada karşılaştırılacak başka boya yok.");
            return;
        }

        boolean anyUnsaved = false;
        for (QuPathViewer vv : qupath.getViewerManager().getAllViewers()) {
            ImageData<BufferedImage> d = vv.getImageData();
            if (d != null && isChangedSafe(d)) {
                anyUnsaved = true;
                break;
            }
        }
        if (anyUnsaved && !confirmReplace())
            return;

        int dropped = 0;
        if (stains.size() > MAX_PANELS) {
            dropped = stains.size() - MAX_PANELS;
            stains = new ArrayList<>(stains.subList(0, MAX_PANELS));
        }

        int[] grid = gridFor(stains.size());
        boolean resized = qupath.getViewerManager().setGridSize(grid[0], grid[1]);
        if (!resized) {
            // QuPath itself already shows a warning notification (e.g. too many open viewers
            // for the requested grid); bail out before touching sync/opening panels into what
            // would still be the old, unrelated grid layout.
            logger.warn("Case compare: setGridSize({}, {}) did not resize the viewer grid; aborting.",
                    grid[0], grid[1]);
            return;
        }

        List<QuPathViewer> viewers = qupath.getViewerManager().getAllViewers();
        int n = Math.min(stains.size(), viewers.size());
        for (int i = 0; i < n; i++) {
            openInto(viewers.get(i), stains.get(i));
        }

        qupath.getViewerManager().setSynchronizeViewers(true);
        if (!viewers.isEmpty())
            qupath.getViewerManager().setActiveViewer(viewers.get(0));

        if (dropped > 0) {
            logger.info("Case compare: {} stain(s) dropped, grid caps at {} panels.", dropped, MAX_PANELS);
        }
    }

    /**
     * Collapses the multi-viewer grid back to a single viewer and turns off pan/zoom sync.
     * <p>
     * {@link qupath.lib.gui.viewer.ViewerManager#resetGridSize()} only redistributes divider
     * <em>positions</em> — it does not remove rows/columns — so an actual collapse requires
     * closing every viewer except the active one first (via {@link QuPathGUI#closeViewer}, which
     * prompts to save unsaved changes exactly as QuPath's own "close viewer" action does), and
     * only then resizing the grid to 1x1. If the user cancels a save-changes prompt for one of
     * the other viewers, the collapse stops there — sync stays off, but the grid is left as-is
     * rather than force-closing unsaved work.
     */
    public static void backToSingle(QuPathGUI qupath) {
        if (qupath == null)
            return;
        qupath.getViewerManager().setSynchronizeViewers(false);

        QuPathViewer keep = qupath.getViewerManager().getActiveViewer();
        List<QuPathViewer> viewers = new ArrayList<>(qupath.getViewerManager().getAllViewers());
        if (keep == null && !viewers.isEmpty())
            keep = viewers.get(0);

        for (QuPathViewer v : viewers) {
            if (v == keep)
                continue;
            if (!qupath.closeViewer(v)) {
                logger.warn("Case compare: user cancelled closing a viewer; grid left as-is (sync off).");
                return;
            }
        }
        qupath.getViewerManager().setGridSize(1, 1);
    }

    /**
     * Opens {@code c} into {@code viewer} on a background daemon thread, mirroring
     * {@code AtlasBrowser}'s no-project open path: builds the {@link DziImageServer} +
     * {@link ImageData} off the JavaFX thread, then hands the result to {@code viewer} inside
     * {@link Platform#runLater}. A failure at either stage is logged; it does not affect any
     * other viewer's open.
     */
    static void openInto(QuPathViewer viewer, AtlasCase c) {
        Thread t = new Thread(() -> {
            try {
                DziImageServer server = new DziImageServer(c.getDziURI());
                ImageData<BufferedImage> imageData = new ImageData<>(server, c.getImageType());
                Platform.runLater(() -> {
                    try {
                        viewer.setImageData(imageData);
                    } catch (Exception ex) {
                        reportOpenFailure(c, ex);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> reportOpenFailure(c, ex));
            }
        }, "atlas-compare-open-" + c.getReponame());
        t.setDaemon(true);
        t.start();
    }

    /** Logs a failed per-stain open. Runs on the FX thread (called only from within Platform.runLater). */
    private static void reportOpenFailure(AtlasCase c, Exception ex) {
        logger.error("Case compare: failed to open {} ({}): {}", c.getTitle(), c.getReponame(), ex.getMessage(), ex);
    }

    /**
     * The active viewer's server's first URI as a string, or {@code null} if the image data, its
     * server, or its URIs are unavailable.
     */
    private static String firstUri(ImageData<BufferedImage> data) {
        try {
            ImageServer<BufferedImage> server = data.getServer();
            if (server == null)
                return null;
            var uris = server.getURIs();
            if (uris == null || uris.isEmpty())
                return null;
            return uris.iterator().next().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@link ImageData#isChanged()}, swallowing any exception to {@code false} — used only to
     * decide whether to show the unsaved-changes confirmation, so an unexpected failure here
     * should fail open (skip the extra prompt) rather than block the compare.
     */
    static boolean isChangedSafe(ImageData<BufferedImage> d) {
        try {
            return d.isChanged();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Asks the user to confirm replacing viewer content that has unsaved changes; {@code true} if
     * they chose to proceed. Used before rebuilding the compare grid, since
     * {@link QuPathViewer#setImageData} does not itself prompt to save.
     */
    private static boolean confirmReplace() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Karşılaştırma görünümü, görüntüleyicideki görüntülerin yerini alır; "
                + "kaydedilmemiş değişiklikler kaybolabilir. Devam edilsin mi?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static void infoAlert(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }
}
