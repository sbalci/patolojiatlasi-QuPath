package com.patolojiatlasi.qupath;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Loads the list of atlas cases, either from the catalog bundled with this
 * extension (a snapshot of {@code lists/list.yaml}) or live from that same file
 * on GitHub.
 */
public class AtlasCatalog {

    private static final Logger logger = LoggerFactory.getLogger(AtlasCatalog.class);

    /** Live list, kept up to date by the atlas author. */
    public static final String LIST_URL =
            "https://raw.githubusercontent.com/patolojiatlasi/patolojiatlasi.github.io/main/lists/list.yaml";

    private static final Set<String> WANTED = Set.of(
            "stainname", "reponame", "titleEN", "titleTR", "organEN", "speciality",
            "type", "url", "screenshot");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Load the catalog snapshot bundled inside the extension jar (offline, instant). */
    public static List<AtlasCase> loadBundled() {
        List<AtlasCase> cases = new ArrayList<>();
        try (InputStream in = AtlasCatalog.class.getResourceAsStream("/catalog.json")) {
            if (in == null) {
                logger.warn("Bundled catalog.json not found on classpath");
                return cases;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // Optional catalog-wide default pixel size (µm/px) applied to any image without its
            // own "mpp". Absent (0) = no calibration, so nothing wrong is imposed by default.
            double defaultMpp = d(root, "defaultMpp");
            JsonArray arr = root.getAsJsonArray("cases");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                double mpp = o.has("mpp") ? d(o, "mpp") : defaultMpp;
                cases.add(new AtlasCase(
                        s(o, "reponame"), s(o, "stainname"), s(o, "image"),
                        s(o, "titleEN"), s(o, "titleTR"), s(o, "organEN"),
                        s(o, "speciality"), s(o, "type"), s(o, "dzi"), s(o, "thumb"), mpp));
            }
        } catch (Exception e) {
            logger.error("Failed to read bundled catalog: {}", e.getMessage(), e);
        }
        return cases;
    }

