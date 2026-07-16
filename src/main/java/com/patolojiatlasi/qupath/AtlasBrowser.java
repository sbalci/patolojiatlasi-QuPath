package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Browser window listing atlas cases grouped by category, with a search box, a
 * published-only filter, a thumbnail preview, and an Open action that streams the
 * selected slide into QuPath.
 */
public class AtlasBrowser {

    private static final Logger logger = LoggerFactory.getLogger(AtlasBrowser.class);

    private static Stage stage;

    private final QuPathGUI qupath;
    private final TreeView<Object> tree = new TreeView<>();
    private final TextField searchField = new TextField();
    private final CheckBox publishedOnly = new CheckBox("Published only");
    private final Label status = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ImageView thumbView = new ImageView();
    private final Label infoLabel = new Label();

    private List<AtlasCase> allCases;

    // Persistent selection basket, deduped by AtlasCase (DZI URL). Independent of the tree,
    // so it survives search/filter/refresh. Touched only on the JavaFX thread.
    private final java.util.LinkedHashSet<AtlasCase> selection = new java.util.LinkedHashSet<>();
    private final Label selectionCount = new Label("0 selected");
    private final Button createProjectBtn = new Button("Create project…");

    // Re-entrancy guard for openSelected(); touched only on the JavaFX thread.
    private boolean opening = false;

