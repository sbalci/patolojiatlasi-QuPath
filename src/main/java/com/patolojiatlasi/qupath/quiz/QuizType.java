package com.patolojiatlasi.qupath.quiz;

/** The kinds of stop a quiz/tour supports. NARRATION is a caption-only stop (no question). */
public enum QuizType {
    MCQ, FREETEXT, ANNOTATION, NAVIGATION, NARRATION;

    /** Turkish user-facing label for UI controls (serialization still uses {@link #name()}). */
    public String displayName() {
        return switch (this) {
            case MCQ -> "Çoktan seçmeli (MCQ)";
            case FREETEXT -> "Açık uçlu (serbest yanıt)";
            case ANNOTATION -> "Çizim / Alan işaretleme";
            case NAVIGATION -> "Bölge bulma (gezinme)";
            case NARRATION -> "Anlatım durağı (tur)";
        };
    }
}
