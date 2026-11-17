package com.patolojiatlasi.qupath.pathologycot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.patolojiatlasi.qupath.quiz.AtlasQuiz;
import java.util.Map;

/** Recording-origin evidence, separate from review text and export consent. No raw slide URI. */
public final class PathologyCotProvenance {
    private static final Gson GSON = new Gson();
    private PathologyCotProvenance() {}

    /** Copy only recording metadata, never the decision/diagnosis or raw recording payload. */
    public static JsonObject fromDecision(Map<String, Object> decision) {
        if (decision == null || !(decision.get("cotProvenance") instanceof Map<?, ?> source))
            return null;
        JsonObject copy = new JsonObject();
        for (String key : new String[]{"sourceSchema", "recordingMode", "captureIntent",
                "recordingIndicatorVisible", "protocolId", "protocolVersion", "protocolSha256",
                "endReason", "creditedMs", "pausedMs", "pointCount", "pathCapped"}) {
            if (source.containsKey(key)) copy.add(key, GSON.toJsonTree(source.get(key)));
        }
        return copy.size() == 0 ? null : copy;
    }

    /** Always emit explicit unknown for old quizzes or ambiguous/contradictory evidence.
     * Schema/5 is the verified blinded writer contract; other legacy schemas are not guessed.
     * This is metadata evidence, not cryptographic authentication of the recording. */
    public static JsonObject exportMetadata(AtlasQuiz quiz) {
        JsonObject evidence = quiz.getCotProvenance();
        String schema = string(evidence, "sourceSchema");
        String mode = string(evidence, "recordingMode");
        String intent = string(evidence, "captureIntent");
        String origin = "unknown";
        if (CotRecordingIO.SCHEMA.equals(schema)
                && absentOr(mode, "authored") && absentOr(intent, "demonstration")
                && indicatorMatches(evidence, true))
            origin = "authored_demonstration";
        else if ("atlas-focus-contribution/5".equals(schema)
                && absentOr(mode, "blinded") && absentOr(intent, "observation")
                && indicatorMatches(evidence, false))
            origin = "blinded_observation";
        JsonObject out = new JsonObject();
        out.addProperty("schema", "pathology-cot-case-provenance/1");
        out.addProperty("recordingOrigin", origin);
        out.addProperty("anonymized", false);
        if (evidence != null) out.add("cotProvenance", evidence);
        return out;
    }

    private static boolean indicatorMatches(JsonObject evidence, boolean expected) {
        JsonElement value = evidence == null ? null : evidence.get("recordingIndicatorVisible");
        return value == null || value.isJsonNull() || (value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean() == expected);
    }

    private static boolean absentOr(String value, String expected) {
        return value == null || expected.equals(value);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || value.isJsonNull()) return null;
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "<invalid>";
    }
}
