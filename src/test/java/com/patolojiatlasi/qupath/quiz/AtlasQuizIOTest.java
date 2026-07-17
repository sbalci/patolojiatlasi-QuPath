package com.patolojiatlasi.qupath.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class AtlasQuizIOTest {

    private static QuizQuestion mcq() {
        QuizQuestion q = new QuizQuestion();
        q.setId("q1");
        q.setType(QuizType.MCQ);
        q.setSlideUrl("https://images.patolojiatlasi.com/case1/HE.dzi?mpp=0.26");
        q.setSlideTitle("Case 1 HE");
        q.setPrompt("What is shown?");
        q.setExplanation("Because reasons.");
        q.setOptions(List.of("Adenocarcinoma", "Normal", "Lymphoma"));
        q.setCorrectIndex(0);
        return q;
    }

    private static QuizQuestion freetext() {
        QuizQuestion q = new QuizQuestion();
        q.setId("q2");
        q.setType(QuizType.FREETEXT);
        q.setSlideUrl("https://images.patolojiatlasi.com/case2/HE.dzi");
        q.setSlideTitle("Case 2 HE");
        q.setPrompt("Describe the lesion.");
        q.setExplanation("Model description here.");
        q.setModelAnswer("A well-differentiated tumour.");
        return q;
    }

    private static QuizQuestion annotation(String referenceGeometryGeoJson) {
        QuizQuestion q = new QuizQuestion();
        q.setId("q3");
        q.setType(QuizType.ANNOTATION);
        q.setSlideUrl("https://images.patolojiatlasi.com/case3/HE.dzi");
        q.setSlideTitle("Case 3 HE");
        q.setPrompt("Draw around the lesion.");
        q.setInstruction("Trace the tumour border.");
        q.setReferenceGeometryGeoJson(referenceGeometryGeoJson);
        return q;
    }

    private static File tempFile() throws IOException {
        File f = File.createTempFile("atlas-quiz", ".json");
        f.deleteOnExit();
        return f;
    }

    @Test
    void roundTripPreservesContent() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setTitle("GI quiz");
        quiz.setDescription("desc");
        quiz.getQuestions().add(mcq());
        quiz.getQuestions().add(freetext());

        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        assertTrue(Files.size(f.toPath()) > 0);

        AtlasQuiz back = AtlasQuizIO.read(f);
        assertEquals("GI quiz", back.getTitle());
        assertEquals(2, back.getQuestions().size());
        QuizQuestion q0 = back.getQuestions().get(0);
        assertEquals(QuizType.MCQ, q0.getType());
        assertEquals(3, q0.getOptions().size());
        assertEquals(0, q0.getCorrectIndex());
        assertEquals(QuizType.FREETEXT, back.getQuestions().get(1).getType());
    }

    @Test
    void rejectsUnknownFormatVersion() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setFormatVersion(999);
        quiz.getQuestions().add(mcq());
        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        assertThrows(IOException.class, () -> AtlasQuizIO.read(f));
    }

    @Test
    void rejectsMcqWithBadCorrectIndex() {
        AtlasQuiz quiz = new AtlasQuiz();
        QuizQuestion q = mcq();
        q.setCorrectIndex(5); // out of range (3 options)
        quiz.getQuestions().add(q);
        assertThrows(IOException.class, () -> AtlasQuizIO.validate(quiz));
    }

    @Test
    void rejectsMcqWithTooFewOptions() {
        AtlasQuiz quiz = new AtlasQuiz();
        QuizQuestion q = mcq();
        q.setOptions(List.of("only one"));
        quiz.getQuestions().add(q);
        assertThrows(IOException.class, () -> AtlasQuizIO.validate(quiz));
    }

    @Test
    void acceptsValidQuiz() throws IOException {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.getQuestions().add(mcq());
        quiz.getQuestions().add(freetext());
        AtlasQuizIO.validate(quiz); // must not throw
    }

    @Test
    void nullTitleReadsAsEmpty() throws IOException {
        // An explicit JSON "title": null passes validation (title isn't checked), and Gson sets
        // AtlasQuiz.title to null directly -- bypassing setTitle's null-guard. getTitle() must
        // still come back empty, not null, so callers like QuizRunnerWindow can safely call
        // .isBlank() on it.
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.setTitle("Will be nulled");
        quiz.getQuestions().add(mcq());
        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        String json = Files.readString(f.toPath());
        assertTrue(json.contains("\"Will be nulled\""));
        json = json.replace("\"Will be nulled\"", "null");
        Files.writeString(f.toPath(), json);

        AtlasQuiz back = AtlasQuizIO.read(f);
        assertEquals("", back.getTitle());
    }

    @Test
    void acceptsAnnotationQuestionWithRealGeometry() throws IOException {
        ROI roi = ROIs.createRectangleROI(10, 20, 100, 50, ImagePlane.getDefaultPlane());
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.getQuestions().add(annotation(QuizGeometry.toGeoJson(roi)));
        AtlasQuizIO.validate(quiz); // must not throw
    }

    @Test
    void rejectsAnnotationQuestionWithBlankGeometry() {
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.getQuestions().add(annotation("   "));
        assertThrows(IOException.class, () -> AtlasQuizIO.validate(quiz));
    }

    @Test
    void trailingGarbageThrowsIOException() throws IOException {
        // Gson reports valid-JSON-plus-trailing-garbage as JsonIOException, not
        // JsonSyntaxException -- both extend JsonParseException, which is what
        // AtlasQuizIO.read must catch for this to surface as a checked IOException rather than
        // escaping as an unchecked exception on the caller.
        AtlasQuiz quiz = new AtlasQuiz();
        quiz.getQuestions().add(mcq());
        File f = tempFile();
        AtlasQuizIO.write(quiz, f);
        String json = Files.readString(f.toPath());
        Files.writeString(f.toPath(), json + "\nGARBAGE");

        assertThrows(IOException.class, () -> AtlasQuizIO.read(f));
    }
}
