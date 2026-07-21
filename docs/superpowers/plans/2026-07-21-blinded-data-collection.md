# Blinded Data Collection (in-project storage + autosave + zip) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Blinded research data saved into `<projectDir>/atlas-focus/`, checkpointed for crash safety, and bundled into one timestamped zip on session end — while preserving the data-only + anonymized guarantees.

**Architecture:** A tested `BlindedStore` helper (dir resolution + zip) + wiring into `FocusHeatmap` (session dir captured at start, periodic checkpoint, JVM shutdown hook, auto-zip on stop).

**Tech Stack:** Java 21, JavaFX, QuPath 0.6 API, JUnit 5, `java.util.zip` (stdlib), Gradle (offline).

## Global Constraints

- Java 21; QuPath 0.6 only; **NO new dependencies**.
- **Data-only preserved:** checkpoints, shutdown-flush, and zip contents are JSON only (via `buildBlindedJson`/`saveBlinded`) — never a PNG, never `save()` (the `currentMapBlinded` guard stands).
- **Anonymized preserved:** no username; `sha256:` non-http slide keys; date-only; zip name = timestamp + random `sessionShort` only.
- **Attribution:** the session's target dir is captured at `startBlinded()` (from `qupath.getProject()` at that moment) and reused for every save — because a project switch that triggers `stopBlinded()` has already moved `qupath.getProject()` to the new project.
- Turkish labels; `String.format`/date formatting pins `java.util.Locale.US`.
- Best-effort: a checkpoint/zip/shutdown failure logs and never breaks recording or stop.

---

### Task 1: `BlindedStore` (dir resolution + zip) + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/focus/BlindedStore.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/focus/BlindedStoreTest.java`

**Interfaces (produces):**
- `static File blindedDir(File projectDir, File homeFallback)`
- `static File zipFragments(File dir, File zipTarget)`
- `static String zipName(String tsStamp, String sessionShort)`

- [ ] **Step 1: Write `BlindedStoreTest` (failing)**

```java
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
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline test --tests BlindedStoreTest` → FAIL (class missing).

- [ ] **Step 3: Implement `BlindedStore`**

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --offline test --tests BlindedStoreTest` → PASS. Then `./gradlew --offline build` → SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/BlindedStore.java \
        src/test/java/com/patolojiatlasi/qupath/focus/BlindedStoreTest.java
git commit -m "feat(focus): BlindedStore — in-project dir resolution + fragment zip + tests"
```

---

### Task 2: `FocusHeatmap` wiring — in-project saves, checkpoint, shutdown hook, auto-zip

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java`
- Modify: `README.md` (blinded-recording subsection: where data goes + the zip + crash safety)

**Interfaces:** Consumes `BlindedStore.blindedDir/zipFragments/zipName`, `BlindedResearch.projectDir(Project)`, `qupath.getProject()`, `FocusMap.getGrid()`.

**Pattern references (read first):** `FocusHeatmap.java` FULL — `startBlinded`/`stopBlinded`/`switchTo`/`saveBlinded`/`buildBlindedJson`/`sessionId`/`currentMap`/`defaultDir`/`tick()` blinded branch; `BlindedResearch.projectDir`.

- [ ] **Step 1: Session dir captured at start**

Add `private File blindedDir;`. In `startBlinded()`, set:
```java
File projectDir = qupath.getProject() == null ? null
        : BlindedResearch.projectDir(qupath.getProject());
