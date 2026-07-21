package com.patolojiatlasi.qupath.focus;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Storage-dir resolution + fragment zipping for blinded research data. */
final class BlindedStore {

    private static final Logger logger = LoggerFactory.getLogger(BlindedStore.class);

    private BlindedStore() {}

    /** {@code <projectDir>/atlas-focus} when in a project, else the home-dir fallback. */
    static File blindedDir(File projectDir, File homeFallback) {
        return projectDir != null ? new File(projectDir, "atlas-focus") : homeFallback;
    }

    static String zipName(String tsStamp, String sessionShort) {
        return "atlas-focus_" + tsStamp + "_" + sessionShort + ".zip";
    }

    private static boolean isFragment(String name) {
        return name.startsWith("focus-blinded__") || name.endsWith(".partial.json");
    }

    /** Zip every blinded fragment (+ any .partial checkpoint) in {@code dir} into {@code zipTarget}. */
    static File zipFragments(File dir, File zipTarget) {
        try {
            File parent = zipTarget.getAbsoluteFile().getParentFile();
            if (parent != null)
                parent.mkdirs();
            File[] files = dir == null ? null : dir.listFiles((d, n) -> isFragment(n));
            try (OutputStream os = Files.newOutputStream(zipTarget.toPath());
                    ZipOutputStream zos = new ZipOutputStream(os)) {
                if (files != null) {
                    for (File f : files) {
                        try {
                            zos.putNextEntry(new ZipEntry(f.getName()));
                            zos.write(Files.readAllBytes(f.toPath()));
                            zos.closeEntry();
                        } catch (IOException e) {
                            logger.warn("Skipping {} in blinded zip: {}", f.getName(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not write blinded zip {}: {}", zipTarget, e.getMessage());
        }
        return zipTarget;
    }
}
