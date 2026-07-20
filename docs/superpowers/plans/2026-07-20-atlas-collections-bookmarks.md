# Portable Collections & Bookmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save/load/share a curated set of atlas slides as a small portable JSON file, plus a per-slide ★ favorite toggle auto-persisted as a "Favorites" collection — both populating the browser's selection basket.

**Architecture:** One reused schema (`AtlasCollection`) with a query-strip-both-sides `resolve`; a fail-soft `AtlasCollectionIO`; a `Favorites` key-set store at a fixed home-dir path; and `AtlasBrowser` UI (buttons + context toggle + the tree's first cell factory for the ★ marker).

**Tech Stack:** Java 21, JavaFX, QuPath 0.6 API (`compileOnly`), Gson (already a dep via quiz), JUnit 5, Gradle (offline).

## Global Constraints

- Java 21; QuPath 0.6 only; **NO new dependencies** (Gson already present).
- Turkish participant-facing labels; menu-path strings English.
- **Portable resolve:** `AtlasCollection.resolve` strips the `?…` query on BOTH the stored entry key and the catalogue `getDziUrl()` (reuse `CaseCompare.stripQuery`) — tested against the `?mpp=` asymmetry, not just a clean round-trip.
- **Fail-soft persistence:** a truncated / absent / wrong-`formatVersion` file loads as empty/null, never throws (favorites is mutable read-modify-write; must not break browser open).
- **★ cell factory (the tree's first):** `updateItem` resets text AND graphic on empty/null; branch on type (`String` category via `toString()`, else `AtlasCase`); `tree.refresh()` after a toggle; favorites held as a `Set<String>` of stripped URLs for O(1) per-cell `contains`.
- **No `setImageData` guard here** — loading only mutates the in-memory basket; it never swaps a viewer. Do not add or hunt for feature #4's swap guard.
- File I/O runs synchronously on the FX thread after a FileChooser / toggle (matches `AtlasQuizIO` convention; files are tiny, no network).
- One schema: Favorites IS an `AtlasCollection` at a fixed path — do not fork a second format.

---

### Task 1: `AtlasCollection` + `AtlasCollectionIO` + `Favorites` + tests

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/AtlasCollection.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/AtlasCollectionIO.java`
- Create: `src/main/java/com/patolojiatlasi/qupath/Favorites.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/AtlasCollectionTest.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/FavoritesTest.java`

**Interfaces:**
- Consumes: `AtlasCase.getDziUrl/getReponame/getImage/getTitle`, `CaseCompare.stripQuery(String)`.
- Produces:
  - `record AtlasCollection.Entry(String dziUrl, String reponame, String image, String title)`
  - `record AtlasCollection(int formatVersion, String name, List<Entry> entries)` + `int FORMAT_VERSION = 1`
  - `static AtlasCollection AtlasCollection.fromCases(String name, java.util.Collection<AtlasCase> cases)`
  - `record AtlasCollection.Resolution(List<AtlasCase> found, List<Entry> missing)`
  - `static Resolution AtlasCollection.resolve(AtlasCollection coll, List<AtlasCase> catalog)`
  - `static void AtlasCollectionIO.save(AtlasCollection, java.io.File) throws java.io.IOException`
  - `static AtlasCollection AtlasCollectionIO.load(java.io.File)` (fail-soft: returns `null` on absent/bad/old file)
  - `class Favorites` with `boolean contains(AtlasCase)`, `boolean toggle(AtlasCase)`, `List<AtlasCase> resolve(List<AtlasCase> catalog)`, `void save()`, and a `static Favorites load()` (corruption-tolerant; fixed path)

- [ ] **Step 1: Write `AtlasCollectionTest` (failing)**

```java
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
    }
}
```

- [ ] **Step 2: Write `FavoritesTest` (failing)**

```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FavoritesTest {

    private static AtlasCase c(String repo, String dzi) {
        return new AtlasCase(repo, "HE", "HE", "T " + repo, "", "Colon", "", "published", dzi, "", 0.0);
    }

    @Test
    void toggleFlipsMembershipKeyedByStrippedUrl() {
        Favorites fav = Favorites.inMemory();          // test ctor: no disk
        AtlasCase withQ = c("a", "https://x/a/HE.dzi?mpp=0.26");
        AtlasCase bare  = c("a", "https://x/a/HE.dzi");
        assertFalse(fav.contains(withQ));
        assertTrue(fav.toggle(withQ));                 // now favorite
        assertTrue(fav.contains(bare));                // same favorite despite query difference
        assertFalse(fav.toggle(bare));                 // toggles off
        assertFalse(fav.contains(withQ));
    }

    @Test
    void resolveReturnsCatalogueCases() {
        Favorites fav = Favorites.inMemory();
        fav.toggle(c("a", "https://x/a/HE.dzi"));
        List<AtlasCase> catalog = List.of(c("a", "https://x/a/HE.dzi"), c("b", "https://x/b/HE.dzi"));
        List<AtlasCase> res = fav.resolve(catalog);
        assertTrue(res.size() == 1 && res.get(0).getReponame().equals("a"));
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew --offline test --tests AtlasCollectionTest --tests FavoritesTest` → FAIL (classes missing).

- [ ] **Step 4: Implement `AtlasCollection`**

```java
package com.patolojiatlasi.qupath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A portable, shareable pick-list of atlas slides. Pure model + resolve; no UI/IO. */
public record AtlasCollection(int formatVersion, String name, List<Entry> entries) {

    public static final int FORMAT_VERSION = 1;

    public record Entry(String dziUrl, String reponame, String image, String title) {}

    public record Resolution(List<AtlasCase> found, List<Entry> missing) {}

    public static AtlasCollection fromCases(String name, Collection<AtlasCase> cases) {
        List<Entry> entries = new ArrayList<>();
        for (AtlasCase c : cases)
            entries.add(new Entry(c.getDziUrl(), c.getReponame(), c.getImage(), c.getTitle()));
        return new AtlasCollection(FORMAT_VERSION, name, entries);
    }

    /** Re-match each entry against {@code catalog} by query-stripped DZI URL (both sides). */
    public static Resolution resolve(AtlasCollection coll, List<AtlasCase> catalog) {
        Map<String, AtlasCase> byKey = new LinkedHashMap<>();
        for (AtlasCase c : catalog)
            byKey.putIfAbsent(CaseCompare.stripQuery(c.getDziUrl()), c);
        List<AtlasCase> found = new ArrayList<>();
        List<Entry> missing = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry e : coll.entries()) {
            String key = CaseCompare.stripQuery(e.dziUrl());
            AtlasCase c = byKey.get(key);
            if (c != null && seen.add(key))
                found.add(c);
            else if (c == null)
                missing.add(e);
        }
        return new Resolution(found, missing);
    }
}
```

- [ ] **Step 5: Implement `AtlasCollectionIO`**

```java
package com.patolojiatlasi.qupath;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Save/load {@link AtlasCollection} JSON. Load is fail-soft (bad/old/absent → null). */
public final class AtlasCollectionIO {

    private static final Logger logger = LoggerFactory.getLogger(AtlasCollectionIO.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AtlasCollectionIO() {}

    public static void save(AtlasCollection coll, File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null)
            parent.mkdirs();
        Files.writeString(file.toPath(), GSON.toJson(coll), StandardCharsets.UTF_8);
    }

    /** Returns null on absent file, parse failure, or a formatVersion this build can't read. */
    public static AtlasCollection load(File file) {
        if (file == null || !file.isFile())
            return null;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            AtlasCollection coll = GSON.fromJson(json, AtlasCollection.class);
            if (coll == null || coll.formatVersion() != AtlasCollection.FORMAT_VERSION
                    || coll.entries() == null) {
                logger.warn("Ignoring collection {} (bad or unsupported format)", file);
                return null;
            }
            return coll;
        } catch (Exception e) {
            logger.warn("Could not read collection {}: {}", file, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 6: Implement `Favorites`**

```java
package com.patolojiatlasi.qupath;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A fixed-path "Favorites" collection keyed by query-stripped DZI URL. Corruption-tolerant. */
public final class Favorites {

    private static final Logger logger = LoggerFactory.getLogger(Favorites.class);
    private static final String FILE_NAME = "favorites.json";
    private static final String COLLECTION_NAME = "Favorites";

    private final Set<String> keys = new LinkedHashSet<>();   // query-stripped DZI URLs
    private final File file;                                   // null = in-memory (tests)

    private Favorites(File file) {
        this.file = file;
    }

    /** In-memory instance for tests (no disk). */
    static Favorites inMemory() {
        return new Favorites(null);
    }

    /** Loads favorites from the fixed path; a missing/corrupt/old file yields empty favorites. */
    public static Favorites load() {
        File f = new File(collectionsDir(), FILE_NAME);
        Favorites fav = new Favorites(f);
        AtlasCollection coll = AtlasCollectionIO.load(f);   // already fail-soft (null on bad file)
        if (coll != null)
            for (AtlasCollection.Entry e : coll.entries())
                fav.keys.add(CaseCompare.stripQuery(e.dziUrl()));
        return fav;
    }

    public static File collectionsDir() {
        return new File(System.getProperty("user.home"), "QuPath-atlas-collections");
    }

    public boolean contains(AtlasCase c) {
        return c != null && keys.contains(CaseCompare.stripQuery(c.getDziUrl()));
    }

    /** Adds/removes the case; returns the new favorite state. Persists if backed by a file. */
    public boolean toggle(AtlasCase c) {
        if (c == null)
            return false;
        String key = CaseCompare.stripQuery(c.getDziUrl());
        boolean nowFavorite;
        if (keys.remove(key)) {
            nowFavorite = false;
        } else {
            keys.add(key);
            nowFavorite = true;
        }
        save();
        return nowFavorite;
    }

    public List<AtlasCase> resolve(List<AtlasCase> catalog) {
        List<AtlasCase> out = new ArrayList<>();
        for (AtlasCase c : catalog)
            if (contains(c))
                out.add(c);
        return out;
    }

    /** Persists the current favorites; no-op for the in-memory test instance; best-effort. */
    public void save() {
        if (file == null)
            return;
        List<AtlasCollection.Entry> entries = new ArrayList<>();
        for (String k : keys)
            entries.add(new AtlasCollection.Entry(k, "", "", ""));   // key is all that's needed
        try {
            AtlasCollectionIO.save(new AtlasCollection(AtlasCollection.FORMAT_VERSION,
                    COLLECTION_NAME, entries), file);
        } catch (Exception e) {
            logger.warn("Could not save favorites: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew --offline test --tests AtlasCollectionTest --tests FavoritesTest` → PASS. Then `./gradlew --offline build` → SUCCESSFUL (full suite green).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasCollection.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasCollectionIO.java \
        src/main/java/com/patolojiatlasi/qupath/Favorites.java \
        src/test/java/com/patolojiatlasi/qupath/AtlasCollectionTest.java \
        src/test/java/com/patolojiatlasi/qupath/FavoritesTest.java
git commit -m "feat(collections): AtlasCollection + IO + Favorites core (portable resolve, fail-soft) + tests"
```

---

### Task 2: `AtlasBrowser` integration — buttons, ★ toggle + cell factory, README

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `AtlasCollection.fromCases/resolve`, `AtlasCollectionIO.save/load`, `Favorites.load/contains/toggle/resolve/collectionsDir`, `AtlasCase.toString`, the browser's `selection`/`allCases`/`tree`/`updateSelectionCount`/`status`/`stage`.

**Pattern references (read first):** `AtlasBrowser.java` (its `selection` basket, `addToSelection`, `updateSelectionCount`, the bottom `HBox`, the `ContextMenu`, `openProjectBuilder`/FileChooser usage if any), `QuizAuthorWindow.java` (its `FileChooser` save/load idiom + extension filter), `FocusHeatmap.java` (the home-dir file pattern).

- [ ] **Step 1: Add a `Favorites` field + load it**

In `AtlasBrowser`, add `private final Favorites favorites = Favorites.load();` (loaded once when the browser is constructed; fail-soft so it never breaks open).

- [ ] **Step 2: Add the ★ cell factory (the tree's first)**

Set `tree.setCellFactory(tv -> new TreeCell<>() { @Override protected void updateItem(Object item, boolean empty) { super.updateItem(item, empty); if (empty || item == null) { setText(null); setGraphic(null); return; } if (item instanceof AtlasCase c) setText((favorites.contains(c) ? "★ " : "") + c.toString()); else setText(item.toString()); } });`
(Category `String`s render via `toString()`; `AtlasCase` gets the ★ prefix when favorited. Import `javafx.scene.control.TreeCell`.)

- [ ] **Step 3: Add the "★ Favori" context-menu toggle**

Add a `MenuItem favoriteItem = new MenuItem("★ Favori (aç/kapat)");` to the existing `ContextMenu` (alongside `addToSelectionItem`/`referenceItem`/`citeItem`). Its action: resolve the tree-selected `AtlasCase` (reuse the same selected-case accessor `addToSelection`/`citeSelected` use); if none → status hint; else `boolean now = favorites.toggle(c); tree.refresh(); status.setText(now ? "Favorilere eklendi: " + c.getTitle() : "Favorilerden çıkarıldı: " + c.getTitle());`.

- [ ] **Step 4: Add the three bottom-bar buttons**

Add to the bottom `HBox` (near `createProjectBtn`):
- **`Button saveCollBtn = new Button("Koleksiyonu kaydet…");`** → if `selection.isEmpty()` → `status.setText("Önce koleksiyona slayt ekleyin");` else a `FileChooser` (`setInitialDirectory(Favorites.collectionsDir())` after `mkdirs`, `setInitialFileName("koleksiyon.json")`, `*.json` filter) `.showSaveDialog(stage)`; on a file → `try { AtlasCollectionIO.save(AtlasCollection.fromCases(baseName(file), selection), file); status.setText("Koleksiyon kaydedildi: " + file.getName()); } catch (IOException ex) { status.setText("Kaydedilemedi: " + ex.getMessage()); }`.
- **`Button loadCollBtn = new Button("Koleksiyon yükle…");`** → a `FileChooser` (`*.json`) `.showOpenDialog(stage)`; on a file → `AtlasCollection coll = AtlasCollectionIO.load(file); if (coll == null) { status.setText("Koleksiyon okunamadı"); return; } var r = AtlasCollection.resolve(coll, allCases); selection.addAll(r.found()); updateSelectionCount(); status.setText(r.found().size() + " yüklendi, " + r.missing().size() + " artık katalogda yok");`.
- **`Button loadFavBtn = new Button("Favorileri yükle");`** → `var favCases = favorites.resolve(allCases); selection.addAll(favCases); updateSelectionCount(); status.setText(favCases.size() + " favori yüklendi");`.
Add them to the `HBox` children and (if the browser pins button widths via a loop) include them in that loop.

- [ ] **Step 5: Build + commit**

Run: `./gradlew --offline build` → BUILD SUCCESSFUL (existing + Task-1 tests pass; no new automated tests for the browser UI, per the repo's dialogs-manual convention).
```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java README.md
git commit -m "feat(collections): browser save/load/favorites + ★ toggle + cell factory + README"
```

- [ ] **Step 6: README**

Add a "Collections & favorites" subsection: in the browser, build a selection, **"Koleksiyonu kaydet…"** writes a small shareable `.json`; **"Koleksiyon yükle…"** re-resolves a file into the basket (reports any slides no longer in the catalogue); right-click a slide → **"★ Favori"** to mark it (favorites persist to `~/QuPath-atlas-collections/favorites.json`), **"Favorileri yükle"** loads them into the basket. Collections are portable — share the `.json` with a colleague.

---

## Self-Review (author)

- **Spec coverage:** §4 core (`AtlasCollection` resolve strip-both-sides, `AtlasCollectionIO` fail-soft, `Favorites` key-set) → Task 1 with full code + the mpp-asymmetry + bad-file tests. §5 UI (buttons, ★ toggle, cell factory with the 3 requirements) → Task 2. §6 threading (sync FX) → honored (no Platform.runLater). §8 tests → Task 1. ✅
- **Type consistency:** `AtlasCollection.fromCases/resolve`, `Resolution.found/missing`, `AtlasCollectionIO.save/load`, `Favorites.load/inMemory/contains/toggle/resolve/collectionsDir` identical across plan + tests; `CaseCompare.stripQuery` is public (verified). Gson is already on the classpath (quiz).
- **Placeholder scan:** none — full code for Task 1; Task 2 gives the exact cell-factory body, the exact button handlers, and the consumed accessors, with named pattern references (repo convention for JavaFX UI).
- **Trap coverage:** query-strip-both-sides in `resolve` (Global Constraints + Task 1 Step 4 + a dedicated test); fail-soft load (IO Step 5 + test); cell-factory 3 requirements (Task 2 Step 2, verbatim); no-swap-guard note (Global Constraints). Favorites `inMemory()` test ctor avoids touching the real home dir in unit tests.
