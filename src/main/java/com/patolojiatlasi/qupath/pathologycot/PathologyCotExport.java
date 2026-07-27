package com.patolojiatlasi.qupath.pathologycot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.patolojiatlasi.qupath.quiz.AtlasQuiz;
import com.patolojiatlasi.qupath.quiz.QuizGeometry;
import com.patolojiatlasi.qupath.quiz.QuizQuestion;
import com.patolojiatlasi.qupath.quiz.QuizType;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a reviewed guided-tour {@link AtlasQuiz} (see {@code PathologyCotDraft}) out as a
 * self-contained "Pathology-CoT case folder": a slide {@code thumbnail.jpeg}, one crop per
 * exportable NARRATION stop (inspect vs peek, named {@code box_N.jpeg} / {@code cyto_box_N.jpeg} —
 * see {@link #buildConversation} for what makes a stop "exportable"), a chat-style
 * {@code conversation.json} transcript ({@link #buildConversation}), and a governance
 * {@code README.txt} explaining that the folder is a consented, non-anonymized export.
 *
 * <p>{@link #buildConversation} is a pure helper (no image I/O) so the JSON turn structure is
 * unit-testable; {@link #export} performs the real cropping via {@link ImageServer#readRegion} and
 * is exercised by manual/GUI smoke test rather than a unit test (see {@code task-4-brief.md}).
 */
public final class PathologyCotExport {

    /**
     * Native-resolution cutoff below/at which a NARRATION stop is classified as a quick "peek"
     * rather than a deliberate "inspect". Matches {@code PathologyCotDiscretizer.PEEK_DS_MAX}
     * (also {@code 1.5}); duplicated here rather than referenced because that constant is
     * package-private and this class intentionally doesn't couple to the discretizer's internals.
     * Keep the two values in sync if either changes.
     */
    static final double PEEK_DS_MAX = 1.5;

    /** Marker {@link PathologyCotDraft} prefixes a recorded diagnosis/decision with in the quiz description. */
    private static final String DX_MARKER = "Kaydedilen tanı/karar:";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private PathologyCotExport() {
    }

    // ------------------------------------------------------------------------------------------
    // Pure: conversation.json turn structure (no image I/O — unit-tested)
    // ------------------------------------------------------------------------------------------

    /**
     * Build the {@code conversation.json} turn list for a reviewed quiz, without touching any
     * image file. Only {@link QuizType#NARRATION} stops that are "exportable" (see
     * {@link #exportableStops}) — i.e. whose {@code highlightGeoJson} parses to an ROI with
     * positive bounds width and height — are turned into planned-action tokens / image turns;
     * a stop with missing, blank, or invalid geometry is skipped entirely by both this method and
     * {@link #export}, so a {@code conversation.json} image reference always corresponds to a crop
     * file {@code export} actually writes, and both number exportable stops {@code 1..M} in the same
     * order. {@code thumbW}/{@code thumbH} are the thumbnail image's own pixel dimensions (i.e.
     * {@code thumbnail.jpeg}'s width/height, not the full-resolution slide's), used to clamp every
     * inspect/peek bbox to the thumbnail's actual extent; {@code thumbDs} is the thumbnail's
     * downsample, so each bbox in the output is the stop's slide-pixel ROI bounds scaled by
     * {@code 1/thumbDs} (rounded with {@link Math#round(double)}) — i.e. thumbnail-pixel coordinates.
     *
     * <p>Turn order: (1) a {@code user} turn with the task text + the thumbnail image; (2) an
     * {@code assistant} turn listing the planned {@code <inspect>}/{@code <peek>} actions, one per
     * exportable NARRATION stop; (3) per exportable stop, a {@code user} turn carrying the crop
     * image reference followed by an {@code assistant} turn with that stop's {@code explanation}
     * (the region interpretation); (4) a final {@code assistant} turn with
     * {@code <conclusion>...</conclusion>} built from the diagnosis/decision recorded in the quiz
     * description.
     */
    public static List<Map<String, Object>> buildConversation(AtlasQuiz quiz, int thumbW, int thumbH, double thumbDs) {
        List<Map<String, Object>> turns = new ArrayList<>();
        List<ExportableStop> stops = exportableStops(quiz);

        turns.add(turn("user", contentList(textPart(taskText(quiz)), imagePart("thumbnail.jpeg"))));

        StringBuilder plan = new StringBuilder("Planlanan adımlar:\n");
        boolean[] peekFlags = new boolean[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            ExportableStop stop = stops.get(i);
            boolean peek = isPeek(stop.question.getViewport());
            peekFlags[i] = peek;
            int[] box = tokenBox(stop.roi, thumbDs, thumbW, thumbH);
            String tag = peek ? "peek" : "inspect";
            String fname = boxFileName(i + 1, peek);
            plan.append(i + 1).append(". ").append(token(tag, box)).append(" (").append(fname).append(")\n");
        }
        turns.add(turn("assistant", contentList(textPart(plan.toString().stripTrailing()))));

        for (int i = 0; i < stops.size(); i++) {
            ExportableStop stop = stops.get(i);
            String fname = boxFileName(i + 1, peekFlags[i]);
            turns.add(turn("user", contentList(imagePart(fname))));
            String explanation = stop.question.getExplanation() == null ? "" : stop.question.getExplanation();
            turns.add(turn("assistant", contentList(textPart(explanation))));
        }

        String dx = extractDiagnosis(quiz == null ? null : quiz.getDescription());
        turns.add(turn("assistant", contentList(textPart("<conclusion>" + dx + "</conclusion>"))));
        return turns;
    }

    private static List<QuizQuestion> narrationStops(AtlasQuiz quiz) {
        List<QuizQuestion> stops = new ArrayList<>();
        if (quiz == null)
            return stops;
        for (QuizQuestion q : quiz.getQuestions())
            if (q.getType() == QuizType.NARRATION)
                stops.add(q);
        return stops;
    }

    /** A NARRATION stop paired with its resolved highlight ROI; only stops that resolve to one
     *  (see {@link #resolveHighlightRoi}) are "exportable" — see {@link #exportableStops}. */
    private static final class ExportableStop {
        final QuizQuestion question;
        final ROI roi;

        ExportableStop(QuizQuestion question, ROI roi) {
            this.question = question;
            this.roi = roi;
        }
    }

    /**
     * Ordered list of NARRATION stops that are "exportable" — i.e. {@link #resolveHighlightRoi}
     * returns a non-null ROI for them — paired with that resolved ROI. Computed once and shared by
     * {@link #buildConversation} (thumbnail-coordinate token/box) and {@link #export}
     * (native-resolution crop) so a stop is counted in the shared {@code 1..M} numbering, gets an
     * image turn/token, and gets a crop file written IFF it is in this list — keeping
     * {@code conversation.json}'s references and the files {@code export} writes in exact lockstep.
     * A NARRATION stop with missing/blank/malformed {@code highlightGeoJson}, or one whose geometry
     * has non-positive bounds width/height, is excluded entirely (no turn, no token, no crop).
     */
    private static List<ExportableStop> exportableStops(AtlasQuiz quiz) {
        List<ExportableStop> result = new ArrayList<>();
        for (QuizQuestion q : narrationStops(quiz)) {
            ROI roi = resolveHighlightRoi(q);
            if (roi != null)
                result.add(new ExportableStop(q, roi));
        }
        return result;
    }

    /** Resolves {@code q}'s {@code highlightGeoJson} to an ROI, or returns {@code null} if the
     *  field is missing/blank, fails to parse, or parses to an ROI whose (int-truncated) bounds
     *  width or height is non-positive — the same validity check {@code export}'s crop loop used
     *  to decide whether to skip a stop, now shared via {@link #exportableStops}. */
    private static ROI resolveHighlightRoi(QuizQuestion q) {
        String hj = q.getHighlightGeoJson();
        if (hj == null || hj.isBlank())
            return null;
        ROI roi;
        try {
            roi = QuizGeometry.fromGeoJson(hj, ImagePlane.getDefaultPlane());
        } catch (RuntimeException e) {
            return null; // malformed geometry
        }
        if (roi == null)
            return null; // parsed to no geometry (e.g. an empty/unsupported GeoJSON shape)
        if ((int) roi.getBoundsWidth() <= 0 || (int) roi.getBoundsHeight() <= 0)
            return null;
        return roi;
    }

    private static boolean isPeek(QuizQuestion.Viewport vp) {
        return vp != null && vp.downsample <= PEEK_DS_MAX;
    }

    /** ROI bbox (slide px) scaled to thumbnail px: {@code round(bounds / thumbDs)}, clamped to the
     *  thumbnail extent. Only called for exportable stops (see {@link #exportableStops}), whose
     *  ROI is already known to be non-null with positive bounds. */
    private static int[] tokenBox(ROI roi, double thumbDs, int thumbW, int thumbH) {
        double bx = roi.getBoundsX();
        double by = roi.getBoundsY();
        double bw = roi.getBoundsWidth();
        double bh = roi.getBoundsHeight();
        int x0 = clamp((int) Math.round(bx / thumbDs), 0, thumbW);
        int y0 = clamp((int) Math.round(by / thumbDs), 0, thumbH);
        int x1 = clamp((int) Math.round((bx + bw) / thumbDs), 0, thumbW);
        int y1 = clamp((int) Math.round((by + bh) / thumbDs), 0, thumbH);
        return new int[]{x0, y0, x1, y1};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static String token(String tag, int[] box) {
        return "<" + tag + ">[" + box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "]</" + tag + ">";
    }

    private static String boxFileName(int i, boolean peek) {
        return (peek ? "cyto_box_" : "box_") + i + ".jpeg";
    }

    private static String taskText(AtlasQuiz quiz) {
        String title = quiz == null ? null : quiz.getTitle();
        String base = (title == null || title.isBlank()) ? "bu vaka" : title;
        return "Görev: \"" + base + "\" slaydını incele. Küçük resimde işaretlenen "
                + "<inspect>/<peek> bölgelerini sırayla incele, her biri için gözlemini gerekçelendir, "
                + "ardından kaydedilen tanı/kararı <conclusion></conclusion> etiketiyle bildir.";
    }

    /** Extracts the recorded diagnosis/decision out of a quiz description written by
     *  {@code PathologyCotDraft} (prefixed with {@link #DX_MARKER}, first line only); falls back to
     *  the whole (trimmed) description when the marker isn't present. */
    private static String extractDiagnosis(String description) {
        if (description == null)
            return "";
        int idx = description.indexOf(DX_MARKER);
        if (idx < 0)
            return description.trim();
        String rest = description.substring(idx + DX_MARKER.length());
        int nl = rest.indexOf('\n');
        return (nl >= 0 ? rest.substring(0, nl) : rest).trim();
    }

    private static Map<String, Object> turn(String role, List<Map<String, Object>> content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> contentList(Map<String, Object>... parts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> p : parts)
            list.add(p);
        return list;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text);
        return m;
    }

    private static Map<String, Object> imagePart(String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "image");
        m.put("image", fileName);
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Real I/O: crops + conversation.json + README.txt (not unit-tested — GUI/manual smoke test)
    // ------------------------------------------------------------------------------------------

    /**
     * Export a reviewed Pathology-CoT tour + its slide into a case folder under {@code outDir}:
     * {@code thumbnail.jpeg}, one {@code box_N.jpeg}/{@code cyto_box_N.jpeg} crop per exportable
     * NARRATION stop (same {@code 1..M} set/order as {@link #buildConversation} — see
     * {@link #exportableStops}), {@code conversation.json} ({@link #buildConversation}), and a
     * governance {@code README.txt}.
     *
     * @return the number of ROI crops written (i.e. the exportable-stop count — see
     *         {@link #exportableStops}), so a caller can report e.g. "Exported: N ROI".
     */
    public static int export(AtlasQuiz reviewed, ImageServer<BufferedImage> server, File outDir) throws IOException {
        outDir.mkdirs();

        int imgW = server.getWidth();
        int imgH = server.getHeight();
        double thumbDs = Math.max(imgW, imgH) / 1024.0;
        // the thumbnail's own pixel extent (not the slide's) — what buildConversation clamps against.
        int thumbPxW = Math.max(1, (int) Math.round(imgW / thumbDs));
        int thumbPxH = Math.max(1, (int) Math.round(imgH / thumbDs));

        // Write the governance note + full conversation transcript FIRST: if a later readRegion/
        // ImageIO.write throws partway through the crop loop, the folder still carries the
        // consented/non-anonymized notice and the complete turn structure rather than a bare,
        // unexplained pile of slide crops.
        List<Map<String, Object>> conversation = buildConversation(reviewed, thumbPxW, thumbPxH, thumbDs);
        Files.writeString(new File(outDir, "conversation.json").toPath(), GSON.toJson(conversation), StandardCharsets.UTF_8);

        String readme = "Bu klasör, kullanıcının açık bir dışa aktarma eylemiyle ürettiği ONAYLI ve "
                + "ANONİMLEŞTİRİLMEMİŞ bir araştırma çıktısıdır: slayt görüntü kırpmaları, kaydedilen "
                + "tanı/karar ve bu karara ait gerekçe metnini içerir. Anotasyon panosuna gönderilen "
                + "anonimleştirilmiş harmanlanmış (blinded) katkılardan farklıdır ve yalnızca açık bir "
                + "kullanıcı dışa aktarma eylemiyle üretilir; başka hiçbir otomatik süreçle oluşmaz.\n";
        Files.writeString(new File(outDir, "README.txt").toPath(), readme, StandardCharsets.UTF_8);

        BufferedImage thumbnail = server.readRegion(
                RegionRequest.createInstance(server.getPath(), thumbDs, 0, 0, imgW, imgH));
        writeJpeg(thumbnail, new File(outDir, "thumbnail.jpeg"));

        // Same exportableStops() list buildConversation() used to number its tokens/turns — every
        // stop here gets exactly the crop file conversation.json already references, and nothing
        // else, so the two halves can never disagree about which box_i/cyto_box_i files exist.
        List<ExportableStop> stops = exportableStops(reviewed);
        for (int i = 0; i < stops.size(); i++) {
            ExportableStop stop = stops.get(i);
            QuizQuestion.Viewport vp = stop.question.getViewport();
            boolean peek = isPeek(vp);
            double downsample = vp != null ? vp.downsample : 1.0;
            String fname = boxFileName(i + 1, peek);

            ROI roi = stop.roi;
            int boundsX = (int) roi.getBoundsX();
            int boundsY = (int) roi.getBoundsY();
            int boundsW = (int) roi.getBoundsWidth();
            int boundsH = (int) roi.getBoundsHeight();

            BufferedImage crop = server.readRegion(
                    RegionRequest.createInstance(server.getPath(), downsample, boundsX, boundsY, boundsW, boundsH));
            writeJpeg(crop, new File(outDir, fname));
        }
        return stops.size();
    }

    /** Flattens {@code img} onto an opaque white background before writing as JPEG — JPEG has no
     *  alpha channel, and {@link ImageIO#write} silently returns {@code false} (writing nothing)
     *  for a source {@link BufferedImage} that has one (e.g. a {@code TYPE_INT_ARGB} region from an
     *  {@link ImageServer} whose tiles carry an alpha channel). Throws if no JPEG writer is found. */
    private static void writeJpeg(BufferedImage img, File file) throws IOException {
        BufferedImage opaque = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = opaque.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(opaque, "jpg", file))
            throw new IOException("JPEG yazıcısı bulunamadı: " + file.getName());
    }
}
