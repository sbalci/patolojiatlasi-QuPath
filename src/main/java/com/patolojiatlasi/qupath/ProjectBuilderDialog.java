package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Modal dialog to turn the browser's selection basket into a QuPath project — a new project on
 * disk, or added to the currently-open project. The build runs on a background daemon thread;
 * the dialog is window-modal on the browser so single-open cannot run concurrently.
 */
public class ProjectBuilderDialog {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBuilderDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;
    private final LinkedHashSet<AtlasCase> basket;
    private final Runnable onSelectionChanged;

    private final ObservableList<AtlasCase> items;
    private final ListView<AtlasCase> listView;
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private RadioButton newRadio;
    private RadioButton currentRadio;
    private TextField nameField;
    private Label locationLabel;
    private File location;
    private Button createBtn;
    private Button cancelBtn;
    private Button manifestBtn;
    private Stage stage;
    private boolean building;

    private ProjectBuilderDialog(QuPathGUI qupath, Stage owner,
            LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged) {
        this.qupath = qupath;
        this.owner = owner;
        this.basket = basket;
        this.onSelectionChanged = onSelectionChanged;
        this.items = FXCollections.observableArrayList(basket);
        this.listView = new ListView<>(items);
    }

    /** Build and show the modal dialog. Must be called on the JavaFX thread. */
    static void show(QuPathGUI qupath, Stage owner,
            LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged) {
        new ProjectBuilderDialog(qupath, owner, basket, onSelectionChanged).build().showAndWait();
    }

    private Stage build() {
        stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null)
            stage.initOwner(owner);
        stage.setTitle("Create project from selection");
        stage.setOnCloseRequest(e -> { if (building) e.consume(); });

        listView.setPrefHeight(220);
        listView.setPlaceholder(new Label("No images selected"));

        Button removeBtn = new Button("Remove");
        removeBtn.setOnAction(e -> removeSelected());
        Button clearBtn = new Button("Clear all");
        clearBtn.setOnAction(e -> clearAll());
        HBox listButtons = new HBox(6, removeBtn, clearBtn);

        ToggleGroup group = new ToggleGroup();
        newRadio = new RadioButton("New project");
        newRadio.setToggleGroup(group);
        newRadio.setSelected(true);
        currentRadio = new RadioButton("Add to current project");
        currentRadio.setToggleGroup(group);
        boolean hasProject = qupath.getProject() != null;
        currentRadio.setDisable(!hasProject);
        if (!hasProject)
            currentRadio.setTooltip(new Tooltip("Open a project first to use this option"));

        nameField = new TextField();
        nameField.setPromptText("Project name");
        Button locationBtn = new Button("Choose location…");
        locationBtn.setOnAction(e -> chooseLocation());
        locationLabel = new Label("(no location chosen)");
        HBox locationRow = new HBox(6, locationBtn, locationLabel);
        VBox newBox = new VBox(6, new Label("Project name:"), nameField, locationRow);
        newBox.setPadding(new Insets(4, 0, 0, 20));
        newBox.disableProperty().bind(newRadio.selectedProperty().not());

