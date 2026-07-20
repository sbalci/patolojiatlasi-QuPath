package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

/**
 * Non-modal companion window showing, for the atlas slide open in the active viewer, that case's
 * other stains and other cases from the same category as two clickable thumbnail filmstrips.
 * <p>
 * Auto-follows the active viewer: an {@link javafx.beans.property.ReadOnlyObjectProperty} listener
 * on {@link QuPathGUI#imageDataProperty()} rebuilds both strips whenever the active image changes
 * (open a new slide, switch viewers, close the slide). Clicking a thumbnail swaps the active
 * viewer's content to that slide via {@link CaseCompare#openInto} — which itself does
 * <b>not</b> guard against discarding unsaved changes (its only other caller, {@link
 * CaseCompare#compareCurrentCase}, does its own guarding first) — so this class guards the swap
 * itself: an outgoing image with unsaved changes triggers a confirmation prompt before the swap
 * proceeds.
 * <p>
 * Exactly one navigator window is allowed at a time (mirroring {@link BenchReference}'s
 * single-instance pattern): a second {@link #show} call while one is already showing just brings
 * it to front, rather than creating a second window with a second (leaking) image-data listener.
 */
public final class RelatedContentNavigator {

    private static final double THUMB_W = 160;

    /** The single live instance, or {@code null} if no navigator window is currently showing. */
    private static RelatedContentNavigator instance;

    private final QuPathGUI qupath;
    private final Stage stage;
    private final Label headerLabel = new Label();
    private final HBox stainsStrip = new HBox(8);
    private final HBox caseStrip = new HBox(8);

    /** Kept so it can be removed from {@link QuPathGUI#imageDataProperty()} on close. */
    private final ChangeListener<ImageData<BufferedImage>> imageListener;

    private RelatedContentNavigator(QuPathGUI qupath) {
        this.qupath = qupath;

        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label stainsHeader = new Label("Bu vakanın diğer boyaları");
        stainsHeader.setStyle("-fx-font-weight: bold;");
        Label caseHeader = new Label("Aynı kategoriden vakalar");
        caseHeader.setStyle("-fx-font-weight: bold;");

        stainsStrip.setPadding(new Insets(6));
        caseStrip.setPadding(new Insets(6));

        ScrollPane stainsScroll = filmstripScroll(stainsStrip);
        ScrollPane caseScroll = filmstripScroll(caseStrip);

        Button refreshBtn = new Button("Yenile");
        refreshBtn.setOnAction(e -> refresh());

        VBox root = new VBox(8,
                headerLabel,
                stainsHeader, stainsScroll,
                caseHeader, caseScroll,
                refreshBtn);
        root.setPadding(new Insets(12));

        stage = new Stage();
        if (qupath != null && qupath.getStage() != null)
            stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle("İlgili içerik");
        stage.setResizable(true);
        stage.setScene(new Scene(root, 720, 460));

        // Auto-follow: rebuild both strips whenever the active image changes.
        imageListener = (obs, oldData, newData) -> Platform.runLater(this::refresh);
        qupath.imageDataProperty().addListener(imageListener);

        // Teardown: remove the listener and clear the single-instance guard so a closed window
        // neither leaks nor blocks a fresh one from being opened.
        stage.setOnHidden(e -> {
            qupath.imageDataProperty().removeListener(imageListener);
            if (instance == this)
                instance = null;
        });

        refresh();
    }

