package com.patolojiatlasi.qupath.autoview;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.images.stores.DefaultImageRegionStore;
import qupath.lib.gui.images.stores.ImageRegionStoreHelpers;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Drives a serpentine auto-sweep of the active viewer: the runtime behind "Otomatik tarama".
 * <p>
 * JavaFX thread only. This is the extension's first {@link AnimationTimer} — every other timed thing
 * here samples on a {@code Timeline}, but a sweep has to move on the render pulse to look smooth.
 * <p>
 * Three things make it more than a constant-speed pan:
 * <ul>
 *   <li><b>Tile-aware pacing.</b> Atlas slides stream over HTTP, so a fixed speed outruns the tile
 *       fetches and glides across un-rendered grey. Each frame probes how much of the view ahead is
 *       already cached and paces via {@link AutoviewPacing}.</li>
 *   <li><b>Empty-area skipping.</b> A {@link TissueMask} built from the slide thumbnail drops blank
 *       fields from the plan, so the sweep does not spend minutes crossing glass.</li>
 *   <li><b>Focus-tracking suppression.</b> {@link FocusHeatmap} cannot tell a programmatic pan from
 *       a human one, so the sweep holds a suppression handle for its whole duration.</li>
 * </ul>
 */
final class AutoviewSweep {

    private static final Logger logger = LoggerFactory.getLogger(AutoviewSweep.class);

    /**
     * How far ahead of the current position tile readiness is probed, in seconds of travel.
     * <p>
     * <b>This must stay small.</b> {@link ImageRegionStoreHelpers#getTilesToRequest} only computes
     * which tiles a region needs — it does not enqueue them. Only the viewer's own paint enqueues
     * tiles, and only for what is actually on screen. So a lookahead band beyond the viewport edge
     * would never be requested, never be cached, and its readiness would be pinned at zero: the
     * sweep would hold, the band would never come on screen, and the hold would never lift. A
     * sub-frame lookahead keeps the early warning while staying inside the region the viewer has
     * already asked for, so any penalty is bounded and self-recovering. {@code 0.0} degenerates to
     * "probe the current viewport", which is the always-safe fallback.
     */
    private static final double LOOKAHEAD_SEC = 0.15;

    /** Fraction of a field shared with its neighbours, so nothing falls between two fields. */
    private static final double OVERLAP = 0.10;

    /** After this long at a standstill, assume the readiness probe is lying and push on regardless. */
    private static final long STALL_MS = 5000;

    /** Endpoints closer than this (image px) count as the same point. */
    private static final double EPS = 1e-6;

    /** Below this movement (image px) the viewer is not asked to re-centre at all. */
    private static final double POSITION_EPS = 1e-3;

    /** Longest side of the tissue-mask grid. */
    private static final int MASK_GRID_MAX = TissueMask.DEFAULT_GRID_MAX;

    /** Frames between readiness debug traces -- roughly once a second at 60 Hz. */
    private static final int PROBE_LOG_EVERY = 60;

    /** Sweep parameters, as chosen in the control window. */
    record Settings(double fovPerSec, AutoviewPath.Axis axis, AutoviewPath.Corner corner,
                    boolean skipEmpty, double tissueThreshold, double tissueSensitivity) {}

    // Single-entry mask cache: re-sweeping the same slide is instant, without retaining a mask for
    // every slide ever opened (288 slides x 192^2 floats would be ~42 MB of garbage).
    private static String cachedMaskPath;
    private static TissueMask cachedMask;

