package com.patolojiatlasi.qupath;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.Locale;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import com.patolojiatlasi.qupath.AtlasCitation.CitationContext;
import com.patolojiatlasi.qupath.AtlasCitation.Viewport;
import com.patolojiatlasi.qupath.quiz.QuizGeometry;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * Modal dialog that cites the currently-selected annotation on the active viewer's atlas slide as
 * a "figure card": the slide citation line, the framing (downsample + viewport center), an
 * editable caption, and the region's geometry as GeoJSON — everything needed to caption and cite
 * a figure taken from an atlas slide.
 * <p>
 * Mirrors {@link CitationDialog}'s off-thread {@link CitationContext} resolution: the dialog
 * renders immediately with a no-network placeholder context, then re-renders once the background
 * catalogue-SHA lookup ({@link ProvenanceService#resolveContext()}) completes.
 */
public final class FigureCitationDialog {

    private FigureCitationDialog() {}

    /**
     * Resolves the active viewer's atlas case and its currently-selected annotation, then shows
     * the figure-citation dialog. Must be called on the JavaFX thread.
     * <p>
     * No-ops with an informational alert if the active viewer isn't showing a cataloged atlas
     * slide, or if there is no selected annotation (or the selection has no {@link ROI}).
     */
    public static void show(QuPathGUI qupath) {
        if (qupath == null)
            return;

        AtlasCase c = AtlasExtension.resolveOpenCase(qupath);
        if (c == null) {
            new Alert(Alert.AlertType.INFORMATION, "Açık slayt atlas kataloğunda bulunamadı.").showAndWait();
            return;
        }

        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> data = viewer == null ? null : viewer.getImageData();
        var hierarchy = data == null ? null : data.getHierarchy();
        PathObject selected = hierarchy == null ? null : hierarchy.getSelectionModel().getSelectedObject();
        ROI roi = selected == null ? null : selected.getROI();
        if (roi == null) {
            new Alert(Alert.AlertType.INFORMATION, "Önce slayt üzerinde bir bölge (anotasyon) seçin.").showAndWait();
            return;
        }

        // Viewport framing: downsample from the viewer, center from the displayed-region bounds —
        // the same accessor FocusHeatmap samples every tick (viewer.getDisplayedRegionShape()).
        double downsample = viewer.getDownsampleFactor();
        Shape shape = viewer.getDisplayedRegionShape();
        Rectangle b = shape == null ? new Rectangle() : shape.getBounds();
        Viewport vp = new Viewport(downsample, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0);
        String geoJson = QuizGeometry.toGeoJson(roi);

        Stage stage = new Stage();
        if (qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setTitle("Bu bölgeyi alıntıla — " + c.getTitle());

        TextField captionField = new TextField();
        captionField.setPromptText("Şekil açıklaması (opsiyonel)…");

        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(18);
        textArea.setPrefColumnCount(64);

        // Cached context, touched only on the FX thread: a no-network placeholder first, replaced
        // once the background SHA lookup completes (or fails/times out and stays null) — same
        // pattern as CitationDialog.
        CitationContext[] ctxHolder = {
                new CitationContext(LocalDate.now(), ProvenanceService.resolveExtensionVersion(), null)
        };

        Runnable render = () -> textArea.setText(
                AtlasCitation.figureCitationCard(c, ctxHolder[0], vp, captionField.getText(), geoJson));
        captionField.textProperty().addListener((obs, old, val) -> render.run());
        render.run();   // placeholder render immediately — dialog is never blank

        Button copyBtn = new Button("Panoya kopyala");
        copyBtn.setOnAction(e -> ProvenanceService.copyToClipboard(textArea.getText()));

        Button saveBtn = new Button("Kaydet…");
        saveBtn.setOnAction(e -> ProvenanceService.saveTextFile(suggestedFileName(c), textArea.getText(), stage));

        Button closeBtn = new Button("Kapat");
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, copyBtn, saveBtn, spacer, closeBtn);

        VBox root = new VBox(8, new Label("Şekil açıklaması:"), captionField, textArea, actions);
        root.setPadding(new Insets(12));
        VBox.setVgrow(textArea, Priority.ALWAYS);

        stage.setScene(new Scene(root, 560, 460));

        // Resolve the full context (incl. the best-effort GitHub catalogue-SHA lookup) off the FX
        // thread; re-render with it once it lands. showAndWait() below still pumps this thread's
        // Platform.runLater callback while the dialog is modal-blocked.
        Thread t = new Thread(() -> {
            CitationContext resolved = ProvenanceService.resolveContext();
            Platform.runLater(() -> {
                ctxHolder[0] = resolved;
                render.run();
            });
        }, "atlas-figure-citation-context");
        t.setDaemon(true);
        t.start();

        stage.showAndWait();
    }

    private static String suggestedFileName(AtlasCase c) {
        String base = slug(c.getReponame());
        String stain = slug(c.getImage());
        if (!stain.isEmpty())
            base = base.isEmpty() ? stain : base + "-" + stain;
        if (base.isEmpty())
            base = "atlas";
        return base + "-figure.txt";
    }

    private static String slug(String s) {
        if (s == null)
            return "";
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        r = r.replaceAll("^-+", "").replaceAll("-+$", "");
        return r;
    }
}
