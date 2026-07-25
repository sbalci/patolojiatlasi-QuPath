package com.patolojiatlasi.qupath.focus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

/**
 * Modal form for entering a per-slide diagnostic decision (free-text diagnosis + optional 1–5
 * confidence), following this repo's hand-rolled-Stage dialog convention (cf. QuizAuthorWindow's
 * QuestionDialog / CitationDialog) — no javafx Dialog/ButtonType, no fxtras. Diagnosis is required;
 * confidence is optional (no radio selected = "not given"). {@link #show} returns the entered
 * {@link DecisionInput} or {@code null} if cancelled. Must be called on the FX thread.
 */
public final class DecisionDialog {

    /** What the reader entered: free-text diagnosis + optional 1–5 confidence (null = not given). */
    public record DecisionInput(String diagnosis, Integer confidence) { }

    private DecisionDialog() { }

    /**
     * @param slideHeader optional (nullable/blank-ok) label identifying which slide this decision is
     *                    for, shown as a bold header above the diagnosis field. The deferred
     *                    leave-prompt fires after the viewer has already moved to the next slide, so
     *                    without this a reader could mistakenly answer about the wrong slide; the
     *                    menu path names the current slide for the same reason (consistency, not
     *                    strictly necessary there since it's already on screen). Omitted (no header
     *                    row at all) when null or blank, keeping the plain generic title.
     */
    public static DecisionInput show(QuPathGUI qupath, String preDiagnosis, Integer preConfidence,
            String slideHeader) {
        Stage owner = qupath == null ? null : qupath.getStage();
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null)
            stage.initOwner(owner);
        stage.setTitle("Bu slayt için tanı/karar");

        TextArea diagnosisArea = new TextArea(preDiagnosis == null ? "" : preDiagnosis);
        diagnosisArea.setWrapText(true);
        diagnosisArea.setPromptText("Tanınız / kararınız (serbest metin)");
        diagnosisArea.setPrefRowCount(4);

        ToggleGroup confGroup = new ToggleGroup();
        HBox confBox = new HBox(6);
        confBox.setAlignment(Pos.CENTER_LEFT);
        RadioButton[] confButtons = new RadioButton[5];
        for (int i = 0; i < 5; i++) {
            RadioButton rb = new RadioButton(Integer.toString(i + 1));
            rb.setToggleGroup(confGroup);
            rb.setUserData(i + 1);
            confButtons[i] = rb;
            confBox.getChildren().add(rb);
        }
        if (preConfidence != null && preConfidence >= 1 && preConfidence <= 5)
            confButtons[preConfidence - 1].setSelected(true);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setWrapText(true);

        final DecisionInput[] result = new DecisionInput[1];
        Button okBtn = new Button("Tamam");
        okBtn.setOnAction(e -> {
            String dx = diagnosisArea.getText() == null ? "" : diagnosisArea.getText().trim();
            if (dx.isEmpty()) {
                errorLabel.setText("Tanı alanı boş olamaz.");
                return;
            }
            Integer conf = confGroup.getSelectedToggle() == null ? null
                    : (Integer) confGroup.getSelectedToggle().getUserData();
            result[0] = new DecisionInput(dx, conf);
            stage.close();
        });
        Button cancelBtn = new Button("İptal");
        cancelBtn.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, errorLabel, spacer, cancelBtn, okBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8);
        if (slideHeader != null && !slideHeader.isBlank()) {
            Label headerLabel = new Label(slideHeader);
            headerLabel.setStyle("-fx-font-weight: bold;");
            headerLabel.setWrapText(true);
            root.getChildren().add(headerLabel);
        }
        root.getChildren().addAll(
                new Label("Tanı / karar:"), diagnosisArea,
                new Label("Güven (1–5, isteğe bağlı):"), confBox,
                actions);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 420, 320));
        stage.showAndWait();
        return result[0];
    }
}
