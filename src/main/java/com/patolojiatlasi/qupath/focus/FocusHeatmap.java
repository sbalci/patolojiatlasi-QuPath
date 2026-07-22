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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

import com.patolojiatlasi.qupath.research.BlindedResearch;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.FeatureCollection;
import qupath.lib.io.GsonTools;
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

    private static final int GRID_MAX = 512;      // longest grid side, in cells
    private static final int SAMPLE_MS = 250;     // viewport sampling interval
    private static final int REFRESH_EVERY = 4;   // rebuild the overlay every N samples (~1s)
    private static final double OVERLAY_OPACITY = 0.5;
    /** Blinded-mode dt clamp: at most two sample intervals, so a stalled/late tick can't over-credit. */
    private static final long DT_CAP_MS = 2L * SAMPLE_MS;
    /** Blinded-mode checkpoint cadence: every 120 ticks * SAMPLE_MS(250) = ~30s, for crash safety. */
    private static final int CHECKPOINT_EVERY_TICKS = 120;

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
     * before averaging, so ms-vs-count units don't need reconciling. Schema/3 additionally carries
     * the ordered {@code path} time-series (see {@link #blindedPath}); {@code grid} is unchanged, so
     * schema/2 readers that ignore unknown fields still work. Schema/4 additionally carries a
     * per-point downsample ({@code dsMilli}, see {@link #blindedPath}) and a fragment-level {@code
     * baseMagnification} (nullable) — both additive; a schema/3 reader that ignores unknown fields
     * still works. Schema/5 extends each {@code path} point with the cursor's position at that tick
     * ({@code mouseX}, {@code mouseY} — see {@link #blindedPath}), captured only while the cursor is
     * over the slide viewer and expressed in the same image-pixel coordinate space as {@code cx}/
     * {@code cy}; {@code -1,-1} means the cursor was off the viewer (or its position unknown) at that
     * tick. Purely additive (two more trailing ints per point); a schema/4 reader that reads points by
     * length still works.
     */
    private static final String CONTRIBUTION_SCHEMA_BLINDED = "atlas-focus-contribution/5";
    /** Hard cap on recorded scanpath points per blinded slide session — a safety bound against an
     *  unbounded list on a pathologically long session, not an expected ceiling in practice (20000
     *  points at the ~250ms sample interval is ~83 minutes of continuous active dwell). */
    private static final int MAX_PATH_POINTS = 20000;

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
    // volatile: read from the JVM shutdown-hook thread (flushOnShutdown) as well as the FX thread.
    private volatile boolean blindedRecording;
    private long lastTickMs;
    /**
     * Where blinded fragments/checkpoints/zip for the <b>current</b> blinded session are written --
     * {@code <projectDir>/atlas-focus} if a project was open when {@link #startBlinded()} ran, else
     * the home-dir fallback. Captured once at {@code startBlinded()} and reused unchanged by every
     * later write (checkpoint, per-slide promotion, shutdown flush, zip): a project switch stops the
     * old blinded session first (see {@code AtlasExtension#onProjectChanged}), by which point {@code
     * qupath.getProject()} already points at the *new* project, so re-resolving it at save time would
     * misattribute the just-finished session's data to the wrong project.
     */
    private volatile File blindedDir;   // read from the shutdown-hook thread too
    /** Ticks since the last blinded checkpoint write; reset in {@link #startBlinded()} and whenever a
     *  fresh slide map is created while blinded (see {@link #switchTo}). */
    private int blindedTicks;
    /**
     * Ordered scanpath for the <b>current</b> blinded slide session: one {@code [tRelMs, cx, cy, w,
     * h, dsMilli, mouseX, mouseY]} point per active tick (viewport center + extent, all in slide pixel
     * coordinates, plus milliseconds since {@link #blindedSlideStartMs}, plus the viewer's downsample
     * factor at that tick times 1000, rounded to an integer — {@code dsMilli =
     * round(viewer.getDownsampleFactor() * 1000)} — kept as a scaled integer so the JSON stays compact;
     * 1.0 downsample = full resolution, higher = more zoomed out; plus the cursor's position at that
     * tick, in the same image-pixel coordinate space as {@code cx}/{@code cy} — see {@link
     * #lastMouseImageX}/{@link #lastMouseImageY} for how it's captured and the {@code -1,-1} off-viewer
     * sentinel). Appended in {@link #tickBlinded} for every tick where the app is focused and a slide
     * is open (i.e. {@code ms > 0}), independent of whether the same tick's dwell-grid deposit ({@code
     * currentMap.deposit(...)}) actually landed on the image — so {@code path} may carry a point for a
     * tick the {@code grid} accumulation didn't credit.
     * Reset (cleared) in {@link #startBlinded()} and in {@link #switchTo} whenever a fresh blinded
     * slide map is created, mirroring {@link #blindedTicks}'s reset points. FX-thread-only to write;
     * read only via a defensive snapshot ({@code new ArrayList<>(blindedPath)}) at serialization time
     * in {@link #buildBlindedJson}, so a concurrent append can't corrupt an in-flight write.
     */
    private final java.util.List<int[]> blindedPath = new java.util.ArrayList<>();
    /** Wall-clock start of the current blinded slide session, for {@code blindedPath}'s relative
     *  timestamps; reset alongside {@code blindedPath} in {@link #startBlinded()} / {@link #switchTo}. */
    private long blindedSlideStartMs;
    /** True once {@link #blindedPath} has hit {@link #MAX_PATH_POINTS} for the current slide session,
     *  so the drop is logged once rather than on every subsequent tick. */
    private boolean blindedPathCapped;
    /** Guards against registering {@link #flushOnShutdown()} more than once across repeated
     *  start/stop cycles within the same JVM (a shutdown hook can't be usefully "un-added" per
     *  session, so it's registered once and self-guards on {@link #blindedRecording} at fire time). */
    private boolean shutdownHookAdded;

    /**
     * Latest cursor position over the actively-tracked viewer, in <b>image (tissue) pixel</b>
     * coordinates — <b>never</b> global/OS mouse position. {@code -1,-1} is the sentinel for "the
     * cursor is not currently over the slide viewer" (also the initial value, before any mouse event
     * has arrived), used both before the first event and whenever the cursor leaves the viewer's view
     * node. Written only by {@link #mouseMovedHandler} (mirrored by {@link #mouseExitedHandler}'s
     * reset) and read only by {@link #tickBlinded} — both run on the JavaFX Application Thread in
     * practice; {@code volatile} costs nothing and matches this class's existing defensive style.
     * When the cursor is over the viewer but outside the displayed image (e.g. a zoomed-out
     * letterboxed margin), the position is clamped to the image bounds (0..width, 0..height) rather
     * than left raw or reset to the sentinel — the sentinel means "off the viewer", not "off the
     * image".
     */
    private volatile int lastMouseImageX = -1;
    private volatile int lastMouseImageY = -1;
    /** Which viewer's view node {@link #mouseMovedHandler}/{@link #mouseExitedHandler} are currently
     *  attached to, or {@code null} when not attached. Mouse tracking is wired up only while blinded
     *  recording is active — see {@link #attachMouseTracking}, {@link #startBlinded()}, {@link
     *  #stopBlinded()} — so this is non-null only during a blinded session. */
    private QuPathViewer mouseViewer;
    /**
     * Converts a mouse-moved/dragged event on the tracked viewer's view node to image pixel
     * coordinates via {@link QuPathViewer#componentPointToImagePoint(double, double,
     * java.awt.geom.Point2D, boolean)} and stores the result in {@link #lastMouseImageX}/{@link
     * #lastMouseImageY}. This is the <b>only</b> source of cursor position anywhere in this class — it
     * reads the JavaFX {@code MouseEvent} delivered to the viewer's own view node (never any
     * global/OS pointer query), so a position can only ever be captured while the cursor is physically
     * over that slide viewer. Best-effort: any failure (e.g. a non-invertible transform, or the
     * tracked viewer having no server) falls back to the off-viewer sentinel rather than throwing into
     * JavaFX event dispatch.
     */
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
    /** Cursor left the tracked viewer's view node — back to the "off viewer" sentinel. */
    private final javafx.event.EventHandler<javafx.scene.input.MouseEvent> mouseExitedHandler =
            e -> resetMousePosition();
    /**
     * Moves {@link #mouseMovedHandler}/{@link #mouseExitedHandler} to whichever viewer becomes active,
     * so cursor tracking always follows the <b>active</b> viewer — mirrors {@link
     * com.patolojiatlasi.qupath.RotationControl}'s {@code rebind()} pattern for the same {@link
     * QuPathGUI#viewerProperty()}.
     * Wired on only while blinded (added in {@link #startBlinded()}, removed in {@link
     * #stopBlinded()}), so there is never a listener registered outside a recording session.
     */
    private final javafx.beans.value.ChangeListener<QuPathViewer> viewerMouseListener =
            (obs, was, now) -> attachMouseTracking(now);

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
    private volatile FocusMap currentMap;   // read from the shutdown-hook thread too
    /** True when {@code currentMap} was created while blinded recording — gates {@link #save}. */
    private boolean currentMapBlinded;
    private String currentSlide;
    private volatile String currentUri;   // read from the shutdown-hook thread too
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
        blindedItem = new CheckMenuItem("Gezinme kaydı (araştırma) — sessizce kaydeder, ısı haritası gösterilmez");
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
    // is unfocused/idle. Driven either by the "Gezinme kaydı (araştırma)" menu toggle, or externally by
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
        // Attribution capture: resolve the target dir once, here, from whichever project is open
        // right now -- see the blindedDir field javadoc for why this must never be re-resolved later.
        File projectDir = qupath.getProject() == null ? null : BlindedResearch.projectDir(qupath.getProject());
        blindedDir = BlindedStore.blindedDir(projectDir, new File(defaultDir(), "contributions"));
        blindedTicks = 0;
        blindedPath.clear();
        blindedPathCapped = false;
        blindedSlideStartMs = System.currentTimeMillis();
        // Viewer-scoped cursor tracking (image coords only, never global mouse) -- starts following
        // whichever viewer is active now, and re-follows on every later active-viewer change.
        qupath.viewerProperty().addListener(viewerMouseListener);
        attachMouseTracking(qupath.getViewer());
        if (!shutdownHookAdded) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown, "atlas-blinded-shutdown"));
            shutdownHookAdded = true;
        }
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
        // Cursor tracking is blinded-only -- stop following the active viewer and detach the mouse
        // handlers now, so no listener/handler is ever left registered outside a recording session.
        qupath.viewerProperty().removeListener(viewerMouseListener);
        attachMouseTracking(null);
        if (currentMap != null && !currentMap.isEmpty())
            saveBlindedSync(currentUri, currentMap);
        // The final fragment above (if any) now carries whatever the checkpoint was tracking --
        // remove the checkpoint so it doesn't linger as stale/duplicate data in blindedDir.
        deleteCheckpoint();
        // Best-effort: bundle every fragment written this session into one zip in the project
        // folder (or the fallback dir) -- the single file to hand to the study coordinator. A zip
        // failure must never prevent recording from stopping cleanly.
        try {
            // Only zip when something was actually recorded — an immediate start/stop (or a
            // session with no dwell) must not litter the project folder with empty zips.
            if (BlindedStore.hasFragments(blindedDir)) {
                String stamp = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
                String shortId = sessionId.length() >= 8 ? sessionId.substring(0, 8) : sessionId;
                File zipParent = "atlas-focus".equals(blindedDir.getName()) ? blindedDir.getParentFile() : blindedDir;
                BlindedStore.zipFragments(blindedDir, new File(zipParent, BlindedStore.zipName(stamp, shortId)));
            }
        } catch (Exception e) {
            logger.debug("Blinded zip failed: {}", e.getMessage());
        }
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
                if (currentMap.deposit(b.getX(), b.getY(), b.getWidth(), b.getHeight(), ms)) {
                    blindedTicks++;
                    if (blindedTicks % CHECKPOINT_EVERY_TICKS == 0)
                        checkpointBlinded();
                }
                // Ordered scanpath point for this same active-deposit tick — reuses `now` and `b`
                // from the dwell-grid deposit above; best-effort, must never throw into tick().
                if (blindedPath.size() < MAX_PATH_POINTS) {
                    long tRel = now - blindedSlideStartMs;
                    int dsMilli = (int) Math.round(v.getDownsampleFactor() * 1000);
                    // mouseX/mouseY: the cursor's last-known position over THIS viewer, in image px
                    // (see lastMouseImageX/Y's javadoc); (-1,-1) when the cursor isn't over the slide
                    // viewer. Read here, not computed here -- mouse events update the fields
                    // independently of the sampling timer as they arrive.
                    blindedPath.add(new int[]{(int) tRel, (int) Math.round(b.getX() + b.getWidth() / 2.0),
                            (int) Math.round(b.getY() + b.getHeight() / 2.0),
                            (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()), dsMilli,
                            lastMouseImageX, lastMouseImageY});
                } else if (!blindedPathCapped) {
                    blindedPathCapped = true;
                    logger.info("Blinded scanpath reached {} points; further points dropped.", MAX_PATH_POINTS);
                }
            }
        }
    }

    /**
     * Crash-safety checkpoint: overwrite {@code <blindedDir>/session-<sessionId>.partial.json} with
     * the current slide's accumulated dwell-ms, so a crash loses at most ~{@link
     * #CHECKPOINT_EVERY_TICKS} ticks (~30s) of data instead of the whole slide. JSON-only, via the
     * same anonymised {@link #buildBlindedJson} used for the final fragment — never a PNG. Best-effort:
     * any failure is logged and swallowed, never thrown into the sampling timer.
     */
    private void checkpointBlinded() {
        if (blindedDir == null || currentMap == null || currentMap.isEmpty())
            return;
        try {
            final String json = buildBlindedJson(currentUri, currentMap);
            writeTextAsync(new File(blindedDir, "session-" + sessionId + ".partial.json"), json);
        } catch (Exception e) {
            logger.debug("Blinded checkpoint failed: {}", e.getMessage());
        }
    }

    /** Best-effort delete of the current session's checkpoint file (its data has just been promoted
     *  into a final fragment). No-op / silent if it doesn't exist or can't be removed. */
    private void deleteCheckpoint() {
        try {
            if (blindedDir != null)
                new File(blindedDir, "session-" + sessionId + ".partial.json").delete();
        } catch (Exception e) {
            logger.debug("Could not remove blinded checkpoint: {}", e.getMessage());
        }
    }

    /**
     * JVM-shutdown safety net, registered once from {@link #startBlinded()}. If QuPath is force-quit
     * (or the process otherwise dies) while blinded recording is active and the current slide has
     * unsaved dwell data, writes one last anonymised JSON fragment to {@link #blindedDir} so a hard
     * crash loses at most ~{@link #CHECKPOINT_EVERY_TICKS} ticks — never a whole session. Self-guards
     * on {@link #blindedRecording}: a shutdown hook can't be removed per start/stop cycle, but firing
     * it while not blinded (or with nothing to save) is a harmless no-op.
     * <p>
     * Reads {@code currentMap} directly rather than snapshotting {@code currentMap.getGrid()} first:
     * a shutdown hook runs on its own JVM-managed thread with no synchronization against the FX
     * sampling thread, so in the rare case both run at the exact same instant, this accepts a tiny
     * torn read of a few grid cells rather than adding a blocking hand-off to the FX thread — which
     * could itself be unresponsive during shutdown and stall JVM exit. Writes synchronously on this
     * hook's own thread (not via {@link #writeTextAsync}, which spawns a further daemon thread the
     * JVM's shutdown sequence does not wait for) so the write actually completes before the process
     * exits. Best-effort throughout: any failure is logged and swallowed, never thrown.
     */
    private void flushOnShutdown() {
        try {
            if (!blindedRecording || currentMap == null || currentMap.isEmpty() || blindedDir == null)
                return;
            String json = buildBlindedJson(currentUri, currentMap);
            String base = "focus-blinded__" + safe(anonymizeSlideKey(slideKey(currentUri))) + "__shutdown-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
            if (!blindedDir.exists())
                blindedDir.mkdirs();
            java.nio.file.Files.writeString(new File(blindedDir, base + ".json").toPath(), json,
                    java.nio.charset.StandardCharsets.UTF_8);
            // The checkpoint this final flush supersedes would otherwise linger as stale/duplicate
            // data in blindedDir; removal here is best-effort like everything else in this method.
            new File(blindedDir, "session-" + sessionId + ".partial.json").delete();
        } catch (Exception e) {
            logger.debug("Blinded shutdown flush failed: {}", e.getMessage());
        }
    }

    private boolean mainStageFocused() {
        return qupath.getStage() != null && qupath.getStage().isFocused();
    }

    /**
     * Wire {@link #mouseMovedHandler}/{@link #mouseExitedHandler} onto {@code v}'s view node, first
     * detaching them from whichever viewer they were previously on (if different). Passing {@code
     * null} only detaches — used when there is no active viewer, or when mouse tracking is being torn
     * down in {@link #stopBlinded()}. Always resets {@link #lastMouseImageX}/{@link
     * #lastMouseImageY} to the off-viewer sentinel on a change: a position captured against the old
     * viewer's transform is meaningless once tracking has moved elsewhere. Best-effort/no-throw — a
     * failed attach/detach is logged and swallowed, never thrown into a property-change callback.
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
                logger.debug("Could not detach cursor tracking from previous viewer: {}", e.getMessage());
            }
        }
        mouseViewer = v;
        resetMousePosition();
        if (v != null) {
            try {
                javafx.scene.Node node = v.getView();
                // MOUSE_MOVED covers normal pointer movement; MOUSE_DRAGGED keeps the position live
                // while panning (a button-down drag delivers MOVED->DRAGGED, not repeated MOVED).
                // Filters (not handlers) so this never competes with the viewer's own pan/zoom/tool
                // handlers on the same node for event consumption.
                node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, mouseMovedHandler);
                node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, mouseMovedHandler);
                node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, mouseExitedHandler);
            } catch (Exception e) {
                logger.debug("Could not attach cursor tracking to viewer: {}", e.getMessage());
            }
        }
    }

    /** Reset the cursor position to the "not over the slide viewer" sentinel ({@code -1,-1}). */
    private void resetMousePosition() {
        lastMouseImageX = -1;
        lastMouseImageY = -1;
    }

    /** Move to a new slide/viewer: persist the old map if requested, then start a fresh one. */
    private void switchTo(QuPathViewer v, ImageData<BufferedImage> id) {
        if (keepMaps && currentMap != null && !currentMap.isEmpty())
            save(currentSlide, currentUri, currentMap, defaultDir());
        // Independent of keepMaps: while blinded, always persist the finished slide's real dwell
        // time before it's discarded — otherwise switching slides mid-session would silently lose it.
        if (blindedRecording && currentMap != null && !currentMap.isEmpty()) {
            // Synchronous: the checkpoint is deleted right after, so the final fragment must be on
            // disk first — an async write could still be in flight if a crash follows, losing this
            // slide with no checkpoint fallback. A small JSON write is negligible vs. slide loading.
            saveBlindedSync(currentUri, currentMap);
            // The fragment just written now carries whatever the checkpoint was tracking for the
            // slide that's being left -- remove it so it doesn't linger as stale/duplicate data.
            deleteCheckpoint();
        }
        removeOverlay();
        currentViewer = v;
        currentImageData = id;
        // A cursor position cached against the previous image's transform is meaningless once the
        // image (or viewer) has changed -- start the new slide session at the off-viewer sentinel.
        resetMousePosition();
        if (id != null && id.getServer() != null) {
            ImageServer<BufferedImage> server = id.getServer();
            currentMap = new FocusMap(server.getWidth(), server.getHeight(), GRID_MAX);
            // Tag the new map with the mode that created it, so save() can refuse a blinded map later.
            currentMapBlinded = blindedRecording;
            currentSlide = server.getMetadata().getName();
            currentUri = firstUri(server);
            if (blindedRecording) {
                blindedTicks = 0;   // fresh slide map while blinded -> restart the checkpoint cadence
                blindedPath.clear();
                blindedPathCapped = false;
                blindedSlideStartMs = System.currentTimeMillis();
            }
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
        // Defense-in-depth: a blinded map is anonymised-JSON-only (see saveBlindedSync) and must never
        // reach the PNG/username-bearing path below, regardless of which caller reached here.
        if (currentMapBlinded)
            return;
        final String json = buildJson(slide, uri, map);
        final BufferedImage png = map.toImage();
        final String base = safe(slide) + "__" + safe(user) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
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
        // A blinded map is anonymised-JSON-only via saveBlindedSync; never re-contribute it under the
        // schema/1 counts path (mirrors the identical guard at the top of save()).
        if (currentMapBlinded)
            return;
        if (currentMap == null || currentMap.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Henüz odak verisi yok — önce izlemeyi açın.").showAndWait();
            return;
        }
        final String json = buildContributionJson(currentUri, currentMap);
        final File dir = new File(defaultDir(), "contributions");
        final String base = "focus-contribution__" + safe(anonymizeSlideKey(slideKey(currentUri))) + "__"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
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
        m.put("slideKey", anonymizeSlideKey(slideKey(uri)));   // stable across readers → server groups by this
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
     * artifact of blinded viewing should ever be produced, on disk or otherwise). Written
     * <b>synchronously</b> on the calling thread into {@link #blindedDir} -- the project's
     * {@code atlas-focus/} folder if one was open when {@link #startBlinded()} ran, else the
     * home-dir fallback -- under schema/3.
     * <p>
     * Both call sites need the write to have completed before they proceed: {@link #switchTo}
     * deletes the checkpoint immediately after (an in-flight async write followed by a crash would
     * lose the slide with no checkpoint fallback), and {@link #stopBlinded()} immediately reads the
     * directory back with {@link BlindedStore#zipFragments} (an async write could race that read and
     * ship a zip missing a slide viewed under {@link #CHECKPOINT_EVERY_TICKS}, before a checkpoint
     * landed). Hence synchronous, not fire-and-forget. Best-effort (logs and swallows any failure
     * rather than throwing into the caller).
     */
    private void saveBlindedSync(String uri, FocusMap map) {
        try {
            final String json = buildBlindedJson(uri, map);
            final String base = "focus-blinded__" + safe(anonymizeSlideKey(slideKey(uri))) + "__"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US));
            File dir = blindedDir;
            if (!dir.exists())
                dir.mkdirs();
            File file = new File(dir, base + ".json");
            java.nio.file.Files.writeString(file.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
            logger.info("Saved focus contribution to {}", file);
        } catch (Exception e) {
            logger.error("Failed to save blinded focus map: {}", e.getMessage(), e);
        }
    }

    /**
     * Blinded contribution payload — same anonymised shape as {@link #buildContributionJson}, but
     * {@code grid} holds real dwell-milliseconds (not fixed-weight sample counts): {@code
     * weightUnit="ms"} and {@code durationMs} (= {@link FocusMap#getTotalWeight()}) make that explicit.
     * Schema/3 additionally carries {@code path}: the ordered scanpath for this slide session, one
     * {@code [tRelMs, cx, cy, w, h]} point per active tick (see {@link #blindedPath}) — a defensive
     * snapshot ({@code new ArrayList<>(blindedPath)}) is taken here so a concurrent FX-thread append
     * can't corrupt Gson's in-flight serialization of the list. Schema/4 extends each path point to
     * {@code [tRelMs, cx, cy, w, h, dsMilli]} (the viewer's downsample factor at that tick, ×1000) and
     * adds a fragment-level {@code baseMagnification} (the slide's objective power, or {@code null}
     * when unavailable) so a later analysis pass can derive true magnification as {@code
     * baseMagnification / (dsMilli / 1000.0)}. Both additions are read defensively — no throw into the
     * sampling timer or a checkpoint/shutdown write if metadata is missing.
     * <p>
     * Schema/4 also adds {@code annotations}: a GeoJSON {@code FeatureCollection} (real nested JSON,
     * not a string) snapshotting {@code currentImageData}'s annotation objects at save time — geometry
     * in image pixel coordinates, plus each annotation's name/classification/description as GeoJSON
     * Feature properties, exactly as {@link qupath.lib.io.GsonTools} already serializes any {@link
     * qupath.lib.io.FeatureCollection} (used elsewhere for the quiz reference geometry -- see {@link
     * com.patolojiatlasi.qupath.quiz.QuizGeometry}). An empty hierarchy yields an empty {@code
     * FeatureCollection} ({@code {"type":"FeatureCollection","features":[]}}), never {@code null} or
     * an omitted field. Best-effort: any failure reading the hierarchy/serializing falls back to that
     * same empty FeatureCollection rather than throwing, so a missing/closed image can never break a
     * blinded save.
     * <p>
     * Schema/5 extends each {@code path} point with the cursor's position at that tick ({@code
     * mouseX}, {@code mouseY} — see {@link #blindedPath} and {@link #lastMouseImageX}), in the same
     * image-pixel coordinate space as the point's {@code cx}/{@code cy} — captured only while the
     * cursor is over the slide viewer (never a global/OS mouse position); {@code -1,-1} means the
     * cursor was off the viewer at that tick. Purely additive to each point; the fragment-level shape
     * ({@code grid}, {@code annotations}, etc.) is unchanged.
     */
    private String buildBlindedJson(String uri, FocusMap map) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", CONTRIBUTION_SCHEMA_BLINDED);
        m.put("slideKey", anonymizeSlideKey(slideKey(uri)));
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
        m.put("path", new java.util.ArrayList<>(blindedPath));   // ordered [tRelMs,cx,cy,w,h,dsMilli,mouseX,mouseY] points; defensive snapshot
        m.put("pathTruncated", blindedPathCapped);   // true if MAX_PATH_POINTS was hit and points were dropped
        Double baseMag = null;
        try {
            double x = currentImageData.getServer().getMetadata().getMagnification();
            if (!Double.isNaN(x) && x > 0)
                baseMag = x;
        } catch (Exception ignored) {
            // currentImageData/server/metadata unavailable, or magnification unreadable -- leave null.
        }
        m.put("baseMagnification", baseMag);   // objective power for the fragment's slide, or null if unknown
        m.put("annotations", buildAnnotationsFeatureCollection());   // schema/4: GeoJSON FeatureCollection, never null
        return new GsonBuilder().create().toJson(m);
    }

    /**
     * Snapshot the current slide's annotations as a GeoJSON {@code FeatureCollection} {@link
     * JsonElement} for embedding in {@link #buildBlindedJson}. Best-effort/no-throw: on any failure
     * (no image loaded, closed hierarchy, serialization error) this returns an empty {@code
     * FeatureCollection} rather than propagating -- a blinded save must never fail because of this.
     * <p>
     * Serializes via {@link GsonTools#getInstance()}'s {@code toJson(Object, Type)} (a JSON
     * <em>string</em>), then reparses with {@link JsonParser} -- deliberately <strong>not</strong>
     * {@code Gson.toJsonTree}: QuPath's own {@code ROITypeAdapters.writeCoordinates} calls the
     * streaming-only {@code JsonWriter.jsonValue(String)} to emit raw coordinate literals, which
     * {@code Gson}'s tree writer (used internally by {@code toJsonTree}) throws {@code
     * UnsupportedOperationException} on for any non-empty geometry (confirmed empirically). The
     * string round-trip avoids that path entirely and still yields a real nested {@link JsonElement}
     * once parsed -- never a quoted string in the final fragment.
     * <p>
     * Threading: {@link #checkpointBlinded()} and {@link #saveBlindedSync} both run on the FX thread
     * (driven by the sampling {@link Timeline}/a menu action), so {@code currentImageData} and its
     * hierarchy are read safely there. {@link #flushOnShutdown()} is the one caller that runs off the
     * FX thread (a JVM shutdown hook, by its own javadoc) -- {@link
     * qupath.lib.objects.hierarchy.PathObjectHierarchy#getAnnotationObjects()} returns a fresh {@code
     * ArrayList} snapshot (verified via the 0.6.0 bytecode), so collection iteration itself can't throw
     * a {@code ConcurrentModificationException}; only a per-object torn read of in-flight name/class
     * edits is possible in that narrow window, which this method's try/catch bounds to "fall back to
     * empty" -- the same tradeoff {@link #flushOnShutdown()} already accepts for {@code currentMap}.
     */
    private JsonElement buildAnnotationsFeatureCollection() {
        try {
            var hierarchy = currentImageData == null ? null : currentImageData.getHierarchy();
            var annotations = hierarchy == null ? java.util.List.<qupath.lib.objects.PathObject>of()
                    : hierarchy.getAnnotationObjects();
            FeatureCollection featureCollection = FeatureCollection.wrap(annotations);
            String json = GsonTools.getInstance().toJson(featureCollection, FeatureCollection.class);
            return JsonParser.parseString(json);
        } catch (Exception e) {
            logger.debug("Could not capture annotations for blinded fragment: {}", e.getMessage(), e);
            JsonObject empty = new JsonObject();
            empty.addProperty("type", "FeatureCollection");
            empty.add("features", new JsonArray());
            return empty;
        }
    }

    /** Stable per-slide key for aggregation: the DZI URL without any query (e.g. no {@code ?mpp=}). */
    private static String slideKey(String uri) {
        if (uri == null || uri.isBlank())
            return "unknown";
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    /**
     * Anonymizes a {@code slideKey} for the blinded payload: a locally-opened slide's key can be a
     * {@code file://} URI carrying an absolute local path -- and with it, the OS username. Atlas
     * DZI slides are stable {@code https://} URLs and are the intended cross-reader grouping key,
     * so those pass through unchanged. Anything else (any scheme other than {@code http(s)://}) is
     * replaced with a stable, non-identifying {@code sha256:<hex>} hash of the original key: the
     * same local slide always hashes the same, so grouping stays stable within/across a session
     * without ever writing the path or username to disk.
     */
    private static String anonymizeSlideKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://"))
            return key;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                sb.append(String.format(java.util.Locale.US, "%02x", b));
            return "sha256:" + sb;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM (see MessageDigest javadoc) --
            // unreachable in practice. If it ever weren't, falling back to the raw key would
            // defeat the whole point of this method, so use a fixed placeholder instead.
            logger.warn("SHA-256 unavailable, cannot anonymize slide key: {}", e.getMessage());
            return "sha256:unavailable";
        }
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
