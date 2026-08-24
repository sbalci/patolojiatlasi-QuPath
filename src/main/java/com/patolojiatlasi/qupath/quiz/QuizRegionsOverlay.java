package com.patolojiatlasi.qupath.quiz;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

/**
 * Draws every stop region of {@link QuizBrowseWindow}'s browse mode at once, the selected one
 * thicker/highlighted and every region labelled with its 1-based stop number — unlike
 * {@link QuizRevealOverlay}, which paints a single one-shot reveal ROI, this overlay is a
 * persistent, always-visible index over a whole slide's stops. One instance per viewer; entries
 * in {@code rois} may be {@code null} (a stop with no geometry) and are simply skipped.
 * <p>
 * Inspired by QuPath Edu's always-visible annotation pins over a slide (Yli-Hallila et al.,
 * <em>Journal of Anatomy</em> 2025;246(5):846-856, doi:10.1111/joa.14172) — a clean-room
 * reimplementation; QuPath Edu's extension/server repos carry no license, so no code from that
 * project is used. Drawing follows {@link QuizRevealOverlay}'s conventions: {@link Graphics2D} is
 * already transformed into full-resolution image space by the viewer, so {@link ROI#getShape()}
 * is drawn as-is and only the stroke/font sizes (specified on-screen) are scaled by
 * {@code downsample} to keep a constant on-screen size at any zoom.
 */
public class QuizRegionsOverlay extends AbstractOverlay {

    private final List<ROI> rois;
    // See QuizRevealOverlay.pinnedImageData: identity pin so a slide swapped under the viewer by
    // another window never gets these regions painted over it in the wrong pixel space.
    private final ImageData<BufferedImage> pinnedImageData;

    /** -1 = nothing selected (every region drawn in the default, thinner style). */
    private volatile int selectedIndex = -1;

    /**
     * @param options overlay display options, from {@code viewer.getOverlayOptions()}
     * @param rois    one entry per stop, index-aligned with {@link QuizBrowseWindow}'s stop list;
     *                a {@code null} entry (a stop with no geometry) is skipped when painting
     */
    public QuizRegionsOverlay(OverlayOptions options, List<ROI> rois) {
        this(options, rois, null);
    }

    /**
     * @param pinnedImageData the slide these regions belong to (typically {@code viewer.getImageData()}
     *                        at creation); when non-null the overlay paints nothing while the viewer
     *                        shows a different ImageData -- the same stale-slide guard as
     *                        {@link QuizRevealOverlay}. {@code null} = always paint.
     */
    public QuizRegionsOverlay(OverlayOptions options, List<ROI> rois,
                              ImageData<BufferedImage> pinnedImageData) {
        super(options);
        this.rois = rois;
        this.pinnedImageData = pinnedImageData;
        // AbstractOverlay's own opacity already defaults to 1.0 (javap-confirmed against 0.6.0,
        // see QuizRevealOverlay) -- set explicitly anyway as cheap insurance against that default
        // ever changing upstream.
        setOpacity(1.0);
    }

    /** Change which stop (0-based index into the constructor's {@code rois}) is drawn thicker/
     *  highlighted; {@code -1} selects none. Does not repaint by itself -- callers must also
     *  {@code viewer.repaint()} (see {@link QuizBrowseWindow}). */
    public void setSelectedIndex(int i) {
        this.selectedIndex = i;
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion region, double downsample,
            ImageData<BufferedImage> imageData, boolean paintCompletely) {
        if (pinnedImageData != null && pinnedImageData != imageData)
            return;   // stale-slide guard (see field comment)
        Graphics2D g = (Graphics2D) g2d.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < rois.size(); i++) {
                ROI roi = rois.get(i);
                if (roi == null)
                    continue;
                boolean selected = i == selectedIndex;
                g.setStroke(new BasicStroke((float) ((selected ? 4.0 : 2.0) * downsample)));
                g.setColor(selected ? new Color(255, 0, 255) : new Color(0, 120, 215));
                try {
                    g.draw(roi.getShape());
                } catch (UnsupportedOperationException noShape) {
                    // Points-type ROIs have no AWT shape -- skip this ROI entirely (outline AND
                    // label), mirroring QuizRevealOverlay's shape-draw try/catch shape.
                    continue;
                }
                // Index label at the region's top-left, sized in image space so it stays a
                // constant on-screen size at any zoom -- same downsample-scaling rationale as the
                // stroke width above.
                g.setFont(g.getFont().deriveFont((float) (14.0 * downsample)));
                g.drawString(String.valueOf(i + 1),
                        (float) roi.getBoundsX(), (float) Math.max(roi.getBoundsY() - 4 * downsample, 12 * downsample));
            }
        } finally {
            g.dispose();
        }
    }
}
