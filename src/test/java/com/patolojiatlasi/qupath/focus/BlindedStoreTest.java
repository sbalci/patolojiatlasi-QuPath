package com.patolojiatlasi.qupath.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Files;
import java.io.IOException;
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
        Files.writeString(new File(dir, "focus-blinded__slideA__session-a__ts.json").toPath(), "{\"a\":1}");
        Files.writeString(new File(dir, "focus-blinded__slideB__session-a__ts.json").toPath(), "{\"b\":2}");
        Files.writeString(new File(dir, "session-session-a__123.partial.json").toPath(), "{\"c\":3}");
        Files.writeString(new File(dir, "focus-blinded__slideC__session-b__ts.json").toPath(), "{\"d\":4}");
        Files.writeString(new File(dir, "note.txt").toPath(), "ignore me");
        File zip = new File(dir, "out.zip");
        BlindedStore.zipFragments(dir, zip, "session-a");
        assertTrue(zip.isFile());
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) names.add(e.getName());
        }
        assertTrue(names.contains("focus-blinded__slideA__session-a__ts.json"));
        assertTrue(names.contains("focus-blinded__slideB__session-a__ts.json"));
        assertTrue(names.contains("session-session-a__123.partial.json"));   // checkpoints included
        assertFalse(names.contains("focus-blinded__slideC__session-b__ts.json"));
        assertFalse(names.contains("note.txt"));                // unrelated excluded
        assertEquals(3, names.size());
    }

    @Test
    void zipFragmentsRejectsEmptyOrMissingDir(@TempDir File dir) {
        File empty = new File(dir, "empty");
        empty.mkdirs();
        File zip = new File(dir, "e.zip");
        assertThrows(IOException.class, () -> BlindedStore.zipFragments(empty, zip, "session-a"));
        assertThrows(IOException.class, () ->
                BlindedStore.zipFragments(new File(dir, "nope"), new File(dir, "n.zip"), "session-a"));
        assertFalse(zip.exists());
    }

    @Test
    void hasFragmentsDetectsFragmentsElseFalse(@TempDir File dir) throws Exception {
        assertFalse(BlindedStore.hasFragments(dir, "session-a"));                    // empty
        assertFalse(BlindedStore.hasFragments(new File(dir, "nope"), "session-a"));  // missing dir
        Files.writeString(new File(dir, "note.txt").toPath(), "x");
        assertFalse(BlindedStore.hasFragments(dir, "session-a"));     // non-fragment only
        Files.writeString(new File(dir, "focus-blinded__s__session-a__t.json").toPath(), "{}");
        assertTrue(BlindedStore.hasFragments(dir, "session-a"));
        assertFalse(BlindedStore.hasFragments(dir, "session-b"));
    }

    @Test
    void failedZipDoesNotPublishPartialFile(@TempDir File dir) throws Exception {
        Files.writeString(new File(dir, "focus-blinded__s__session-a__t.json").toPath(), "{}");
        File targetDirectory = new File(dir, "target.zip");
        assertTrue(targetDirectory.mkdir());
        assertThrows(IOException.class, () -> BlindedStore.zipFragments(dir, targetDirectory, "session-a"));
        assertTrue(targetDirectory.isDirectory());
    }

    @Test
    void atomicJsonWriteReplacesCompletePayload(@TempDir File dir) throws Exception {
        File file = new File(dir, "fragment.json");
        BlindedStore.writeAtomically(file, "{\"old\":true}");
        BlindedStore.writeAtomically(file, "{\"new\":true}");
        assertEquals("{\"new\":true}", Files.readString(file.toPath()));
    }
}
