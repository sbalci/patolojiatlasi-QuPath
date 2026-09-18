package com.patolojiatlasi.qupath.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.patolojiatlasi.qupath.quiz.QuizGeometry;

import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Reads and writes {@code <slide>.regions.json} -- the small, named "teaching region" file that
 * travels beside a Deep Zoom pyramid ({@code HE.dzi} / {@code HE_files/}) and is consumed by three
 * very different things: this extension, the OpenSeadragon web viewer, and the atlas MCP server.
 *
 * <h2>Why a bounding box and not just the outline</h2>
 * Every consumer needs to <i>navigate</i> to a region; only some can draw one. The box is therefore
 * mandatory and {@code geometry} optional, so a viewer can implement "jump here" with a single
 * {@code fitBounds} call and nothing else.
 *
 * <h2>Coordinates</h2>
 * All values are <b>full-resolution image pixels with a top-left origin</b>. That is QuPath's ROI
 * space, the DZI descriptor's {@code Size} space, and OpenSeadragon's
 * {@code imageToViewportRectangle} space, all of which coincide -- so no transform is applied here
 * or anywhere downstream. {@code size} records the dimensions the regions were authored against so
 * a consumer can refuse to place them on a differently-sized re-export.
 *
 * <h2>Bilingual labels</h2>
 * The atlas is bilingual, but QuPath annotations carry a single name. A name may therefore use the
 * atlas' existing {@code |} convention -- {@code "Lenfoid agregat | Lymphoid aggregate"} -- which is
 * split into {@code label_tr} / {@code label_en}. A plain name becomes {@code label_tr} alone, and
 * consumers fall back to it.
 */
public final class RegionsExport {

    /** Value of the {@code format} member; bump if the shape ever changes incompatibly. */
    public static final String FORMAT = "atlas-regions/1";

    /** Suffix appended to the slide name, e.g. {@code HE} -> {@code HE.regions.json}. */
    public static final String SUFFIX = ".regions.json";

    private RegionsExport() {}

    /**
     * Build the regions document. Pure: no I/O, no QuPath GUI, no clock -- {@code exportedDate} is
     * passed in so the output is reproducible and testable.
     *
     * @param annotations   annotations to export, in the order they should appear
     * @param slideId       e.g. {@code "lymphocytic-gastritis/HE"}; may be null
     * @param width         full-resolution image width, in pixels
     * @param height        full-resolution image height, in pixels
     * @param mpp           microns per pixel, or null when the image is uncalibrated
     * @param tool          e.g. {@code "QuPath 0.6.0"}; may be null
     * @param exportedDate  ISO date, e.g. {@code "2026-09-18"}; may be null
     * @param includeGeometry whether to emit the full outline alongside the bounding box
     */
    public static String toJson(Collection<PathObject> annotations, String slideId,
            int width, int height, Double mpp, String tool, String exportedDate,
            boolean includeGeometry) {

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        if (slideId != null && !slideId.isBlank())
            root.addProperty("slide", slideId);

        JsonObject size = new JsonObject();
        size.addProperty("width", width);
        size.addProperty("height", height);
        root.add("size", size);

        if (mpp != null && mpp > 0)
            root.addProperty("mpp", mpp);

        if ((tool != null && !tool.isBlank()) || (exportedDate != null && !exportedDate.isBlank())) {
            JsonObject source = new JsonObject();
            if (tool != null && !tool.isBlank())
                source.addProperty("tool", tool);
            if (exportedDate != null && !exportedDate.isBlank())
                source.addProperty("exported", exportedDate);
            root.add("source", source);
        }

        JsonArray array = new JsonArray();
        for (PathObject annotation : annotations) {
            JsonObject region = toRegion(annotation, includeGeometry);
            if (region != null)
                array.add(region);
        }
        root.add("regions", array);
        return root.toString();
    }

