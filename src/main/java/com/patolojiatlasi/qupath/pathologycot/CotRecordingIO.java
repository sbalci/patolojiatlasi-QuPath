package com.patolojiatlasi.qupath.pathologycot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for an <b>authored</b> Pathology-CoT take ({@link CotRecording}).
 *
 * <h2>Three deliberate isolations from blinded data</h2>
 * <ol>
 *   <li><b>Schema.</b> {@link #SCHEMA} is {@code atlas-cot-authored/1} — a new family, not an
 *       {@code atlas-focus-contribution/N} bump. The analysis toolkits accept a file as a study
 *       fragment only when its schema string is in their {@code SCHEMAS} set
 *       ({@code analysis/python/blinded_focus/io.py}, {@code analysis/R/blinded_focus.R}), so every
 *       already-deployed reader rejects an authored take with <b>no code change</b> — it fails
 *       closed. A mode flag inside {@code /5}, or a {@code /6} bump, would fail <i>open</i> for any
 *       reader later extended to accept it.</li>
 *   <li><b>Location.</b> Authored takes are written under {@link #defaultRoot()} in the user's home
 *       directory, never into {@code <project>/atlas-focus/} and by default not into the project at
 *       all. {@code SHARING.md} instructs study participants to send their whole
 *       {@code atlas-focus/} folder, and the analysis toolkits are pointed at a parent directory and
 *       glob {@code *.json} recursively; keeping authored files physically elsewhere means a
 *       participant hand-off can never sweep one up.</li>
 *   <li><b>Identity.</b> Session ids are namespaced {@code auth-<uuid>} ({@link #newSessionId()}),
 *       so an authored id can never collide with or be mistaken for a blinded one.</li>
 * </ol>
 *
 * <h2>The raw slide URI</h2>
 * Unlike a blinded fragment — which carries only an anonymized {@code slideKey} and whose
 * JSON-only, no-username, no-PNG contract is a public promise ({@code README.md}) — an authored
 * take records the <b>raw</b> {@code slideUrl}, because the author is deliberately demonstrating on
 * a known slide and the draft needs to reopen it. For a local file that URI contains a filesystem
 * path, which can carry an OS user name and an accession number. Two consequences, both load
 * bearing: the file stays out of the project folder (above), and it must never be copied into an
 * exported case folder — {@code PathologyCotExport} writes only crops plus
 * {@code conversation.json}, and {@code recording.json} is not among its inputs.
 *
 * <h2>Timestamps</h2>
 * The JSON body carries a <b>date only</b> ({@link LocalDate#now()}), matching the blinded
 * convention of never writing a wall-clock time into a data file. The take <i>folder</i> name does
 * carry {@code yyyyMMdd-HHmmss}, purely to keep successive local takes from colliding; it is a
 * local path, not part of any shared artifact.
 */
public final class CotRecordingIO {

    /** Schema string of an authored take. See the class javadoc for why this is a new family. */
    public static final String SCHEMA = "atlas-cot-authored/1";

    /** Folder, under the user's home directory, that holds authored takes. */
    public static final String ROOT_FOLDER_NAME = "QuPath-atlas-cot";

    /** File name of the take inside its own folder. */
    public static final String RECORDING_FILE_NAME = "recording.json";

    /** Session-id prefix that marks an authored take. */
    public static final String SESSION_ID_PREFIX = "auth-";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private CotRecordingIO() {
    }

    /** A fresh authored session id, namespaced so it can never be read as a blinded one. */
    public static String newSessionId() {
        return SESSION_ID_PREFIX + UUID.randomUUID();
    }

    /** Root directory for authored takes: {@code <user.home>/QuPath-atlas-cot}. */
    public static File defaultRoot() {
        return new File(System.getProperty("user.home", "."), ROOT_FOLDER_NAME);
    }

    /**
     * Directory for one take: {@code <root>/<slide-slug>__<stamp>}. Created by the caller via
     * {@link #ensureDir(File)}; this method only computes the path, so it is testable without
     * touching the filesystem.
     *
     * @param root       {@link #defaultRoot()}, or any caller-chosen root
     * @param slideTitle slide name used for the human-readable half of the folder name
     * @param stamp      a {@code yyyyMMdd-HHmmss} stamp supplied by the caller (never read from a
     *                   clock here, so tests are deterministic)
     */
    public static File takeDir(File root, String slideTitle, String stamp) {
        String slug = slugify(slideTitle);
        return new File(root, slug + "__" + (stamp == null || stamp.isBlank() ? "take" : stamp));
    }

    /** The take's JSON file inside its directory. */
    public static File recordingFile(File takeDir) {
        return new File(takeDir, RECORDING_FILE_NAME);
    }

    /** Creates {@code dir} (and parents) if absent. */
    public static void ensureDir(File dir) throws IOException {
        if (dir != null && !dir.isDirectory())
            Files.createDirectories(dir.toPath());
    }

    /**
     * ASCII-safe, filesystem-safe folder slug from a slide title. Turkish letters are folded to
     * their ASCII counterparts so a take folder is safe on every filesystem and in every shell;
     * everything else non-alphanumeric collapses to a single hyphen.
     */
    public static String slugify(String title) {
        if (title == null || title.isBlank())
            return "slayt";
        // Fold BEFORE lowercasing, then strip any combining marks that lowercasing introduced.
        // Both halves are needed: 'İ'.toLowerCase(ROOT) is TWO code points (i + U+0307 COMBINING
        // DOT ABOVE), so a fold applied after lowercasing never matches the 'İ' and the orphaned
        // mark becomes a separator — "İşlenmiş örnek" slugged to "i-slenmis-ornek".
        String folded = title
                .replace('İ', 'I').replace('ı', 'i')
                .replace('Ş', 'S').replace('ş', 's')
                .replace('Ğ', 'G').replace('ğ', 'g')
                .replace('Ü', 'U').replace('ü', 'u')
                .replace('Ö', 'O').replace('ö', 'o')
                .replace('Ç', 'C').replace('ç', 'c')
                .replace('Â', 'A').replace('â', 'a')
                .replace('Î', 'I').replace('î', 'i')
                .replace('Û', 'U').replace('û', 'u');
        String s = java.text.Normalizer
                .normalize(folded.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}+", "");
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastHyphen = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastHyphen = false;
            } else if (!lastHyphen && sb.length() > 0) {
                sb.append('-');
                lastHyphen = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-')
            sb.setLength(sb.length() - 1);
        if (sb.length() == 0)
            return "slayt";
        return sb.length() > 60 ? sb.substring(0, 60) : sb.toString();
    }

    /** Serializes a take to its JSON body. Exposed separately from {@link #write} for testing. */
    public static String toJson(CotRecording rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("sessionId", rec.sessionId());
        m.put("date", rec.startedDate());
        m.put("slideKey", rec.slideKey());
        m.put("slideUrl", rec.slideUrl());
        m.put("slideTitle", rec.slideTitle());
        m.put("imageWidth", rec.imageWidth());
        m.put("imageHeight", rec.imageHeight());
        m.put("baseMagnification", rec.baseMagnification());
        m.put("provenance", rec.provenance());
        // Written a SECOND time under "decision" on purpose. PathologyCotIO.readFragment ignores the
        // schema string, so the pre-existing file-based menu action ("Gezinme kaydından CoT taslağı
        // oluştur…") can open an authored take — and PathologyCotIO.writeBehaviors copies a
        // fragment's "decision" map verbatim into the behaviors sidecar. Mirroring the provenance
        // here is what makes the authored marker survive that path too, with no change to
        // PathologyCotIO. Without it, drafting an authored take through the old menu item would emit
        // a sidecar indistinguishable from one derived from naturalistic blinded data.
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("cotProvenance", rec.provenance());
        m.put("decision", decision);
        m.put("path", rec.pathSnapshot());
        List<Map<String, Object>> pauses = new ArrayList<>();
        for (long[] p : rec.pauseSnapshot()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("startMs", p[0]);
            pm.put("endMs", p[1]);
            pm.put("reason", CotRecording.PauseReason.values()[(int) p[2]].name().toLowerCase(Locale.ROOT));
            pauses.add(pm);
        }
        m.put("pauses", pauses);
        return GSON.toJson(m);
    }

    /** Today's date, as the {@code date} field wants it. */
    public static String today() {
        return LocalDate.now().toString();
    }

    /** Writes (or replaces) a take's JSON atomically. */
    public static void write(File file, CotRecording rec) throws IOException {
        ensureDir(file.getParentFile());
        writeAtomically(file, toJson(rec));
    }

    /**
     * Writes {@code text} to {@code file} via a sibling temporary file and a move, so a crash (or a
     * JVM shutdown mid-write) can never leave a half-written take behind. A private copy of the same
     * pattern {@code focus.BlindedStore} uses — that class is package-private to {@code focus}, so
     * it cannot be called from here, and duplicating ~10 lines is preferable to widening its
     * visibility and coupling the authored path to the blinded store.
     */
    public static void writeAtomically(File file, String text) throws IOException {
        Path target = file.toPath();
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, "cot-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** What {@link #read} recovers from an authored take on disk. */
    public record Recovered(CotFragment fragment, String slideUrl, String slideTitle,
                            Map<String, Object> provenance) {
    }

    /**
     * Reads an authored take back — the crash-recovery path, and the way the draft action rebuilds a
     * take it did not record itself.
     *
     * @throws IOException if the file is unreadable, malformed, or is not an authored take (a
     *                     blinded fragment is refused here by design: it must go through the
     *                     file-picker action, which treats it as study data)
     */
    public static Recovered read(File file) throws IOException {
        JsonObject o;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Bozuk CoT kaydı: " + e.getMessage(), e);
        }
        String schema = str(o, "schema");
        if (!SCHEMA.equals(schema))
            throw new IOException("Bu dosya bir CoT demo kaydı değil (schema: " + schema + ").");

        List<int[]> path = new ArrayList<>();
        if (o.has("path") && o.get("path").isJsonArray())
            for (JsonElement pe : o.getAsJsonArray("path")) {
                if (!pe.isJsonArray())
                    continue;
                JsonArray pa = pe.getAsJsonArray();
                int[] pt = new int[pa.size()];
                for (int i = 0; i < pa.size(); i++)
                    pt[i] = pa.get(i).getAsInt();
                if (pt.length >= 5)
                    path.add(pt);
            }

        Double baseMag = o.has("baseMagnification") && !o.get("baseMagnification").isJsonNull()
                ? o.get("baseMagnification").getAsDouble() : null;

        Map<String, Object> provenance = new LinkedHashMap<>();
        if (o.has("provenance") && o.get("provenance").isJsonObject())
            for (var e : o.getAsJsonObject("provenance").entrySet())
                provenance.put(e.getKey(), plain(e.getValue()));

        provenance.put("sourceSchema", schema);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("cotProvenance", provenance);

        CotFragment fragment = new CotFragment(str(o, "slideKey"), str(o, "sessionId"),
                intt(o, "imageWidth"), intt(o, "imageHeight"), baseMag, path, decision);
        return new Recovered(fragment, str(o, "slideUrl"), str(o, "slideTitle"), provenance);
    }

    private static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull())
            return null;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isBoolean()) return p.getAsBoolean();
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement el : e.getAsJsonArray())
                list.add(plain(el));
            return list;
        }
        if (e.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet())
                map.put(entry.getKey(), plain(entry.getValue()));
            return map;
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static int intt(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }
}
