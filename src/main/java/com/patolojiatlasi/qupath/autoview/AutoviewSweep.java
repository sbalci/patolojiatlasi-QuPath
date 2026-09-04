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

    /** Loop guard: a frame may never walk more legs than this. */
    private static final int MAX_LEGS_PER_FRAME = 100_000;

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
    private AutoviewPath.Plan plan;
    private int[] fieldsBeforeLeg;
    private double extentX;
    private double extentY;
    private double extentW;
    private double extentH;

    private int legIndex;
    private double legT;
    private double curX;
    private double curY;
    private double lastAppliedX = Double.NaN;
    private double lastAppliedY = Double.NaN;
    private double speedFactor;
    private long lastFrameNanos;
    private long stalledSinceMs;

    private int maskToken;

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
            adoptPlan(built);

            this.legIndex = 0;
            this.legT = 0;
            this.speedFactor = 0;
            this.lastFrameNanos = 0;
            this.stalledSinceMs = 0;
            this.probeLogTicks = 0;   // so each sweep traces its very first probe
            this.paused = false;
            this.running = true;

            AutoviewPath.Leg first = built.legs().get(0);
            curX = first.x0();
            curY = first.y0();
            lastAppliedX = Double.NaN;
            lastAppliedY = Double.NaN;

            v.getView().addEventFilter(MouseEvent.MOUSE_PRESSED, abortOnMouse);
            v.getView().addEventFilter(ScrollEvent.SCROLL, abortOnScroll);
            v.rotationProperty().addListener(rotationListener);
            qupath.viewerProperty().addListener(viewerListener);

            // Acquired last, after every refusal path, so no early return can leak it. stop()
            // releases it unconditionally, so every abort route funnels through the same release.
            suppression = focusHeatmap == null ? null : focusHeatmap.suppressTracking("autoview");

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
        plan = null;
        fieldsBeforeLeg = null;
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
            if (v == null || plan == null || v.getImageData() != pinnedImageData
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

            double pxPerSec = settings.fovPerSec() * fovAlongTravel();
            double fullStep = pxPerSec * dt;

            double[] look = lookaheadPoint(Math.max(fullStep, pxPerSec * LOOKAHEAD_SEC));
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
                if (advance(step)) {
                    finish();
                    return;
                }
                applyPosition(v);
            }
            updateReadouts();
        } catch (Exception e) {
            logger.debug("Autoview frame failed: {}", e.getMessage());
            stop();
        }
    }

    /** Advance the cursor along the plan. Returns true once the plan is exhausted. */
    private boolean advance(double step) {
        List<AutoviewPath.Leg> legs = plan.legs();
        int guard = 0;
        while (true) {
            if (++guard > MAX_LEGS_PER_FRAME) {
                logger.warn("Autoview leg walk exceeded {} legs in one frame; stopping", MAX_LEGS_PER_FRAME);
                return true;
            }
            if (legIndex >= legs.size())
                return true;

            AutoviewPath.Leg leg = legs.get(legIndex);
            double len = leg.length();
            boolean pointLeg = len <= EPS && leg.fieldCount() > 0;
            // A lone kept field is a zero-length leg. Give it one field's worth of dwell so it is
            // actually seen, rather than flashing past in a single 16 ms frame.
            double effLen = pointLeg ? fovAlongTravel() : len;

            if (effLen > 0) {
                double remaining = effLen - legT;
                if (step < remaining) {
                    legT += step;
                    double f = legT / effLen;
                    if (!pointLeg) {
                        curX = leg.x0() + (leg.x1() - leg.x0()) * f;
                        curY = leg.y0() + (leg.y1() - leg.y0()) * f;
                    }
                    return false;
                }
                step -= remaining;
            }

            legIndex++;
            legT = 0;
            if (legIndex >= legs.size()) {
                curX = leg.x1();
                curY = leg.y1();
                return true;
            }

            AutoviewPath.Leg next = legs.get(legIndex);
            curX = next.x0();
            curY = next.y0();
            if (next.jumpToStart()) {
                // A teleport is a known discontinuity; do not slew across it, and let the next frame
                // re-probe at the new location rather than gliding on stale readiness.
                speedFactor = 0;
                return false;
            }
            if (step <= 0)
                return false;
        }
    }

    /**
     * The point tile readiness is probed at. Clamped to the current leg: the lookahead is a fraction
     * of a frame, so walking further would add complexity without changing the answer.
     */
    private double[] lookaheadPoint(double distance) {
        double[] here = { curX, curY };
        if (plan == null || legIndex >= plan.legs().size())
            return here;
        AutoviewPath.Leg leg = plan.legs().get(legIndex);
        double len = leg.length();
        if (len <= EPS)
            return here;
        double f = Math.min(legT + distance, len) / len;
        return new double[] { leg.x0() + (leg.x1() - leg.x0()) * f, leg.y0() + (leg.y1() - leg.y0()) * f };
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
            if (requests == null || requests.isEmpty())
                return 1.0;

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
     * Periodic bring-up trace of the readiness probe. This exists because a broken probe is
     * invisible from the outside: a constant 1/1 means the fail-open catch is swallowing something,
     * and a constant 0/N means the tile requests are not keying the same way the paint path caches,
     * so the stall watchdog is quietly driving the sweep at full speed with no tile-awareness at
     * all. Both look exactly like a smooth glide on screen — only this log tells them apart, so a
     * healthy sweep should show the ready count varying across the range.
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
        if (Math.abs(curX - lastAppliedX) < POSITION_EPS && Math.abs(curY - lastAppliedY) < POSITION_EPS)
            return;
        lastAppliedX = curX;
        lastAppliedY = curY;
        v.setCenterPixelLocation(curX, curY);
    }

    private void finish() {
        stopInternal("Tamamlandı", true);
    }

    private double fovAlongTravel() {
        return settings.axis() == AutoviewPath.Axis.ROWS ? fovW : fovH;
    }

    private void updateReadouts() {
        if (plan == null || plan.fieldCount() <= 0)
            return;
        int done = fieldsBeforeLeg[Math.min(legIndex, fieldsBeforeLeg.length - 1)];
        if (legIndex < plan.legs().size()) {
            AutoviewPath.Leg leg = plan.legs().get(legIndex);
            double len = leg.length();
            boolean pointLeg = len <= EPS && leg.fieldCount() > 0;
            double effLen = pointLeg ? fovAlongTravel() : len;
            if (effLen > 0)
                done += (int) Math.floor(Math.min(legT / effLen, 1.0) * leg.fieldCount());
        }
        done = Math.min(done, plan.fieldCount());
        fieldIndex.set(done);
        progress.set(done / (double) plan.fieldCount());
    }

    private void adoptPlan(AutoviewPath.Plan built) {
        this.plan = built;
        this.fieldsBeforeLeg = new int[built.legs().size() + 1];
        for (int i = 0; i < built.legs().size(); i++)
            fieldsBeforeLeg[i + 1] = fieldsBeforeLeg[i] + built.legs().get(i).fieldCount();
        fieldCount.set(built.fieldCount());
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
            replanTail(mask);
        } catch (Exception e) {
            logger.debug("Autoview replan after mask failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuild the plan with the tissue filter applied and resume at the equivalent place.
     * <p>
     * Filtering only removes fields — it never reorders them or changes how many lines there are —
     * so a leg's line index is a stable key across the rebuild. That is what lets the sweep pick up
     * where it was rather than restarting.
     */
    private void replanTail(TissueMask mask) {
        AutoviewPath.Plan rebuilt = AutoviewPath.build(extentX, extentY, extentW, extentH,
                fovW, fovH, OVERLAP, settings.axis(), settings.corner(), filterFor(mask, settings));
        if (rebuilt.isEmpty())
            return;

        int currentLine = legIndex < plan.legs().size() ? plan.legs().get(legIndex).line() : Integer.MAX_VALUE;
        adoptPlan(rebuilt);

        int target = 0;
        while (target < rebuilt.legs().size() && rebuilt.legs().get(target).line() < currentLine)
            target++;
        if (target >= rebuilt.legs().size()) {
            // Everything still ahead of us turned out to be empty glass -- the sweep is done.
            finish();
            return;
        }

        legIndex = target;
        legT = 0;
        AutoviewPath.Leg leg = rebuilt.legs().get(target);
        curX = leg.x0();
        curY = leg.y0();
        speedFactor = 0;
        updateReadouts();
        // Move the viewer NOW, not on the next frame. The replan can jump the cursor a long way, and
        // until the viewer follows it the readiness probe would be asking about tiles for a region
        // that is not on screen -- which is never requested, never cached, and so reads as zero
        // readiness. That is precisely the hold-forever condition LOOKAHEAD_SEC exists to avoid; it
        // would show up as a multi-second freeze every time the mask lands.
        applyPosition(pinnedViewer);
        logger.debug("Autoview replanned with tissue mask: {} fields (was line {})",
                rebuilt.fieldCount(), currentLine);
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
