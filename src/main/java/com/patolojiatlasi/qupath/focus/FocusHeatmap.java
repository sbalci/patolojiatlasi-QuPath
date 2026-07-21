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
    /** Blinded-mode dt clamp: at most two sample intervals, so a stalled/late tick can't over-credit. */
    private static final long DT_CAP_MS = 2L * SAMPLE_MS;

    // Research contribution (anonymous, opt-in). Uploading is DISABLED until the atlas website
    // has a receiver — see docs/focus-aggregation-plan.md. Until then "Contribute" only writes an
    // anonymised file locally that can be shared later. To enable: set UPLOAD_ENABLED = true and
    // point UPLOAD_ENDPOINT at the receiver.
    private static final boolean UPLOAD_ENABLED = false;
    private static final String UPLOAD_ENDPOINT = "";
    private static final String CONTRIBUTION_SCHEMA = "atlas-focus-contribution/1";
    /**
     * Blinded (research) recording schema: same anonymised shape, but {@code grid} holds real dwell
     * milliseconds (not fixed-weight sample counts) — {@code weightUnit="ms"} tells a reader which.
     * tools/aggregate-focus.py accepts both schemas: it normalises each contribution by its own max
     * before averaging, so ms-vs-count units don't need reconciling.
     */
    private static final String CONTRIBUTION_SCHEMA_BLINDED = "atlas-focus-contribution/2";

    private final QuPathGUI qupath;
    private final String user = System.getProperty("user.name", "unknown");
    /** Anonymous per-session id — lets contributions be de-duplicated/weighted without identifying anyone. */
    private final String sessionId = java.util.UUID.randomUUID().toString();

    // The heat map can be shown two ways, independently: as a translucent layer on the slide, and/or
    // in a separate small window. Sampling also runs — silently, no visuals — while blinded
    // recording is active (see startBlinded/stopBlinded).
    private boolean overlayVisible;
    private boolean windowVisible;
    private boolean keepMaps;

    /** Blinded (research) recording: real per-tick dwell-ms, paused when idle/unfocused, no visuals. */
    private boolean blindedRecording;
    private long lastTickMs;

    private CheckMenuItem overlayItem;  // kept so blinded mode can disable/untick it
    private CheckMenuItem windowItem;   // kept so closing the window can untick it, and for blinded mode
    private CheckMenuItem blindedItem;  // kept so an externally-driven start/stop (Task 3) stays reflected
    // Manual actions on the current map are also disabled while blinded: "Kaydet…" would otherwise
    // write a PNG of the (ms) map, and "Araştırmaya katkıda bulun…" would mislabel ms values as the
    // schema/1 "raw dwell counts" — both are exactly the artifact blinded recording must not produce.
    private MenuItem clearItem;
    private MenuItem saveItem;
    private MenuItem contributeItem;
    private Stage window;
    private ImageView windowView;

    private Timeline timer;
    private int ticksSinceRefresh;

    private QuPathViewer currentViewer;
    private ImageData<BufferedImage> currentImageData;
    private FocusMap currentMap;
    /** True when {@code currentMap} was created while blinded recording — gates {@link #save}. */
    private boolean currentMapBlinded;
    private String currentSlide;
    private String currentUri;
    private BufferedImageOverlay currentOverlay;

    public FocusHeatmap(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Build the "Odak ısı haritası" submenu with all controls wired up. */
    public Menu buildMenu() {
        overlayItem = new CheckMenuItem("Slayt üzerinde göster (ısı katmanı)");
        overlayItem.selectedProperty().addListener((obs, was, now) -> setOverlayVisible(now));

        windowItem = new CheckMenuItem("Ayrı pencerede göster");
        windowItem.selectedProperty().addListener((obs, was, now) -> setWindowVisible(now));

        clearItem = new MenuItem("Temizle");
        clearItem.setOnAction(e -> clear());

        saveItem = new MenuItem("Kaydet…");
        saveItem.setOnAction(e -> saveDialog());

        contributeItem = new MenuItem("Araştırmaya katkıda bulun…");
        contributeItem.setOnAction(e -> contribute());

        CheckMenuItem keepItem = new CheckMenuItem("Oturumdan sonra sakla (kalıcı)");
        keepItem.selectedProperty().addListener((obs, was, now) -> keepMaps = now);

        // JavaFX's MenuItem/CheckMenuItem has no setTooltip(...) — a hover tooltip isn't available
        // on a plain menu row, so the description is folded into the label itself instead.
        blindedItem = new CheckMenuItem("Kör kayıt (araştırma) — sessizce kaydeder, ısı haritası gösterilmez");
        blindedItem.selectedProperty().addListener((obs, was, now) -> {
            if (now)
                startBlinded();
            else
                stopBlinded();
        });

        Menu menu = new Menu("Odak ısı haritası");
        menu.getItems().addAll(overlayItem, windowItem, clearItem, saveItem, contributeItem,
                new SeparatorMenuItem(), keepItem, blindedItem);
        return menu;
    }

    // --- tracking lifecycle -------------------------------------------------

    private void setOverlayVisible(boolean on) {
        // Belt-and-suspenders: the item is disabled while blinded, but refuse programmatic
        // enabling too, so blinded recording can never surface a visual.
        if (on && blindedRecording) {
            overlayItem.setSelected(false);
            return;
        }
        this.overlayVisible = on;
        if (!on)
            removeOverlay();
        refreshTracking();
    }

    private void setWindowVisible(boolean on) {
        if (on && blindedRecording) {
            windowItem.setSelected(false);
            return;
        }
        this.windowVisible = on;
        if (on)
            showWindow();
        else
            hideWindow();
        refreshTracking();
    }

    /** Start/stop the sampling timer based on whether any display — or blinded recording — is active. */
    private void refreshTracking() {
        boolean track = overlayVisible || windowVisible || blindedRecording;
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

    // --- blinded (research) recording ---------------------------------------
    //
    // Records real per-slide viewing time silently: no overlay, no window, no status message —
    // just the tested FocusMap.activeDwellMs kernel accumulating dwell-ms, paused whenever the app
    // is unfocused/idle. Driven either by the "Kör kayıt (araştırma)" menu toggle, or externally by
    // Task 3's project-open/close hook via startBlinded()/stopBlinded() on this same retained
    // instance (see AtlasExtension).

    public boolean isBlinded() {
        return blindedRecording;
    }

    /** Begin blinded recording: hides/disables any visuals, resets the current slide's map, and
     *  starts the sampling timer if it isn't already running. Idempotent (no-op if already blinded). */
    public void startBlinded() {
        if (blindedRecording)
            return;
        // Hide/clear any visible overlay or window — blinded recording must render nothing.
        if (overlayVisible)
            overlayItem.setSelected(false);   // -> setOverlayVisible(false) -> removeOverlay()
        if (windowVisible)
            windowItem.setSelected(false);    // -> setWindowVisible(false) -> hideWindow()
        if (overlayItem != null)
            overlayItem.setDisable(true);
        if (windowItem != null)
            windowItem.setDisable(true);
        // Also block manual actions on the map while blinded (see field comment): they'd otherwise
        // let a click produce the exact PNG/mislabeled-file artifact blinded recording forbids.
        if (clearItem != null)
            clearItem.setDisable(true);
        if (saveItem != null)
            saveItem.setDisable(true);
        if (contributeItem != null)
            contributeItem.setDisable(true);
        if (currentMap != null)
            currentMap.clear();
        lastTickMs = System.currentTimeMillis();
        blindedRecording = true;
        // A pre-existing (visible-mode) map is reused here via clear(), not replaced with a `new
        // FocusMap(...)` — so the flag must be re-tagged explicitly: from this point on, every
        // sample deposited into currentMap comes from tickBlinded(), so it must be treated as
        // blinded for save()'s guard. (Placed after the overlay/window off-toggles above so their
        // refreshTracking()-triggered legitimate visible-mode keepMaps save, if any, still sees the
        // old false value and saves the still-visible data normally.)
        currentMapBlinded = true;
        if (blindedItem != null && !blindedItem.isSelected())
            blindedItem.setSelected(true);
        refreshTracking();
    }

    /** Stop blinded recording: saves the accumulated dwell-ms as a raw JSON, re-enables the overlay
     *  and window toggles, and stops the timer if nothing else needs it. No-op if not blinded. */
    public void stopBlinded() {
        if (!blindedRecording)
            return;
        blindedRecording = false;
        if (currentMap != null && !currentMap.isEmpty())
            saveBlinded(currentUri, currentMap);
        // Clean slate: the blinded map is now persisted (or discarded if empty); drop it so no
        // stray keepMaps/save() path downstream (e.g. refreshTracking()'s stop-tracking save) can
        // ever touch it, and so a future visible session starts from a fresh, non-blinded map.
        currentMap = null;
        currentMapBlinded = false;
        if (overlayItem != null)
            overlayItem.setDisable(false);
        if (windowItem != null)
            windowItem.setDisable(false);
        if (clearItem != null)
            clearItem.setDisable(false);
        if (saveItem != null)
            saveItem.setDisable(false);
        if (contributeItem != null)
            contributeItem.setDisable(false);
        if (blindedItem != null && blindedItem.isSelected())
            blindedItem.setSelected(false);
        refreshTracking();
    }

    private void tick() {
        try {
            QuPathViewer v = qupath.getViewer();
            ImageData<BufferedImage> id = (v == null) ? null : v.getImageData();
            if (id != currentImageData)
                switchTo(v, id);
            if (blindedRecording) {
                tickBlinded(v);
                return;
            }
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

    /**
     * Blinded-mode sampling: deposits real elapsed dwell-milliseconds (via the pure
     * {@link FocusMap#activeDwellMs} kernel, paused whenever the app is unfocused/idle or no slide
     * is open) — and nothing else. No overlay refresh, no window update, no status: this path must
     * never render anything.
     */
    private void tickBlinded(QuPathViewer v) {
        long now = System.currentTimeMillis();
        boolean active = mainStageFocused() && v != null && v.getImageData() != null;
        long ms = FocusMap.activeDwellMs(now, lastTickMs, active, DT_CAP_MS);
        lastTickMs = now;
        if (ms > 0 && currentMap != null) {
            Shape shape = v.getDisplayedRegionShape();
            if (shape != null) {
                Rectangle b = shape.getBounds();
                currentMap.deposit(b.getX(), b.getY(), b.getWidth(), b.getHeight(), ms);
            }
        }
    }

    private boolean mainStageFocused() {
        return qupath.getStage() != null && qupath.getStage().isFocused();
    }

    /** Move to a new slide/viewer: persist the old map if requested, then start a fresh one. */
    private void switchTo(QuPathViewer v, ImageData<BufferedImage> id) {
        if (keepMaps && currentMap != null && !currentMap.isEmpty())
            save(currentSlide, currentUri, currentMap, defaultDir());
        // Independent of keepMaps: while blinded, always persist the finished slide's real dwell
        // time before it's discarded — otherwise switching slides mid-session would silently lose it.
        if (blindedRecording && currentMap != null && !currentMap.isEmpty())
            saveBlinded(currentUri, currentMap);
        removeOverlay();
        currentViewer = v;
        currentImageData = id;
        if (id != null && id.getServer() != null) {
            ImageServer<BufferedImage> server = id.getServer();
            currentMap = new FocusMap(server.getWidth(), server.getHeight(), GRID_MAX);
            // Tag the new map with the mode that created it, so save() can refuse a blinded map later.
            currentMapBlinded = blindedRecording;
            currentSlide = server.getMetadata().getName();
            currentUri = firstUri(server);
        } else {
            currentMap = null;
            currentMapBlinded = false;
            currentSlide = null;
            currentUri = null;
        }
        ticksSinceRefresh = REFRESH_EVERY;
        if (blindedRecording)
            lastTickMs = System.currentTimeMillis();
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
        // Defense-in-depth: a blinded map is anonymised-JSON-only (see saveBlinded) and must never
        // reach the PNG/username-bearing path below, regardless of which caller reached here.
        if (currentMapBlinded)
            return;
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

    /**
     * Persist a finished blinded-recording map: raw JSON only, <b>never</b> a PNG (no visual
     * artifact of blinded viewing should ever be produced, on disk or otherwise). Written to the
     * same {@code contributions/} folder as {@link #contribute()}, under schema/2.
     */
    private void saveBlinded(String uri, FocusMap map) {
        final String json = buildBlindedJson(uri, map);
        final File dir = new File(defaultDir(), "contributions");
        final String base = "focus-blinded__" + safe(slideKey(uri)) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        writeTextAsync(new File(dir, base + ".json"), json);
    }

    /**
     * Blinded contribution payload — same anonymised shape as {@link #buildContributionJson}, but
     * {@code grid} holds real dwell-milliseconds (not fixed-weight sample counts): {@code
     * weightUnit="ms"} and {@code durationMs} (= {@link FocusMap#getTotalWeight()}) make that explicit.
     */
    private String buildBlindedJson(String uri, FocusMap map) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", CONTRIBUTION_SCHEMA_BLINDED);
        m.put("slideKey", slideKey(uri));
        m.put("sessionId", sessionId);
        m.put("imageWidth", map.getImageWidth());
        m.put("imageHeight", map.getImageHeight());
        m.put("gridWidth", map.getGridWidth());
        m.put("gridHeight", map.getGridHeight());
        m.put("sampleCount", map.getSampleCount());
        m.put("weightUnit", "ms");
        m.put("durationMs", map.getTotalWeight());
        m.put("date", java.time.LocalDate.now().toString());
        m.put("grid", map.getGrid().clone());   // dwell-ms per cell; aggregator normalises per-contribution
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