blindedDir = BlindedStore.blindedDir(projectDir, new File(defaultDir(), "contributions"));
```
Change `saveBlinded(...)` (and any blinded write) to target `blindedDir` instead of the hardcoded `new File(defaultDir(), "contributions")`. (Leave the non-blinded `save()`/`contribute()` paths on `defaultDir()` unchanged.)

- [ ] **Step 2: Periodic checkpoint**

Add `private static final int CHECKPOINT_EVERY_TICKS = 120;` (~30 s at SAMPLE_MS=250) and `private int blindedTicks;`. In the blinded branch of `tick()`, after a successful deposit, `blindedTicks++`; when `blindedTicks % CHECKPOINT_EVERY_TICKS == 0` and `currentMap` non-empty, write `<blindedDir>/session-<sessionId>.partial.json` via `buildBlindedJson(currentUri, currentMap)` (overwrite; best-effort try/catch, no throw, no PNG). Reset `blindedTicks = 0` in `startBlinded()` and whenever a fresh slide map is created while blinded (in `switchTo`).

- [ ] **Step 3: Promotion / checkpoint cleanup**

In `switchTo` (blinded branch) and `stopBlinded()`, after the final `saveBlinded(...)` for the finished slide, delete the checkpoint: `new File(blindedDir, "session-" + sessionId + ".partial.json").delete()` (best-effort). This removes the checkpoint once its data is safely in a final fragment.

- [ ] **Step 4: JVM shutdown hook**

Add `private boolean shutdownHookAdded;`. In `startBlinded()`, if `!shutdownHookAdded`, register once:
```java
Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown, "atlas-blinded-shutdown"));
shutdownHookAdded = true;
```
`flushOnShutdown()` (best-effort, no throw): if `blindedRecording && currentMap != null && !currentMap.isEmpty()`, take a snapshot (`float[] snap = currentMap.getGrid().clone();`) and write a final blinded JSON from the snapshot into `blindedDir` (build the JSON from a small snapshot map or a snapshot-aware `buildBlindedJson` variant; simplest: synchronize a quick copy into a fresh `FocusMap`-like payload, OR reuse `buildBlindedJson(currentUri, currentMap)` directly accepting the tiny race — document the choice). Wrap everything in try/catch; log only.

- [ ] **Step 5: Auto-zip on session end**

In `stopBlinded()`, after the final fragment + checkpoint cleanup, best-effort:
```java
String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US));
String shortId = sessionId.length() >= 8 ? sessionId.substring(0, 8) : sessionId;
File zipParent = blindedDir.getName().equals("atlas-focus") ? blindedDir.getParentFile() : blindedDir;
BlindedStore.zipFragments(blindedDir, new File(zipParent, BlindedStore.zipName(stamp, shortId)));
```
(Zip goes in the project folder when in-project, else the fallback dir. Wrap in try/catch; a zip failure must not break stop.)

- [ ] **Step 6: README + build + commit**

Update the blinded-recording README subsection: data is written to `<project>/atlas-focus/`; a checkpoint autosaves every ~30 s (so a crash loses at most that); on stop a single `atlas-focus_<timestamp>_<session>.zip` is written to the project folder — the one file to send the coordinator; still JSON-only + anonymized.

Run `./gradlew --offline build` → SUCCESSFUL (BlindedStoreTest + full suite green; FX wiring not unit-tested per convention).
```bash
git add src/main/java/com/patolojiatlasi/qupath/focus/FocusHeatmap.java README.md
git commit -m "feat(focus): blinded data → project/atlas-focus, ~30s checkpoint, shutdown flush, auto-zip"
```

---

## Self-Review (author)

- **Spec coverage:** §3 BlindedStore → Task 1 (full code + tests). §4 wiring (session dir at start, checkpoint, promotion, shutdown hook, auto-zip) → Task 2. §2 attribution (capture dir at start) → Task 2 Step 1. §5 data-only/anon invariants → Task 2 uses only `buildBlindedJson`/`saveBlinded`, never `save()`. ✅
- **Type consistency:** `blindedDir/zipFragments/zipName` match across plan + tests; `BlindedResearch.projectDir` + `FocusMap.getGrid` reused as-is.
- **Traps:** attribution (dir captured at start, not re-resolved at save); shutdown hook self-guards on `blindedRecording` + snapshots the grid; every new path is best-effort no-throw; zip/checkpoint are JSON-only (no `save()`/PNG).
- **Placeholder scan:** none — full code for Task 1; Task 2 gives exact fields/handlers/paths + named references (FX wiring per repo convention).
