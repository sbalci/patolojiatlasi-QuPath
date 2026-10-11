package com.patolojiatlasi.qupath.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class RegionsGeoJsonTest {

    @Test
    void exportsAFeatureCollectionWithSlideMetadataAndStableIdentity() {
        PathObject annotation = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(10.25, 20.5, 30.75, 40.25, ImagePlane.getDefaultPlane()));
        annotation.setName("Tümör | Tumour");
        annotation.setPathClass(PathClass.fromString("Tumor"));

        String json = RegionsGeoJson.toGeoJson(List.of(annotation), "case/HE", 2048, 2048, 0.25);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("FeatureCollection", root.get("type").getAsString());
        assertEquals(2048, root.getAsJsonObject("atlas").getAsJsonObject("size")
                .get("width").getAsInt());
        JsonObject feature = root.getAsJsonArray("features").get(0).getAsJsonObject();
        assertEquals(annotation.getID().toString(), feature.get("id").getAsString());
        assertEquals("Tümör | Tumour", feature.getAsJsonObject("properties")
                .get("name").getAsString());
        assertEquals("Tumor", feature.getAsJsonObject("properties")
                .get("class").getAsString());

        RegionsExport.Parsed restored = RegionsGeoJson.fromGeoJson(json, null);
        assertEquals(2048, restored.width());
        assertEquals(0.25, restored.mpp(), 1e-9);
        assertEquals(annotation.getID(), restored.annotations().get(0).getID());
        assertEquals("Tumor", restored.annotations().get(0).getPathClass().getName());
        assertEquals(10.25, restored.annotations().get(0).getROI().getBoundsX(), 1e-6);
    }

    @Test
    void importsEditedPolygonWithHoleAndFractionalCoordinates() {
        String json = """
                {"type":"FeatureCollection","features":[{"type":"Feature",
                "id":"bdc4bc32-3c16-4748-929b-9714c19714df",
                "properties":{"name":"Delikli bölge | Region with hole","class":"Tumor"},
                "geometry":{"type":"Polygon","coordinates":[
                [[1.25,1.5],[100.5,1.5],[100.5,100.75],[1.25,100.75],[1.25,1.5]],
                [[20.5,20.25],[20.5,40.5],[40.75,40.5],[40.75,20.25],[20.5,20.25]]
                ]}}]}
                """;
        RegionsExport.Parsed parsed = RegionsGeoJson.fromGeoJson(json, null);
        assertEquals(0, parsed.width(), "third-party GeoJSON has no slide size");
        assertEquals(1, parsed.annotations().size());
        PathObject annotation = parsed.annotations().get(0);
        assertEquals("Delikli bölge | Region with hole", annotation.getName());
        assertEquals("Tumor", annotation.getPathClass().getName());
        assertTrue(annotation.getROI().getGeometry() instanceof Polygon);
        assertEquals(1, ((Polygon) annotation.getROI().getGeometry()).getNumInteriorRing());
        assertEquals(1.25, annotation.getROI().getBoundsX(), 1e-6);

        String exported = RegionsGeoJson.toGeoJson(parsed.annotations(), "case/HE",
                2048, 2048, null);
        RegionsExport.Parsed again = RegionsGeoJson.fromGeoJson(exported, null);
        assertTrue(annotation.getROI().getGeometry().equalsTopo(
                again.annotations().get(0).getROI().getGeometry()));
        assertEquals(annotation.getID(), again.annotations().get(0).getID());
        assertFalse(JsonParser.parseString(exported).getAsJsonObject().getAsJsonObject("atlas")
                .has("mpp"));
    }

    @Test
    void rejectsInvalidFeatureGeometryInsteadOfSilentlyDroppingIt() {
        assertThrows(IllegalArgumentException.class,
                () -> RegionsGeoJson.fromGeoJson("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\"}]}", null));
        assertThrows(IllegalArgumentException.class,
                () -> RegionsGeoJson.fromGeoJson("{\"type\":\"Feature\",\"geometry\":null}", null));
    }

    @Test
    void rejectsDuplicateUuidsBeforeAddingAnnotations() {
        String id = "bdc4bc32-3c16-4748-929b-9714c19714df";
        String feature = "{\"type\":\"Feature\",\"id\":\"" + id
                + "\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[5,0],[5,5],[0,0]]]}}";
        String json = "{\"type\":\"FeatureCollection\",\"features\":[" + feature + "," + feature + "]}";
        assertThrows(IllegalArgumentException.class, () -> RegionsGeoJson.fromGeoJson(json, null));
    }

    @Test
    void convertsPublishedRegionsWithoutChangingFractionalCoordinatesOrHoles() {
        String atlas = """
                {"format":"atlas-regions/1","slide":"case/HE",
                 "size":{"width":2048,"height":2048},"mpp":0.25,
                 "regions":[{"id":"bdc4bc32","uuid":"bdc4bc32-3c16-4748-929b-9714c19714df",
                 "label_tr":"Delikli bölge","label_en":"Region with hole",
                 "geometry":{"type":"Polygon","coordinates":[
                 [[1.123456789,2.5],[100.5,2.5],[100.5,100.75],[1.123456789,2.5]],
                 [[20.5,20.25],[20.5,40.5],[40.75,20.25],[20.5,20.25]]
                 ]}}]}
                """;
        JsonObject originalGeometry = JsonParser.parseString(atlas).getAsJsonObject()
                .getAsJsonArray("regions").get(0).getAsJsonObject().getAsJsonObject("geometry");
        JsonObject converted = JsonParser.parseString(RegionsGeoJson.fromAtlasRegionsJson(atlas))
                .getAsJsonObject();
        assertEquals("FeatureCollection", converted.get("type").getAsString());
        assertEquals(2048, converted.getAsJsonObject("atlas").getAsJsonObject("size")
                .get("width").getAsInt());
        JsonObject feature = converted.getAsJsonArray("features").get(0).getAsJsonObject();
        assertEquals(originalGeometry, feature.getAsJsonObject("geometry"));
        assertEquals("Delikli bölge | Region with hole",
                feature.getAsJsonObject("properties").get("name").getAsString());
    }

    @Test
    void convertsBoundingBoxOnlyRegionToPolygon() {
        String atlas = """
                {"format":"atlas-regions/1","regions":[
                  {"id":"aabbccdd","label_tr":"Kutu","x":10,"y":20,"width":30,"height":40}
                ]}
                """;
        JsonObject feature = JsonParser.parseString(RegionsGeoJson.fromAtlasRegionsJson(atlas))
                .getAsJsonObject().getAsJsonArray("features").get(0).getAsJsonObject();
        assertEquals("Polygon", feature.getAsJsonObject("geometry").get("type").getAsString());
        assertEquals(40, feature.getAsJsonObject("geometry").getAsJsonArray("coordinates")
                .get(0).getAsJsonArray().get(1).getAsJsonArray().get(0).getAsInt());
    }
}
