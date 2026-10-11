package com.patolojiatlasi.qupath.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.patolojiatlasi.qupath.quiz.QuizGeometry;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/**
 * GeoJSON exchange for the named Atlas regions. The site-facing {@code .regions.json} contract is
 * deliberately separate: R and QuPath expect a FeatureCollection, whereas the Atlas website and
 * MCP server expect the compact regions document. Coordinates stay in level-0 image pixels.
 */
public final class RegionsGeoJson {

    private RegionsGeoJson() {}

    public static String toGeoJson(Collection<PathObject> annotations, String slideId,
            int width, int height, Double mpp) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "FeatureCollection");
        JsonObject atlas = new JsonObject();
        atlas.addProperty("format", "atlas-regions-geojson/1");
        if (slideId != null && !slideId.isBlank())
            atlas.addProperty("slide", slideId);
        JsonObject size = new JsonObject();
        size.addProperty("width", width);
        size.addProperty("height", height);
        atlas.add("size", size);
        if (mpp != null && mpp > 0)
            atlas.addProperty("mpp", mpp);
        root.add("atlas", atlas);

        JsonArray features = new JsonArray();
        for (PathObject annotation : annotations) {
            ROI roi = annotation.getROI();
            if (roi == null)
                continue;
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "Feature");
            if (annotation.getID() != null)
                feature.addProperty("id", annotation.getID().toString());
            feature.add("geometry", geometryJson(roi));

            JsonObject properties = new JsonObject();
            properties.addProperty("objectType", "annotation");
            if (annotation.getID() != null)
                properties.addProperty("uuid", annotation.getID().toString());
            properties.addProperty("atlas_region_id", RegionsExport.shortId(annotation));
            String name = annotation.getName();
            if (name != null && !name.isBlank()) {
                properties.addProperty("name", name);
                String[] labels = RegionsExport.splitBilingual(name);
                if (labels[0] != null)
                    properties.addProperty("label_tr", labels[0]);
                if (labels[1] != null)
                    properties.addProperty("label_en", labels[1]);
            }
            if (annotation.getPathClass() != null) {
                String className = annotation.getPathClass().getName();
                properties.addProperty("class", className);
                JsonObject classification = new JsonObject();
                classification.addProperty("name", className);
                properties.add("classification", classification);
            }
            if (annotation instanceof PathAnnotationObject pao && pao.getDescription() != null)
                properties.addProperty("description", pao.getDescription());
            feature.add("properties", properties);
            features.add(feature);
        }
        root.add("features", features);
        return root.toString();
    }

    /** Convert a published Atlas regions document without passing its coordinates through an ROI. */
    public static String fromAtlasRegionsJson(String json) {
        JsonObject source;
        try {
            source = JsonParser.parseString(json).getAsJsonObject();
            if (!RegionsExport.FORMAT.equals(string(source, "format"))
                    || !source.has("regions") || !source.get("regions").isJsonArray())
                throw new IllegalArgumentException("An atlas-regions/1 document is required");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a readable Atlas regions document", e);
        }
        JsonObject root = new JsonObject();
        root.addProperty("type", "FeatureCollection");
        JsonObject atlas = new JsonObject();
        atlas.addProperty("format", "atlas-regions-geojson/1");
        if (source.has("slide")) atlas.add("slide", source.get("slide").deepCopy());
        if (source.has("size")) atlas.add("size", source.get("size").deepCopy());
        if (source.has("mpp")) atlas.add("mpp", source.get("mpp").deepCopy());
        root.add("atlas", atlas);

        JsonArray features = new JsonArray();
        for (JsonElement element : source.getAsJsonArray("regions")) {
            if (!element.isJsonObject())
                throw new IllegalArgumentException("An Atlas region is not an object");
            JsonObject region = element.getAsJsonObject();
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "Feature");
            String uuid = string(region, "uuid");
            String shortId = string(region, "id");
            if (uuid != null) feature.addProperty("id", uuid);
            else if (shortId != null) feature.addProperty("id", shortId);

            if (region.has("geometry") && region.get("geometry").isJsonObject())
                feature.add("geometry", region.get("geometry").deepCopy());
            else
                feature.add("geometry", boundingBoxGeometry(region));

            JsonObject properties = new JsonObject();
            properties.addProperty("objectType", "annotation");
            if (uuid != null) properties.addProperty("uuid", uuid);
            if (shortId != null) properties.addProperty("atlas_region_id", shortId);
            String tr = string(region, "label_tr");
            String en = string(region, "label_en");
            String name = RegionsExport.joinBilingual(tr, en);
            if (name != null) properties.addProperty("name", name);
            if (tr != null) properties.addProperty("label_tr", tr);
            if (en != null) properties.addProperty("label_en", en);
            String description = RegionsExport.joinBilingual(
                    string(region, "note_tr"), string(region, "note_en"));
            if (description != null) properties.addProperty("description", description);
            feature.add("properties", properties);
            features.add(feature);
        }
        root.add("features", features);
        return root.toString();
    }

    public static RegionsExport.Parsed fromGeoJson(String json, ImagePlane plane) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
            if (!"FeatureCollection".equals(string(root, "type")) || !root.has("features")
                    || !root.get("features").isJsonArray())
                throw new IllegalArgumentException("A GeoJSON FeatureCollection is required");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a readable GeoJSON FeatureCollection", e);
        }

        int width = 0;
        int height = 0;
        Double mpp = null;
        if (root.has("atlas") && root.get("atlas").isJsonObject()) {
            JsonObject atlas = root.getAsJsonObject("atlas");
            if (atlas.has("size") && atlas.get("size").isJsonObject()) {
                JsonObject size = atlas.getAsJsonObject("size");
                if (size.has("width")) width = size.get("width").getAsInt();
                if (size.has("height")) height = size.get("height").getAsInt();
            }
            if (atlas.has("mpp") && !atlas.get("mpp").isJsonNull())
                mpp = atlas.get("mpp").getAsDouble();
        }

        ImagePlane imagePlane = plane == null ? ImagePlane.getDefaultPlane() : plane;
        List<PathObject> annotations = new ArrayList<>();
        Set<UUID> incomingIds = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("features")) {
            if (!element.isJsonObject())
                throw new IllegalArgumentException("A GeoJSON feature is not an object");
            JsonObject feature = element.getAsJsonObject();
            if (!"Feature".equals(string(feature, "type")) || !feature.has("geometry")
                    || feature.get("geometry").isJsonNull())
                throw new IllegalArgumentException("A feature has no geometry");
            ROI roi;
            try {
                roi = QuizGeometry.fromGeoJson(feature.get("geometry").toString(), imagePlane);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("A feature has unreadable geometry", e);
            }
            if (roi == null)
                throw new IllegalArgumentException("A feature has unreadable geometry");

            PathObject annotation = PathObjects.createAnnotationObject(roi);
            JsonObject properties = feature.has("properties") && feature.get("properties").isJsonObject()
                    ? feature.getAsJsonObject("properties") : new JsonObject();
            String uuid = string(properties, "uuid");
            if (uuid == null) uuid = string(feature, "id");
            if (uuid == null) uuid = string(properties, "roi_id");
            if (uuid == null) uuid = string(properties, "id");
            if (uuid != null) {
                UUID parsedId = null;
                try {
                    parsedId = UUID.fromString(uuid);
                } catch (IllegalArgumentException ignored) {
                    // A third-party GeoJSON may use a non-UUID feature id.
                }
                if (parsedId != null) {
                    if (!incomingIds.add(parsedId))
                        throw new IllegalArgumentException("Duplicate feature UUID: " + parsedId);
                    annotation.setID(parsedId);
                }
            }
            String name = string(properties, "name");
            if (name == null)
                name = RegionsExport.joinBilingual(string(properties, "label_tr"),
                        string(properties, "label_en"));
            if (name == null) name = string(properties, "label");
            if (name != null) annotation.setName(name);

            String className = string(properties, "class");
            if (className == null && properties.has("classification")) {
                JsonElement classification = properties.get("classification");
                className = classification.isJsonObject()
                        ? string(classification.getAsJsonObject(), "name")
                        : classification.isJsonPrimitive() ? classification.getAsString() : null;
            }
            if (className != null) annotation.setPathClass(PathClass.fromString(className));
            if (annotation instanceof PathAnnotationObject pao) {
                String description = string(properties, "description");
                if (description != null) pao.setDescription(description);
            }
            annotations.add(annotation);
        }
        return new RegionsExport.Parsed(width, height, mpp, annotations);
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive())
            return null;
        String value = object.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    /** Avoid an additional JSON rounding pass on QuPath's already quantized ROI coordinates. */
    private static JsonElement geometryJson(ROI roi) {
        var geometry = roi.getGeometry();
        JsonObject object = new JsonObject();
        if (geometry instanceof Polygon polygon) {
            object.addProperty("type", "Polygon");
            object.add("coordinates", polygonCoordinates(polygon));
            return object;
        }
        if (geometry instanceof MultiPolygon multiPolygon) {
            JsonArray polygons = new JsonArray();
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++)
                polygons.add(polygonCoordinates((Polygon) multiPolygon.getGeometryN(i)));
            object.addProperty("type", "MultiPolygon");
            object.add("coordinates", polygons);
            return object;
        }
        return JsonParser.parseString(QuizGeometry.toGeoJson(roi));
    }

    private static JsonArray polygonCoordinates(Polygon polygon) {
        JsonArray rings = new JsonArray();
        rings.add(ringCoordinates(polygon.getExteriorRing()));
        for (int i = 0; i < polygon.getNumInteriorRing(); i++)
            rings.add(ringCoordinates(polygon.getInteriorRingN(i)));
        return rings;
    }

    private static JsonArray ringCoordinates(LineString ring) {
        JsonArray points = new JsonArray();
        for (Coordinate coordinate : ring.getCoordinates()) {
            JsonArray point = new JsonArray();
            point.add(coordinate.x);
            point.add(coordinate.y);
            points.add(point);
        }
        return points;
    }

    private static JsonObject boundingBoxGeometry(JsonObject region) {
        for (String key : List.of("x", "y", "width", "height"))
            if (!region.has(key))
                throw new IllegalArgumentException("Region has neither geometry nor a complete bounding box");
        double x = region.get("x").getAsDouble();
        double y = region.get("y").getAsDouble();
        double right = x + region.get("width").getAsDouble();
        double bottom = y + region.get("height").getAsDouble();
        JsonArray ring = new JsonArray();
        for (double[] point : new double[][] {
                {x, y}, {right, y}, {right, bottom}, {x, bottom}, {x, y}}) {
            JsonArray coordinate = new JsonArray();
            coordinate.add(point[0]);
            coordinate.add(point[1]);
            ring.add(coordinate);
        }
        JsonArray rings = new JsonArray();
        rings.add(ring);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", "Polygon");
        geometry.add("coordinates", rings);
        return geometry;
    }
}
