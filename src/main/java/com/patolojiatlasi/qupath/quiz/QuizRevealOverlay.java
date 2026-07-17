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

    private static final Color STROKE_COLOR = new Color(255, 0, 255); // magenta -- distinct from
                                                                       // QuPath's default yellow/red
                                                                       // annotation/selection colors
    private static final float STROKE_WIDTH_PX = 2.5f; // on-screen (component-space) width

    private final ROI roi;

    /**
     * @param options overlay display options, from {@code viewer.getOverlayOptions()}
     * @param roi     the reference/target geometry to paint; may be {@code null} (paints nothing)
     */
    public QuizRevealOverlay(OverlayOptions options, ROI roi) {
        super(options);
        this.roi = roi;
        // AbstractOverlay's own opacity already defaults to 1.0 (javap-confirmed against 0.6.0),
        // so isVisible() is already true out of the box -- set explicitly anyway as cheap
        // insurance against that default ever changing upstream.
        setOpacity(1.0);
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion, double downsampleFactor,
            ImageData<BufferedImage> imageData, boolean paintCompletely) {
        if (roi == null)
            return;
        Graphics2D g = (Graphics2D) g2d.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke((float) (STROKE_WIDTH_PX * downsampleFactor)));
            g.setColor(STROKE_COLOR);
            g.draw(roi.getShape());
        } finally {
            g.dispose();
        }
    }
}
