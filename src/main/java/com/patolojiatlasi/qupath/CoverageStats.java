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
