package com.patolojiatlasi.qupath.quiz;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;

/**
 * In-QuPath author for a quiz pack ({@link AtlasQuiz}/{@link AtlasQuizIO}): edit the pack's
 * title/description, add/edit/remove/reorder questions, and save the result as one portable JSON
 * file. Nothing here touches the QuPath project — a quiz pack lives entirely outside it.
 * <p>
 * Mirrors {@code com.patolojiatlasi.qupath.AtlasBrowser}'s single-window {@code show(...)} /
 * focus-if-open pattern, {@code com.patolojiatlasi.qupath.ProjectBuilderDialog}'s modal
 * sub-dialog-with-form-and-OK/Cancel shape, and {@link QuizRunnerWindow}'s "load a pack, show an
 * {@link Alert} on {@link IOException}" load handling. Unlike the runner, every operation here
 * (file read/write, dialog build) is quick synchronous work, so — per the task spec — everything
 * runs directly on the JavaFX application thread; no background {@code Thread} is spawned.
 * <p>
 * This slice authors MCQ and FREETEXT questions only; see the {@code slice 2} seams in
 * {@link #show(QuPathGUI)}, {@link QuestionDialog}, and {@link #editQuestion()} for where
 * ANNOTATION/NAVIGATION authoring (draw-a-reference-geometry capture) will slot in.
 */
public class QuizAuthorWindow {

    private static final Logger logger = LoggerFactory.getLogger(QuizAuthorWindow.class);

    private static Stage stage;

    private final QuPathGUI qupath;

    private AtlasQuiz quiz = new AtlasQuiz();

    private final TextField titleField = new TextField();
    private final TextField descriptionField = new TextField();

    private final ObservableList<QuizQuestion> items = FXCollections.observableArrayList();
    private final ListView<QuizQuestion> listView = new ListView<>(items);

    private QuizAuthorWindow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single author window. */
    public static void show(QuPathGUI qupath) {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        QuizAuthorWindow author = new QuizAuthorWindow(qupath);
        stage = author.buildStage();
        stage.show();
        // slice 2: ANNOTATION/NAVIGATION types + draw-reference capture
    }

    private Stage buildStage() {
        Stage s = new Stage();
        s.setTitle("Sınav/quiz hazırla");

        Button newBtn = new Button("Yeni");
        newBtn.setOnAction(e -> newQuiz());
        Button openBtn = new Button("Aç…");
        openBtn.setOnAction(e -> openQuiz());
        Button saveBtn = new Button("Kaydet…");
        saveBtn.setOnAction(e -> saveQuiz());
        HBox buttonsRow = new HBox(8, newBtn, openBtn, saveBtn);
        buttonsRow.setAlignment(Pos.CENTER_LEFT);

        titleField.setPromptText("Sınav başlığı");
        descriptionField.setPromptText("Açıklama");
        HBox.setHgrow(titleField, Priority.ALWAYS);
        HBox.setHgrow(descriptionField, Priority.ALWAYS);
        HBox fieldsRow = new HBox(8, new Label("Başlık:"), titleField, new Label("Açıklama:"), descriptionField);
        fieldsRow.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, buttonsRow, fieldsRow);
        top.setPadding(new Insets(8));

