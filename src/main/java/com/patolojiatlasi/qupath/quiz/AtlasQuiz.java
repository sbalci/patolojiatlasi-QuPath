package com.patolojiatlasi.qupath.quiz;

import java.util.ArrayList;
import java.util.List;

/** A portable quiz: metadata + an ordered list of questions. Serialized as one JSON file. */
public class AtlasQuiz {

    private int formatVersion = AtlasQuizIO.FORMAT_VERSION;
    private String title = "";
    private String description = "";
    private List<QuizQuestion> questions = new ArrayList<>();

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int v) {
        this.formatVersion = v;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String t) {
        this.title = t == null ? "" : t;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String d) {
        this.description = d == null ? "" : d;
    }

    public List<QuizQuestion> getQuestions() {
        if (questions == null)
            questions = new ArrayList<>();
        return questions;
    }

    public void setQuestions(List<QuizQuestion> q) {
        this.questions = q;
    }
}
