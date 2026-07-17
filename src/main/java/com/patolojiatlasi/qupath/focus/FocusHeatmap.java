package com.patolojiatlasi.qupath.focus;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.GsonBuilder;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.ImageRegion;

/**
 * Focus (dwell) heatmap. While active, it samples the active viewer's visible region a few times a
 * second and accumulates a per-slide {@link FocusMap}, shown as a translucent overlay — a record of
 * where the reader has looked (focused high-magnification viewing heats an area far faster than a
 * zoomed-out browse), useful for reviewing whether a whole slide was inspected and for studying
 * where pathologists focus.
 * <p>
 * Persistence is optional. By default maps live only for the session and are discarded; with
 * <em>Keep focus maps</em> enabled, each slide's map is written (JSON grid + PNG preview) when you
 * move to another slide, close it, or stop tracking, so it can be analysed later.
 * <p>
 * All viewer/overlay access happens on the JavaFX thread (the sampling timer runs there); only file
 * writes are pushed to a background thread.
 */
public final class FocusHeatmap {

    private static final Logger logger = LoggerFactory.getLogger(FocusHeatmap.class);

    private static final int GRID_MAX = 256;      // longest grid side, in cells
    private static final int SAMPLE_MS = 250;     // viewport sampling interval
    private static final int REFRESH_EVERY = 4;   // rebuild the overlay every N samples (~1s)
    private static final double OVERLAY_OPACITY = 0.5;

    private final QuPathGUI qupath;
    private final String user = System.getProperty("user.name", "unknown");

    private boolean active;
    private boolean keepMaps;

    private Timeline timer;
    private int ticksSinceRefresh;

    private QuPathViewer currentViewer;
    private ImageData<BufferedImage> currentImageData;
    private FocusMap currentMap;
    private String currentSlide;
    private String currentUri;
    private BufferedImageOverlay currentOverlay;

