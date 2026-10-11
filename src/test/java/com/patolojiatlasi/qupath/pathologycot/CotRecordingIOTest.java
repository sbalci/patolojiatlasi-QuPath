package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistence and — more importantly — the governance invariants that keep an authored take from
 * ever being read as blinded study data.
 */
class CotRecordingIOTest {

    private static CotRecording take() {
        CotRecording rec = new CotRecording("auth-abc", "file:/C:/slides/case1.svs", "case1.svs",
                "sha256:deadbeef", 100_000, 80_000, 40.0, "2026-10-11",
                CotRecorder.PROTOCOL_ID, CotRecorder.PROTOCOL_VERSION, CotRecorder.protocolSha256());
        for (int i = 0; i < 8; i++)
            rec.addPoint(i * 250L, 5000, 5000, 4096, 4096, 4000, -1, -1, 250);
        rec.addPause(2000, 5000, CotRecording.PauseReason.UNFOCUSED);
        rec.setEndReason(CotRecording.EndReason.USER);
        return rec;
    }

    // --- the three isolations ------------------------------------------------

    @Test void schemaIsItsOwnFamilyNotAFocusContributionBump() {
        // Load-bearing: the analysis toolkits accept a file as a study fragment only when its
        // schema string is in their SCHEMAS set, so a brand-new family is rejected by every
        // already-deployed reader with no code change (fail closed). A /6 bump would fail open.
        assertEquals("atlas-cot-authored/1", CotRecordingIO.SCHEMA);
        assertFalse(CotRecordingIO.SCHEMA.startsWith("atlas-focus-contribution/"));
        assertTrue(CotRecordingIO.toJson(take()).contains("\"atlas-cot-authored/1\""));
    }

    @Test void sessionIdsAreNamespacedAwayFromBlindedOnes() {
        String id = CotRecordingIO.newSessionId();
        assertTrue(id.startsWith("auth-"), id);
        assertNotEquals(CotRecordingIO.newSessionId(), id);
    }

    @Test void defaultRootIsOutsideAnyProject() {
        // Authored takes carry the RAW slide URI, and SHARING.md tells study participants to send
        // their whole atlas-focus/ folder — so authored files must not live in the project tree.
        File root = CotRecordingIO.defaultRoot();
        assertEquals("QuPath-atlas-cot", root.getName());
        assertFalse(root.getPath().contains("atlas-focus"));
    }

    // --- provenance survives every route ------------------------------------

    @Test void provenanceMarksTheTakeAsAnAuthoredDemonstration() {
        Map<String, Object> p = take().provenance();
        assertEquals("authored", p.get("recordingMode"));
        assertEquals("demonstration", p.get("captureIntent"));
        assertEquals(Boolean.TRUE, p.get("recordingIndicatorVisible"));
        assertEquals("user", p.get("endReason"));
        assertEquals(CotRecorder.PROTOCOL_ID, p.get("protocolId"));
        assertNotNull(p.get("protocolSha256"));
        assertEquals(2000L, p.get("creditedMs"), "8 points x 250 ms");
        assertEquals(3000L, p.get("pausedMs"));
    }

    @Test void provenanceIsMirroredUnderDecisionSoTheLegacyDraftPathKeepsIt() throws IOException {
        // PathologyCotIO.readFragment ignores the schema string, so the pre-existing file-picker
        // menu action can open an authored take; PathologyCotIO.writeBehaviors copies a fragment's
        // "decision" map verbatim into the sidecar. Mirroring provenance there is what stops that
        // route from emitting a sidecar indistinguishable from naturalistic blinded data.
        String json = CotRecordingIO.toJson(take());
        assertTrue(json.contains("\"cotProvenance\""), json.substring(0, Math.min(400, json.length())));

        File f = Files.createTempFile("cot-", ".json").toFile();
        Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
        try {
            CotFragment viaLegacyReader = PathologyCotIO.readFragment(f);
            assertNotNull(viaLegacyReader.decision().get("cotProvenance"));
            @SuppressWarnings("unchecked")
            Map<String, Object> prov = (Map<String, Object>) viaLegacyReader.decision().get("cotProvenance");
            assertEquals("authored", prov.get("recordingMode"));
        } finally {
            assertTrue(f.delete() || !f.exists());
        }
    }

