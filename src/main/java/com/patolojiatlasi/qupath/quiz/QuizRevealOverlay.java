package com.patolojiatlasi.qupath.quiz;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

/**
 * A one-shot "reveal" overlay for the quiz runner: paints a single reference/target {@link ROI}'s
 * shape in a distinct, high-contrast stroke so a learner can compare it against their own answer
 * (ANNOTATION questions) or see where they should have navigated to (NAVIGATION questions).
 * <p>
 * A vector draw (rather than {@code BufferedImageOverlay}, see {@link com.patolojiatlasi.qupath.focus.FocusHeatmap})
 * is the simpler correct choice here: the shape is a single small ROI, not a whole-slide raster
 * grid, so there is no rasterization/resolution tradeoff to manage -- {@link ROI#getShape()}
 * already gives a full-resolution {@link java.awt.Shape} that can be drawn directly.
 * <p>
 * {@link #paintOverlay} receives {@code g2d} already transformed by QuPath into full-resolution
 * image space (the viewer applies the downsample scale to the {@link Graphics2D} before invoking
 * this method -- confirmed against QuPath's built-in overlays, e.g. {@code GridOverlay} /
 * {@code HierarchyOverlay}, which draw full-resolution coordinates directly with no additional
 * {@code Graphics2D.scale} call). So {@link ROI#getShape()} -- itself already in full-resolution
 * image coordinates -- is drawn as-is, with no extra scaling of the graphics context; applying
 * {@code 1.0 / downsampleFactor} on top would double-apply the transform and push the shape off
 * canvas at any non-1:1 zoom. Only the stroke width needs adjusting: it is specified on-screen
 * (component-space) but drawn in image space, so it is multiplied by {@code downsampleFactor} (a
 * bigger downsample means more image pixels per screen pixel, i.e. zoomed out, so the stroke needs
 * to be wider in image px to keep a constant on-screen width).
 */
public class QuizRevealOverlay extends AbstractOverlay {

    /** Reveal (answer) stroke: magenta -- distinct from QuPath's default yellow/red
     *  annotation/selection colors. */
    public static final Color REVEAL_COLOR = new Color(255, 0, 255);
    /** Per-stop highlight stroke: amber -- distinct from {@link #REVEAL_COLOR} so a stop that shows
     *  both its highlight and (after "Göster") its reference/target stays readable. */
    public static final Color HIGHLIGHT_COLOR = new Color(255, 160, 0);
    private static final float STROKE_WIDTH_PX = 2.5f; // on-screen (component-space) width

    private final ROI roi;
    private final Color strokeColor;
    // The ImageData this overlay's geometry belongs to, or null for "any". When set, paintOverlay
    // draws nothing while the viewer shows a different ImageData -- the stale-slide guard: another
    // window (browse mode, File > Open…) can swap the slide under the viewer this overlay is
    // attached to, and without the pin the ROI would keep painting in the OLD slide's pixel space
    // over the NEW slide. Identity comparison is deliberate: the same slide reopened is a new
    // ImageData, and the owning window re-shows its overlays on its own navigation anyway.
    private final ImageData<BufferedImage> pinnedImageData;

    /**
     * Reveal-colored overlay with no slide pin (legacy behaviour).
     *
     * @param options overlay display options, from {@code viewer.getOverlayOptions()}
     * @param roi     the reference/target geometry to paint; may be {@code null} (paints nothing)
     */
    public QuizRevealOverlay(OverlayOptions options, ROI roi) {
        this(options, roi, REVEAL_COLOR, null);
    }

    /**
     * @param options         overlay display options, from {@code viewer.getOverlayOptions()}
     * @param roi             the geometry to paint; may be {@code null} (paints nothing)
     * @param strokeColor     {@link #REVEAL_COLOR}, {@link #HIGHLIGHT_COLOR}, or any color; null → reveal
     * @param pinnedImageData the slide this geometry belongs to (typically {@code viewer.getImageData()}
     *                        at creation); when non-null the overlay stays invisible while the viewer
     *                        shows a different ImageData. May be {@code null} (always paints).
     */
    public QuizRevealOverlay(OverlayOptions options, ROI roi, Color strokeColor,
                             ImageData<BufferedImage> pinnedImageData) {
        super(options);
        this.roi = roi;
        this.strokeColor = strokeColor == null ? REVEAL_COLOR : strokeColor;
        this.pinnedImageData = pinnedImageData;
        // AbstractOverlay's own opacity already defaults to 1.0 (javap-confirmed against 0.6.0),
        // so isVisible() is already true out of the box -- set explicitly anyway as cheap
        // insurance against that default ever changing upstream.
        setOpacity(1.0);
    }

    /** True when this overlay would currently paint for {@code imageData} (pin check). */
    public boolean appliesTo(ImageData<BufferedImage> imageData) {
        return pinnedImageData == null || pinnedImageData == imageData;
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion, double downsampleFactor,
            ImageData<BufferedImage> imageData, boolean paintCompletely) {
        if (roi == null || !appliesTo(imageData))
            return;
        Graphics2D g = (Graphics2D) g2d.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke((float) (STROKE_WIDTH_PX * downsampleFactor)));
            g.setColor(strokeColor);
            try {
                g.draw(roi.getShape());
            } catch (UnsupportedOperationException noShape) {
                // Points-type ROIs have no AWT shape -- draw a small marker at each point (full-res coords).
                double r = 5.0 * downsampleFactor;
                for (qupath.lib.geom.Point2 p : roi.getAllPoints()) {
                    g.draw(new java.awt.geom.Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r));
                }
            }
        } finally {
            g.dispose();
        }
    }
}
