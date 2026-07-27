package com.patolojiatlasi.qupath.pathologycot;

import com.google.gson.*;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.Files;
import java.security.MessageDigest; import java.util.*;

/**
 * Reads a recorded focus-contribution fragment (schema {@code atlas-focus-contribution/3-5}, see
 * {@code focus.FocusHeatmap}) into a {@link CotFragment}, and writes the discretized
 * {@link Behavior} list out as a {@code pathology-cot-behaviors/1} analysis-contract JSON file.
 * Also exposes a slide-key matcher so a fragment can be paired back up with the currently open
 * slide in QuPath (anonymized the same way the AI Session Recorder anonymizes it).
 */
public final class PathologyCotIO {
    public static final String BEHAVIORS_SCHEMA = "pathology-cot-behaviors/1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private PathologyCotIO() {}

    public static CotFragment readFragment(File file) throws IOException {
        JsonObject o;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("Bozuk fragment JSON: " + e.getMessage(), e); }

        List<int[]> path = new ArrayList<>();
        if (o.has("path") && o.get("path").isJsonArray())
            for (JsonElement pe : o.getAsJsonArray("path")) {
                JsonArray pa = pe.getAsJsonArray();
                int[] pt = new int[pa.size()];
                for (int i = 0; i < pa.size(); i++) pt[i] = pa.get(i).getAsInt();
                if (pt.length >= 5) path.add(pt);
            }
        Double baseMag = o.has("baseMagnification") && !o.get("baseMagnification").isJsonNull()
                ? o.get("baseMagnification").getAsDouble() : null;
        Map<String,Object> decision = new LinkedHashMap<>();
        if (o.has("decision") && o.get("decision").isJsonObject())
            for (var e : o.getAsJsonObject("decision").entrySet())
                decision.put(e.getKey(), toPlainValue(e.getValue()));
        return new CotFragment(str(o,"slideKey"), str(o,"sessionId"),
                intt(o,"imageWidth"), intt(o,"imageHeight"), baseMag, path, decision);
    }

    /** Coerces a parsed {@link JsonElement} to a plain Java value (String/Number/Boolean/null/
     *  List/Map) so downstream consumers of {@link CotFragment#decision()} never have to unwrap
     *  Gson types — and so re-serializing the map with {@link #GSON} round-trips cleanly. */
    private static Object toPlainValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isBoolean()) return p.getAsBoolean();
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement el : e.getAsJsonArray()) list.add(toPlainValue(el));
            return list;
        }
        if (e.isJsonObject()) {
            Map<String,Object> map = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet()) map.put(entry.getKey(), toPlainValue(entry.getValue()));
            return map;
        }
        return null;
    }

    public static void writeBehaviors(File out, CotFragment f, List<Behavior> behaviors) throws IOException {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("schema", BEHAVIORS_SCHEMA);
        m.put("slideKey", f.slideKey()); m.put("sessionId", f.sessionId());
        m.put("imageWidth", f.imageWidth()); m.put("imageHeight", f.imageHeight());
        m.put("baseMagnification", f.baseMagnification());
        if (f.decision() != null && !f.decision().isEmpty()) m.put("decision", f.decision());
        List<Map<String,Object>> bs = new ArrayList<>();
        for (Behavior b : behaviors) {
            Map<String,Object> bm = new LinkedHashMap<>();
            bm.put("type", b.type().name().toLowerCase(Locale.ROOT));
            bm.put("magBin", b.magBin());
            bm.put("x", b.x()); bm.put("y", b.y()); bm.put("w", b.w()); bm.put("h", b.h());
            bm.put("startMs", b.startMs()); bm.put("endMs", b.endMs()); bm.put("dwellMs", b.dwellMs());
            bs.add(bm);
        }
        m.put("behaviors", bs);
        Files.writeString(out.toPath(), GSON.toJson(m), StandardCharsets.UTF_8);
    }

    // --- verbatim mirror of FocusHeatmap.slideKey / anonymizeSlideKey (keep in sync) ---
    static String slideKey(String uri) {
        if (uri == null || uri.isBlank()) return "unknown";
        int q = uri.indexOf('?'); return q >= 0 ? uri.substring(0, q) : uri;
    }
    public static String anonymize(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return key;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b));
            return "sha256:" + sb;
        } catch (Exception e) { return "sha256:unavailable"; }
    }
    public static String anonymizedKeyForOpenSlide(String uri) { return anonymize(slideKey(uri)); }
    public static boolean slideMatches(CotFragment f, String openUri) {
        return f.slideKey() != null && f.slideKey().equals(anonymizedKeyForOpenSlide(openUri));
    }

    private static String str(JsonObject o, String k){ return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null; }
    private static int intt(JsonObject o, String k){ return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0; }
}
