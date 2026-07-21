package com.patolojiatlasi.qupath.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlindedStoreTest {

    @Test
    void blindedDirUsesProjectSubdirElseFallback() {
        File proj = new File("/tmp/proj");
        File fb = new File("/tmp/home/contributions");
        assertEquals(new File(proj, "atlas-focus"), BlindedStore.blindedDir(proj, fb));
        assertEquals(fb, BlindedStore.blindedDir(null, fb));
    }

    @Test
    void zipNameFormat() {
        assertEquals("atlas-focus_20260721-210000_43287be8.zip",
                BlindedStore.zipName("20260721-210000", "43287be8"));
    }

    @Test
    void zipFragmentsSelectsOnlyFocusFilesAndRoundTrips(@TempDir File dir) throws Exception {
        Files.writeString(new File(dir, "focus-blinded__slideA__ts.json").toPath(), "{\"a\":1}");
        Files.writeString(new File(dir, "focus-blinded__slideB__ts.json").toPath(), "{\"b\":2}");
        Files.writeString(new File(dir, "session-x.partial.json").toPath(), "{\"c\":3}");
        Files.writeString(new File(dir, "note.txt").toPath(), "ignore me");
        File zip = new File(dir, "out.zip");
        BlindedStore.zipFragments(dir, zip);
        assertTrue(zip.isFile());
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) names.add(e.getName());
        }
        assertTrue(names.contains("focus-blinded__slideA__ts.json"));
        assertTrue(names.contains("focus-blinded__slideB__ts.json"));
        assertTrue(names.contains("session-x.partial.json"));   // checkpoints included
        assertFalse(names.contains("note.txt"));                // unrelated excluded
        assertEquals(3, names.size());
    }

    @Test
    void zipFragmentsNoThrowOnEmptyOrMissingDir(@TempDir File dir) {
        File empty = new File(dir, "empty");
        empty.mkdirs();
        File zip = new File(dir, "e.zip");
        BlindedStore.zipFragments(empty, zip);   // must not throw
        BlindedStore.zipFragments(new File(dir, "nope"), new File(dir, "n.zip"));  // missing dir, no throw
    }

    @Test
    void hasFragmentsDetectsFragmentsElseFalse(@TempDir File dir) throws Exception {
        assertFalse(BlindedStore.hasFragments(dir));                    // empty
        assertFalse(BlindedStore.hasFragments(new File(dir, "nope")));  // missing dir, no throw
        Files.writeString(new File(dir, "note.txt").toPath(), "x");
        assertFalse(BlindedStore.hasFragments(dir));                    // non-fragment only
        Files.writeString(new File(dir, "focus-blinded__s__t.json").toPath(), "{}");
        assertTrue(BlindedStore.hasFragments(dir));                     // a fragment present
    }
}
