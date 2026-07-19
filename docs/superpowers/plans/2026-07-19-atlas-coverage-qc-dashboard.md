# Catalogue Coverage & QC Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only, catalogue-wide dashboard: category × stain-bucket slide-count matrix with published% / mpp-known% summaries, an opt-in best-effort DZI link check, drill-down that seeds the project builder, and CSV/MD export.

**Architecture:** A pure, unit-tested aggregation core (`CoverageStats`) with a NEW 4-way stain classifier; a best-effort off-thread `LinkCheck`; a JavaFX `CoverageDashboard` modal that reuses `ProjectBuilderDialog` (drill-down) and `ProvenanceService` (copy/save); one new top-level menu item.

**Tech Stack:** Java 21, JavaFX, QuPath 0.6 API (`compileOnly`), JUnit 5, Gradle (offline).

## Global Constraints

- Java 21; QuPath 0.6 API only; **NO new bundled dependencies**.
- Turkish participant-facing labels; QuPath menu-path strings stay English.
- **All network + file I/O OFF the JavaFX Application Thread; all UI mutation ON it via `Platform.runLater`.** (Repo's documented data-loss/threading trap.)
- Every `String.format` pins `java.util.Locale.US`.
- Read-only atlas: output is on-screen + clipboard + local file only; no writable backend.
- The stain classifier and its keyword lists are load-bearing (a pathologist audits them) — implement the §3 lists verbatim; the classifier is `CoverageStats.stainBucket`, NOT `AtlasCatalog.looksLikeStain`.
- Matrix counts **slides** (`AtlasCase`), and also reports **distinct cases** (distinct `reponame`) — consistent with feature #2's "N whole-slide images (M cases)".
- Data source is the offline bundled catalogue (`AtlasCatalog.loadBundled()`); the only network call is the opt-in link check.

---

### Task 1: `CoverageStats` pure core + `AtlasCase.getStainname()` + tests

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasCase.java` (add `getStainname()`)
- Create: `src/main/java/com/patolojiatlasi/qupath/CoverageStats.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/CoverageStatsTest.java`

**Interfaces:**
- Consumes: `AtlasCase.getCategory()`, `getImage()`, `getStainname()` (new), `isPublished()`, `getMpp()`, `getReponame()`.
- Produces (later tasks rely on these exact signatures):
  - `enum CoverageStats.StainBucket { HE, IHC, SPECIAL, OTHER }` with `String label()` → `"H&E"`, `"IHK"`, `"Özel boya"`, `"Diğer"`.
  - `static StainBucket stainBucket(String image, String stainname)`
  - `record CategoryRow(String category, int[] counts, int slides, int cases, int published, int mppKnown)` with `int publishedPct()`, `int mppKnownPct()`.
  - `record CoverageMatrix(java.util.List<CategoryRow> rows, int[] colTotals, int totalSlides, int totalCases, int totalPublished, int totalMppKnown)` with `int publishedPct()`, `int mppKnownPct()`.
  - `static CoverageMatrix compute(java.util.List<AtlasCase> cases)`
  - `static String toCsv(CoverageMatrix m)`
  - `static String toMarkdown(CoverageMatrix m, java.time.LocalDate generated)`

- [ ] **Step 1: Add `getStainname()` to `AtlasCase`**

Next to `getImage()` (around line 53), add:
```java
public String getStainname() {
    return stainname;
}
```
(`stainname` is the existing private final field, already `nz(...)`-normalized in the constructor — no other change.)

- [ ] **Step 2: Write `CoverageStatsTest` (failing)**

Create `src/test/java/com/patolojiatlasi/qupath/CoverageStatsTest.java`:
```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.patolojiatlasi.qupath.CoverageStats.CoverageMatrix;
import com.patolojiatlasi.qupath.CoverageStats.StainBucket;

class CoverageStatsTest {

    // reponame, stainname, image, titleEN, titleTR, organEN, speciality, type, dziUrl, thumbUrl, mpp
    private static AtlasCase mk(String repo, String image, String organ, String type, double mpp) {
        return new AtlasCase(repo, image, image, "T " + repo, "", organ, "", type,
                "https://images.x/" + repo + "/" + image + ".dzi", "", mpp);
    }

    @Test
    void stainBucketClassifiesEachFamily() {
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("HE", ""));
        assertEquals(StainBucket.HE, CoverageStats.stainBucket("H&E", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CD3", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("CD20", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("Ki67", ""));
        assertEquals(StainBucket.IHC, CoverageStats.stainBucket("p63", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("PAS", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("masson", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("warthinstarry", ""));
        assertEquals(StainBucket.SPECIAL, CoverageStats.stainBucket("congo", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("bluething", ""));
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("", ""));
    }

    @Test
    void stainBucketBoundaryGuardsDoNotShadow() {
        // "syn" (synaptophysin) must not match inside "synovial"
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("synovial", ""));
        // an H&E token must not match a word merely containing "he"
        assertEquals(StainBucket.OTHER, CoverageStats.stainBucket("heart", ""));
    }

    @Test
    void computeCountsSlidesCasesAndPercents() {
        // repoA: 2 stains (HE published mpp-known, CD3 published mpp-unknown) -> 1 case, 2 slides
        // repoB: 1 stain (HE unpublished mpp-known) -> 1 case, 1 slide
        List<AtlasCase> cases = List.of(
                mk("repoA", "HE", "Colon", "published", 0.26),
                mk("repoA", "CD3", "Colon", "published", 0.0),
                mk("repoB", "HE", "Breast", "unpublished", 0.50));
        CoverageMatrix m = CoverageStats.compute(cases);
        assertEquals(3, m.totalSlides());
        assertEquals(2, m.totalCases());          // repoA + repoB
        assertEquals(2, m.totalPublished());       // repoA HE + repoA CD3
        assertEquals(2, m.totalMppKnown());        // repoA HE + repoB HE
        assertEquals(67, m.publishedPct());        // Math.round(100*2/3) = 67
        // Colon row: 2 slides (HE+CD3), 1 case, HE-bucket 1, IHC-bucket 1
        CoverageStats.CategoryRow colon = m.rows().stream()
                .filter(r -> r.category().equals("Gastrointestinal")).findFirst().orElseThrow();
        assertEquals(2, colon.slides());
        assertEquals(1, colon.cases());
        assertEquals(1, colon.counts()[StainBucket.HE.ordinal()]);
        assertEquals(1, colon.counts()[StainBucket.IHC.ordinal()]);
    }

    @Test
    void csvHasHeaderTotalRowAndEscaping() {
        CoverageMatrix m = CoverageStats.compute(List.of(mk("r", "HE", "Colon", "published", 0.26)));
        String csv = CoverageStats.toCsv(m);
        String[] lines = csv.strip().split("\n");
        assertTrue(lines[0].startsWith("category,HE,IHC,special,other,slides,cases,"));
        assertTrue(csv.contains("\nTOTAL,"));
    }

    @Test
    void markdownIsATableWithBothCounts() {
        CoverageMatrix m = CoverageStats.compute(List.of(mk("r", "HE", "Colon", "published", 0.26)));
        String md = CoverageStats.toMarkdown(m, LocalDate.of(2026, 7, 19));
        assertTrue(md.contains("| category |"));
        assertTrue(md.contains("| --- |"));
        assertTrue(md.contains("1 slides"));
        assertTrue(md.contains("1 cases") || md.contains("1 case"));   // provenance line
        assertTrue(md.contains("2026-07-19"));
    }

    @Test
    void uncategorizedSortsLast() {
        List<AtlasCase> cases = List.of(
                mk("u", "HE", "", "published", 0.0),                 // -> Uncategorized
                mk("a", "HE", "Colon", "published", 0.0),
                mk("b", "HE", "Colon", "published", 0.0));           // Colon has more slides
        CoverageMatrix m = CoverageStats.compute(cases);
        assertEquals("Uncategorized", m.rows().get(m.rows().size() - 1).category());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew --offline test --tests CoverageStatsTest`
Expected: FAIL (compilation — `CoverageStats` does not exist).

- [ ] **Step 4: Implement `CoverageStats`**

Create `src/main/java/com/patolojiatlasi/qupath/CoverageStats.java`:
```java
package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Pure, catalogue-wide coverage aggregation for the QC dashboard. No UI, no network. */
final class CoverageStats {

    private CoverageStats() {}

    enum StainBucket {
        HE("H&E"), IHC("IHK"), SPECIAL("Özel boya"), OTHER("Diğer");
        private final String label;
        StainBucket(String label) { this.label = label; }
        String label() { return label; }
    }

    private static final Pattern CD_MARKER = Pattern.compile("\\bcd\\d+\\b");

    private static final String[] SPECIAL_KEYS = {
        "pas", "pasd", "giemsa", "mgg", "congo", "amyloid", "crystal", "trichrome", "masson",
        "reticulin", "mucicarmine", "warthin", "grocott", "gms", "ziehl", "afb", "verhoeff",
        "vvg", "elastic", "perls", "prussian", "iron", "alcian", "silver", "pap", "trypsin",
        "fontana"
    };
    // Substring IHC keywords (safe — no short ambiguous tokens here).
    private static final String[] IHC_KEYS = {
        "ihc", "immuno", "ki67", "ki-67", "p53", "p63", "p40", "p16", "ttf", "napsin",
        "chromogranin", "synaptophysin", "s100", "sox", "melan", "hmb", "desmin", "actin",
        "vimentin", "panck", "ck7", "ck20", "ck5", "cytokeratin", "keratin", "her2", "estrogen",
        "progesterone", "gata", "pax", "wt1", "calretinin", "inhibin", "dog1", "ckit", "c-kit",
        "mib", "bcl", "alk", "pdl1", "pd-l1", "mart", "cea", "psa", "tdt", "mpo"
    };
    // Whole-token IHC keywords (short/ambiguous — must not match inside a larger word).
    private static final String[] IHC_TOKENS = { "sma", "syn", "er", "pr" };
    // H&E whole-token names.
    private static final String[] HE_TOKENS = { "he", "h&e", "hande", "h and e", "h e" };
    private static final String[] HE_SUBSTR = { "hematox", "haematox" };

    static StainBucket stainBucket(String image, String stainname) {
        String hay = ((image == null ? "" : image) + " " + (stainname == null ? "" : stainname))
                .toLowerCase(Locale.ROOT).trim();
        if (hay.isEmpty()) return StainBucket.OTHER;
        if (hasToken(hay, HE_TOKENS) || contains(hay, HE_SUBSTR)) return StainBucket.HE;
        if (contains(hay, SPECIAL_KEYS)) return StainBucket.SPECIAL;
        if (CD_MARKER.matcher(hay).find() || contains(hay, IHC_KEYS) || hasToken(hay, IHC_TOKENS))
            return StainBucket.IHC;
        return StainBucket.OTHER;
    }

    private static boolean contains(String hay, String[] needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    /** True if any needle appears as a whole token (delimited by non-alphanumerics or ends). */
    private static boolean hasToken(String hay, String[] needles) {
        for (String n : needles) {
            int i = 0;
            while ((i = hay.indexOf(n, i)) >= 0) {
                boolean leftOk = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
                int end = i + n.length();
                boolean rightOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
                if (leftOk && rightOk) return true;
                i = end;
            }
        }
        return false;
    }

    record CategoryRow(String category, int[] counts, int slides, int cases,
                       int published, int mppKnown) {
        int publishedPct() { return slides == 0 ? 0 : (int) Math.round(100.0 * published / slides); }
        int mppKnownPct()  { return slides == 0 ? 0 : (int) Math.round(100.0 * mppKnown / slides); }
    }

    record CoverageMatrix(List<CategoryRow> rows, int[] colTotals, int totalSlides, int totalCases,
                          int totalPublished, int totalMppKnown) {
        int publishedPct() { return totalSlides == 0 ? 0 : (int) Math.round(100.0 * totalPublished / totalSlides); }
        int mppKnownPct()  { return totalSlides == 0 ? 0 : (int) Math.round(100.0 * totalMppKnown / totalSlides); }
    }

    static CoverageMatrix compute(List<AtlasCase> cases) {
        int nb = StainBucket.values().length;
        Map<String, int[]> counts = new LinkedHashMap<>();       // category -> counts[nb]
        Map<String, int[]> tallies = new LinkedHashMap<>();      // category -> {slides, published, mppKnown}
        Map<String, Set<String>> repos = new LinkedHashMap<>();  // category -> distinct reponames
        Set<String> allRepos = new TreeSet<>();
        int[] colTotals = new int[nb];
        int totalSlides = 0, totalPublished = 0, totalMppKnown = 0;

        for (AtlasCase c : cases) {
            String cat = c.getCategory();
            int[] cc = counts.computeIfAbsent(cat, k -> new int[nb]);
            int[] tt = tallies.computeIfAbsent(cat, k -> new int[3]);
            Set<String> rp = repos.computeIfAbsent(cat, k -> new TreeSet<>());
            StainBucket b = stainBucket(c.getImage(), c.getStainname());
            cc[b.ordinal()]++;
            colTotals[b.ordinal()]++;
            tt[0]++; totalSlides++;
            if (c.isPublished()) { tt[1]++; totalPublished++; }
            if (c.getMpp() > 0)  { tt[2]++; totalMppKnown++; }
            rp.add(c.getReponame());
            allRepos.add(c.getReponame());
        }

        List<CategoryRow> rows = new ArrayList<>();
        for (String cat : counts.keySet()) {
            int[] tt = tallies.get(cat);
            rows.add(new CategoryRow(cat, counts.get(cat), tt[0], repos.get(cat).size(), tt[1], tt[2]));
        }
        // Slide-count descending; "Uncategorized" always last; then alphabetical for stability.
        rows.sort(Comparator
                .comparing((CategoryRow r) -> r.category().equals("Uncategorized"))
                .thenComparing(Comparator.comparingInt(CategoryRow::slides).reversed())
                .thenComparing(CategoryRow::category));
        return new CoverageMatrix(rows, colTotals, totalSlides, allRepos.size(),
                totalPublished, totalMppKnown);
    }

    static String toCsv(CoverageMatrix m) {
        StringBuilder sb = new StringBuilder();
        sb.append("category,HE,IHC,special,other,slides,cases,published_pct,mpp_known_pct\n");
        for (CategoryRow r : m.rows()) csvRow(sb, r.category(), r.counts(), r.slides(), r.cases(),
                r.publishedPct(), r.mppKnownPct());
        csvRow(sb, "TOTAL", m.colTotals(), m.totalSlides(), m.totalCases(),
                m.publishedPct(), m.mppKnownPct());
        return sb.toString();
    }

    private static void csvRow(StringBuilder sb, String cat, int[] c, int slides, int cases,
                               int pubPct, int mppPct) {
        sb.append(csv(cat)).append(',')
          .append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(',').append(c[3])
          .append(',').append(slides).append(',').append(cases).append(',')
          .append(pubPct).append(',').append(mppPct).append('\n');
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    static String toMarkdown(CoverageMatrix m, LocalDate generated) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "Atlas kapsamı — %d slides, %d cases, %d kategori (üretim: %s)\n\n",
                m.totalSlides(), m.totalCases(), m.rows().size(), generated));
        sb.append("| category | H&E | IHC | special | other | slides | cases | published% | mpp-known% |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CategoryRow r : m.rows()) mdRow(sb, r.category(), r.counts(), r.slides(), r.cases(),
                r.publishedPct(), r.mppKnownPct());
        mdRow(sb, "TOTAL", m.colTotals(), m.totalSlides(), m.totalCases(),
                m.publishedPct(), m.mppKnownPct());
        return sb.toString();
    }

    private static void mdRow(StringBuilder sb, String cat, int[] c, int slides, int cases,
                              int pubPct, int mppPct) {
        sb.append(String.format(Locale.US, "| %s | %d | %d | %d | %d | %d | %d | %d%% | %d%% |\n",
                cat, c[0], c[1], c[2], c[3], slides, cases, pubPct, mppPct));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew --offline test --tests CoverageStatsTest`
Expected: PASS (all 6 tests). The plan already locks `publishedPct` at 67 (`Math.round(66.67)`); if any other rounding assertion trips, lock it to whatever `Math.round` actually yields.

- [ ] **Step 6: Full build + commit**

Run: `./gradlew --offline build` → BUILD SUCCESSFUL
```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasCase.java \
        src/main/java/com/patolojiatlasi/qupath/CoverageStats.java \
        src/test/java/com/patolojiatlasi/qupath/CoverageStatsTest.java
git commit -m "feat(coverage): CoverageStats pure core + 4-way stain classifier + tests"
```

---

### Task 2: `LinkCheck` — best-effort off-thread DZI HEAD check

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/LinkCheck.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/LinkCheckTest.java` (pure helper only)

**Interfaces:**
- Consumes: `AtlasCase.getDziUrl()`.
- Produces:
  - `static java.util.Map<String, Boolean> checkAll(java.util.List<AtlasCase> cases, java.util.function.IntConsumer progress)` — key = distinct DZI URL, value = reachable. Never throws.
  - `static boolean reachable(int statusCode)` (pure; testable) — `true` for 2xx, 3xx, and 405 (method-not-allowed still means the URL resolves); `false` otherwise.

**Interfaces note:** `checkAll` MUST be called from a background thread by the caller — it blocks. It does its own bounded-concurrency internally but returns synchronously.

- [ ] **Step 1: Write `LinkCheckTest` (failing)**

```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LinkCheckTest {
    @Test void statusInterpretation() {
        assertTrue(LinkCheck.reachable(200));
        assertTrue(LinkCheck.reachable(301));
        assertTrue(LinkCheck.reachable(405));   // method not allowed = URL resolves
        assertFalse(LinkCheck.reachable(404));
        assertFalse(LinkCheck.reachable(500));
        assertFalse(LinkCheck.reachable(0));     // our sentinel for "no response"
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline test --tests LinkCheckTest` → FAIL (class missing).

- [ ] **Step 3: Implement `LinkCheck`**

```java
package com.patolojiatlasi.qupath;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Best-effort DZI URL reachability check. Blocks; call from a background thread. Never throws. */
final class LinkCheck {

    private LinkCheck() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static boolean reachable(int statusCode) {
        if (statusCode == 405) return true;
        return statusCode >= 200 && statusCode < 400;
    }

    static Map<String, Boolean> checkAll(List<AtlasCase> cases, IntConsumer progress) {
        // Distinct URLs, preserve first-seen order for a stable footer listing.
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (AtlasCase c : cases) result.putIfAbsent(c.getDziUrl(), Boolean.TRUE);
        List<String> urls = List.copyOf(result.keySet());
        Map<String, Boolean> out = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, urls.size())), r -> {
            Thread t = new Thread(r, "atlas-linkcheck");
            t.setDaemon(true);
            return t;
        });
        try {
            for (String url : urls) {
                pool.submit(() -> {
                    out.put(url, probe(url));
                    if (progress != null) progress.accept(done.incrementAndGet());
                });
            }
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // best-effort: any URL not probed stays as its default below
        } finally {
            pool.shutdownNow();
        }
        // Preserve insertion order; unprobed (e.g. interrupted) default to false.
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (String url : urls) ordered.put(url, out.getOrDefault(url, Boolean.FALSE));
        return ordered;
    }

    private static boolean probe(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return reachable(resp.statusCode());
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --offline test --tests LinkCheckTest` → PASS.

- [ ] **Step 5: Build + commit**

Run: `./gradlew --offline build` → SUCCESSFUL
```bash
git add src/main/java/com/patolojiatlasi/qupath/LinkCheck.java \
        src/test/java/com/patolojiatlasi/qupath/LinkCheckTest.java
git commit -m "feat(coverage): best-effort off-thread DZI link check"
```

---

### Task 3: `CoverageDashboard` UI + menu wiring + README

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/CoverageDashboard.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java` (one menu item)
- Modify: `README.md`

**Interfaces:**
- Consumes: `CoverageStats.compute/toCsv/toMarkdown`, `CoverageStats.CoverageMatrix`/`CategoryRow`/`StainBucket`, `LinkCheck.checkAll`, `AtlasCatalog.loadBundled()`, `ProjectBuilderDialog.show(QuPathGUI, Stage, LinkedHashSet<AtlasCase>, Runnable)`, `ProvenanceService.copyToClipboard(String)`/`saveTextFile(String, String, Window)`, `qupath.getStage()`.
- Produces: `static void show(QuPathGUI qupath)`.

**Pattern references (read before writing):** `CitationDialog.java` (modal Stage + format ComboBox + copy/save + `.initOwner`), `ProjectBuilderDialog.java` (its `show(...)` signature + how it builds a basket), `FocusHeatmap.java` (background thread + `Platform.runLater` + progress), `AtlasExtension.java` (menu wiring + `getStage()` null-guard).

- [ ] **Step 1: Verify the standalone drill-down launch**

Read `ProjectBuilderDialog.show(...)` and confirm it works with a fresh `new LinkedHashSet<>(sliceCases)` + `() -> {}` callback + a non-null owner `Stage`, launched with no browser open (the callback only syncs browser stars, so a no-op is safe; the owner is used for the DirectoryChooser/alerts). If `show` dereferences anything browser-specific beyond those four params, note it in the report and adapt (e.g. pass `qupath.getStage()` as owner). Do NOT change `ProjectBuilderDialog`'s behavior.

- [ ] **Step 2: Implement `CoverageDashboard`**

`public static void show(QuPathGUI qupath)` — on the FX thread:
1. `List<AtlasCase> all = AtlasCatalog.loadBundled();` → `CoverageMatrix m = CoverageStats.compute(all);`
2. Build a modal `Stage` (`initModality(Modality.NONE)` is fine — match `CitationDialog`), `.initOwner(qupath.getStage())` when non-null, title "Katalog kapsamı ve QC".
3. Header `Label`: `String.format(Locale.US, "Katalog kapsamı — %d slayt, %d vaka, %d kategori", m.totalSlides(), m.totalCases(), m.rows().size())`.
4. A `TableView<CategoryRow>` with columns: Kategori (`category`), H&E / IHK / Özel boya / Diğer (`counts()[bucket.ordinal()]`), Slayt (`slides`), Vaka (`cases`), Yayın % (`publishedPct()+"%"`), mpp % (`mppKnownPct()+"%"`). Append the TOTAL as an extra pinned row is awkward in TableView — instead put the TOTAL in a separate one-row summary `Label`/`GridPane` under the table (bold), or add it as a final synthetic `CategoryRow` named "TOPLAM"; either is acceptable, keep it visually distinct.
5. **Drill-down:** a row-click handler (`table.setRowFactory` or a "Seçili slayt setini projeye aktar…" button acting on the selected row) → gather that category's cases: `all.stream().filter(c -> c.getCategory().equals(row.category())).collect(...)` into a `LinkedHashSet<>`; call `ProjectBuilderDialog.show(qupath, stage, slice, () -> {})`. (Cell-level bucket drill-down is optional polish; category-row drill-down is the required behavior — a double-click on the row.)
6. **"Bağlantıları denetle" button** + a `ProgressBar` (hidden until run): on click, disable the button, show the bar, spawn a daemon thread:
   ```java
   Thread t = new Thread(() -> {
       Map<String,Boolean> res = LinkCheck.checkAll(all, done ->
           Platform.runLater(() -> { if (stage.isShowing()) bar.setProgress((double) done / total); }));
       Platform.runLater(() -> {
           if (!stage.isShowing()) return;
           button.setDisable(false); bar.setVisible(false);
           // collect dead URLs -> footer label listing failed slide titles + urls; if none, "Tüm bağlantılar erişilebilir."
       });
   }, "atlas-linkcheck-ui");
   t.setDaemon(true); t.start();
   ```
   `total` = distinct URL count. Map a dead URL back to its `AtlasCase` titles for the footer.
7. **Export:** a format `ComboBox` (CSV / Markdown) + "Panoya kopyala" (`ProvenanceService.copyToClipboard(...)`) + "Kaydet…" (`ProvenanceService.saveTextFile("atlas-coverage.csv"|".md", content, stage)`). Content = `CoverageStats.toCsv(m)` or `toMarkdown(m, LocalDate.now())`. NOTE: `LocalDate.now()` is fine here (UI code, not the pure core).
8. All `Alert`s `.initOwner(stage)`. A short footer note: "Sınıflandırma anahtar-kelime sezgiseldir; 'Diğer'/'Uncategorized' dürüst artık kovalardır."
9. `stage.show()` (non-blocking) — do not `showAndWait()` if you want the dashboard non-modal; match whichever `CitationDialog` uses for consistency.

- [ ] **Step 3: Wire the menu in `AtlasExtension`**

Add a top-level item under the Patoloji Atlası menu (near the browser/"Atlas'ı Aç" item, NOT under Atıf):
```java
MenuItem coverageItem = new MenuItem("Katalog kapsamı ve QC…");
coverageItem.setOnAction(e -> CoverageDashboard.show(qupath));
```
Add it to the same parent menu the browser-launch item uses. Match the existing add-order/grouping style in that method.

- [ ] **Step 4: README**

Add a "Coverage & QC dashboard" subsection: Extensions → Patoloji Atlası → "Katalog kapsamı ve QC…" opens a read-only category×stain matrix (slides + distinct cases, published% + mpp-known%); "Bağlantıları denetle" runs an opt-in best-effort DZI link check; double-click a category row to seed the project builder with that slice; copy/save the matrix as CSV or Markdown for a data-availability statement.

- [ ] **Step 5: Build + manual-scope check + commit**

Run: `./gradlew --offline build` → BUILD SUCCESSFUL (existing tests still pass; no new automated tests for the JavaFX dashboard, consistent with the repo's pure-logic-tested / dialogs-manual boundary).
```bash
git add src/main/java/com/patolojiatlasi/qupath/CoverageDashboard.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasExtension.java README.md
git commit -m "feat(coverage): CoverageDashboard UI + menu + README"
```

---

## Self-Review (author)

- **Spec coverage:** §3 classifier → Task 1 (verbatim lists + tests). §4 CoverageStats → Task 1. §5 LinkCheck → Task 2. §6 dashboard → Task 3. §7 menu → Task 3. §2 slide/case terminology → Task 1 (`compute` distinct-reponame + CSV/MD both counts). §9 tests → Tasks 1–2. ✅
- **Type consistency:** `StainBucket`/`CategoryRow`/`CoverageMatrix`/`compute`/`toCsv`/`toMarkdown`/`stainBucket` signatures identical across the plan; `LinkCheck.checkAll`/`reachable` consistent; `ProjectBuilderDialog.show` 4-arg signature matches the verified source.
- **Placeholder scan:** none — full code for the pure core + link check; the UI task gives exact strings, the four consumed signatures, the threading skeleton, and named pattern references (the repo's convention for JavaFX dialogs, as in the #2 plan).
- **Rounding:** the `publishedPct` assertion is locked at 67 (`Math.round(100.0*2/3)=Math.round(66.67)=67`) directly in the plan test — no implementer correction needed.