        createBtn = new Button("Create");
        createBtn.setOnAction(e -> runBuild());
        cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> stage.close());
        manifestBtn = new Button("Künye / manifest dışa aktar…");
        manifestBtn.setOnAction(e -> exportManifest());
        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, statusLabel, progress, spacer, manifestBtn, cancelBtn, createBtn);

        VBox rootBox = new VBox(10,
                new Label("Selected images:"), listView, listButtons,
                new Label("Target:"), newRadio, newBox, currentRadio,
                actions);
        rootBox.setPadding(new Insets(12));

        updateStatus();
        stage.setScene(new Scene(rootBox, 460, 540));
        return stage;
    }

    private void removeSelected() {
        AtlasCase c = listView.getSelectionModel().getSelectedItem();
        if (c == null)
            return;
        items.remove(c);
        basket.remove(c);
        onSelectionChanged.run();
        updateStatus();
    }

    private void clearAll() {
        items.clear();
        basket.clear();
        onSelectionChanged.run();
        updateStatus();
    }

    /**
     * "Künye / manifest dışa aktar…": writes a cohort manifest (CSV + Markdown + methods
     * paragraph, see {@link ProvenanceService#saveManifest}) for the same case list the dialog
     * displays ({@link #items}) — independent of the Create/Cancel project-build flow below; no
     * project is created or required. The SHA-lookup context resolution and the file writes run
     * on a background daemon thread, with the resulting confirmation/error alert shown back on
     * the FX thread.
     */
    private void exportManifest() {
        List<AtlasCase> cases = new ArrayList<>(items);
        if (cases.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Dışa aktarılacak görüntü seçilmedi.").showAndWait();
            return;
        }
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Künye / manifest için klasör seçin");
        File dir = dc.showDialog(stage);
        if (dir == null)
            return;

        manifestBtn.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                AtlasCitation.CitationContext ctx = ProvenanceService.resolveContext();
                ProvenanceService.saveManifest(dir, cases, ctx);
                Platform.runLater(() -> {
                    manifestBtn.setDisable(false);
                    new Alert(Alert.AlertType.INFORMATION,
                            "Künye kaydedildi: " + dir.getAbsolutePath()).showAndWait();
                });
            } catch (java.io.IOException ex) {
                logger.error("Manifest export failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> {
                    manifestBtn.setDisable(false);
                    new Alert(Alert.AlertType.ERROR, "Künye kaydedilemedi:\n" + ex.getMessage()).showAndWait();
                });
            }
        }, "atlas-manifest-export");
        t.setDaemon(true);
        t.start();
    }

    private void chooseLocation() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose a parent folder for the new project");
        File dir = dc.showDialog(stage);
        if (dir != null) {
            location = dir;
            locationLabel.setText(dir.getAbsolutePath());
        }
    }

    private void updateStatus() {
        statusLabel.setText(items.size() + " image(s)");
        if (createBtn != null)
            createBtn.setDisable(items.isEmpty());
    }

    private void runBuild() {
        List<AtlasCase> cases = new ArrayList<>(items);
        if (cases.isEmpty()) {
            statusLabel.setText("No images selected");
            return;
        }
        boolean makeNew = newRadio.isSelected();
        final File projectDir;
        if (makeNew) {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                statusLabel.setText("Enter a project name");
                return;
            }
            if (location == null) {
                statusLabel.setText("Choose a location");
                return;
            }
            File candidate = new File(location, name);
            String[] existing = candidate.list();
            if (candidate.exists() && existing != null && existing.length > 0) {
                statusLabel.setText("Folder exists and is not empty — choose another name");
                return;
            }
            projectDir = candidate;
        } else {
            if (qupath.getProject() == null) {
                statusLabel.setText("No project open");
                return;
            }
            projectDir = null;
        }

        building = true;
        createBtn.setDisable(true);
        cancelBtn.setDisable(true);
        progress.setVisible(true);
        statusLabel.setText("Building…");

        Thread t = new Thread(() -> {
            try {
                if (makeNew) {
                    AtlasProjectService.BuildOutcome outcome =
                            AtlasProjectService.createProject(projectDir, cases);
                    Platform.runLater(() -> {
                        try {
                            qupath.setProject(outcome.project());
                        } catch (Throwable ex) {
                            logger.warn("Could not open new project: {}", ex.getMessage());
                        }
                        finish(outcome.result());
                    });
                } else {
                    Project<BufferedImage> project = qupath.getProject();
                    AtlasProjectService.BuildResult result =
                            AtlasProjectService.addCasesToProject(project, cases);
                    Platform.runLater(() -> {
                        try {
                            qupath.refreshProject();
                        } catch (Throwable ignore) {
                            // project already updated on disk
                        }
                        finish(result);
                    });
                }
            } catch (Throwable ex) {
                logger.error("Project build failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> {
                    building = false;
                    progress.setVisible(false);
                    createBtn.setDisable(false);
                    cancelBtn.setDisable(false);
                    statusLabel.setText("Failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR,
                            "Could not build project\n\n" + ex.getMessage()).showAndWait();
                });
            }
        }, "atlas-project-build");
        t.setDaemon(true);
        t.start();
    }

    /** Runs on the FX thread after a build: keep failed cases for retry, drop the rest, report, close. */
    private void finish(AtlasProjectService.BuildResult result) {
        building = false;
        progress.setVisible(false);
        // Keep only the cases that failed to add, so the user can retry them without re-finding
        // them from the tree; succeeded and skipped (already-present) cases leave the selection.
        java.util.Set<AtlasCase> failed = new java.util.HashSet<>();
        for (AtlasProjectService.BuildResult.Failure f : result.failures())
            failed.add(f.c());
        basket.removeIf(c -> !failed.contains(c));
        items.setAll(basket);
        onSelectionChanged.run();
        if (result.hasFailures()) {
            StringBuilder sb = new StringBuilder(
                    "Some images could not be added (kept in the selection so you can retry):\n\n");
            for (AtlasProjectService.BuildResult.Failure f : result.failures())
                sb.append("• ").append(f.c().getTitle()).append(" — ").append(f.reason()).append('\n');
            new Alert(Alert.AlertType.WARNING, sb.toString()).showAndWait();
        }
        statusLabel.setText("Done — " + result.summary());
        stage.close();
    }
}
