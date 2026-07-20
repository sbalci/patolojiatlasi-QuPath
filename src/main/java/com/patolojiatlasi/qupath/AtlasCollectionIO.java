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
