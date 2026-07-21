package com.patolojiatlasi.qupath.research;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.projects.Project;

/**
 * Project-level "blinded research tracking" sidecar: {@code <projectDir>/atlas-research.json}
 * carries a {@code blindedTracking} opt-in flag (set by the project builder) and a one-time
 * {@code consented} flag (set once the user accepts the consent notice shown by
 * {@code AtlasExtension}'s project-open hook). Save/load mirrors
 * {@link com.patolojiatlasi.qupath.AtlasCollectionIO}'s fail-soft pattern: a missing, unreadable,
 * or unrecognised sidecar is treated as "not blinded / not consented" rather than thrown.
 */
public final class BlindedResearch {

    private static final Logger logger = LoggerFactory.getLogger(BlindedResearch.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "atlas-research.json";
    private static final String SCHEMA = "atlas-research/1";

    private BlindedResearch() {}

    /** On-disk shape of {@code atlas-research.json}. */
    private static final class Sidecar {
        String schema;
        boolean blindedTracking;
        boolean consented;

        Sidecar(String schema, boolean blindedTracking, boolean consented) {
            this.schema = schema;
            this.blindedTracking = blindedTracking;
            this.consented = consented;
        }
    }

    // --- Project-taking public API (used by AtlasExtension's project-open hook) ---------------

    /** Whether {@code project} carries the blinded-tracking flag. Null-safe (unsaved project -> false). */
    public static boolean isBlindedProject(Project<?> project) {
        File dir = projectDir(project);
        return dir != null && readBlinded(dir);
    }

    /** Whether the one-time consent notice has already been accepted for {@code project}. */
    public static boolean hasConsented(Project<?> project) {
        File dir = projectDir(project);
        return dir != null && readConsented(dir);
    }

    /** Record acceptance of the one-time consent notice for {@code project}. No-op if unsaved. */
    public static void markConsented(Project<?> project) {
        File dir = projectDir(project);
        if (dir != null)
            markConsented(dir);
    }

    /**
     * The project's directory (the parent of its {@code .qpproj} path), or {@code null} for a
     * project that hasn't been saved to disk yet (or {@code project} itself is {@code null}).
     */
    public static File projectDir(Project<?> project) {
        if (project == null || project.getPath() == null)
            return null;
        var parent = project.getPath().getParent();
        return parent == null ? null : parent.toFile();
    }

    // --- File-taking helpers --------------------------------------------------------------
    // writeFlag is public: it's the entry point the project builder calls directly with a
    // known project directory (before/without going through a Project handle). The readers and
    // markConsented(File) stay package-private -- they're exercised directly by BlindedResearchTest
    // and used internally by the Project-taking overloads above; no other caller needs them.

    /** Write (or update) the sidecar in {@code dir}, setting {@code blindedTracking}. Preserves
     *  any existing {@code consented} value. Fail-soft: logs and returns on write failure. */
    public static void writeFlag(File dir, boolean blinded) {
        Sidecar existing = read(dir);
        boolean consented = existing != null && existing.consented;
        write(dir, new Sidecar(SCHEMA, blinded, consented));
    }

    /** Whether the sidecar in {@code dir} has {@code blindedTracking} set. Missing/corrupt -> false. */
    static boolean readBlinded(File dir) {
        Sidecar s = read(dir);
        return s != null && s.blindedTracking;
    }

    /** Mark the sidecar in {@code dir} as consented, preserving its {@code blindedTracking} value. */
    static void markConsented(File dir) {
        Sidecar existing = read(dir);
        boolean blinded = existing != null && existing.blindedTracking;
        write(dir, new Sidecar(SCHEMA, blinded, true));
    }

    /** Whether the sidecar in {@code dir} has {@code consented} set. Missing/corrupt -> false. */
    static boolean readConsented(File dir) {
        Sidecar s = read(dir);
        return s != null && s.consented;
    }

    // --- shared read/write --------------------------------------------------------------------

    private static Sidecar read(File dir) {
        if (dir == null)
            return null;
        File file = new File(dir, FILE_NAME);
        if (!file.isFile())
            return null;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Sidecar s = GSON.fromJson(json, Sidecar.class);
            if (s == null || !SCHEMA.equals(s.schema)) {
                logger.warn("Ignoring research sidecar {} (bad or unsupported format)", file);
                return null;
            }
            return s;
        } catch (Exception e) {
            logger.warn("Could not read research sidecar {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static void write(File dir, Sidecar sidecar) {
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                logger.warn("Could not create project directory {}", dir);
            File file = new File(dir, FILE_NAME);
            Files.writeString(file.toPath(), GSON.toJson(sidecar), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not write research sidecar in {}: {}", dir, e.getMessage());
        }
    }
}