    private final QuPathGUI qupath;
    private final FocusHeatmap focusHeatmap;

    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0);
    private final ReadOnlyIntegerWrapper fieldIndex = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper fieldCount = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");

    private Runnable onStateChanged;

    private AnimationTimer timer;
    private boolean running;
    private boolean paused;

    // Pinned at start(); every frame checks these still hold before touching anything.
    private QuPathViewer pinnedViewer;
    private ImageData<BufferedImage> pinnedImageData;
    private ImageServer<BufferedImage> pinnedServer;
    private double pinnedDownsample;
    private double fovW;
    private double fovH;
    private int zPos;
    private int tPos;

    private Settings settings;
    private AutoviewCursor cursor;
    private double extentX;
    private double extentY;
    private double extentW;
    private double extentH;

    private double lastAppliedX = Double.NaN;
    private double lastAppliedY = Double.NaN;
    private double speedFactor;
    private long lastFrameNanos;
    private long stalledSinceMs;

    private int maskToken;

    /**
     * A mask that arrived while the sweep was paused. Applying a replan immediately would teleport
     * the view out from under a reader who paused precisely to look at something, so it is held here
     * and applied on {@link #resume()}.
     */
    private TissueMask pendingMask;

    private int lastProbeReady = -1;
    private int lastProbeTotal = -1;
    private long probeLogTicks;

    private FocusHeatmap.Suppression suppression;

    // Reused across frames: avoids an allocation per frame at 60 Hz, and sidesteps any question of
    // whether getTilesToRequest tolerates a null list.
    private final List<RegionRequest> probeScratch = new ArrayList<>();

    private final ChangeListener<QuPathViewer> viewerListener = (obs, was, now) -> {
        if (running && now != pinnedViewer)
            stop();
    };
    private final ChangeListener<Number> rotationListener = (obs, was, now) -> {
        if (running && Math.abs(now.doubleValue()) > EPS)
            stop();
    };
    private final EventHandler<MouseEvent> abortOnMouse = e -> {
        if (running)
            stop();
    };
    private final EventHandler<ScrollEvent> abortOnScroll = e -> {
        if (running)
            stop();
    };

    AutoviewSweep(QuPathGUI qupath, FocusHeatmap focusHeatmap) {
        this.qupath = qupath;
        this.focusHeatmap = focusHeatmap;
    }

    // --- public surface ------------------------------------------------------

    /**
     * Begin a sweep with the given settings.
     *
     * @return {@code null} on success, or a Turkish message explaining why it cannot start
     */
    String start(Settings s) {
        stop();

        QuPathViewer v = qupath.getViewer();
        if (v == null)
            return "Etkin bir görüntüleyici yok.";
        ImageData<BufferedImage> id = v.getImageData();
        if (id == null)
            return "Önce bir slayt açın.";
        if (Math.abs(v.getRotation()) > EPS)
            return "Otomatik tarama döndürülmüş görüntüde çalışmaz; önce açıyı sıfırlayın.";

        double viewW = v.getView().getWidth();
        double viewH = v.getView().getHeight();
        if (!(viewW > 0) || !(viewH > 0))
            return "Görüntüleyici henüz boyutlandırılmadı.";

        ImageServer<BufferedImage> server = v.getServer();
        if (server == null)
            return "Slayt görüntü sunucusu okunamadı.";

        double ds = v.getDownsampleFactor();
        if (!(ds > 0))
            return "Geçersiz büyütme; slaytta bir kez yakınlaşıp tekrar deneyin.";

        double[] extent = extentFor(v, server);
        double fw = viewW * ds;
        double fh = viewH * ds;

        // Start on the unfiltered plan so the sweep begins immediately; the tissue mask replans the
        // unvisited tail when it lands (it is an HTTP fetch and can take seconds).
        TissueMask mask = s.skipEmpty() ? maskFor(server) : null;
        AutoviewPath.Plan built = AutoviewPath.build(extent[0], extent[1], extent[2], extent[3],
                fw, fh, OVERLAP, s.axis(), s.corner(), filterFor(mask, s));
        if (built.isEmpty())
            return "Taranacak alan bulunamadı.";

        try {
            this.settings = s;
            this.pinnedViewer = v;
            this.pinnedImageData = id;
            this.pinnedServer = server;
            this.pinnedDownsample = ds;
            this.fovW = fw;
            this.fovH = fh;
            this.zPos = v.getZPosition();
            this.tPos = v.getTPosition();
            this.extentX = extent[0];
            this.extentY = extent[1];
            this.extentW = extent[2];
            this.extentH = extent[3];
            this.cursor = new AutoviewCursor(built, fovAlongTravel(s.axis()), s.axis());
            fieldCount.set(built.fieldCount());

            this.speedFactor = 0;
            this.lastFrameNanos = 0;
            this.stalledSinceMs = 0;
            this.probeLogTicks = 0;   // so each sweep traces its very first probe
            this.pendingMask = null;
            this.paused = false;
            this.running = true;

            lastAppliedX = Double.NaN;
            lastAppliedY = Double.NaN;

            v.getView().addEventFilter(MouseEvent.MOUSE_PRESSED, abortOnMouse);
            v.getView().addEventFilter(ScrollEvent.SCROLL, abortOnScroll);
            v.rotationProperty().addListener(rotationListener);
            qupath.viewerProperty().addListener(viewerListener);

            // Acquired last, after every refusal path, so no early return can leak it. stop()
            // releases it unconditionally, so every abort route funnels through the same release.
            suppression = focusHeatmap == null ? null : focusHeatmap.suppressTracking("autoview");

            // Verified against the 0.6.0 bytecode: doFasterRepaint is read in exactly two places --
            // it selects nearest-neighbour instead of bilinear interpolation, and it clears the
            // `paintCompletely` flag handed to PathOverlay.paintOverlay. It does NOT take part in
            // choosing the downsample or the tile set, so it cannot shift the painted resolution
            // level away from the one readyFraction() probes.
            v.setDoFasterRepaint(true);
            // Pan only. The viewer is already at pinnedDownsample (we just read it from there), and
            // re-setting the zoom could have it clamped to a different value, which would leave the
            // readiness probe querying a different resolution level than the one being painted.
            applyPosition(v);

            status.set("Tarıyor");
            updateReadouts();
            startMaskBuild(s, mask);

            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    frame(now);
                }
            };
            timer.start();
            fireStateChanged();
            return null;
        } catch (Exception e) {
            logger.warn("Autoview could not start: {}", e.getMessage(), e);
            stop();
            return "Otomatik tarama başlatılamadı: " + e.getMessage();
        }
    }

    void pause() {
        if (!running || paused)
            return;
        paused = true;
        // Genuinely stop the timer -- a paused sweep must not keep pulsing at 60 Hz.
        if (timer != null)
            timer.stop();
        safely(() -> pinnedViewer.setDoFasterRepaint(false));
        status.set("Duraklatıldı");
        fireStateChanged();
    }

    void resume() {
        if (!running || !paused)
            return;
        paused = false;
        lastFrameNanos = 0;   // do not credit the pause as one giant frame
        speedFactor = 0;
        safely(() -> pinnedViewer.setDoFasterRepaint(true));
        // A mask that landed during the pause is applied now, not while the reader was looking.
        if (pendingMask != null) {
            TissueMask held = pendingMask;
            pendingMask = null;
            safely(() -> replanTail(held));
            // The replan can finish the sweep outright (nothing but glass left ahead), which already
            // tore everything down. Carrying on would leave a stopped sweep reading "Tarıyor".
            if (!running)
                return;
        }
        if (timer != null)
            timer.start();
        status.set("Tarıyor");
        fireStateChanged();
    }

    /** Idempotent: safe from the timer, an abort filter, window close, or an exception path. */
    void stop() {
        stopInternal("", false);
    }

    /**
     * @param completed true when the plan ran to its end, in which case the progress readout is left
     *                  showing a finished sweep rather than being reset to zero
     */
    private void stopInternal(String finalStatus, boolean completed) {
        boolean wasRunning = running;
        running = false;
        paused = false;

        if (timer != null) {
            safely(timer::stop);
            timer = null;
        }
        QuPathViewer v = pinnedViewer;
        if (v != null) {
            safely(() -> v.setDoFasterRepaint(false));
            safely(v::repaint);
            safely(() -> v.getView().removeEventFilter(MouseEvent.MOUSE_PRESSED, abortOnMouse));
            safely(() -> v.getView().removeEventFilter(ScrollEvent.SCROLL, abortOnScroll));
            safely(() -> v.rotationProperty().removeListener(rotationListener));
        }
        safely(() -> qupath.viewerProperty().removeListener(viewerListener));

        pinnedViewer = null;
        pinnedImageData = null;
        pinnedServer = null;
        cursor = null;
        pendingMask = null;
        maskToken++;   // invalidates any in-flight mask build

        if (suppression != null) {
            try {
                suppression.close();
            } catch (Exception e) {
                logger.debug("Releasing autoview suppression failed: {}", e.getMessage());
            } finally {
                suppression = null;
            }
        }

        if (completed) {
            progress.set(1);
            fieldIndex.set(fieldCount.get());
        } else {
            progress.set(0);
            fieldIndex.set(0);
            fieldCount.set(0);
        }
        status.set(finalStatus);
        if (wasRunning)
            fireStateChanged();
    }

    boolean isRunning() {
        return running;
    }

    boolean isPaused() {
        return paused;
    }

    /** Live speed adjustment; takes effect on the next frame. */
    void setFovPerSec(double v) {
        if (settings != null && v > 0)
            settings = new Settings(v, settings.axis(), settings.corner(), settings.skipEmpty(),
                    settings.tissueThreshold(), settings.tissueSensitivity());
    }

    ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    ReadOnlyIntegerProperty fieldIndexProperty() {
        return fieldIndex.getReadOnlyProperty();
    }

    ReadOnlyIntegerProperty fieldCountProperty() {
        return fieldCount.getReadOnlyProperty();
    }

    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    void setOnStateChanged(Runnable r) {
        this.onStateChanged = r;
    }

    // --- the frame loop ------------------------------------------------------

    /**
     * One render pulse. The whole body is guarded: a broken sweep must halt cleanly, never throw
     * into the FX event queue and never spin.
     * <p>
     * Note that a modal dialog spins a nested event loop in which animation pulses keep firing, so a
     * sweep would keep moving behind one. In practice unreachable: the only modal near this path is
     * the focus-heatmap leave prompt, which fires on a slide change, and a slide change aborts the
     * sweep in this same method.
     */
    private void frame(long nowNanos) {
        try {
            if (!running || paused)
                return;

            QuPathViewer v = pinnedViewer;
            if (v == null || cursor == null || v.getImageData() != pinnedImageData
                    || qupath.getViewer() != v || Math.abs(v.getRotation()) > EPS) {
                stop();
                return;
            }

            if (lastFrameNanos == 0) {
                lastFrameNanos = nowNanos;
                return;
            }
            double dt = (nowNanos - lastFrameNanos) / 1_000_000_000.0;
            lastFrameNanos = nowNanos;
            if (!(dt > 0))
                return;
            dt = Math.min(dt, AutoviewPacing.MAX_DT);

            double pxPerSec = settings.fovPerSec() * fovAlongTravel(settings.axis());
            double fullStep = pxPerSec * dt;

            double[] look = cursor.lookahead(Math.max(fullStep, pxPerSec * LOOKAHEAD_SEC));
            double ready = readyFraction(look[0], look[1]);
            speedFactor = AutoviewPacing.speedFactor(ready, speedFactor, dt);
            traceReadiness(ready);

            long nowMs = System.currentTimeMillis();
            if (speedFactor < 0.02) {
                if (stalledSinceMs == 0) {
                    stalledSinceMs = nowMs;
                } else if (nowMs - stalledSinceMs > STALL_MS) {
                    logger.debug("Autoview stalled for {} ms at readiness {}; forcing on", STALL_MS, ready);
                    speedFactor = 1.0;
                    stalledSinceMs = 0;
                }
                status.set("Karo bekleniyor");
            } else {
                stalledSinceMs = 0;
                status.set("Tarıyor");
            }

            double step = fullStep * speedFactor;
            if (step > 0) {
                AutoviewCursor.Event event = cursor.advance(step);
                if (event == AutoviewCursor.Event.FINISHED) {
                    applyPosition(v);
                    finish();
                    return;
                }
                if (event == AutoviewCursor.Event.JUMPED) {
                    // A teleport is a known discontinuity: do not slew across it, and let the next
                    // frame re-probe at the new location rather than gliding on stale readiness.
                    speedFactor = 0;
                }
                applyPosition(v);
            }
            updateReadouts();
        } catch (Exception e) {
            logger.debug("Autoview frame failed: {}", e.getMessage());
            stop();
        }
    }

    /**
     * Fraction of the tiles covering the view centred at {@code (cx, cy)} that are already cached.
     * <p>
     * Uses {@link ImageRegionStoreHelpers#getTilesToRequest} rather than building a
     * {@link RegionRequest} by hand: that is the painting path's own request builder, so its
     * requests are exactly the keys the store caches under. A hand-built request would not guarantee
     * that parity and would silently read as "nothing cached".
     *
     * @return 0..1, or 1.0 if readiness cannot be determined -- a failed probe must never wedge the
     *         sweep, so this fails open
     */
    private double readyFraction(double cx, double cy) {
        try {
            ImageServer<BufferedImage> server = pinnedServer;
            DefaultImageRegionStore store = qupath.getImageRegionStore();
            if (server == null || store == null)
                return 1.0;

            probeScratch.clear();
            Shape shape = new Rectangle2D.Double(cx - fovW / 2, cy - fovH / 2, fovW, fovH);
            List<RegionRequest> requests = ImageRegionStoreHelpers.getTilesToRequest(
                    server, shape, pinnedDownsample, zPos, tPos, probeScratch);
            if (requests == null || requests.isEmpty()) {
                lastProbeReady = 0;
                lastProbeTotal = 0;   // 0/0 in the trace means "no tiles asked for"
                return 1.0;
            }

            int ready = 0;
            for (RegionRequest request : requests) {
                if (store.getCachedTile(server, request) != null)
                    ready++;
            }
            lastProbeReady = ready;
            lastProbeTotal = requests.size();
            return (double) ready / requests.size();
        } catch (Exception e) {
            logger.trace("Autoview readiness probe failed: {}", e.getMessage());
            lastProbeReady = -1;
            lastProbeTotal = -1;
            return 1.0;
        }
    }

    /**
     * Periodic bring-up trace of the readiness probe, because a broken probe is nearly invisible
     * from the outside. The signatures to look for:
     * <ul>
     *   <li><b>{@code N/M} varying across the range</b> — healthy.</li>
     *   <li><b>{@code -1/-1}</b> — the probe threw and the fail-open catch is swallowing it; the
     *       sweep is running with no tile-awareness at all.</li>
     *   <li><b>{@code 0/0}</b> — no tiles were asked for (off-image, or a degenerate viewport).</li>
     *   <li><b>constant {@code 0/M}</b> — the requests are not keying the way the paint path caches.
     *       This does <em>not</em> degrade to full speed: the watchdog lifts the factor to 1.0, slew
     *       drags it back under the 0.02 threshold within ~20 frames, and the watchdog re-arms — so
     *       the sweep crawls at roughly 3 % speed, lurching about every {@value #STALL_MS} ms with
     *       the status stuck on "Karo bekleniyor". Visible, but easy to misread as a slow network.</li>
     * </ul>
     */
    private void traceReadiness(double ready) {
        if (!logger.isDebugEnabled())
            return;
        if (++probeLogTicks % PROBE_LOG_EVERY != 0 && probeLogTicks != 1)
            return;
        logger.debug("Autoview readiness {}/{} ({}), speedFactor {}",
                lastProbeReady, lastProbeTotal,
                String.format(java.util.Locale.US, "%.2f", ready),
                String.format(java.util.Locale.US, "%.2f", speedFactor));
    }

    /**
     * Move the viewer, but only when the position actually changed. A lone field is held for a
     * dwell during which the cursor does not move, and re-issuing the same centre every frame would
     * ask the viewer to repaint 60 times a second for nothing.
     */
    private void applyPosition(QuPathViewer v) {
        if (cursor == null)
            return;
        double x = cursor.getX();
        double y = cursor.getY();
        if (Math.abs(x - lastAppliedX) < POSITION_EPS && Math.abs(y - lastAppliedY) < POSITION_EPS)
            return;
        lastAppliedX = x;
        lastAppliedY = y;
        v.setCenterPixelLocation(x, y);
    }

    private void finish() {
        stopInternal("Tamamlandı", true);
    }

    private double fovAlongTravel(AutoviewPath.Axis axis) {
        return axis == AutoviewPath.Axis.ROWS ? fovW : fovH;
    }

    private void updateReadouts() {
        if (cursor == null)
            return;
        fieldIndex.set(cursor.fieldsVisited());
        progress.set(cursor.progress());
    }

    // --- extent and tissue mask ---------------------------------------------

    /** The selected object's bounding box if there is one, else the whole slide. */
    private static double[] extentFor(QuPathViewer v, ImageServer<BufferedImage> server) {
        PathObject selected = v.getSelectedObject();
        if (selected != null && selected.hasROI()) {
            ROI roi = selected.getROI();
            if (roi != null && roi.getBoundsWidth() > 0 && roi.getBoundsHeight() > 0)
                return new double[] { roi.getBoundsX(), roi.getBoundsY(),
                        roi.getBoundsWidth(), roi.getBoundsHeight() };
        }
        ImageRegion bounds = v.getServerBounds();
        if (bounds != null && bounds.getWidth() > 0 && bounds.getHeight() > 0)
            return new double[] { bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight() };
        return new double[] { 0, 0, server.getWidth(), server.getHeight() };
    }

    private static AutoviewPath.FieldFilter filterFor(TissueMask mask, Settings s) {
        if (mask == null || mask.isEmpty())
            return null;   // no usable mask: sweep everything rather than nothing
        double threshold = s.tissueThreshold();
        return (cx, cy, w, h) -> mask.coverage(cx - w / 2, cy - h / 2, w, h) >= threshold;
    }

    private static TissueMask maskFor(ImageServer<BufferedImage> server) {
        try {
            String path = server.getPath();
            if (path != null && path.equals(cachedMaskPath))
                return cachedMask;
        } catch (Exception e) {
            logger.debug("Autoview mask cache lookup failed: {}", e.getMessage());
        }
        return null;
    }

    /** Fetch the thumbnail off the FX thread and re-filter the unvisited tail when it lands. */
    private void startMaskBuild(Settings s, TissueMask alreadyHave) {
        if (!s.skipEmpty() || alreadyHave != null)
            return;
        ImageServer<BufferedImage> server = pinnedServer;
        if (server == null)
            return;

        int token = ++maskToken;
        Thread t = new Thread(() -> {
            TissueMask built = null;
            try {
                BufferedImage thumb = server.getDefaultThumbnail(zPos, tPos);
                if (thumb != null) {
                    int w = thumb.getWidth();
                    int h = thumb.getHeight();
                    int[] argb = thumb.getRGB(0, 0, w, h, null, 0, w);
                    built = TissueMask.fromThumbnail(argb, w, h, server.getWidth(), server.getHeight(),
                            MASK_GRID_MAX, s.tissueSensitivity());
                }
            } catch (Exception e) {
                // Network hiccup or an unreadable thumbnail: the unfiltered plan simply stands.
                logger.debug("Autoview tissue mask could not be built: {}", e.getMessage());
            }
            TissueMask result = built;
            Platform.runLater(() -> onMaskReady(token, server, result));
        }, "atlas-autoview-mask");
        t.setDaemon(true);
        t.start();
    }

    private void onMaskReady(int token, ImageServer<BufferedImage> server, TissueMask mask) {
        try {
            if (token != maskToken || !running || server != pinnedServer || mask == null || mask.isEmpty())
                return;
            cachedMaskPath = server.getPath();
            cachedMask = mask;
            if (paused) {
                // Replanning now would teleport the view out from under a reader who paused in order
                // to look at something. Hold it; resume() applies it.
                pendingMask = mask;
                return;
            }
            replanTail(mask);
        } catch (Exception e) {
            logger.debug("Autoview replan after mask failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuild the plan with the tissue filter applied and carry on from the equivalent place.
     * <p>
     * The re-seating itself is {@link AutoviewCursor#resumeOn}, which is where the "never move
     * backwards" guarantee and its tests live.
     */
    private void replanTail(TissueMask mask) {
        if (cursor == null)
            return;
        AutoviewPath.Plan rebuilt = AutoviewPath.build(extentX, extentY, extentW, extentH,
                fovW, fovH, OVERLAP, settings.axis(), settings.corner(), filterFor(mask, settings));
        if (rebuilt.isEmpty())
            return;

        int wasLine = cursor.currentLine();
        AutoviewCursor.Event event = cursor.resumeOn(rebuilt, settings.corner());
        fieldCount.set(rebuilt.fieldCount());
        if (event == AutoviewCursor.Event.FINISHED) {
            // Everything still ahead of us turned out to be empty glass.
            applyPosition(pinnedViewer);
            finish();
            return;
        }

        speedFactor = 0;
        updateReadouts();
        // Move the viewer NOW, not on the next frame. The replan can jump the cursor a long way, and
        // until the viewer follows it the readiness probe would be asking about tiles for a region
        // that is not on screen -- which is never requested, never cached, and so reads as zero
        // readiness. That is precisely the hold-forever condition LOOKAHEAD_SEC exists to avoid; it
        // would show up as a multi-second freeze every time the mask lands.
        applyPosition(pinnedViewer);
        logger.debug("Autoview replanned with tissue mask: {} fields, {} (was line {})",
                rebuilt.fieldCount(), event, wasLine);
    }

    // --- helpers -------------------------------------------------------------

    private void fireStateChanged() {
        if (onStateChanged != null) {
            try {
                onStateChanged.run();
            } catch (Exception e) {
                logger.debug("Autoview state callback failed: {}", e.getMessage());
            }
        }
    }

    /** Run a teardown step, swallowing anything it throws so the rest of teardown still happens. */
    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            logger.debug("Autoview teardown step failed: {}", e.getMessage());
        }
    }
}
