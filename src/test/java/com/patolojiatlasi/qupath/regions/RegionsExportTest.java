package com.patolojiatlasi.qupath.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * The regions file is consumed by three independent programs (this extension, the OpenSeadragon
 * site, the atlas MCP server), so its shape is a contract rather than an implementation detail.
 * These tests pin the parts each consumer depends on: the bounding box, the pixel coordinate space,
 * the bilingual label split, and stable ids for deep links.
 */
class RegionsExportTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    private static PathObject rect(double x, double y, double w, double h, String name) {
        ROI roi = ROIs.createRectangleROI(x, y, w, h, PLANE);
        PathObject annotation = PathObjects.createAnnotationObject(roi);
        if (name != null)
            annotation.setName(name);
        return annotation;
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject firstRegion(String json) {
        return parse(json).getAsJsonArray("regions").get(0).getAsJsonObject();
    }

    // --- envelope --------------------------------------------------------------------

    @Test
    void writesFormatSizeAndSource() {
        String json = RegionsExport.toJson(List.of(rect(10, 20, 30, 40, "A")),
                "lymphocytic-gastritis/HE", 79923, 42180, 0.263018,
                "QuPath 0.6.0", "2026-09-18", false);

        JsonObject root = parse(json);
        assertEquals("atlas-regions/1", root.get("format").getAsString());
        assertEquals("lymphocytic-gastritis/HE", root.get("slide").getAsString());
        assertEquals(79923, root.getAsJsonObject("size").get("width").getAsInt());
        assertEquals(42180, root.getAsJsonObject("size").get("height").getAsInt());
        assertEquals(0.263018, root.get("mpp").getAsDouble(), 1e-9);
        assertEquals("QuPath 0.6.0", root.getAsJsonObject("source").get("tool").getAsString());
        assertEquals("2026-09-18", root.getAsJsonObject("source").get("exported").getAsString());
    }

    /** An uncalibrated slide must omit mpp rather than writing a misleading 1.0. */
    @Test
    void omitsMppWhenUncalibrated() {
        String json = RegionsExport.toJson(List.of(rect(0, 0, 5, 5, "A")),
                null, 100, 80, null, null, null, false);
        assertFalse(parse(json).has("mpp"));
        assertFalse(parse(json).has("source"));
        assertFalse(parse(json).has("slide"));
    }

    @Test
    void emptyAnnotationListStillProducesAValidDocument() {
        String json = RegionsExport.toJson(List.of(), "x/HE", 100, 80, null, null, null, false);
        assertEquals(0, parse(json).getAsJsonArray("regions").size());
    }

    // --- the bounding box ------------------------------------------------------------

    @Test
    void boundingBoxIsInFullResolutionPixels() {
        String json = RegionsExport.toJson(List.of(rect(12000, 8000, 3000, 2400, "A")),
                null, 79923, 42180, null, null, null, false);
        JsonObject r = firstRegion(json);
        assertEquals(12000, r.get("x").getAsInt());
        assertEquals(8000, r.get("y").getAsInt());
        assertEquals(3000, r.get("width").getAsInt());
        assertEquals(2400, r.get("height").getAsInt());
    }

    /** Rounding must grow the box, never shrink it, so a region is never clipped. */
    @Test
    void fractionalBoundsRoundOutwards() {
        String json = RegionsExport.toJson(List.of(rect(10.7, 20.2, 5.6, 5.1, "A")),
                null, 100, 80, null, null, null, false);
        JsonObject r = firstRegion(json);
        assertEquals(10, r.get("x").getAsInt());
        assertEquals(20, r.get("y").getAsInt());
        assertEquals(7, r.get("width").getAsInt(), "10.7..16.3 rounds outwards to 10..17");
        assertEquals(6, r.get("height").getAsInt(), "20.2..25.3 rounds outwards to 20..26");
    }

    // --- labels ----------------------------------------------------------------------

    @Test
    void splitsBilingualNameOnPipe() {
        String json = RegionsExport.toJson(
                List.of(rect(0, 0, 5, 5, "Lenfoid agregat | Lymphoid aggregate")),
                null, 100, 80, null, null, null, false);
        JsonObject r = firstRegion(json);
        assertEquals("Lenfoid agregat", r.get("label_tr").getAsString());
        assertEquals("Lymphoid aggregate", r.get("label_en").getAsString());
    }

    @Test
    void plainNameBecomesTurkishLabelOnly() {
        String json = RegionsExport.toJson(List.of(rect(0, 0, 5, 5, "Yuzey epiteli")),
                null, 100, 80, null, null, null, false);
        JsonObject r = firstRegion(json);
        assertEquals("Yuzey epiteli", r.get("label_tr").getAsString());
        assertFalse(r.has("label_en"));
    }

