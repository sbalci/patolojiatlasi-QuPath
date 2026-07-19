package com.patolojiatlasi.qupath;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.lib.gui.QuPathGUI;

/**
 * Small modal picker for choosing an atlas case to open as a bench-side reference beside the
 * user's own slide (see {@link BenchReference#openBeside}). Mirrors {@link ProjectBuilderDialog}'s
 * modal-{@link Stage} style: a live-filtered {@link ListView}, an owner-window-modal stage, and a
 * single primary action gated on there being a selection.
 */
public final class ReferencePickerDialog {

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final ObservableList<AtlasCase> allCases;
    private final FilteredList<AtlasCase> filtered;
    private final ListView<AtlasCase> listView;
    private final TextField searchField = new TextField();
    private final Button openBtn = new Button("Yanında aç");

    private ReferencePickerDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.allCases = FXCollections.observableArrayList(AtlasCatalog.loadBundled());
        this.filtered = new FilteredList<>(allCases, c -> true);
        this.listView = new ListView<>(filtered);
    }

    /** Build and show the modal picker. Must be called on the JavaFX application thread. */
    public static void show(QuPathGUI qupath) {
        new ReferencePickerDialog(qupath).build().showAndWait();
    }

    private Stage build() {
        stage.setTitle("Referans slayt seç");
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
            stage.initModality(Modality.WINDOW_MODAL);
        }

        searchField.setPromptText("Ara…");
        searchField.textProperty().addListener((obs, old, txt) -> applyFilter(txt));

        listView.setPrefHeight(360);
        listView.setPlaceholder(new Label("Eşleşen vaka yok"));
        // Cell shows the title explicitly (AtlasCase#toString() also returns the title, but an
        // explicit cellFactory keeps this dialog independent of that fallback).
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AtlasCase item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle());
            }
        });
        listView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, sel) -> openBtn.setDisable(sel == null));
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2)
                openSelected();
        });

        openBtn.setDisable(true);
        openBtn.setOnAction(e -> openSelected());
        Button cancelBtn = new Button("Kapat");
        cancelBtn.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, spacer, cancelBtn, openBtn);

        VBox root = new VBox(8, searchField, listView, actions);
        root.setPadding(new Insets(12));

        stage.setScene(new Scene(root, 420, 460));
        return stage;
    }

    /** Case-insensitive substring filter over title, organ (English) and stain/image basename. */
    private void applyFilter(String text) {
        String f = text == null ? "" : text.trim().toLowerCase();
        filtered.setPredicate(c -> f.isEmpty()
                || c.getTitle().toLowerCase().contains(f)
                || c.getOrganEN().toLowerCase().contains(f)
                || c.getImage().toLowerCase().contains(f));
    }

    private void openSelected() {
        AtlasCase c = listView.getSelectionModel().getSelectedItem();
        if (c == null)
            return;
        BenchReference.openBeside(qupath, c);
        stage.close();
    }
}
