package com.patolojiatlasi.qupath.quiz;

import java.util.List;

/** One self-check question. Type-specific fields are null when not applicable to {@link #type}. */
public class QuizQuestion {

    private String id;
    private QuizType type;
    private String slideUrl;
    private String slideTitle;
    private String prompt;
    private String explanation;
    private Viewport viewport;

    private List<String> options;              // MCQ
    private Integer correctIndex;               // MCQ
    private String modelAnswer;                 // FREETEXT
    private String instruction;                 // ANNOTATION
    private String referenceGeometryGeoJson;    // ANNOTATION
    private String targetGeometryGeoJson;       // NAVIGATION

    /** No-arg constructor for Gson deserialization. */
    public QuizQuestion() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public QuizType getType() {
        return type;
    }

    public void setType(QuizType type) {
        this.type = type;
    }

    public String getSlideUrl() {
        return slideUrl;
    }

    public void setSlideUrl(String slideUrl) {
        this.slideUrl = slideUrl;
    }

    public String getSlideTitle() {
        return slideTitle;
    }

    public void setSlideTitle(String slideTitle) {
        this.slideTitle = slideTitle;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public Viewport getViewport() {
        return viewport;
    }

    public void setViewport(Viewport viewport) {
        this.viewport = viewport;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public Integer getCorrectIndex() {
        return correctIndex;
    }

    public void setCorrectIndex(Integer correctIndex) {
        this.correctIndex = correctIndex;
    }

    public String getModelAnswer() {
        return modelAnswer;
    }

    public void setModelAnswer(String modelAnswer) {
        this.modelAnswer = modelAnswer;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getReferenceGeometryGeoJson() {
        return referenceGeometryGeoJson;
    }

    public void setReferenceGeometryGeoJson(String referenceGeometryGeoJson) {
        this.referenceGeometryGeoJson = referenceGeometryGeoJson;
    }

    public String getTargetGeometryGeoJson() {
        return targetGeometryGeoJson;
    }

    public void setTargetGeometryGeoJson(String targetGeometryGeoJson) {
        this.targetGeometryGeoJson = targetGeometryGeoJson;
    }

    /** Where the learner should start on the slide (full-resolution pixels). */
    public static class Viewport {
        public double downsample;
        public double centerX;
        public double centerY;
    }
}
