package com.patolojiatlasi.qupath.quiz;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;

/**
 * Guided sequential quiz runner: load a portable quiz pack ({@link AtlasQuizIO}/{@link AtlasQuiz}),
 * step through its questions one at a time, and self-check with a Reveal button. Nothing is
 * scored, graded, or saved — this is read-only self-study over slides that stream in read-only
 * via {@link QuizSlide}.
 * <p>
 * Mirrors {@code com.patolojiatlasi.qupath.AtlasBrowser}'s single-window {@code show(...)} /
 * focus-if-open pattern and {@code com.patolojiatlasi.qupath.ProjectBuilderDialog}'s FX-thread-only
 * in-flight boolean guard around a background operation.
 * <p>
 * This slice implements MCQ and FREETEXT questions. ANNOTATION/NAVIGATION questions in a mixed
 * pack are shown as an inert placeholder rather than crashing; see the {@code slice 2} seams in
 * {@link #buildInput(QuizQuestion)} and {@link #revealAnswer()}.
 */
public class QuizRunnerWindow {

    private static final Logger logger = LoggerFactory.getLogger(QuizRunnerWindow.class);

    private static Stage stage;

    private final QuPathGUI qupath;

    private AtlasQuiz quiz;
    private int currentIndex = 0; // 1-based; 0 = no quiz loaded yet

    // Guards overlapping slide opens: set true just before QuizSlide.openAsync is called for the
    // question being shown, cleared in its onDone/onError callback — both of which QuizSlide
    // guarantees run on the FX thread. While true, "Sınav yükle…", Önceki, Göster and Sonraki are
    // all disabled (see setControlsDisabled), so a second navigation click can't fire a second
    // openAsync while the first is still resolving. Touched only on the JavaFX thread, mirroring
    // AtlasBrowser's "opening" / ProjectBuilderDialog's "building" guard.
    private boolean opening = false;

    private final Label titleLabel = new Label("Sınav yüklenmedi");
    private final Label progressLabel = new Label("");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button loadBtn = new Button("Sınav yükle…");

    private final Label promptLabel = new Label();
    private final VBox inputArea = new VBox(6);

    private final VBox revealArea = new VBox(6);
    private final Label revealAnswerLabel = new Label();
    private final Label revealExplanationLabel = new Label();

    private final Button prevBtn = new Button("Önceki");
    private final Button revealBtn = new Button("Göster");
    private final Button nextBtn = new Button("Sonraki");

    // Type-specific input state for the currently-displayed question; rebuilt by buildInput() on
    // every question change so stale controls from the previous question can't leak in.
    private ToggleGroup mcqGroup;

    private QuizRunnerWindow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single runner window. */
    public static void show(QuPathGUI qupath) {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        QuizRunnerWindow runner = new QuizRunnerWindow(qupath);
        stage = runner.buildStage();
        stage.show();
    }

    private Stage buildStage() {
        Stage s = new Stage();
        s.setTitle("Sınav/quiz çöz");

        loadBtn.setOnAction(e -> promptLoad());
        progress.setVisible(false);
        progress.setPrefSize(18, 18);

        HBox top = new HBox(10, loadBtn, titleLabel, progressLabel, progress);
        top.setPadding(new Insets(8));
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        promptLabel.setWrapText(true);
        promptLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        inputArea.setPadding(new Insets(4, 0, 4, 0));

        revealAnswerLabel.setWrapText(true);
        revealExplanationLabel.setWrapText(true);
        revealExplanationLabel.setStyle("-fx-font-style: italic;");
        revealArea.getChildren().addAll(new Separator(), revealAnswerLabel, revealExplanationLabel);
        revealArea.setPadding(new Insets(8, 0, 0, 0));
        setRevealVisible(false);

        VBox center = new VBox(10, promptLabel, inputArea, revealArea);
        center.setPadding(new Insets(12));

        prevBtn.setOnAction(e -> goPrev());
        revealBtn.setOnAction(e -> revealAnswer());
        nextBtn.setOnAction(e -> goNext());
        // Nothing loaded yet — Önceki/Göster/Sonraki start disabled; loadBtn stays enabled.
        prevBtn.setDisable(true);
        revealBtn.setDisable(true);
        nextBtn.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(6, spacer, prevBtn, revealBtn, nextBtn);
        bottom.setPadding(new Insets(8));
        bottom.setAlignment(Pos.CENTER_RIGHT);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);
        root.setBottom(bottom);

