package com.patolojiatlasi.qupath.focus;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
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

    // Research contribution (anonymous, opt-in). Uploading is DISABLED until the atlas website
    // has a receiver — see docs/focus-aggregation-plan.md. Until then "Contribute" only writes an
    // anonymised file locally that can be shared later. To enable: set UPLOAD_ENABLED = true and
    // point UPLOAD_ENDPOINT at the receiver.
    private static final boolean UPLOAD_ENABLED = false;
    private static final String UPLOAD_ENDPOINT = "";
    private static final String CONTRIBUTION_SCHEMA = "atlas-focus-contribution/1";

    private final QuPathGUI qupath;
    private final String user = System.getProperty("user.name", "unknown");
    /** Anonymous per-session id — lets contributions be de-duplicated/weighted without identifying anyone. */
    private final String sessionId = java.util.UUID.randomUUID().toString();

    // The heat map can be shown two ways, independently: as a translucent layer on the slide, and/or
    // in a separate small window. Sampling runs while EITHER is visible.
    private boolean overlayVisible;
    private boolean windowVisible;
    private boolean keepMaps;

    private CheckMenuItem windowItem;   // kept so closing the window can untick it
    private Stage window;
    private ImageView windowView;

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
        CheckMenuItem overlayItem = new CheckMenuItem("Slayt üzerinde göster (ısı katmanı)");
        overlayItem.selectedProperty().addListener((obs, was, now) -> setOverlayVisible(now));

        windowItem = new CheckMenuItem("Ayrı pencerede göster");
        windowItem.selectedProperty().addListener((obs, was, now) -> setWindowVisible(now));

        MenuItem clearItem = new MenuItem("Temizle");
        clearItem.setOnAction(e -> clear());

        MenuItem saveItem = new MenuItem("Kaydet…");
        saveItem.setOnAction(e -> saveDialog());

        MenuItem contributeItem = new MenuItem("Araştırmaya katkıda bulun…");
        contributeItem.setOnAction(e -> contribute());

        CheckMenuItem keepItem = new CheckMenuItem("Oturumdan sonra sakla (kalıcı)");
        keepItem.selectedProperty().addListener((obs, was, now) -> keepMaps = now);

        Menu menu = new Menu("Odak ısı haritası");
        menu.getItems().addAll(overlayItem, windowItem, clearItem, saveItem, contributeItem,
                new SeparatorMenuItem(), keepItem);
        return menu;
    }

    // --- tracking lifecycle -------------------------------------------------

    private void setOverlayVisible(boolean on) {
        this.overlayVisible = on;
        if (!on)
            removeOverlay();
        refreshTracking();
    }

    private void setWindowVisible(boolean on) {
        this.windowVisible = on;
        if (on)
            showWindow();
        else
            hideWindow();
        refreshTracking();
    }

    /** Start/stop the sampling timer based on whether either display is showing. */
    private void refreshTracking() {
        boolean track = overlayVisible || windowVisible;
        if (track) {
            if (timer == null) {
                timer = new Timeline(new KeyFrame(Duration.millis(SAMPLE_MS), e -> tick()));
                timer.setCycleCount(Animation.INDEFINITE);
            }
            ticksSinceRefresh = REFRESH_EVERY;   // refresh on the first tick
            timer.play();
        } else {
            if (timer != null)
                timer.stop();
            // Persist the in-progress map before going idle, if the user asked to keep maps.
            if (keepMaps && currentMap != null && !currentMap.isEmpty())
                save(currentSlide, currentUri, currentMap, defaultDir());
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
                if (overlayVisible)
                    refreshOverlay(v);
                if (windowVisible)
                    updateWindow();
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
        if (windowVisible)
            updateWindow();
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
            if (overlayVisible && currentViewer != null)
                refreshOverlay(currentViewer);
            if (windowVisible)
                updateWindow();
        }
    }

    // --- separate monitor window --------------------------------------------

    /** Open (or focus) the small window that shows the heat map without touching the slide view. */
    private void showWindow() {
        if (window != null) {
            window.show();
            window.toFront();
            return;
        }
        windowView = new ImageView();
        windowView.setPreserveRatio(true);
        StackPane pane = new StackPane(windowView);
        pane.setStyle("-fx-background-color: #1c1c1e;");
        pane.setPadding(new Insets(8));
        window = new Stage();
        window.setTitle("Odak ısı haritası");
        window.setScene(new Scene(pane, 360, 320));
        window.setOnHidden(e -> {
            window = null;
            windowView = null;
            if (windowItem != null && windowItem.isSelected())
                windowItem.setSelected(false);   // re-entrant-safe: hideWindow() no-ops (window already null)
        });
        updateWindow();
        window.show();
    }

    private void hideWindow() {
        if (window != null)
            window.close();
    }

    /** Repaint the window with the current map, scaled up over a dark background. FX thread only. */
    private void updateWindow() {
        if (window == null || windowView == null)
            return;
        FocusMap map = currentMap;
        if (map == null) {
            windowView.setImage(null);
            return;
        }
        BufferedImage heat = map.toImage();   // grid-sized ARGB, transparent where cold
        int gw = map.getGridWidth();
        int gh = map.getGridHeight();
        int maxDim = 320;
        double scale = (double) maxDim / Math.max(gw, gh);
        int dw = Math.max(1, (int) Math.round(gw * scale));
        int dh = Math.max(1, (int) Math.round(gh * scale));
        BufferedImage disp = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = disp.createGraphics();
        try {
            g.setColor(new Color(28, 28, 30));
            g.fillRect(0, 0, dw, dh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(heat, 0, 0, dw, dh, null);
        } finally {
            g.dispose();
        }
        windowView.setImage(toFXImage(disp));
    }

    /** Convert an ARGB BufferedImage to a JavaFX image without a javafx.swing dependency. */
    private static WritableImage toFXImage(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        WritableImage img = new WritableImage(w, h);
        int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return img;
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

    // --- research contribution (anonymous, opt-in; upload gated off) ---------

    /**
     * Contribute the current slide's focus map to the aggregate research/education dataset. The
     * contribution is <b>anonymised</b> (no user name — only a random session id, a stable slide
     * key for grouping, and a date), written locally under {@code contributions/}. Uploading is
     * disabled until the atlas website has a receiver; until then the file can be shared manually.
     */
    private void contribute() {
        if (currentMap == null || currentMap.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Henüz odak verisi yok — önce izlemeyi açın.").showAndWait();
            return;
        }
        final String json = buildContributionJson(currentUri, currentMap);
        final File dir = new File(defaultDir(), "contributions");
        final String base = "focus-contribution__" + safe(slideKey(currentUri)) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        final File file = new File(dir, base + ".json");
        writeTextAsync(file, json);
        if (UPLOAD_ENABLED && !UPLOAD_ENDPOINT.isBlank()) {
            uploadAsync(json);
        } else {
            new Alert(Alert.AlertType.INFORMATION,
                    "Katkınız yerel olarak (anonim) kaydedildi:\n" + file
                    + "\n\nAtlas sunucusu henüz hazır değil; paylaşım açıldığında bu dosyayı gönderebilirsiniz.")
                    .showAndWait();
        }
    }

    /** Anonymised contribution payload: no user name; a stable slide key + random session id + date. */
    private String buildContributionJson(String uri, FocusMap map) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", CONTRIBUTION_SCHEMA);
        m.put("slideKey", slideKey(uri));       // stable across readers → server groups by this
        m.put("sessionId", sessionId);          // anonymous, not a user identity
        m.put("imageWidth", map.getImageWidth());
        m.put("imageHeight", map.getImageHeight());
        m.put("gridWidth", map.getGridWidth());
        m.put("gridHeight", map.getGridHeight());
        m.put("sampleCount", map.getSampleCount());
        m.put("date", java.time.LocalDate.now().toString());   // date only (no time) for privacy
        m.put("grid", map.getGrid().clone());   // raw dwell counts; the server normalises
        return new GsonBuilder().create().toJson(m);
    }

    /** Stable per-slide key for aggregation: the DZI URL without any query (e.g. no {@code ?mpp=}). */
    private static String slideKey(String uri) {
        if (uri == null || uri.isBlank())
            return "unknown";
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private void writeTextAsync(File file, String text) {
        Thread t = new Thread(() -> {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists())
                    dir.mkdirs();
                java.nio.file.Files.writeString(file.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
                logger.info("Saved focus contribution to {}", file);
            } catch (Exception e) {
                logger.error("Failed to save focus contribution: {}", e.getMessage(), e);
            }
        }, "focus-contribution-save");
        t.setDaemon(true);
        t.start();
    }

    /** POST a contribution to the atlas receiver. Only invoked when {@link #UPLOAD_ENABLED}. */
    private void uploadAsync(String json) {
        Thread t = new Thread(() -> {
            try {
                var client = java.net.http.HttpClient.newHttpClient();
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(UPLOAD_ENDPOINT))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json,
                                java.nio.charset.StandardCharsets.UTF_8))
                        .build();
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                logger.info("Uploaded focus contribution to {}", UPLOAD_ENDPOINT);
            } catch (Exception e) {
                logger.error("Focus contribution upload failed: {}", e.getMessage());
            }
        }, "focus-contribution-upload");
        t.setDaemon(true);
        t.start();
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
