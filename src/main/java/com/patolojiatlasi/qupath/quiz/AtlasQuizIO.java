package com.patolojiatlasi.qupath.quiz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/** Read/write/validate a quiz-pack JSON file. UI-free and unit-tested. */
public final class AtlasQuizIO {

    /** Current on-disk format. Bump only with a matching reader change. */
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AtlasQuizIO() {
    }

    public static void write(AtlasQuiz quiz, File file) throws IOException {
        String json = GSON.toJson(quiz);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    public static AtlasQuiz read(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        AtlasQuiz quiz;
        try {
            quiz = GSON.fromJson(json, AtlasQuiz.class);
        } catch (JsonParseException e) {
            throw new IOException("Not a valid quiz file: " + e.getMessage(), e);
        }
        if (quiz == null)
            throw new IOException("Empty or invalid quiz file: " + file);
        validate(quiz);
        return quiz;
    }

    /** Throw IOException on any structural problem. Package-private for tests. */
    static void validate(AtlasQuiz quiz) throws IOException {
        if (quiz.getFormatVersion() != FORMAT_VERSION)
            throw new IOException("Unsupported quiz format version " + quiz.getFormatVersion()
                    + " (this extension reads version " + FORMAT_VERSION + ")");
        if (quiz.getQuestions().isEmpty())
            throw new IOException("Quiz has no questions");
        int i = 0;
        for (QuizQuestion q : quiz.getQuestions()) {
            i++;
            if (q.getType() == null)
                throw new IOException("Question " + i + " has no type");
            if (isBlank(q.getSlideUrl()))
                throw new IOException("Question " + i + " has no slideUrl");
            if (isBlank(q.getPrompt()))
                throw new IOException("Question " + i + " has no prompt");
            switch (q.getType()) {
                case MCQ -> {
                    if (q.getOptions() == null || q.getOptions().size() < 2)
                        throw new IOException("Question " + i + " (MCQ) needs at least 2 options");
                    Integer ci = q.getCorrectIndex();
                    if (ci == null || ci < 0 || ci >= q.getOptions().size())
                        throw new IOException("Question " + i + " (MCQ) has an out-of-range correctIndex");
                }
                case FREETEXT -> {
                    if (q.getModelAnswer() == null)
                        throw new IOException("Question " + i + " (free-text) has no modelAnswer");
                }
                case ANNOTATION -> {
                    if (isBlank(q.getReferenceGeometryGeoJson()))
                        throw new IOException("Question " + i + " (annotation) has no reference geometry");
                }
                case NAVIGATION -> {
                    if (isBlank(q.getTargetGeometryGeoJson()))
                        throw new IOException("Question " + i + " (navigation) has no target geometry");
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