    public FocusHeatmap(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Build the "Odak ısı haritası" submenu with all controls wired up. */
    public Menu buildMenu() {
        CheckMenuItem trackItem = new CheckMenuItem("Görünür — izlemeyi aç/kapat");
        trackItem.selectedProperty().addListener((obs, was, now) -> setActive(now));

        MenuItem clearItem = new MenuItem("Temizle");
        clearItem.setOnAction(e -> clear());

        MenuItem saveItem = new MenuItem("Kaydet…");
        saveItem.setOnAction(e -> saveDialog());

        CheckMenuItem keepItem = new CheckMenuItem("Oturumdan sonra sakla (kalıcı)");
        keepItem.selectedProperty().addListener((obs, was, now) -> keepMaps = now);

        Menu menu = new Menu("Odak ısı haritası");
        menu.getItems().addAll(trackItem, clearItem, saveItem, new SeparatorMenuItem(), keepItem);
        return menu;
    }

    // --- tracking lifecycle -------------------------------------------------

    private void setActive(boolean on) {
        this.active = on;
        if (on) {
            if (timer == null) {
                timer = new Timeline(new KeyFrame(Duration.millis(SAMPLE_MS), e -> tick()));
                timer.setCycleCount(Animation.INDEFINITE);
            }
            ticksSinceRefresh = REFRESH_EVERY;
            timer.play();
        } else {
            if (timer != null)
                timer.stop();
            // Persist the in-progress map before hiding, if the user asked to keep maps.
            if (keepMaps && currentMap != null && !currentMap.isEmpty())
                save(currentSlide, currentUri, currentMap, defaultDir());
            removeOverlay();
        }
    }

    private void tick() {
        try {
            QuPathViewer v = qupath.getViewer();
            ImageData<BufferedImage> id = (v == null) ? null : v.getImageData();
            if (id != currentImageData)
                switchTo(v, id);
            if (v == null || currentMap == null)
                return;
            Shape shape = v.getDisplayedRegionShape();
            if (shape == null)
                return;
            Rectangle b = shape.getBounds();
            currentMap.deposit(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            if (++ticksSinceRefresh >= REFRESH_EVERY) {
                ticksSinceRefresh = 0;
                refreshOverlay(v);
            }
        } catch (Exception ex) {
            logger.debug("Focus heatmap tick failed: {}", ex.getMessage());
        }
    }

    /** Move to a new slide/viewer: persist the old map if requested, then start a fresh one. */
    private void switchTo(QuPathViewer v, ImageData<BufferedImage> id) {
        if (keepMaps && currentMap != null && !currentMap.isEmpty())
            save(currentSlide, currentUri, currentMap, defaultDir());
        removeOverlay();
        currentViewer = v;
        currentImageData = id;
        if (id != null && id.getServer() != null) {
            ImageServer<BufferedImage> server = id.getServer();
            currentMap = new FocusMap(server.getWidth(), server.getHeight(), GRID_MAX);
            currentSlide = server.getMetadata().getName();
            currentUri = firstUri(server);
        } else {
            currentMap = null;
            currentSlide = null;
            currentUri = null;
        }
        ticksSinceRefresh = REFRESH_EVERY;
    }

    private void refreshOverlay(QuPathViewer v) {
        if (currentMap == null || currentImageData == null || currentImageData.getServer() == null)
            return;
        ImageServer<BufferedImage> server = currentImageData.getServer();
        BufferedImage img = currentMap.toImage();
        ImageRegion region = ImageRegion.createInstance(0, 0, server.getWidth(), server.getHeight(), 0, 0);
        BufferedImageOverlay overlay = new BufferedImageOverlay(v.getOverlayOptions(), region, img);
        overlay.setOpacity(OVERLAY_OPACITY);
        if (currentOverlay != null)
            v.getCustomOverlayLayers().remove(currentOverlay);
        v.getCustomOverlayLayers().add(overlay);
        currentOverlay = overlay;
        v.repaint();
    }

    private void removeOverlay() {
        if (currentOverlay != null && currentViewer != null) {
            try {
                currentViewer.getCustomOverlayLayers().remove(currentOverlay);
                currentViewer.repaint();
            } catch (Exception e) {
                logger.debug("Could not remove focus overlay: {}", e.getMessage());
            }
        }
        currentOverlay = null;
    }

    private void clear() {
        if (currentMap != null) {
            currentMap.clear();
            if (active && currentViewer != null)
                refreshOverlay(currentViewer);
        }
    }

    // --- persistence --------------------------------------------------------

    private void saveDialog() {
        if (currentMap == null || currentMap.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Henüz odak verisi yok — önce izlemeyi açın.").showAndWait();
            return;
        }
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Odak haritasını kaydet");
        File dir = dc.showDialog(qupath.getStage());
        if (dir != null)
            save(currentSlide, currentUri, currentMap, dir);
    }

    /** Snapshot the map on the (FX) calling thread, then write JSON + PNG on a background thread. */
    private void save(String slide, String uri, FocusMap map, File dir) {
        final String json = buildJson(slide, uri, map);
        final BufferedImage png = map.toImage();
        final String base = safe(slide) + "__" + safe(user) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Thread t = new Thread(() -> {
            try {
                if (!dir.exists())
                    dir.mkdirs();
                java.nio.file.Files.writeString(new File(dir, base + ".json").toPath(), json,
                        java.nio.charset.StandardCharsets.UTF_8);
                ImageIO.write(png, "png", new File(dir, base + ".png"));
                logger.info("Saved focus map to {}", new File(dir, base + ".json"));
            } catch (Exception e) {
                logger.error("Failed to save focus map: {}", e.getMessage(), e);
            }
        }, "focus-map-save");
        t.setDaemon(true);
        t.start();
    }

    private String buildJson(String slide, String uri, FocusMap map) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slide", slide);
        m.put("uri", uri);
        m.put("user", user);
        m.put("imageWidth", map.getImageWidth());
        m.put("imageHeight", map.getImageHeight());
        m.put("gridWidth", map.getGridWidth());
        m.put("gridHeight", map.getGridHeight());
        m.put("sampleCount", map.getSampleCount());
        m.put("savedAt", LocalDateTime.now().toString());
        m.put("grid", map.getGrid().clone());
        return new GsonBuilder().create().toJson(m);
    }

    private static File defaultDir() {
        return new File(System.getProperty("user.home"), "QuPath-atlas-focus-maps");
    }

    private static String firstUri(ImageServer<BufferedImage> server) {
        try {
            var uris = server.getURIs();
            if (uris != null && !uris.isEmpty())
                return uris.iterator().next().toString();
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    private static String safe(String s) {
        if (s == null || s.isBlank())
            return "slide";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