        listView.setPlaceholder(new Label("Henüz soru yok — \"Ekle\" ile başlayın"));
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(QuizQuestion q, boolean empty) {
                super.updateItem(q, empty);
                if (empty || q == null) {
                    setText(null);
                } else {
                    String prompt = q.getPrompt() == null ? "" : q.getPrompt().replace("\n", " ");
                    if (prompt.length() > 60)
                        prompt = prompt.substring(0, 60) + "…";
                    String type = q.getType() == null ? "?" : q.getType().toString();
                    setText((getIndex() + 1) + ". [" + type + "] " + prompt);
                }
            }
        });

        Button addBtn = new Button("Ekle");
        addBtn.setOnAction(e -> addQuestion());
        Button editBtn = new Button("Düzenle");
        editBtn.setOnAction(e -> editQuestion());
        Button deleteBtn = new Button("Sil");
        deleteBtn.setOnAction(e -> deleteQuestion());
        Button upBtn = new Button("Yukarı");
        upBtn.setOnAction(e -> moveQuestion(-1));
        Button downBtn = new Button("Aşağı");
        downBtn.setOnAction(e -> moveQuestion(1));
        HBox listButtons = new HBox(6, addBtn, editBtn, deleteBtn, upBtn, downBtn);
        listButtons.setPadding(new Insets(6, 0, 0, 0));

        VBox center = new VBox(6, listView, listButtons);
        center.setPadding(new Insets(8));
        VBox.setVgrow(listView, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);

        s.setScene(new Scene(root, 640, 480));
        s.setOnHidden(e -> stage = null);
        return s;
    }

    /** "Yeni": start a fresh, empty pack. Does not ask about unsaved changes (see class notes). */
    private void newQuiz() {
        quiz = new AtlasQuiz();
        titleField.setText("");
        descriptionField.setText("");
        items.setAll(quiz.getQuestions());
    }

    /** "Aç…": pick a *.json pack and load it for editing. On failure, keep the current pack. */
    private void openQuiz() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Sınav paketi aç…");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null)
            return;
        // Mirrors QuizRunnerWindow.promptLoad: the try wraps ONLY the read, so a failed load
        // never leaves the editor half-mutated -- state is assigned below, after a fully
        // successful read+validate.
        AtlasQuiz loaded;
        try {
            loaded = AtlasQuizIO.read(file);
        } catch (IOException ex) {
            logger.warn("Failed to load quiz pack {}: {}", file, ex.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Sınav dosyası okunamadı:\n\n" + ex.getMessage());
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
            return;
        }
        quiz = loaded;
        titleField.setText(quiz.getTitle());
        descriptionField.setText(quiz.getDescription());
        items.setAll(quiz.getQuestions());
    }

    /**
     * "Kaydet…": sync the title/description fields into the in-memory pack, validate it, then
     * write it. Validation runs <em>before</em> the save-location prompt (rather than after, as a
     * literal reading of the task brief's step order would have it) so an invalid pack is caught
     * with a clear message before the user is asked to pick a file. {@link AtlasQuizIO#write}
     * itself performs no structural validation -- only {@link AtlasQuizIO#read} does -- so the
     * package-private {@link AtlasQuizIO#validate} is called explicitly first (both classes share
     * this {@code quiz} package, so the call is legal without touching {@code AtlasQuizIO.java}).
     * On either an invalid pack or an I/O failure, the in-memory quiz is left untouched.
     */
    private void saveQuiz() {
        quiz.setTitle(titleField.getText());
        quiz.setDescription(descriptionField.getText());

        try {
            AtlasQuizIO.validate(quiz);
        } catch (IOException ex) {
            logger.warn("Quiz failed validation before save: {}", ex.getMessage());
            showError("Sınav kaydedilemedi:\n\n" + ex.getMessage());
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Sınav paketini kaydet…");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
        File file = fc.showSaveDialog(stage);
        if (file == null)
            return;
        // The native Save dialog usually appends the filter's extension, but not on every
        // platform/filter combination -- append it defensively if missing.
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
            file = new File(file.getAbsolutePath() + ".json");

        try {
            AtlasQuizIO.write(quiz, file);
        } catch (IOException ex) {
            logger.warn("Failed to save quiz pack {}: {}", file, ex.getMessage());
            showError("Sınav kaydedilemedi:\n\n" + ex.getMessage());
            return;
        }
        Alert done = new Alert(Alert.AlertType.INFORMATION, "Kaydedildi: " + file.getName());
        if (stage != null)
            done.initOwner(stage);
        done.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        if (stage != null)
            alert.initOwner(stage);
        alert.showAndWait();
    }

    /** "Ekle": open the sub-dialog for a brand-new question; append it to the pack on OK. */
    private void addQuestion() {
        QuizQuestion result = QuestionDialog.show(stage, qupath, null);
        if (result == null)
            return;
        quiz.getQuestions().add(result);
        items.setAll(quiz.getQuestions());
        listView.getSelectionModel().select(result);
    }

    /**
     * "Düzenle": open the sub-dialog on the selected question. ANNOTATION/NAVIGATION questions
     * (possible in a mixed pack loaded via "Aç…", even though "Ekle" can never create one in this
     * slice) cannot yet be edited here -- refuse with an explanation rather than mis-editing them.
     */
    private void editQuestion() {
        QuizQuestion selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        if (selected.getType() == QuizType.ANNOTATION || selected.getType() == QuizType.NAVIGATION) {
            // slice 2: ANNOTATION/NAVIGATION types + draw-reference capture
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "\"" + selected.getType() + "\" tipi sorular bu sürümde düzenlenemiyor "
                            + "— bir sonraki sürümde eklenecek.");
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
            return;
        }
        // QuestionDialog mutates `selected` in place on OK (same object identity), so the list
        // itself needs no splice -- only the ListView's cells need to redraw.
        QuizQuestion result = QuestionDialog.show(stage, qupath, selected);
        if (result == null)
            return;
        items.setAll(quiz.getQuestions());
        listView.getSelectionModel().select(result);
    }

    /** "Sil": remove the selected question. */
    private void deleteQuestion() {
        QuizQuestion selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        quiz.getQuestions().remove(selected);
        items.setAll(quiz.getQuestions());
    }

    /** "Yukarı" ({@code delta == -1}) / "Aşağı" ({@code delta == +1}): swap with the neighbor. */
    private void moveQuestion(int delta) {
        QuizQuestion selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        List<QuizQuestion> qs = quiz.getQuestions();
        int idx = qs.indexOf(selected);
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= qs.size())
            return;
        Collections.swap(qs, idx, target);
        items.setAll(qs);
        listView.getSelectionModel().select(selected);
    }

    /**
     * Modal Add/Edit sub-dialog for one question. Mirrors
     * {@code com.patolojiatlasi.qupath.ProjectBuilderDialog}'s form-with-OK/Cancel shape: a
     * window-modal {@link Stage} owned by the author window, built once, shown via
     * {@link Stage#showAndWait()}, with the result read back from an instance field afterwards.
     * <p>
     * When editing ({@code existing != null}), the same {@link QuizQuestion} instance is mutated
     * in place on OK (so the caller's list already holds the updated object -- no splice needed);
     * when adding ({@code existing == null}), a new instance is created and given a generated id.
     * Cancel touches nothing: all edits are held in local dialog fields until OK commits them.
     */
    private static final class QuestionDialog {

        private final QuPathGUI qupath;
        private final Stage stage;
        private final QuizQuestion result;
        private boolean confirmed = false;

        // slice 2: ANNOTATION/NAVIGATION types + draw-reference capture -- this ChoiceBox is
        // deliberately limited to the two slice-1 types.
        private final ChoiceBox<QuizType> typeChoice =
                new ChoiceBox<>(FXCollections.observableArrayList(QuizType.MCQ, QuizType.FREETEXT));
        private final TextArea promptArea = new TextArea();
        private final TextArea explanationArea = new TextArea();
        private final Label slideLabel = new Label();
        private final Label errorLabel = new Label();

        private final VBox optionsBox = new VBox(4);
        private final ToggleGroup correctGroup = new ToggleGroup();
        private final List<OptionRow> optionRows = new ArrayList<>();
        private final TextArea modelAnswerArea = new TextArea();
        private final VBox typeSpecificBox = new VBox(6);

        private String boundSlideUrl;
        private String boundSlideTitle;

        private QuestionDialog(Stage owner, QuPathGUI qupath, QuizQuestion existing) {
            this.qupath = qupath;
            this.result = existing != null ? existing : new QuizQuestion();
            this.boundSlideUrl = existing != null ? existing.getSlideUrl() : null;
            this.boundSlideTitle = existing != null ? existing.getSlideTitle() : null;

            this.stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null)
                stage.initOwner(owner);
            stage.setTitle(existing != null ? "Soruyu düzenle" : "Soru ekle");

            build(existing);
        }

        /** Build and show the modal dialog; returns the built/updated question, or null if canceled. */
        static QuizQuestion show(Stage owner, QuPathGUI qupath, QuizQuestion existing) {
            QuestionDialog dialog = new QuestionDialog(owner, qupath, existing);
            dialog.stage.showAndWait();
            return dialog.confirmed ? dialog.result : null;
        }

        private void build(QuizQuestion existing) {
            typeChoice.setValue(existing != null && existing.getType() != null ? existing.getType() : QuizType.MCQ);
            typeChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> rebuildTypeSpecific());

            promptArea.setPromptText("Soru metni");
            promptArea.setWrapText(true);
            promptArea.setPrefRowCount(3);
            if (existing != null && existing.getPrompt() != null)
                promptArea.setText(existing.getPrompt());

            explanationArea.setPromptText("Açıklama (Göster'de gösterilir)");
            explanationArea.setWrapText(true);
            explanationArea.setPrefRowCount(3);
            if (existing != null && existing.getExplanation() != null)
                explanationArea.setText(existing.getExplanation());

            modelAnswerArea.setPromptText("Model cevap");
            modelAnswerArea.setWrapText(true);
            modelAnswerArea.setPrefRowCount(4);

            // Pre-fill type-specific state from the existing question, before the first
            // rebuildTypeSpecific() render.
            if (existing != null && existing.getType() == QuizType.MCQ) {
                List<String> opts = existing.getOptions() == null ? List.of() : existing.getOptions();
                for (String opt : opts)
                    addOptionRow(opt);
                Integer ci = existing.getCorrectIndex();
                if (ci != null && ci >= 0 && ci < optionRows.size())
                    optionRows.get(ci).radio.setSelected(true);
            } else if (existing != null && existing.getType() == QuizType.FREETEXT) {
                modelAnswerArea.setText(existing.getModelAnswer() == null ? "" : existing.getModelAnswer());
            }
            if (optionRows.isEmpty()) {
                addOptionRow("");
                addOptionRow("");
            }

            rebuildTypeSpecific();
            updateSlideLabel();

            Button bindBtn = new Button("Geçerli slayta bağla");
            bindBtn.setOnAction(e -> bindCurrentSlide());
            HBox slideRow = new HBox(8, bindBtn, slideLabel);
            slideRow.setAlignment(Pos.CENTER_LEFT);

            Button okBtn = new Button("Tamam");
            okBtn.setOnAction(e -> onOk());
            Button cancelBtn = new Button("İptal");
            cancelBtn.setOnAction(e -> stage.close());
            errorLabel.setStyle("-fx-text-fill: #b00020;");
            errorLabel.setWrapText(true);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox actions = new HBox(6, errorLabel, spacer, cancelBtn, okBtn);
            actions.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(errorLabel, Priority.ALWAYS);

            VBox root = new VBox(8,
                    new Label("Soru tipi:"), typeChoice,
                    new Label("Soru metni:"), promptArea,
                    typeSpecificBox,
                    new Label("Açıklama:"), explanationArea,
                    slideRow,
                    actions);
            root.setPadding(new Insets(12));

            stage.setScene(new Scene(root, 480, 640));
        }

        /** Swap the type-specific area's content to match the current {@link #typeChoice} value. */
        private void rebuildTypeSpecific() {
            typeSpecificBox.getChildren().clear();
            if (typeChoice.getValue() == QuizType.FREETEXT) {
                typeSpecificBox.getChildren().addAll(new Label("Model cevap:"), modelAnswerArea);
            } else {
                // MCQ is the default for a null/unrecognized value too.
                Button addOptionBtn = new Button("Seçenek ekle");
                addOptionBtn.setOnAction(e -> addOptionRow(""));
                typeSpecificBox.getChildren().addAll(
                        new Label("Seçenekler (doğru olanı işaretleyin):"), optionsBox, addOptionBtn);
            }
        }

        private void addOptionRow(String text) {
            OptionRow row = new OptionRow(text, correctGroup);
            row.removeBtn.setOnAction(e -> removeOptionRow(row));
            optionRows.add(row);
            optionsBox.getChildren().add(row.box);
        }

        private void removeOptionRow(OptionRow row) {
            optionRows.remove(row);
            optionsBox.getChildren().remove(row.box);
            // Detach from the ToggleGroup too -- otherwise a removed-but-still-selected radio
            // lingers as the group's "selected" toggle even though it's no longer shown or in
            // optionRows (selectedOptionIndex() would find no matching row and return null, which
            // onOk() already treats as "no correct option chosen", but detaching keeps the group's
            // internal state tidy rather than relying on that fallback).
            row.radio.setToggleGroup(null);
        }

        /**
         * "Geçerli slayta bağla": capture the viewer's current slide as this question's slide.
         * Refuses (leaves the previous binding, if any, untouched) when no slide is open --
         * {@link QuizSlide#currentSlideUrl} returns {@code ""} in that case.
         */
        private void bindCurrentSlide() {
            String url = QuizSlide.currentSlideUrl(qupath.getViewer());
            if (url.isBlank()) {
                errorLabel.setText("Açık bir slayt yok — önce QuPath'te bir slayt açın.");
                return;
            }
            boundSlideUrl = url;
            boundSlideTitle = currentViewerImageName();
            errorLabel.setText("");
            updateSlideLabel();
        }

        /** The open slide's display name, or "" if unavailable for any reason. */
        private String currentViewerImageName() {
            try {
                var viewer = qupath.getViewer();
                if (viewer == null)
                    return "";
                var imageData = viewer.getImageData();
                if (imageData == null)
                    return "";
                var server = imageData.getServer();
                if (server == null)
                    return "";
                String name = server.getMetadata().getName();
                return name == null ? "" : name;
            } catch (Exception e) {
                return "";
            }
        }

        private void updateSlideLabel() {
            if (boundSlideUrl == null || boundSlideUrl.isBlank()) {
                slideLabel.setText("(Slayt bağlı değil)");
            } else {
                String title = (boundSlideTitle == null || boundSlideTitle.isBlank()) ? boundSlideUrl : boundSlideTitle;
                slideLabel.setText("Slayt: " + title);
            }
        }

        private Integer selectedOptionIndex() {
            Toggle t = correctGroup.getSelectedToggle();
            if (t == null)
                return null;
            for (int i = 0; i < optionRows.size(); i++)
                if (optionRows.get(i).radio == t)
                    return i;
            return null;
        }

        /**
         * "Tamam": validate locally (mirrors the rules in {@link AtlasQuizIO#validate}, checked
         * again there at save time) and, only if everything required is present, commit into
         * {@link #result} and close. Any failure sets {@link #errorLabel} and leaves the dialog
         * open so the user can fix it.
         */
        private void onOk() {
            String prompt = promptArea.getText();
            if (prompt == null || prompt.isBlank()) {
                errorLabel.setText("Soru metni boş olamaz.");
                return;
            }
            if (boundSlideUrl == null || boundSlideUrl.isBlank()) {
                errorLabel.setText("Önce \"Geçerli slayta bağla\" ile bir slayt seçin.");
                return;
            }
            QuizType type = typeChoice.getValue();
            if (type == null) {
                errorLabel.setText("Soru tipini seçin.");
                return;
            }

            List<String> options = null;
            Integer correctIndex = null;
            String modelAnswer = null;

            if (type == QuizType.MCQ) {
                options = new ArrayList<>();
                for (OptionRow row : optionRows)
                    options.add(row.field.getText() == null ? "" : row.field.getText());
                if (options.size() < 2) {
                    errorLabel.setText("En az 2 seçenek girin.");
                    return;
                }
                Integer selected = selectedOptionIndex();
                if (selected == null) {
                    errorLabel.setText("Doğru seçeneği işaretleyin.");
                    return;
                }
                correctIndex = selected;
            } else { // FREETEXT
                modelAnswer = modelAnswerArea.getText() == null ? "" : modelAnswerArea.getText();
            }

            if (result.getId() == null || result.getId().isBlank())
                result.setId("q" + System.currentTimeMillis());
            result.setType(type);
            result.setPrompt(prompt);
            result.setExplanation(explanationArea.getText());
            result.setSlideUrl(boundSlideUrl);
            result.setSlideTitle(boundSlideTitle);
            result.setOptions(options);
            result.setCorrectIndex(correctIndex);
            result.setModelAnswer(modelAnswer);

            confirmed = true;
            stage.close();
        }

        /** One editable MCQ option: its text, a "this is correct" radio, and a remove button. */
        private static final class OptionRow {
            final TextField field = new TextField();
            final RadioButton radio = new RadioButton();
            final Button removeBtn = new Button("Sil");
            final HBox box;

            OptionRow(String text, ToggleGroup group) {
                field.setText(text == null ? "" : text);
                field.setPromptText("Seçenek metni");
                radio.setToggleGroup(group);
                HBox.setHgrow(field, Priority.ALWAYS);
                box = new HBox(6, radio, field, removeBtn);
                box.setAlignment(Pos.CENTER_LEFT);
            }
        }
    }
}