    /** Fetch and parse the live list.yaml from GitHub. Runs on the caller's machine. */
    public static List<AtlasCase> refreshFromList() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(LIST_URL))
                .header("User-Agent", "QuPath-Atlas-Extension")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200)
            throw new Exception("Could not fetch list.yaml (HTTP " + resp.statusCode() + ")");
        return parseList(resp.body());
    }

    /**
     * Minimal parser for the atlas list.yaml. The file is machine-generated with a
     * very regular shape (a top-level list of mappings whose fields are simple
     * scalars), so we read the scalar fields we need line by line and ignore the
     * nested sequences (authors, categories).
     */
    static List<AtlasCase> parseList(String yaml) {
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> cur = null;
        for (String raw : yaml.split("\r?\n")) {
            if (raw.startsWith("- ")) {
                cur = new LinkedHashMap<>();
                records.add(cur);
                putKV(cur, raw.substring(2));
            } else if (raw.startsWith("  - ")) {
                // sequence item (author/category) - ignored
            } else if (raw.startsWith("  ") && cur != null) {
                putKV(cur, raw.trim());
            }
        }

        // Build cases, deduped by (reponame, image), preferring published entries.
        Map<String, AtlasCase> byKey = new LinkedHashMap<>();
        for (Map<String, String> r : records) {
            String url = r.get("url");
            if (url == null || !url.endsWith(".html"))
                continue;
            String image = imageOf(url);
            String dzi = url.substring(0, url.length() - ".html".length()) + ".dzi";
            AtlasCase c = new AtlasCase(
                    r.get("reponame"), r.get("stainname"), image,
                    r.get("titleEN"), r.get("titleTR"), r.get("organEN"),
                    r.get("speciality"), r.get("type"), dzi, r.get("screenshot"));
            String key = c.getReponame() + "/" + image;
            AtlasCase existing = byKey.get(key);
            if (existing == null || (!existing.isPublished() && c.isPublished()))
                byKey.put(key, c);
        }
        return new ArrayList<>(byKey.values());
    }

    private static void putKV(Map<String, String> map, String line) {
        int idx = line.indexOf(':');
        if (idx <= 0)
            return;
        String key = line.substring(0, idx).trim();
        if (!WANTED.contains(key))
            return;
        String val = line.substring(idx + 1).trim();
        if (val.length() >= 2) {
            char a = val.charAt(0), b = val.charAt(val.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"'))
                val = val.substring(1, val.length() - 1);
        }
        map.put(key, val);
    }

    private static String imageOf(String url) {
        String tail = url.substring(url.lastIndexOf('/') + 1);
        return tail.endsWith(".html") ? tail.substring(0, tail.length() - ".html".length()) : tail;
    }

    /** Group cases by category, categories and cases sorted alphabetically. */
    public static Map<String, List<AtlasCase>> groupByCategory(List<AtlasCase> cases) {
        Map<String, List<AtlasCase>> map = new LinkedHashMap<>();
        cases.stream()
                .sorted((a, b) -> a.getCategory().compareToIgnoreCase(b.getCategory()))
                .forEach(c -> map.computeIfAbsent(c.getCategory(), k -> new ArrayList<>()).add(c));
        map.values().forEach(list -> list.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle())));
        return map;
    }

    /**
     * Map the (often sparse or misspelled) speciality/organ fields to a tidy category.
     * Organ/speciality are authoritative; when both are empty, the diagnosis in the
     * title/slug is used as a fallback so cases without organ metadata still group sensibly.
     */
    public static String normalizeCategory(String speciality, String organEN,
                                           String titleEN, String reponame) {
        String primary = classify(((speciality == null ? "" : speciality) + " "
                + (organEN == null ? "" : organEN)).toLowerCase());
        if (primary != null)
            return primary;

        String sp = speciality == null ? "" : speciality.trim();
        if (!sp.isEmpty())
            return capitalize(sp);
        String org = organEN == null ? "" : organEN.trim();
        if (!org.isEmpty())
            return capitalize(org);

        // Fallback: infer from the title / repo name.
        String fromTitle = classify(((titleEN == null ? "" : titleEN) + " "
                + (reponame == null ? "" : reponame)).toLowerCase());
        return fromTitle != null ? fromTitle : "Uncategorized";
    }

    /**
     * Keyword classifier; returns a category or {@code null} if nothing matched.
     * Most keywords are matched as substrings so stems catch compound words
     * (e.g. "sarcoma" in "leiomyosarcoma"). A few short keywords that also occur
     * inside unrelated words ("renal" in "adrenal", "anal" in "analogue", "oral"
     * in "intratumoral", "ent" as an ENT abbreviation) are matched with word-start
     * ({@link #hasBoundary}) or whole-word ({@link #hasFullWord}) checks instead,
     * so they don't shadow more specific categories.
     */
    private static String classify(String hay) {
        if (has(hay, "pancrea", "gallbladder", "bile", "cholecyst", "ampulla"))
            return "Pancreatobiliary";
        if (has(hay, "liver", "hepat"))
            return "Liver";
        if (has(hay, "gastr", "colon", "stomach", "gastric", "rectum", "esophag", "oesophag",
                "duoden", "ileum", "jejun", "appendix", "appendic", "bowel", "intestin", "celiac")
                || hasFullWord(hay, "anal", "anus"))
            return "Gastrointestinal";
        if (has(hay, "neuro", "brain", "cerebell", "mening", "spinal", "glial", "astrocyt",
                "nerve", "schwann", "glioma", "pituitary adenoma"))
            return "Neuropathology";
        if (has(hay, "lung", "pleura", "thorac", "bronch"))
            return "Lung / thoracic";
        if (has(hay, "bone", "cartilage", "osteo", "ochronosis", "chondro"))
            return "Bone";
        if (has(hay, "soft tissue", "adipose", "sarcoma", "liposarc", "pleomorph"))
            return "Soft tissue";
        if (has(hay, "skin", "dermat", "keloid", "morphea", "molluscum"))
            return "Dermatopathology";
        if (has(hay, "breast", "mammary"))
            return "Breast";
        if (has(hay, "prostate", "kidney", "bladder", "testis", "ureter",
                "genitourinary", "urothel") || hasBoundary(hay, "renal"))
            return "Genitourinary";
        if (has(hay, "uter", "cervix", "cervical", "ovar", "endometri", "placenta",
                "vulva", "gynec", "gyneac", "serous"))
            return "Gynecological";
        if (has(hay, "thyroid", "adrenal", "pituitary", "parathyroid", "endocrine"))
            return "Endocrine";
        if (has(hay, "lymph", "spleen", "marrow", "hemato", "hodgkin", "leukemia", "myeloma"))
            return "Hematopathology";
        if (has(hay, "salivary", "larynx", "nasophary", "tongue", "tonsil", "head", "neck")
                || hasBoundary(hay, "oral") || hasFullWord(hay, "ent"))
            return "Head & neck";
        if (has(hay, "cyto"))
            return "Cytopathology";
        if (has(hay, "lecture"))
            return "Lectures";
        if (has(hay, "autopsy"))
            return "Autopsy";
        // Diagnosis-only fallback: generic terms that usually indicate GI but also occur in
        // other organs (thyroid adenoma, endometrial polyp). Checked LAST so organ-specific
        // metadata wins first.
        if (has(hay, "adenoma", "polyp", "signet"))
            return "Gastrointestinal";
        return null;
    }

    /** Heuristic: does this image basename / stain name look like a special stain or IHC marker? */
    public static boolean looksLikeStain(String image, String stainname) {
        String s = ((image == null ? "" : image) + " " + (stainname == null ? "" : stainname)).toLowerCase();
        return has(s, "immunohisto", "histochem", "ihc", "cish", "fish", "pas", "pasd",
                "giemsa", "congo", "crystalviolet", "crystal violet", "trichrome", "reticulin",
                "mucicarmine", "warthin", "trypsin", "ki67", "ki-67", "amyloid", "mgg", "pap",
                "syn", "chromogranin", "grocott", "gms", "ziehl", "afb", "verhoeff", "masson");
    }

    private static boolean has(String hay, String... needles) {
        for (String n : needles)
            if (hay.contains(n))
                return true;
        return false;
    }

    /** True if a needle appears at the START of a word (preceded by start-of-string or a non-letter). */
    private static boolean hasBoundary(String hay, String... needles) {
        for (String n : needles) {
            int from = 0, idx;
            while ((idx = hay.indexOf(n, from)) >= 0) {
                if (idx == 0 || !Character.isLetter(hay.charAt(idx - 1)))
                    return true;
                from = idx + 1;
            }
        }
        return false;
    }

    /** True if a needle appears as a whole word (a non-letter, or string edge, on both sides). */
    private static boolean hasFullWord(String hay, String... needles) {
        for (String n : needles) {
            int from = 0, idx;
            while ((idx = hay.indexOf(n, from)) >= 0) {
                boolean startOk = idx == 0 || !Character.isLetter(hay.charAt(idx - 1));
                int end = idx + n.length();
                boolean endOk = end >= hay.length() || !Character.isLetter(hay.charAt(end));
                if (startOk && endOk)
                    return true;
                from = idx + 1;
            }
        }
        return false;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String s(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    /** Read a numeric field as a double; 0 if absent, null, or not a number. */
    private static double d(JsonObject o, String key) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber())
                return o.get(key).getAsDouble();
        } catch (Exception e) {
            // fall through
        }
        return 0.0;
    }
}
