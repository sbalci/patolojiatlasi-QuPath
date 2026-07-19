package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.Locale;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import com.patolojiatlasi.qupath.AtlasCitation.CitationContext;

import qupath.lib.gui.QuPathGUI;

/**
 * Modal dialog that cites a single atlas slide in BibTeX / RIS / plain-text form, with
 * copy-to-clipboard and save-to-file actions.
 * <p>
 * The catalogue commit SHA in {@link AtlasCitation.CitationContext} comes from a network call
 * ({@link ProvenanceService#resolveContext()}), so the dialog renders immediately with a
 * placeholder context (today's date, the extension version, no SHA) and re-renders once the real
 * context arrives from a background thread — the dialog is never blank waiting on the network,
 * and never blocks the FX thread either.
 */
public final class CitationDialog {

    private static final String FMT_BIBTEX = "BibTeX";
    private static final String FMT_RIS = "RIS";
    private static final String FMT_TEXT = "Düz metin";

    private CitationDialog() {}

    /** Build and show the modal dialog for {@code c}. Must be called on the JavaFX thread. */
    public static void show(QuPathGUI qupath, AtlasCase c) {
        if (c == null)
            return;

        Stage stage = new Stage();
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setTitle("Alıntıla — " + c.getTitle());

        ComboBox<String> formatBox = new ComboBox<>();
        formatBox.getItems().addAll(FMT_BIBTEX, FMT_RIS, FMT_TEXT);
        formatBox.getSelectionModel().select(FMT_BIBTEX);

        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(18);
        textArea.setPrefColumnCount(64);

        // Cached context, touched only on the FX thread: a no-network placeholder first, replaced
        // once the background SHA lookup completes (or fails/times out and stays null).
        CitationContext[] ctxHolder = {
                new CitationContext(LocalDate.now(), ProvenanceService.resolveExtensionVersion(), null)
        };

        Runnable render = () -> textArea.setText(renderText(formatBox.getValue(), c, ctxHolder[0]));
        formatBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> render.run());
        render.run();   // placeholder render immediately — dialog is never blank

        Button copyBtn = new Button("Panoya kopyala");
        copyBtn.setOnAction(e -> ProvenanceService.copyToClipboard(textArea.getText()));

        Button saveBtn = new Button("Kaydet…");
        saveBtn.setOnAction(e -> ProvenanceService.saveTextFile(
                suggestedFileName(c, formatBox.getValue()), textArea.getText(), stage));

        Button closeBtn = new Button("Kapat");
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, copyBtn, saveBtn, spacer, closeBtn);

        VBox root = new VBox(8, new Label("Biçim:"), formatBox, textArea, actions);
        root.setPadding(new Insets(12));
        VBox.setVgrow(textArea, Priority.ALWAYS);

        stage.setScene(new Scene(root, 560, 440));

        // Resolve the full context (incl. the best-effort GitHub catalogue-SHA lookup) off the FX
        // thread; re-render with it once it lands. showAndWait() below still pumps this thread's
        // Platform.runLater callback while the dialog is modal-blocked, same as
        // ProjectBuilderDialog's background-build pattern.
        Thread t = new Thread(() -> {
            CitationContext resolved = ProvenanceService.resolveContext();
            Platform.runLater(() -> {
                ctxHolder[0] = resolved;
                render.run();
            });
        }, "atlas-citation-context");
        t.setDaemon(true);
        t.start();

        stage.showAndWait();
    }

    private static String renderText(String format, AtlasCase c, CitationContext ctx) {
        if (FMT_RIS.equals(format))
            return AtlasCitation.ris(c, ctx);
        if (FMT_TEXT.equals(format))
            return AtlasCitation.plainText(c, ctx);
        return AtlasCitation.bibtex(c, ctx);
    }

    private static String suggestedFileName(AtlasCase c, String format) {
        String base = slug(c.getReponame());
        String stain = slug(c.getImage());
        if (!stain.isEmpty())
            base = base.isEmpty() ? stain : base + "-" + stain;
        if (base.isEmpty())
            base = "atlas-alinti";
        String ext = FMT_RIS.equals(format) ? "ris" : FMT_TEXT.equals(format) ? "txt" : "bib";
        return base + "." + ext;
    }

    private static String slug(String s) {
        if (s == null)
            return "";
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        r = r.replaceAll("^-+", "").replaceAll("-+$", "");
        return r;
    }
}
