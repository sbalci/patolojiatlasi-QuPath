package com.patolojiatlasi.qupath.focus;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Storage-dir resolution + fragment zipping for blinded research data. */
final class BlindedStore {

    private BlindedStore() {}

    /** {@code <projectDir>/atlas-focus} when in a project, else the home-dir fallback. */
    static File blindedDir(File projectDir, File homeFallback) {
        return projectDir != null ? new File(projectDir, "atlas-focus") : homeFallback;
    }

    static String zipName(String tsStamp, String sessionShort) {
        return "atlas-focus_" + tsStamp + "_" + sessionShort + ".zip";
    }

    /** Replace a JSON file only after the full payload has been written. */
    static void writeAtomically(File file, String text) throws IOException {
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "atlas-focus-", ".json.tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            moveIntoPlace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean isSessionFragment(String name, String sessionId) {
        return (name.startsWith("focus-blinded__") && name.contains("__" + sessionId + "__")
                && name.endsWith(".json"))
                || (name.startsWith("session-" + sessionId + "__") && name.endsWith(".partial.json"));
    }

    /** True if {@code dir} holds a fragment from this recording. */
    static boolean hasFragments(File dir, String sessionId) {
        File[] files = dir == null ? null : dir.listFiles((d, n) -> isSessionFragment(n, sessionId));
        return files != null && files.length > 0;
    }

    /** Write this recording's fragments to a complete ZIP, or throw without publishing a partial ZIP. */
    static File zipFragments(File dir, File zipTarget, String sessionId) throws IOException {
        File[] files = dir == null ? null : dir.listFiles((d, n) -> isSessionFragment(n, sessionId));
        if (files == null || files.length == 0)
            throw new IOException("No blinded fragments for recording " + sessionId + " in " + dir);
        Arrays.sort(files);
        Path target = zipTarget.toPath();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "atlas-focus-", ".zip.tmp");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (File f : files) {
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    Files.copy(f.toPath(), zos);
                    zos.closeEntry();
                }
            }
            moveIntoPlace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        return zipTarget;
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
