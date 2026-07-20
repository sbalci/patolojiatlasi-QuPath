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