    private static ScrollPane filmstripScroll(HBox strip) {
        ScrollPane scroll = new ScrollPane(strip);
        scroll.setFitToHeight(true);
        scroll.setPrefHeight(THUMB_W + 70);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    /** Show (or focus) the single navigator window. Must be called on the JavaFX thread. */
    public static void show(QuPathGUI qupath) {
        if (qupath == null)
            return;
        if (instance != null && instance.stage.isShowing()) {
            instance.stage.toFront();
            return;
        }
        instance = new RelatedContentNavigator(qupath);
        instance.stage.show();
    }

    /**
     * Rebuilds both strips for whatever atlas case is currently open. Runs on the FX thread — the
     * auto-follow listener wraps calls in {@link Platform#runLater}, and the initial call (from
     * the constructor) and the "Yenile" button are already on the FX thread.
     */
    private void refresh() {
        AtlasCase open = AtlasExtension.resolveOpenCase(qupath);
        if (open == null) {
            headerLabel.setText("İlgili içerik için bir atlas slaytı açın.");
            stainsStrip.getChildren().clear();
            caseStrip.getChildren().clear();
            return;
        }
        headerLabel.setText(open.getTitle());
        List<AtlasCase> catalog = AtlasCatalog.loadBundled();
        fillStrip(stainsStrip, RelatedContent.otherStains(catalog, open), true);
        fillStrip(caseStrip, RelatedContent.sameCategoryCases(catalog, open), false);
    }

    /** Clears {@code strip} and rebuilds it with one clickable tile per item ("—" if empty). */
    private void fillStrip(HBox strip, List<AtlasCase> items, boolean captionIsStain) {
        strip.getChildren().clear();
        if (items.isEmpty()) {
            strip.getChildren().add(new Label("—"));
            return;
        }
        for (AtlasCase c : items)
            strip.getChildren().add(buildTile(c, captionIsStain));
    }

    /**
     * One clickable thumbnail tile: an image (background-loaded, blank if there's no thumbnail
     * URL) over a caption, wrapped in a {@link Button} so the whole tile is one click target.
     */
    private Button buildTile(AtlasCase c, boolean captionIsStain) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(THUMB_W);
        imageView.setPreserveRatio(true);
        try {
            if (!c.getThumbUrl().isBlank())
                // Background-loading idiom (AtlasBrowser.updatePreview): JavaFX fetches this off
                // the calling thread natively, so no manual Thread is needed here.
                imageView.setImage(new Image(c.getThumbUrl(), THUMB_W, 0, true, true, true));
        } catch (Exception ex) {
            // Leave the ImageView blank — a bad thumbnail URL shouldn't break the tile.
        }

        Label captionLabel = new Label(captionIsStain ? stainLabel(c) : c.getTitle());
        captionLabel.setWrapText(true);
        captionLabel.setMaxWidth(THUMB_W);
        captionLabel.setStyle("-fx-font-size: 11px;");

        VBox content = new VBox(4, imageView, captionLabel);
        content.setPadding(new Insets(4));

        Button tile = new Button();
        tile.setGraphic(content);
        tile.setTooltip(new Tooltip(c.getTitle()));
        tile.setOnAction(e -> swapTo(c));
        return tile;
    }

    /** The stain name to caption a "other stains" tile with: {@code stainname}, else {@code image}. */
    private static String stainLabel(AtlasCase c) {
        String s = c.getStainname();
        return s != null && !s.isBlank() ? s : c.getImage();
    }

    /**
     * Swaps the active viewer's content to {@code target} — the guarded action.
     * <p>
     * {@link CaseCompare#openInto} does not itself prompt to save, so an outgoing image with
     * unsaved changes must be confirmed here first; skipping this check would silently discard
     * work via {@link QuPathViewer#setImageData}, which bypasses QuPath's own save prompt. Must
     * run on the FX thread.
     */
    private void swapTo(AtlasCase target) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null)
            return;
        ImageData<BufferedImage> outgoing = viewer.getImageData();
        if (outgoing != null && CaseCompare.isChangedSafe(outgoing) && !confirmSwap())
            return;
        // Off-thread build + Platform.runLater setImageData; on success, imageDataProperty fires
        // and the auto-follow listener above rebuilds the strips for the new case automatically.
        CaseCompare.openInto(viewer, target);
    }

    /** Same confirm-replace shape as {@link CaseCompare#confirmReplace}: a plain OK/CANCEL alert. */
    private boolean confirmSwap() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Açık slaytta kaydedilmemiş değişiklikler var. Başka bir slaytla değiştirilsin mi?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (qupath.getStage() != null)
            confirm.initOwner(qupath.getStage());
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
