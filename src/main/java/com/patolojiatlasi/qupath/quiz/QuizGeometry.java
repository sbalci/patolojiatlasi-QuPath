package com.patolojiatlasi.qupath.quiz;

import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/**
 * Serialize a QuPath {@link ROI} to a GeoJSON geometry string and back, for storing reference /
 * target geometries inside a quiz-pack JSON file (see {@link QuizQuestion#getReferenceGeometryGeoJson()}
 * and {@link QuizQuestion#getTargetGeometryGeoJson()}).
 *
 * <p>QuPath's {@link GsonTools#getInstance()} registers a dedicated {@code ROITypeAdapter} that reads
 * and writes {@link ROI} instances directly as GeoJSON geometry objects (a {@code "type"}/{@code
 * "coordinates"} pair plus a QuPath-specific {@code "plane"} member for {@code c}/{@code z}/{@code t}
 * -- no surrounding GeoJSON {@code Feature} wrapper needed). This is the same format QuPath itself uses
 * for {@code PathObject} geometry when exporting/importing annotations. Round-tripping through the plain
 * {@link ROI} type therefore works without wrapping in a {@code PathObject}; confirmed empirically by
 * probing the adapter's output (a rectangle ROI created on a non-default plane serializes with
 * {@code "plane":{"c":-1,"z":2,"t":3}} and deserializes back onto that same plane).
 */
public final class QuizGeometry {

    private QuizGeometry() {
    }

    /** Serialize a ROI to a GeoJSON geometry string (e.g. {@code {"type":"Polygon",...}}). */
    public static String toGeoJson(ROI roi) {
        return GsonTools.getInstance().toJson(roi, ROI.class);
    }

    /**
     * Parse a GeoJSON geometry string back to a ROI.
     *
     * <p>The GeoJSON produced by {@link #toGeoJson(ROI)} always encodes its own plane (a {@code
     * "plane":{"c":...,"z":...,"t":...}} member), so the deserialized ROI carries that plane regardless
     * of {@code plane} -- the argument is accepted (and used by callers such as Task 7/8, which pass the
     * question's plane) but is intentionally not applied here; it exists for signature symmetry /
     * forward use rather than to override an already-encoded plane.
     */
    public static ROI fromGeoJson(String geoJson, ImagePlane plane) {
        return GsonTools.getInstance().fromJson(geoJson, ROI.class);
    }
}
