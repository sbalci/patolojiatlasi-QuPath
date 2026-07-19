package com.patolojiatlasi.qupath;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.CoverageStats.CategoryRow;
import com.patolojiatlasi.qupath.CoverageStats.CoverageMatrix;
import com.patolojiatlasi.qupath.CoverageStats.StainBucket;

import qupath.lib.gui.QuPathGUI;

/**
 * Read-only catalogue-coverage &amp; QC dashboard: a category x stain-bucket matrix (slide and
 * distinct-case counts, published% and mpp-known%) computed from the bundled catalogue snapshot,
 * with an opt-in best-effort DZI link check, a double-click drill-down into the project builder
 * for a category's slice, and CSV/Markdown export for a data-availability statement.
 * <p>
 * Non-modal, matching {@link CitationDialog}'s dialog style. The catalogue load and matrix
 * computation are pure/offline (no network), so they run directly on the calling (FX) thread; only
 * the opt-in link check does network I/O, and that runs on a background daemon thread with every
 * UI mutation marshalled back via {@link Platform#runLater} and guarded by
 * {@code stage.isShowing()} so a closed dashboard is never touched by a late callback.
 */
public final class CoverageDashboard {

    private static final Logger logger = LoggerFactory.getLogger(CoverageDashboard.class);

    private static final String FMT_CSV = "CSV";
    private static final String FMT_MD = "Markdown";

    private CoverageDashboard() {}

    /** Build and show the dashboard. Must be called on the JavaFX thread. */
    public static void show(QuPathGUI qupath) {
        List<AtlasCase> all = AtlasCatalog.loadBundled();
        CoverageMatrix matrix = CoverageStats.compute(all);

        Stage stage = new Stage();
        if (qupath != null && qupath.getStage() != null)
            stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle("Katalog kapsamı ve QC");

        Label header = new Label(String.format(Locale.US,
                "Katalog kapsamı — %d slayt, %d vaka, %d kategori",
                matrix.totalSlides(), matrix.totalCases(), matrix.rows().size()));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        TableView<CategoryRow> table = buildTable(matrix);
        table.setRowFactory(tv -> {
            TableRow<CategoryRow> row = new TableRow<>();
            row.setOnMouseClicked(evt -> {
                if (evt.getButton() == MouseButton.PRIMARY && evt.getClickCount() == 2 && !row.isEmpty())
                    drillDown(qupath, stage, all, row.getItem());
            });
            return row;
        });

        Label ddHint = new Label(
                "İpucu: bir kategori satırına çift tıklayın — o kategorinin slaytlarıyla proje "
                        + "oluşturucuyu açar.");
        ddHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        GridPane totalsGrid = buildTotalsGrid(matrix);

        // --- link check ------------------------------------------------------
        Button checkBtn = new Button("Bağlantıları denetle");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        Label linkResultLabel = new Label();
        linkResultLabel.setWrapText(true);
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox linkRow = new HBox(8, checkBtn, bar);
        linkRow.setStyle("-fx-alignment: center-left;");

        checkBtn.setOnAction(e -> runLinkCheck(all, stage, checkBtn, bar, linkResultLabel));

        // --- export ------------------------------------------------------------
        ComboBox<String> formatBox = new ComboBox<>();
        formatBox.getItems().addAll(FMT_CSV, FMT_MD);
        formatBox.getSelectionModel().select(FMT_CSV);

        Button copyBtn = new Button("Panoya kopyala");
        copyBtn.setOnAction(e -> ProvenanceService.copyToClipboard(exportContent(formatBox.getValue(), matrix)));

        Button saveBtn = new Button("Kaydet…");
        saveBtn.setOnAction(e -> {
            String content = exportContent(formatBox.getValue(), matrix);
            String name = FMT_MD.equals(formatBox.getValue()) ? "atlas-coverage.md" : "atlas-coverage.csv";
            ProvenanceService.saveTextFile(name, content, stage);
        });

        Button closeBtn = new Button("Kapat");
        closeBtn.setOnAction(e -> stage.close());

        Region exportSpacer = new Region();
        HBox.setHgrow(exportSpacer, Priority.ALWAYS);
        HBox exportRow = new HBox(6, new Label("Biçim:"), formatBox, copyBtn, saveBtn, exportSpacer, closeBtn);
        exportRow.setStyle("-fx-alignment: center-left;");

        Label footerNote = new Label(
                "Sınıflandırma anahtar-kelime sezgiseldir; 'Diğer'/'Uncategorized' dürüst artık kovalardır.");
        footerNote.setWrapText(true);
        footerNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        VBox root = new VBox(10,
                header,
                table,
                totalsGrid,
                ddHint,
                new Separator(),
                linkRow,
                linkResultLabel,
                new Separator(),
                exportRow,
                footerNote);
        root.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        stage.setScene(new Scene(root, 860, 640));
        stage.show();
    }

    // --- table + totals ------------------------------------------------------