    // --- round trip ---------------------------------------------------------

    @Test void writeThenReadRoundTrips(@TempDir File dir) throws IOException {
        File file = CotRecordingIO.recordingFile(dir);
        CotRecordingIO.write(file, take());
        assertTrue(file.isFile());

        CotRecordingIO.Recovered r = CotRecordingIO.read(file);
        assertEquals("file:/C:/slides/case1.svs", r.slideUrl());
        assertEquals("case1.svs", r.slideTitle());
        assertEquals("authored", r.provenance().get("recordingMode"));
        assertEquals("sha256:deadbeef", r.fragment().slideKey());
        assertEquals("auth-abc", r.fragment().sessionId());
        assertEquals(100_000, r.fragment().imageWidth());
        assertEquals(40.0, r.fragment().baseMagnification());
        assertEquals(8, r.fragment().path().size());
        assertEquals(8, r.fragment().path().get(0).length, "the /5 point layout is preserved");
    }

    @Test void readRefusesAnythingThatIsNotAnAuthoredTake(@TempDir File dir) throws IOException {
        File f = new File(dir, "blinded.json");
        Files.writeString(f.toPath(),
                "{\"schema\":\"atlas-focus-contribution/5\",\"slideKey\":\"sha256:x\",\"path\":[]}",
                StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> CotRecordingIO.read(f));
        assertTrue(e.getMessage().contains("atlas-focus-contribution/5"), e.getMessage());
    }

    @Test void readRejectsMalformedJson(@TempDir File dir) throws IOException {
        File f = new File(dir, "broken.json");
        Files.writeString(f.toPath(), "{not json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> CotRecordingIO.read(f));
    }

    @Test void writeIsAtomicAndLeavesNoTempFileBehind(@TempDir File dir) throws IOException {
        File file = new File(dir, "recording.json");
        CotRecordingIO.write(file, take());
        CotRecordingIO.write(file, take());   // replace an existing file
        File[] leftovers = dir.listFiles((d, n) -> n.endsWith(".tmp"));
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length, "the temp file must be moved or deleted, never left");
        assertEquals(1, dir.listFiles().length);
    }

    @Test void writeCreatesMissingParentDirectories(@TempDir File dir) throws IOException {
        File nested = new File(new File(dir, "a"), "b");
        File file = CotRecordingIO.recordingFile(nested);
        CotRecordingIO.write(file, take());
        assertTrue(file.isFile());
    }

    // --- naming -------------------------------------------------------------

    @Test void slugifyFoldsTurkishAndStaysFilesystemSafe() {
        assertEquals("gogus-biyopsisi", CotRecordingIO.slugify("Göğüs Biyopsisi"));
        assertEquals("case1-svs", CotRecordingIO.slugify("case1.svs"));
        assertEquals("islenmis-ornek", CotRecordingIO.slugify("İşlenmiş  örnek!!"));
        assertEquals("slayt", CotRecordingIO.slugify(null));
        assertEquals("slayt", CotRecordingIO.slugify("   "));
        assertEquals("slayt", CotRecordingIO.slugify("///"), "a slug can never be empty");
        assertTrue(CotRecordingIO.slugify("x".repeat(200)).length() <= 60);
        assertFalse(CotRecordingIO.slugify("a/b\\c:d*e?f").matches(".*[/\\\\:*?].*"));
    }

    @Test void takeDirCombinesSlugAndStamp() {
        File d = CotRecordingIO.takeDir(new File("/root"), "Göğüs Biyopsisi", "20261011-141500");
        assertEquals("gogus-biyopsisi__20261011-141500", d.getName());
        assertEquals("slayt__take", CotRecordingIO.takeDir(new File("/root"), null, null).getName());
    }

    @Test void bodyCarriesADateOnlyNeverAWallClockTime() {
        // Same convention as the blinded fragment: no time-of-day in a data file.
        String json = CotRecordingIO.toJson(take());
        assertTrue(json.contains("\"date\": \"2026-10-11\""), json.substring(0, 300));
        assertFalse(json.contains("14:15"), "no clock time in the body");
        assertTrue(CotRecordingIO.today().matches("\\d{4}-\\d{2}-\\d{2}"), CotRecordingIO.today());
    }
}
