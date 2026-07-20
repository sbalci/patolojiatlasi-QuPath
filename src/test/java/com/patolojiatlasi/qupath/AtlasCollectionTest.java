package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.patolojiatlasi.qupath.AtlasCollection.Resolution;

class AtlasCollectionTest {

    // reponame, stainname, image, titleEN, titleTR, organEN, speciality, type, dziUrl, thumbUrl, mpp
    private static AtlasCase c(String repo, String image, String dzi) {
        return new AtlasCase(repo, image, image, "T " + repo, "", "Colon", "", "published", dzi, "", 0.0);
    }

    @Test
    void resolveMatchesAcrossMppQueryAsymmetry() {
        // Stored key HAS ?mpp=; catalogue does NOT — must still match (and the reverse).
        AtlasCase catA = c("a", "HE", "https://x/a/HE.dzi");           // catalogue: no query
        AtlasCase catB = c("b", "HE", "https://x/b/HE.dzi?mpp=0.26");  // catalogue: has query
        List<AtlasCase> catalog = List.of(catA, catB);
        AtlasCollection coll = new AtlasCollection(AtlasCollection.FORMAT_VERSION, "s", List.of(
                new AtlasCollection.Entry("https://x/a/HE.dzi?mpp=0.5", "a", "HE", "T a"),  // stored: has query
                new AtlasCollection.Entry("https://x/b/HE.dzi", "b", "HE", "T b"),          // stored: no query
                new AtlasCollection.Entry("https://x/gone/HE.dzi", "gone", "HE", "Gone case")));
        Resolution r = AtlasCollection.resolve(coll, catalog);
        assertEquals(2, r.found().size());
        assertEquals(1, r.missing().size());
        assertEquals("Gone case", r.missing().get(0).title());   // nameable via stored display field
    }

    @Test
    void fromCasesCarriesDisplayFields() {
        AtlasCollection coll = AtlasCollection.fromCases("set", List.of(c("a", "CD3", "https://x/a/CD3.dzi")));
        assertEquals(1, coll.entries().size());
        assertEquals("a", coll.entries().get(0).reponame());
        assertEquals("CD3", coll.entries().get(0).image());
        assertEquals(AtlasCollection.FORMAT_VERSION, coll.formatVersion());
    }

    @Test
    void ioRoundTrips(@TempDir File dir) throws Exception {
        File f = new File(dir, "c.json");
        AtlasCollection coll = AtlasCollection.fromCases("set", List.of(c("a", "HE", "https://x/a/HE.dzi")));
        AtlasCollectionIO.save(coll, f);
        AtlasCollection back = AtlasCollectionIO.load(f);
        assertEquals("set", back.name());
        assertEquals(1, back.entries().size());
        assertEquals("https://x/a/HE.dzi", back.entries().get(0).dziUrl());
    }

    @Test
    void ioFailsSoftOnBadFiles(@TempDir File dir) throws Exception {
        assertNull(AtlasCollectionIO.load(new File(dir, "nope.json")));          // absent
        File garbage = new File(dir, "g.json");
        Files.writeString(garbage.toPath(), "{ not json ");
        assertNull(AtlasCollectionIO.load(garbage));                              // truncated/garbage
        File wrongVer = new File(dir, "v.json");
        Files.writeString(wrongVer.toPath(), "{\"formatVersion\":999,\"name\":\"x\",\"entries\":[]}");
        assertNull(AtlasCollectionIO.load(wrongVer));                             // future version
        File nullEntry = new File(dir, "n.json");
        Files.writeString(nullEntry.toPath(), "{\"formatVersion\":1,\"name\":\"x\",\"entries\":[null]}");
        assertNull(AtlasCollectionIO.load(nullEntry));   // null element inside entries → must not NPE later
    }
}