    @Test
    void splitBilingualHandlesEdgeCases() {
        assertNull(RegionsExport.splitBilingual(null)[0]);
        assertNull(RegionsExport.splitBilingual("   ")[0]);
        assertEquals("A", RegionsExport.splitBilingual("A |")[0]);
        assertNull(RegionsExport.splitBilingual("A |")[1]);
        assertEquals("A | B", RegionsExport.joinBilingual("A", "B"));
        assertEquals("A", RegionsExport.joinBilingual("A", null));
        assertNull(RegionsExport.joinBilingual(null, null));
    }

    @Test
    void unnamedAnnotationFallsBackToItsClassification() {
        PathObject annotation = rect(0, 0, 5, 5, null);
        annotation.setPathClass(qupath.lib.objects.classes.PathClass.fromString("Tumor"));
        assertEquals("Tumor", RegionsExport.labelFor(annotation));
    }

    @Test
    void notesComeFromTheAnnotationDescription() {
        PathObject annotation = rect(0, 0, 5, 5, "A");
        ((PathAnnotationObject) annotation).setDescription("Not | Note");
        String json = RegionsExport.toJson(List.of(annotation), null, 100, 80, null, null, null, false);
        JsonObject r = firstRegion(json);
        assertEquals("Not", r.get("note_tr").getAsString());
        assertEquals("Note", r.get("note_en").getAsString());
    }

    // --- ids -------------------------------------------------------------------------

    /** Deep links (#r=1a2b3c4d) only work if an id survives re-export of the same annotation. */
    @Test
    void idIsShortStableHexDerivedFromTheObjectUuid() {
        PathObject annotation = rect(0, 0, 5, 5, "A");
        String first = RegionsExport.shortId(annotation);
        assertEquals(8, first.length());
        assertTrue(first.matches("[0-9a-f]{8}"), "expected 8 lowercase hex chars, got " + first);
        assertEquals(first, RegionsExport.shortId(annotation), "id must be stable");
        assertFalse(first.equals(RegionsExport.shortId(rect(0, 0, 5, 5, "B"))),
                "different annotations must get different ids");
    }

    /**
     * The whole point of a stable id is that a published deep link keeps working after the author
     * pulls the regions back into QuPath, edits them, and exports again. That only holds if import
     * restores each object's identity, so this walks the full cycle rather than a single hop.
     */
    @Test
    void idSurvivesAnExportImportExportCycle() {
        var original = List.of(rect(10, 20, 30, 40, "Bir"), rect(50, 60, 10, 10, "Iki"));
        String first = RegionsExport.toJson(original, "x/HE", 100, 80, null, null, null, true);

        RegionsExport.Parsed reimported = RegionsExport.fromJson(first, PLANE);
        String second = RegionsExport.toJson(reimported.annotations(), "x/HE", 100, 80,
                null, null, null, true);

        var firstIds = parse(first).getAsJsonArray("regions").asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
        var secondIds = parse(second).getAsJsonArray("regions").asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
        assertEquals(firstIds, secondIds, "re-export must not renumber regions");
    }

    /** A label beginning with the delimiter must not keep it, or it grows on every cycle. */
    @Test
    void leadingPipeDoesNotLeaveTheDelimiterInTheLabel() {
        assertEquals("Lymphoid aggregate", RegionsExport.splitBilingual(" | Lymphoid aggregate")[0]);
        assertNull(RegionsExport.splitBilingual(" | Lymphoid aggregate")[1]);

        // And the pathological case cannot compound across a round trip.
        String json = RegionsExport.toJson(List.of(rect(0, 0, 5, 5, "| X")),
                null, 100, 80, null, null, null, false);
        assertEquals("X", firstRegion(json).get("label_tr").getAsString());
        String again = RegionsExport.toJson(RegionsExport.fromJson(json, PLANE).annotations(),
                null, 100, 80, null, null, null, false);
        assertEquals("X", firstRegion(again).get("label_tr").getAsString());
    }

    // --- geometry --------------------------------------------------------------------

    @Test
    void geometryIsOptional() {
        var annotations = List.of(rect(0, 0, 5, 5, "A"));
        assertFalse(firstRegion(RegionsExport.toJson(annotations, null, 100, 80, null, null, null, false))
                .has("geometry"));
        assertTrue(firstRegion(RegionsExport.toJson(annotations, null, 100, 80, null, null, null, true))
                .has("geometry"));
    }