        s.setScene(new Scene(root, 640, 480));
        s.setOnHidden(e -> stage = null);
        return s;
    }

    /** "Sınav yükle…": pick a *.json pack and load it. On failure, keep the current state. */
    private void promptLoad() {
        if (opening)
            return; // extra guard beyond the disabled button; mirrors ProjectBuilderDialog
        FileChooser fc = new FileChooser();
        fc.setTitle("Sınav yükle…");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null)
            return;
        try {
            AtlasQuiz loaded = AtlasQuizIO.read(file);
            // Only mutate state once the read+validate has fully succeeded.
            this.quiz = loaded;
            titleLabel.setText(loaded.getTitle().isBlank() ? file.getName() : loaded.getTitle());
            showQuestion(1);
        } catch (IOException ex) {
            logger.warn("Failed to load quiz pack {}: {}", file, ex.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Sınav dosyası okunamadı:\n\n" + ex.getMessage());
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
            // Current state (previously-loaded quiz, if any) is left untouched.
        } catch (RuntimeException ex) {
            // Defensive: AtlasQuizIO.read only declares IOException, but Gson can throw an
            // unchecked JsonIOException (not a JsonSyntaxException) for some malformed input
            // (e.g. valid JSON followed by trailing garbage) that AtlasQuizIO does not currently
            // catch. Without this, a bad pack would escape as an uncaught exception on the FX
            // thread from a plain file pick — so it's handled here rather than left to crash.
            logger.warn("Unexpected error loading quiz pack {}: {}", file, ex.getMessage(), ex);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Sınav dosyası okunamadı (beklenmeyen hata):\n\n" + ex.getMessage());
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
        }
    }

    /**
     * Show question {@code i} (1-based), clamped to {@code [1, N]}: update the progress label,
     * rebuild the type-specific input, hide the reveal area, then apply the question's slide
     * (reopening only if it differs from what's currently in the viewer).
     */
    private void showQuestion(int i) {
        if (quiz == null || quiz.getQuestions().isEmpty())
            return;
        int n = quiz.getQuestions().size();
        currentIndex = Math.max(1, Math.min(i, n));
        QuizQuestion q = quiz.getQuestions().get(currentIndex - 1);

        progressLabel.setText("Soru " + currentIndex + " / " + n);
        promptLabel.setText(q.getPrompt());
        setRevealVisible(false);
        buildInput(q);
        applySlide(q);
    }

    /** Build the type-specific input control for {@code q} into {@link #inputArea}. */
    private void buildInput(QuizQuestion q) {
        inputArea.getChildren().clear();
        mcqGroup = null;
        switch (q.getType()) {
            case MCQ -> {
                mcqGroup = new ToggleGroup();
                List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
                for (int idx = 0; idx < options.size(); idx++) {
                    RadioButton rb = new RadioButton(options.get(idx));
                    rb.setToggleGroup(mcqGroup);
                    rb.setUserData(idx);
                    rb.setWrapText(true);
                    inputArea.getChildren().add(rb);
                }
            }
            case FREETEXT -> {
                TextArea freetextArea = new TextArea();
                freetextArea.setPromptText("Notlarınızı buraya yazın…");
                freetextArea.setPrefRowCount(6);
                freetextArea.setWrapText(true);
                inputArea.getChildren().add(freetextArea);
            }
            default -> {
                // slice 2: ANNOTATION/NAVIGATION input + reveal + viewport navigation
                Label placeholder = new Label("(Bu soru tipi bir sonraki sürümde)");
                placeholder.setWrapText(true);
                inputArea.getChildren().add(placeholder);
            }
        }
    }

    /**
     * Open {@code q}'s slide unless it's already the one shown in the viewer. When a reload is
     * needed, disable "Sınav yükle…"/Önceki/Göster/Sonraki and show the progress indicator until
     * {@code onDone}/{@code onError} fires (both guaranteed by {@link QuizSlide} to run on the FX
     * thread); when the slide already matches, the controls are simply left enabled.
     */
    private void applySlide(QuizQuestion q) {
        String current = QuizSlide.currentSlideUrl(qupath.getViewer());
        if (q.getSlideUrl() != null && q.getSlideUrl().equals(current)) {
            setControlsDisabled(false);
            return;
        }
        opening = true;
        progress.setVisible(true);
        setControlsDisabled(true);
        QuizSlide.openAsync(qupath, q.getSlideUrl(),
                () -> {
                    opening = false;
                    progress.setVisible(false);
                    setControlsDisabled(false);
                },
                ex -> {
                    opening = false;
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Slayt açılamadı:\n\n" + ex.getMessage());
                    if (stage != null)
                        alert.initOwner(stage);
                    alert.showAndWait();
                });
    }

    /** "Göster": reveal the answer/explanation area for the current question. Idempotent. */
    private void revealAnswer() {
        if (quiz == null || currentIndex < 1)
            return;
        QuizQuestion q = quiz.getQuestions().get(currentIndex - 1);
        switch (q.getType()) {
            case MCQ -> {
                List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
                Integer correct = q.getCorrectIndex();
                String correctText = (correct != null && correct >= 0 && correct < options.size())
                        ? options.get(correct) : "?";
                StringBuilder sb = new StringBuilder("Doğru cevap: ").append(correctText);
                Integer picked = selectedMcqIndex();
                if (picked != null && !picked.equals(correct)) {
                    String pickedText = (picked >= 0 && picked < options.size()) ? options.get(picked) : "?";
                    sb.append("\nSizin cevabınız: ").append(pickedText);
                }
                revealAnswerLabel.setText(sb.toString());
            }
            case FREETEXT -> {
                String model = q.getModelAnswer() == null ? "" : q.getModelAnswer();
                revealAnswerLabel.setText("Model cevap: " + model);
            }
            default -> {
                // slice 2: ANNOTATION/NAVIGATION input + reveal + viewport navigation
                revealAnswerLabel.setText("(Bu soru tipi için gösterim bir sonraki sürümde)");
            }
        }
        revealExplanationLabel.setText(q.getExplanation() == null ? "" : q.getExplanation());
        setRevealVisible(true);
    }

    /** The 0-based index of the learner's selected MCQ radio button, or null if none picked. */
    private Integer selectedMcqIndex() {
        if (mcqGroup == null)
            return null;
        Toggle t = mcqGroup.getSelectedToggle();
        return t == null ? null : (Integer) t.getUserData();
    }

    /** "Önceki": no-op at question 1 (nothing changed to re-show); otherwise show i-1. */
    private void goPrev() {
        if (quiz == null || opening)
            return;
        int target = Math.max(1, currentIndex - 1);
        if (target != currentIndex)
            showQuestion(target);
    }

    /** "Sonraki": no-op at the last question; otherwise show i+1. */
    private void goNext() {
        if (quiz == null || opening)
            return;
        int n = quiz.getQuestions().size();
        int target = Math.min(n, currentIndex + 1);
        if (target != currentIndex)
            showQuestion(target);
    }

    private void setRevealVisible(boolean visible) {
        revealArea.setVisible(visible);
        revealArea.setManaged(visible);
    }

    /** Disable/enable "Sınav yükle…" + Önceki/Göster/Sonraki together, while a slide is loading. */
    private void setControlsDisabled(boolean disabled) {
        loadBtn.setDisable(disabled);
        prevBtn.setDisable(disabled);
        revealBtn.setDisable(disabled);
        nextBtn.setDisable(disabled);
    }
}