    /** @return the region object, or null when the annotation has no ROI to describe */
    static JsonObject toRegion(PathObject annotation, boolean includeGeometry) {
        ROI roi = annotation.getROI();
        if (roi == null)
            return null;

        JsonObject region = new JsonObject();
        region.addProperty("id", shortId(annotation));
        // The short id is what deep links use, but 8 hex characters cannot be turned back into a
        // UUID. Carrying the full one lets an import restore the object's identity, so re-exporting
        // after an edit yields the SAME short id and previously published links keep resolving.
        // Web and MCP consumers ignore this member.
        if (annotation.getID() != null)
            region.addProperty("uuid", annotation.getID().toString());

        String[] labels = splitBilingual(labelFor(annotation));
        region.addProperty("label_tr", labels[0]);
        if (labels[1] != null)
            region.addProperty("label_en", labels[1]);

        // Bounding box, rounded outwards so the region is never clipped by rounding.
        int x = (int) Math.floor(roi.getBoundsX());
        int y = (int) Math.floor(roi.getBoundsY());
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("width", Math.max(1, (int) Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()) - x));
        region.addProperty("height", Math.max(1, (int) Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()) - y));

        if (annotation instanceof PathAnnotationObject pao) {
            String[] notes = splitBilingual(pao.getDescription());
            if (notes[0] != null) {
                region.addProperty("note_tr", notes[0]);
                if (notes[1] != null)
                    region.addProperty("note_en", notes[1]);
            }
        }

        if (includeGeometry) {
            try {
                JsonElement geometry = JsonParser.parseString(QuizGeometry.toGeoJson(roi));
                if (geometry != null && geometry.isJsonObject())
                    region.add("geometry", geometry);
            } catch (Exception e) {
                // An un-serialisable ROI still exports as a usable bounding box; that is the point
                // of making geometry optional.
            }
        }
        return region;
    }

    /** Annotation name, else its classification, else a generic fallback. */
    static String labelFor(PathObject annotation) {
        String name = annotation.getName();
        if (name != null && !name.isBlank())
            return name;
        var pathClass = annotation.getPathClass();
        if (pathClass != null && pathClass.getName() != null && !pathClass.getName().isBlank())
            return pathClass.getName();
        return "Bolge";
    }

    /**
     * Split {@code "Turkce | English"} into its two halves. A string without {@code |} is returned
     * as Turkish only. Never returns null in slot 0 unless the input was blank.
     *
     * @return a two-element array {@code [tr, en]}; {@code en} may be null
     */
    static String[] splitBilingual(String text) {
        if (text == null || text.isBlank())
            return new String[] {null, null};
        int i = text.indexOf('|');
        if (i < 0)
            return new String[] {text.trim(), null};
        String tr = text.substring(0, i).trim();
        String en = text.substring(i + 1).trim();
        if (tr.isEmpty())
            // "| English" -- there is no Turkish half. Returning the raw text here would keep the
            // delimiter in the label and grow another "| " on every export/import cycle.
            return new String[] {en.isEmpty() ? null : en, null};
        return new String[] {tr, en.isEmpty() ? null : en};
    }

    /**
     * A short, stable identifier. QuPath gives every object a UUID that survives save/reload, so
     * the first 8 characters make a deep link (e.g. {@code #r=1a2b3c4d}) that still resolves after
     * the regions file is regenerated.
     */
    static String shortId(PathObject annotation) {
        var id = annotation.getID();
        if (id == null)
            return "r" + Integer.toHexString(System.identityHashCode(annotation));
        return id.toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    // --- reading back ----------------------------------------------------------------

    /** Dimensions a regions file was authored against, plus its parsed regions. */
    public record Parsed(int width, int height, Double mpp, List<PathObject> annotations) {}

    /**
     * Rebuild annotations from a regions document, so an exported file can be revised in QuPath and
     * written out again. Uses {@code geometry} when present and the bounding box otherwise.
     *
     * @throws IllegalArgumentException if the document is not a readable regions file
     */
    public static Parsed fromJson(String json, ImagePlane plane) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a readable regions file", e);
        }
        if (!root.has("regions"))
            throw new IllegalArgumentException("No \"regions\" member found");

        int width = root.has("size") && root.getAsJsonObject("size").has("width")
                ? root.getAsJsonObject("size").get("width").getAsInt() : 0;
        int height = root.has("size") && root.getAsJsonObject("size").has("height")
                ? root.getAsJsonObject("size").get("height").getAsInt() : 0;
        Double mpp = root.has("mpp") ? root.get("mpp").getAsDouble() : null;

        ImagePlane p = plane == null ? ImagePlane.getDefaultPlane() : plane;
        List<PathObject> annotations = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("regions")) {
            if (!element.isJsonObject())
                continue;
            JsonObject region = element.getAsJsonObject();
            ROI roi = null;
            if (region.has("geometry")) {
                try {
                    roi = QuizGeometry.fromGeoJson(region.get("geometry").toString(), p);
                } catch (Exception e) {
                    roi = null;
                }
            }
            if (roi == null && region.has("x") && region.has("y")
                    && region.has("width") && region.has("height")) {
                roi = ROIs.createRectangleROI(
                        region.get("x").getAsDouble(), region.get("y").getAsDouble(),
                        region.get("width").getAsDouble(), region.get("height").getAsDouble(), p);
            }
            if (roi == null)
                continue;

            PathObject annotation = PathObjects.createAnnotationObject(roi);
            if (region.has("uuid")) {
                try {
                    annotation.setID(UUID.fromString(region.get("uuid").getAsString()));
                } catch (Exception e) {
                    // A hand-edited or third-party file may carry no usable UUID; the region is
                    // still perfectly importable, it just becomes a new object.
                }
            }
            annotation.setName(joinBilingual(
                    region.has("label_tr") ? region.get("label_tr").getAsString() : null,
                    region.has("label_en") ? region.get("label_en").getAsString() : null));
            if (annotation instanceof PathAnnotationObject pao) {
                String note = joinBilingual(
                        region.has("note_tr") ? region.get("note_tr").getAsString() : null,
                        region.has("note_en") ? region.get("note_en").getAsString() : null);
                if (note != null)
                    pao.setDescription(note);
            }
            annotations.add(annotation);
        }
        return new Parsed(width, height, mpp, annotations);
    }

    /** Inverse of {@link #splitBilingual(String)}. */
    static String joinBilingual(String tr, String en) {
        boolean hasTr = tr != null && !tr.isBlank();
        boolean hasEn = en != null && !en.isBlank();
        if (hasTr && hasEn)
            return tr.trim() + " | " + en.trim();
        if (hasTr)
            return tr.trim();
        return hasEn ? en.trim() : null;
    }
}