    // --- reading back ----------------------------------------------------------------

    @Test
    void roundTripsThroughGeometry() {
        ROI roi = ROIs.createPolygonROI(
                new double[] {100, 400, 400, 100}, new double[] {200, 200, 500, 500}, PLANE);
        PathObject original = PathObjects.createAnnotationObject(roi);
        original.setName("Lenfoid agregat | Lymphoid aggregate");

        String json = RegionsExport.toJson(List.of(original), "x/HE", 1000, 1000, null, null, null, true);
        RegionsExport.Parsed parsed = RegionsExport.fromJson(json, PLANE);

        assertEquals(1000, parsed.width());
        assertEquals(1, parsed.annotations().size());
        PathObject restored = parsed.annotations().get(0);
        assertEquals("Lenfoid agregat | Lymphoid aggregate", restored.getName());
        assertEquals(roi.getBoundsX(), restored.getROI().getBoundsX(), 1e-6);
        assertEquals(roi.getBoundsY(), restored.getROI().getBoundsY(), 1e-6);
        assertEquals(roi.getBoundsWidth(), restored.getROI().getBoundsWidth(), 1e-6);
        assertEquals(roi.getBoundsHeight(), restored.getROI().getBoundsHeight(), 1e-6);
    }

    /** A file written without geometry must still import, as a rectangle. */
    @Test
    void fallsBackToTheBoundingBoxWhenGeometryIsAbsent() {
        String json = RegionsExport.toJson(List.of(rect(10, 20, 30, 40, "A")),
                null, 100, 80, null, null, null, false);
        RegionsExport.Parsed parsed = RegionsExport.fromJson(json, PLANE);
        assertEquals(1, parsed.annotations().size());
        ROI roi = parsed.annotations().get(0).getROI();
        assertEquals(10, roi.getBoundsX(), 1e-6);
        assertEquals(30, roi.getBoundsWidth(), 1e-6);
    }

    /** The authored-against size is what lets a consumer refuse a mismatched re-export. */
    @Test
    void parsedSizeIsReportedForMismatchChecking() {
        String json = RegionsExport.toJson(List.of(rect(0, 0, 5, 5, "A")),
                null, 79923, 42180, 0.263018, null, null, false);
        RegionsExport.Parsed parsed = RegionsExport.fromJson(json, PLANE);
        assertEquals(79923, parsed.width());
        assertEquals(42180, parsed.height());
        assertNotNull(parsed.mpp());
        assertEquals(0.263018, parsed.mpp(), 1e-9);
    }

    /**
     * Cross-stage integration: the regions file the OpenSeadragon site ships must be readable by
     * this importer, and vice versa. The two were written independently against the same written
     * contract, so this is the seam most likely to drift. Self-skips when the site is not checked
     * out here.
     */
    @Test
    void readsTheRegionsFileShippedWithTheWebsite() throws Exception {
        Path shipped = Path.of("E:", "atlas", "lymphocytic-gastritis", "HE.regions.json");
        Assumptions.assumeTrue(Files.isRegularFile(shipped), "website regions file not present here");

        RegionsExport.Parsed parsed = RegionsExport.fromJson(
                Files.readString(shipped, StandardCharsets.UTF_8), PLANE);

        assertEquals(79923, parsed.width(), "must match the real slide the site serves");
        assertEquals(42180, parsed.height());
        assertEquals(0.263018, parsed.mpp(), 1e-9);
        assertFalse(parsed.annotations().isEmpty());
        // Bilingual names must survive the round trip through the site's file.
        assertTrue(parsed.annotations().stream()
                        .anyMatch(a -> a.getName() != null && a.getName().contains(" | ")),
                "expected at least one bilingual label");
        // And every region must land inside the slide.
        for (PathObject a : parsed.annotations()) {
            ROI roi = a.getROI();
            assertTrue(roi.getBoundsX() >= 0 && roi.getBoundsY() >= 0, "region starts inside the slide");
            assertTrue(roi.getBoundsX() + roi.getBoundsWidth() <= 79923, "region ends inside the slide");
            assertTrue(roi.getBoundsY() + roi.getBoundsHeight() <= 42180, "region ends inside the slide");
        }
    }

    @Test
    void rejectsSomethingThatIsNotARegionsFile() {
        assertThrows(IllegalArgumentException.class, () -> RegionsExport.fromJson("not json", PLANE));
        assertThrows(IllegalArgumentException.class, () -> RegionsExport.fromJson("{\"a\":1}", PLANE));
    }
}