    private static TableView<CategoryRow> buildTable(CoverageMatrix matrix) {
        TableView<CategoryRow> table = new TableView<>();
        table.setPlaceholder(new Label("Katalogda kayıtlı slayt bulunamadı."));

        TableColumn<CategoryRow, String> catCol = new TableColumn<>("Kategori");
        catCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().category()));
        catCol.setPrefWidth(220);

        TableColumn<CategoryRow, Number> heCol = bucketColumn("H&E", StainBucket.HE);
        TableColumn<CategoryRow, Number> ihcCol = bucketColumn("IHK", StainBucket.IHC);
        TableColumn<CategoryRow, Number> specialCol = bucketColumn("Özel boya", StainBucket.SPECIAL);
        TableColumn<CategoryRow, Number> otherCol = bucketColumn("Diğer", StainBucket.OTHER);

        TableColumn<CategoryRow, Number> slidesCol = new TableColumn<>("Slayt");
        slidesCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().slides()));
        slidesCol.setPrefWidth(70);

        TableColumn<CategoryRow, Number> casesCol = new TableColumn<>("Vaka");
        casesCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().cases()));
        casesCol.setPrefWidth(70);

        TableColumn<CategoryRow, String> pubCol = new TableColumn<>("Yayın %");
        pubCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().publishedPct() + "%"));
        pubCol.setPrefWidth(80);

        TableColumn<CategoryRow, String> mppCol = new TableColumn<>("mpp %");
        mppCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().mppKnownPct() + "%"));
        mppCol.setPrefWidth(80);

        table.getColumns().addAll(List.of(catCol, heCol, ihcCol, specialCol, otherCol,
                slidesCol, casesCol, pubCol, mppCol));
        table.getItems().addAll(matrix.rows());
        table.setPrefHeight(320);
        return table;
    }

    private static TableColumn<CategoryRow, Number> bucketColumn(String title, StainBucket bucket) {
        TableColumn<CategoryRow, Number> col = new TableColumn<>(title);
        col.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().counts()[bucket.ordinal()]));
        col.setPrefWidth(75);
        return col;
    }

    /**
     * TableView has no built-in pinned footer row, so the TOTAL line is rendered as a separate
     * bold summary strip under the table instead of a synthetic {@link CategoryRow} — keeps it
     * visually distinct from the sortable data rows above.
     */
    private static GridPane buildTotalsGrid(CoverageMatrix m) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setPadding(new Insets(4, 4, 4, 4));
        grid.setStyle("-fx-font-weight: bold; -fx-background-color: derive(-fx-base, 60%);");

        int col = 0;
        grid.add(new Label("TOPLAM"), col++, 0);
        grid.add(new Label("H&E=" + m.colTotals()[StainBucket.HE.ordinal()]), col++, 0);
        grid.add(new Label("IHK=" + m.colTotals()[StainBucket.IHC.ordinal()]), col++, 0);
        grid.add(new Label("Özel boya=" + m.colTotals()[StainBucket.SPECIAL.ordinal()]), col++, 0);
        grid.add(new Label("Diğer=" + m.colTotals()[StainBucket.OTHER.ordinal()]), col++, 0);
        grid.add(new Label("Slayt=" + m.totalSlides()), col++, 0);
        grid.add(new Label("Vaka=" + m.totalCases()), col++, 0);
        grid.add(new Label("Yayın %=" + m.publishedPct() + "%"), col++, 0);
        grid.add(new Label("mpp %=" + m.mppKnownPct() + "%"), col, 0);
        return grid;
    }

    // --- drill-down ------------------------------------------------------------

    /** Seed the project builder with the given category's cases, in catalogue order. */
    private static void drillDown(QuPathGUI qupath, Stage owner, List<AtlasCase> all, CategoryRow row) {
        LinkedHashSet<AtlasCase> slice = all.stream()
                .filter(c -> c.getCategory().equals(row.category()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ProjectBuilderDialog.show(qupath, owner, slice, () -> {});
    }

    // --- link check ------------------------------------------------------------

    private static void runLinkCheck(List<AtlasCase> all, Stage stage, Button checkBtn,
            ProgressBar bar, Label resultLabel) {
        checkBtn.setDisable(true);
        bar.setProgress(0);
        bar.setVisible(true);
        resultLabel.setText("");

        int total = (int) all.stream().map(AtlasCase::getDziUrl).distinct().count();
        Thread t = new Thread(() -> {
            try {
                Map<String, Boolean> res = LinkCheck.checkAll(all, done ->
                        Platform.runLater(() -> {
                            if (!stage.isShowing())
                                return;
                            bar.setProgress(total <= 0 ? 1.0 : (double) done / total);
                        }));
                Platform.runLater(() -> {
                    if (!stage.isShowing())
                        return;
                    checkBtn.setDisable(false);
                    bar.setVisible(false);
                    resultLabel.setText(describeDeadLinks(all, res));
                });
            } catch (Throwable ex) {
                logger.error("Link check failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> {
                    if (!stage.isShowing())
                        return;
                    checkBtn.setDisable(false);
                    bar.setVisible(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Bağlantı denetimi başarısız oldu:\n" + ex.getMessage());
                    alert.initOwner(stage);
                    alert.showAndWait();
                });
            }
        }, "atlas-linkcheck-ui");
        t.setDaemon(true);
        t.start();
    }

    /** Maps every unreachable URL in {@code res} back to the titles of the cases that use it. */
    private static String describeDeadLinks(List<AtlasCase> all, Map<String, Boolean> res) {
        long deadCount = res.values().stream().filter(ok -> !ok).count();
        if (deadCount == 0)
            return "Tüm bağlantılar erişilebilir.";
        StringBuilder sb = new StringBuilder();
        sb.append(deadCount).append(" bağlantı erişilemedi:\n");
        for (Map.Entry<String, Boolean> entry : res.entrySet()) {
            if (entry.getValue())
                continue;
            String url = entry.getKey();
            String titles = all.stream()
                    .filter(c -> c.getDziUrl().equals(url))
                    .map(AtlasCase::getTitle)
                    .distinct()
                    .collect(Collectors.joining(", "));
            sb.append("• ").append(titles.isEmpty() ? url : titles).append(" — ").append(url).append('\n');
        }
        return sb.toString();
    }

    // --- export ------------------------------------------------------------

    private static String exportContent(String format, CoverageMatrix m) {
        return FMT_MD.equals(format) ? CoverageStats.toMarkdown(m, LocalDate.now()) : CoverageStats.toCsv(m);
    }
}
