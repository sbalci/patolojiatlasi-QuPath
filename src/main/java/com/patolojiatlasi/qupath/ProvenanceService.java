package com.patolojiatlasi.qupath;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provenance context resolution (extension version + best-effort catalogue commit SHA) and the
 * clipboard / file-system side effects behind the citation and manifest dialogs.
 * <p>
 * {@link #resolveContext()} does a network call and must be invoked off the JavaFX thread (e.g.
 * from a background {@code Thread}, as {@link CitationDialog} does). Every other method here
 * (clipboard, file dialogs) touches JavaFX controls/state and must run on the FX thread.
 */
public final class ProvenanceService {

    private static final Logger logger = LoggerFactory.getLogger(ProvenanceService.class);

    private static final String COMMITS_URL =
            "https://api.github.com/repos/patolojiatlasi/patolojiatlasi.github.io/commits"
                    + "?path=lists/list.yaml&per_page=1";
    private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(3);
    private static final String DEFAULT_VERSION = "0.1.0";

    private ProvenanceService() {}

    /**
     * Full provenance context for a citation/manifest export: today's date, this extension's
     * version, and a best-effort catalogue commit SHA fetched from GitHub. The SHA lookup is
     * network I/O, so this method should be called off the JavaFX thread (e.g. from a background
     * {@code Thread}); it never throws, degrading to a {@code null} SHA on any failure.
     */
    public static AtlasCitation.CitationContext resolveContext() {
        return new AtlasCitation.CitationContext(
                LocalDate.now(), resolveExtensionVersion(), resolveCatalogCommitSha());
    }

    /**
     * This extension's implementation version, read from the running jar's manifest
     * ({@code Implementation-Version}, set by the {@code jar{}} block in build.gradle). Falls
     * back to {@value #DEFAULT_VERSION} when unavailable (e.g. running from an IDE/test
     * classpath rather than the built jar, where the manifest attribute doesn't exist). Never
     * throws. Package-visible so {@link CitationDialog} can reuse it for its no-network
     * placeholder context.
     */
    static String resolveExtensionVersion() {
        try {
            String v = AtlasExtension.class.getPackage().getImplementationVersion();
            return (v == null || v.isBlank()) ? DEFAULT_VERSION : v;
        } catch (Exception e) {
            return DEFAULT_VERSION;
        }
    }

    /**
     * Best-effort short (7-character) commit SHA of the atlas catalogue's most recent change to
     * {@code lists/list.yaml}, via an unauthenticated GitHub REST call
     * ({@code GET /repos/.../commits?path=lists/list.yaml&per_page=1}). Returns {@code null} on
     * <em>any</em> exception, connect/read timeout, non-200 response (including GitHub's
     * unauthenticated rate limit, which responds 403), or unexpected payload shape — this is a
     * "nice to have" provenance detail, never a hard requirement for citing a slide.
     */
    private static String resolveCatalogCommitSha() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(NETWORK_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(COMMITS_URL))
                    .timeout(NETWORK_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                return null;
            JsonArray commits = JsonParser.parseString(response.body()).getAsJsonArray();
            if (commits.isEmpty())
                return null;
            JsonObject first = commits.get(0).getAsJsonObject();
            if (!first.has("sha") || first.get("sha").isJsonNull())
                return null;
            String sha = first.get("sha").getAsString();
            return sha.length() > 7 ? sha.substring(0, 7) : sha;
        } catch (Exception e) {
            logger.debug("Catalog commit SHA lookup failed (non-fatal, omitted from citation): {}", e.getMessage());
            return null;
        }
    }

    /** Puts {@code text} on the system clipboard as plain text. FX thread only. */
    public static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Prompts (via {@link FileChooser}) to save {@code content} as a UTF-8 text file, with the
     * chooser's initial file name set to {@code suggestedName}. A no-op if the user cancels the
     * dialog. On a write failure, logs and shows an error {@link Alert} rather than throwing. FX
     * thread only.
     */
    public static void saveTextFile(String suggestedName, String content, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Kaydet…");
        if (suggestedName != null && !suggestedName.isBlank())
            fc.setInitialFileName(suggestedName);
        File file = fc.showSaveDialog(owner);
        if (file == null)
            return;
        try {
            Files.writeString(file.toPath(), content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to save {}: {}", file, e.getMessage(), e);
            new Alert(Alert.AlertType.ERROR, "Dosya kaydedilemedi:\n" + e.getMessage()).showAndWait();
        }
    }

    /**
     * Writes the three cohort-manifest files for {@code cases} into {@code dir} (created if it
     * doesn't exist yet): {@code atlas-manifest.csv}, {@code atlas-manifest.md}, and
     * {@code atlas-methods.txt} (see {@link AtlasCitation#manifestCsv}, {@link
     * AtlasCitation#manifestMarkdown}, {@link AtlasCitation#methodsParagraph}). Propagates any
     * {@link IOException} from the underlying writes so the caller can report it (unlike {@link
     * #saveTextFile}, which is a self-contained UI action, this is meant to be driven by a caller
     * that already manages its own progress/error UI on a background thread).
     */
    public static void saveManifest(File dir, List<AtlasCase> cases, AtlasCitation.CitationContext ctx)
            throws IOException {
        if (dir != null && !dir.exists())
            dir.mkdirs();
        Files.writeString(new File(dir, "atlas-manifest.csv").toPath(),
                AtlasCitation.manifestCsv(cases), StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "atlas-manifest.md").toPath(),
                AtlasCitation.manifestMarkdown(cases, ctx), StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "atlas-methods.txt").toPath(),
                AtlasCitation.methodsParagraph(cases, ctx), StandardCharsets.UTF_8);
    }
}