    private AtlasBrowser(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single browser window. */
    public static void show(QuPathGUI qupath) {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        AtlasBrowser browser = new AtlasBrowser(qupath);
        stage = browser.buildStage();
        stage.show();
    }

    private Stage buildStage() {
        Stage s = new Stage();
        s.setTitle("Patoloji Atlası");

        searchField.setPromptText("Search cases…");
        searchField.textProperty().addListener((obs, old, txt) -> rebuildTree());
        publishedOnly.setSelected(false);
        publishedOnly.selectedProperty().addListener((obs, old, v) -> rebuildTree());

        Button refreshBtn = new Button("Refresh list");
        refreshBtn.setTooltip(new javafx.scene.control.Tooltip("Fetch the latest list.yaml from GitHub"));
        refreshBtn.setOnAction(e -> refreshFromList(refreshBtn));

        progress.setVisible(false);
        progress.setPrefSize(18, 18);

        HBox top = new HBox(6, searchField, publishedOnly, refreshBtn, progress);
        top.setPadding(new Insets(8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        tree.setShowRoot(false);
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2)
                openSelected();
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> updatePreview());

        MenuItem addToSelectionItem = new MenuItem("Add to selection");
        addToSelectionItem.setOnAction(e -> addToSelection());
        tree.setContextMenu(new ContextMenu(addToSelectionItem));

        // Preview pane
        thumbView.setFitWidth(220);
        thumbView.setPreserveRatio(true);
        infoLabel.setWrapText(true);
        infoLabel.setPadding(new Insets(6, 0, 0, 0));
        VBox preview = new VBox(6, thumbView, infoLabel);
        preview.setPadding(new Insets(8));
        preview.setPrefWidth(240);

        SplitPane split = new SplitPane(tree, preview);
        split.setDividerPositions(0.62);

        Button openBtn = new Button("Open in QuPath");
        openBtn.setOnAction(e -> openSelected());
        Button addSelBtn = new Button("Add to selection");
        addSelBtn.setOnAction(e -> addToSelection());
        Button webBtn = new Button("Copy web link");
        webBtn.setOnAction(e -> copyWebLink());
        Button aboutBtn = new Button("About");
        aboutBtn.setTooltip(new javafx.scene.control.Tooltip("About this extension and the atlas websites"));
        aboutBtn.setOnAction(e -> showAbout());

        // Spacer pushes the About button to the far right of the bar.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        createProjectBtn.setDisable(true);
        createProjectBtn.setOnAction(e -> openProjectBuilder());

        HBox bottom = new HBox(6, openBtn, addSelBtn, webBtn, selectionCount, createProjectBtn, status, spacer, aboutBtn);
        bottom.setPadding(new Insets(8));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(split);
        root.setBottom(bottom);

        allCases = AtlasCatalog.loadBundled();
        rebuildTree();
        status.setText(allCases.size() + " images (bundled snapshot)");

        s.setScene(new Scene(root, 720, 660));
        s.setOnHidden(e -> stage = null);
        return s;
    }

    /**
     * Small About dialog explaining what the extension does, with clickable links to the
     * atlas's public websites (Turkish and English). Links open in the system browser via
     * QuPath's own {@code openInBrowser}.
     */
    private void showAbout() {
        Label desc = new Label(
                "Browse whole-slide images published on the Patoloji Atlası and open them "
                + "directly in QuPath. Slides are streamed tile-by-tile as Deep Zoom (DZI) "
                + "pyramids, so there is nothing to download in advance.\n\n"
                + "The images belong to the atlas and are shared for viewing and study.");
        desc.setWrapText(true);
        desc.setMaxWidth(440);

        Label sitesHeader = new Label("Websites");
        sitesHeader.setStyle("-fx-font-weight: bold;");

        Hyperlink trLink = new Hyperlink("patolojiatlasi.com  —  Türkçe");
        trLink.setOnAction(e -> QuPathGUI.openInBrowser("https://www.patolojiatlasi.com/"));
        Hyperlink enLink = new Hyperlink("histopathologyatlas.com  —  English");
        enLink.setOnAction(e -> QuPathGUI.openInBrowser("https://www.histopathologyatlas.com/"));

        VBox content = new VBox(4, desc, new Separator(), sitesHeader, trLink, enLink);
        content.setPadding(new Insets(4));

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About");
        alert.setHeaderText("QuPath Patoloji Atlası extension");
        alert.getDialogPane().setContent(content);
        if (stage != null)
            alert.initOwner(stage);
        alert.showAndWait();
    }

    private void rebuildTree() {
        String f = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        boolean pubOnly = publishedOnly.isSelected();
        TreeItem<Object> root = new TreeItem<>("Atlas");
        Map<String, List<AtlasCase>> grouped = AtlasCatalog.groupByCategory(allCases);
        int shown = 0;
        for (var entry : grouped.entrySet()) {
            TreeItem<Object> catItem = new TreeItem<>(entry.getKey());
            for (AtlasCase c : entry.getValue()) {
                if (pubOnly && !c.isPublished())
                    continue;
                if (f.isEmpty()
                        || c.getTitle().toLowerCase().contains(f)
                        || c.getReponame().toLowerCase().contains(f)
                        || c.getOrganEN().toLowerCase().contains(f)
                        || c.getCategory().toLowerCase().contains(f)) {
                    catItem.getChildren().add(new TreeItem<>(c));
                    shown++;
                }
            }
            if (!catItem.getChildren().isEmpty()) {
                catItem.setExpanded(!f.isEmpty());
                root.getChildren().add(catItem);
            }
        }
        tree.setRoot(root);
        if (!f.isEmpty() || pubOnly)
            status.setText(shown + " images shown");
    }

    private AtlasCase getSelectedCase() {
        TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
        if (sel != null && sel.getValue() instanceof AtlasCase c)
            return c;
        return null;
    }

    private void updatePreview() {
        AtlasCase c = getSelectedCase();
        if (c == null) {
            thumbView.setImage(null);
            infoLabel.setText("");
            return;
        }
        infoLabel.setText(c.getTitle()
                + "\n\nCategory: " + c.getCategory()
                + (c.getOrganEN().isBlank() ? "" : "\nOrgan: " + c.getOrganEN())
                + "\nStain: " + c.getImage()
                + "\n" + (c.isPublished() ? "Published" : "Unpublished"));
        try {
            if (!c.getThumbUrl().isBlank())
                thumbView.setImage(new Image(c.getThumbUrl(), 220, 0, true, true, true));
            else
                thumbView.setImage(null);
        } catch (Exception e) {
            thumbView.setImage(null);
        }
    }

    private void copyWebLink() {
        AtlasCase c = getSelectedCase();
        if (c == null) {
            status.setText("Select a case first");
            return;
        }
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(c.getViewerUrl());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        status.setText("Copied: " + c.getViewerUrl());
    }

    private void addToSelection() {
        AtlasCase c = getSelectedCase();
        if (c == null) {
            status.setText("Select a case first");
            return;
        }
        if (selection.add(c))
            status.setText("Added to selection: " + c.getTitle());
        else
            status.setText("Already in selection: " + c.getTitle());
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        selectionCount.setText(selection.size() + " selected");
        createProjectBtn.setDisable(selection.isEmpty());
    }

    /**
     * Open the project-builder dialog, but refuse while a single-image open is still in
     * flight: that open mutates the shared QuPath project on a background thread, and a
     * concurrent build would race it (addImage/syncChanges are not thread-safe). Because the
     * dialog is window-modal, once it is open no single-open can start and a second build is
     * blocked too — so gating here is what makes single-open and builds mutually exclusive.
     */
    private void openProjectBuilder() {
        if (opening) {
            status.setText("Please wait — an image is still opening…");
            return;
        }
        if (selection.isEmpty()) {
            status.setText("Add some images to the selection first");
            return;
        }
        ProjectBuilderDialog.show(qupath, stage, selection, this::updateSelectionCount);
    }

    private void openSelected() {
        AtlasCase c = getSelectedCase();
        if (c == null) {
            status.setText("Select a case first");
            return;
        }
        // Re-entrancy guard: opening a case spawns a background thread that mutates the
        // shared QuPath project (project.addImage / syncChanges are not thread-safe, so
        // two overlapping opens could corrupt the project entry list). Allow only one
        // open in flight at a time. This flag is touched only on the JavaFX thread —
        // openSelected() and the done()/fail() handlers all run there.
        if (opening) {
            status.setText("Please wait — an image is still opening…");
            return;
        }
        opening = true;
        status.setText("Opening " + c.getTitle() + "…");
        progress.setVisible(true);

        Thread t = new Thread(() -> {
            try {
                Project<BufferedImage> project = qupath.getProject();
                if (project != null) {
                    ProjectImageEntry<BufferedImage> entry =
                            AtlasProjectService.addCaseToProject(project, c);
                    project.syncChanges();
                    Platform.runLater(() -> {
                        try {
                            qupath.refreshProject();
                        } catch (Throwable ignore) {
                            // API differences across versions: project is still updated on disk.
                        }
                        try {
                            qupath.openImageEntry(entry);
                        } catch (Throwable ex) {
                            logger.warn("Could not open project entry: {}", ex.getMessage());
                        }
                        done(c, "Added to project & opened: ");
                    });
                } else {
                    DziImageServer server = new DziImageServer(c.getDziURI());
                    ImageData<BufferedImage> imageData = new ImageData<>(server, c.getImageType());
                    Platform.runLater(() -> {
                        try {
                            qupath.getViewer().setImageData(imageData);
                            done(c, "Opened (no project): ");
                        } catch (Exception ex) {
                            logger.error("Failed to display atlas case {}: {}", c.getReponame(), ex.getMessage(), ex);
                            fail(c, ex);
                        }
                    });
                }
            } catch (Exception ex) {
                logger.error("Failed to open atlas case {}: {}", c.getReponame(), ex.getMessage(), ex);
                Platform.runLater(() -> fail(c, ex));
            }
        }, "atlas-open-" + c.getReponame());
        t.setDaemon(true);
        t.start();
    }

    private void done(AtlasCase c, String prefix) {
        opening = false;
        progress.setVisible(false);
        String cal = c.getMpp() > 0
                ? "  (" + c.getMpp() + " µm/px)"
                : "  (no µm/px calibration — set it in the Image tab if known)";
        status.setText(prefix + c.getTitle() + cal);
    }

    /** Reset the UI + re-entrancy guard after a failed open and report the error. Runs on the FX thread. */
    private void fail(AtlasCase c, Exception ex) {
        opening = false;
        progress.setVisible(false);
        status.setText("Failed: " + ex.getMessage());
        new Alert(Alert.AlertType.ERROR,
                "Could not open " + c.getTitle() + "\n\n" + ex.getMessage()).showAndWait();
    }

    private void refreshFromList(Button refreshBtn) {
        refreshBtn.setDisable(true);
        progress.setVisible(true);
        status.setText("Fetching latest list from GitHub…");
        Thread t = new Thread(() -> {
            try {
                List<AtlasCase> fresh = AtlasCatalog.refreshFromList();
                Platform.runLater(() -> {
                    if (!fresh.isEmpty()) {
                        allCases = fresh;
                        rebuildTree();
                        status.setText(fresh.size() + " images (live from GitHub)");
                    } else {
                        status.setText("No images parsed; keeping bundled snapshot");
                    }
                    progress.setVisible(false);
                    refreshBtn.setDisable(false);
                });
            } catch (Exception ex) {
                logger.warn("List refresh failed: {}", ex.getMessage());
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    refreshBtn.setDisable(false);
                    status.setText("Refresh failed: " + ex.getMessage());
                });
            }
        }, "atlas-list-refresh");
        t.setDaemon(true);
        t.start();
    }
}
