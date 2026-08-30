package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

/**
 * Set the pixel size (µm/px) of the image that is currently open.
 *
 * <p><b>Why this exists.</b> Atlas slides are served as Deep Zoom (DZI) pyramids, and
 * {@code vips dzsave} does not carry microns-per-pixel through, so a slide opens
 * <b>uncalibrated</b> unless a value is supplied via the catalogue ({@code "mpp"} /
 * {@code "defaultMpp"}) or a {@code ?mpp=} query on the URL. On an uncalibrated image QuPath's
 * {@code PixelCalibration} keeps its default of {@code 1} with unit {@code "px"} — so
 * {@code hasPixelSizeMicrons()} is {@code false}, but {@code getAveragedPixelSize()} still returns
 * {@code 1.0}.
 *
 * <p>Tools that read the averaged value without first checking {@code hasPixelSizeMicrons()} then
 * behave as if 1 pixel were 1 micron. The WSInfer extension is a verified example: it computes
 * {@code downsample = spacing_um_px / averagedPixelSize} with no calibration check, so a model
 * trained at 0.5 µm/px silently runs at roughly twice the intended magnification and the run
 * completes with no error. Cell detection takes the other shape — it checks, and returns
 * {@code NaN}. Because downstream tools fail in different ways for the same missing value, the
 * calibration is best fixed once, here, on the open image.
 *
 * <p>Nothing is applied automatically: the atlas never imposes a pixel size it has not been given,
 * because a wrong calibration is worse than none.
 *
 * <p>The calculator mirrors {@code docs/pixel-size-mpp.md} exactly:
 * {@code mpp_export = mpp_original × (width_original / width_export)}.
 */
public final class PixelSizeDialog {

    private static final Logger logger = LoggerFactory.getLogger(PixelSizeDialog.class);

    /** Documented lab scanner defaults (see docs/pixel-size-mpp.md). Fallbacks, not per-slide truth. */
    private static final String PRESET_GT450 = "Leica Aperio GT450 — 0.26 µm/px (40×)";
    private static final String PRESET_AT2 = "Leica Aperio AT2 — 0.25 µm/px (40×)";
    private static final String PRESET_CUSTOM = "Diğer / elle gir";

    private PixelSizeDialog() {}

    /** Show the dialog for the image open in the active viewer. */
    public static void show(QuPathGUI qupath) {
        QuPathViewer viewer = qupath == null ? null : qupath.getViewer();
        ImageData<BufferedImage> imageData = viewer == null ? null : viewer.getImageData();
        if (imageData == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Önce bir görüntü açın — piksel boyutu açık görüntüye uygulanır.");
            alert.setTitle("Piksel boyutu ayarla");
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }
        buildStage(qupath, viewer, imageData).show();
    }

    private static Stage buildStage(QuPathGUI qupath, QuPathViewer viewer,
                                    ImageData<BufferedImage> imageData) {

        Stage stage = new Stage();
        stage.setTitle("Piksel boyutu ayarla (µm/px)");

        // ── Current state, stated honestly ──────────────────────────────────
        var cal = imageData.getServer().getPixelCalibration();
        boolean calibrated = cal != null && cal.hasPixelSizeMicrons();

        Label state = new Label(calibrated
                ? String.format(Locale.US, "Şu an kalibre: %.4f µm/px", cal.getAveragedPixelSizeMicrons())
                : "Şu an KALİBRE DEĞİL — QuPath 1 pikseli 1 mikron sayar.");
        state.setStyle("-fx-font-weight: bold;");

        Label consequence = new Label(calibrated
                ? "Alan, uzunluk ve yoğunluk ölçümleri ile µm tabanlı modeller bu değere göre çalışır."
                : "Bu durumda µm tabanlı araçların tamamı yanlış ölçekte çalışır: WSInfer modeli\n"
                  + "eğitildiğinden farklı bir büyütmede çıkarım yapar ve bunu size bildirmez;\n"
                  + "hücre tespiti ise µm parametrelerini çözemez. Alan (mm²) ölçümleri de yanlış olur.");
        consequence.setWrapText(true);
        consequence.setStyle("-fx-opacity: 0.8;");

        // ── Mode: direct entry vs calculator ────────────────────────────────
        ToggleGroup mode = new ToggleGroup();
        RadioButton directMode = new RadioButton("Doğrudan gir (µm/px değeri elimde)");
        RadioButton calcMode = new RadioButton("Hesapla (tarayıcı µm/px + dışa aktarma küçültmesi)");
        directMode.setToggleGroup(mode);
        calcMode.setToggleGroup(mode);
        directMode.setSelected(true);

        TextField directField = new TextField();
        directField.setPromptText("ör. 0.5042");

        ComboBox<String> preset = new ComboBox<>();
        preset.getItems().addAll(PRESET_GT450, PRESET_AT2, PRESET_CUSTOM);
        preset.setValue(PRESET_GT450);

        TextField origMppField = new TextField("0.26");
        TextField origWidthField = new TextField();
        origWidthField.setPromptText("orijinal genişlik (px) ya da tarama büyütmesi");
        TextField expWidthField = new TextField();
        expWidthField.setPromptText("dışa aktarılan genişlik (px) ya da dışa aktarma büyütmesi");

        Label calcResult = new Label("—");
        calcResult.setStyle("-fx-font-family: monospace;");

        preset.valueProperty().addListener((obs, was, now) -> {
            if (PRESET_GT450.equals(now))
                origMppField.setText("0.26");
            else if (PRESET_AT2.equals(now))
                origMppField.setText("0.25");
            origMppField.setDisable(false);
        });

        Runnable recompute = () -> {
            Double mpp = computeFromRatio(origMppField.getText(), origWidthField.getText(), expWidthField.getText());
            calcResult.setText(mpp == null
                    ? "—"
                    : String.format(Locale.US, "mpp = %s × (%s / %s) = %.4f µm/px",
                            origMppField.getText().trim(), origWidthField.getText().trim(),
                            expWidthField.getText().trim(), mpp));
        };
        origMppField.textProperty().addListener((o, w, n) -> recompute.run());
        origWidthField.textProperty().addListener((o, w, n) -> recompute.run());
        expWidthField.textProperty().addListener((o, w, n) -> recompute.run());

        GridPane calcGrid = new GridPane();
        calcGrid.setHgap(8);
        calcGrid.setVgap(6);
        calcGrid.addRow(0, new Label("Tarayıcı:"), preset);
        calcGrid.addRow(1, new Label("Orijinal µm/px:"), origMppField);
        calcGrid.addRow(2, new Label("Orijinal genişlik / büyütme:"), origWidthField);
        calcGrid.addRow(3, new Label("Dışa aktarılan genişlik / büyütme:"), expWidthField);
        calcGrid.addRow(4, new Label("Sonuç:"), calcResult);

        Label calcNote = new Label(
                "Dışa aktarma küçültme yaptıysa (ImageScope \"Export Image\"), her dışa aktarılmış piksel\n"
                + "birden fazla taranmış pikseli kapsar — bu yüzden dışa aktarılan görüntünün µm/px değeri\n"
                + "orijinalinden BÜYÜKTÜR. Oran yerine büyütme de yazabilirsiniz (40 ve 20 → 2).");
        calcNote.setWrapText(true);
        calcNote.setStyle("-fx-opacity: 0.75; -fx-font-size: 11px;");

        VBox calcBox = new VBox(6, calcGrid, calcNote);
        calcBox.disableProperty().bind(calcMode.selectedProperty().not());
        directField.disableProperty().bind(directMode.selectedProperty().not());

        // ── Actions ─────────────────────────────────────────────────────────
        Button applySession = new Button("Bu oturum için uygula");
        Button copyUrl = new Button("Kalıcı ?mpp= URL'sini kopyala");
        Button reopen = new Button("Kalıcı URL ile yeniden aç");
        Button close = new Button("Kapat");
        close.setDefaultButton(true);

        Label durability = new Label(
                "\"Bu oturum için uygula\" YALNIZCA şu an açık görüntü için ve yalnızca bu oturumda geçerlidir;\n"
                + "görüntüyü kapatıp yeniden açtığınızda kaybolur. Kalıcı çözüm, DZI adresine ?mpp= eklemektir —\n"
                + "bu değer görüntü her açıldığında yeniden okunur.");
        durability.setWrapText(true);
        durability.setStyle("-fx-opacity: 0.8; -fx-font-size: 11px;");

        applySession.setOnAction(e -> {
            Double mpp = resolveValue(directMode.isSelected(), directField, origMppField, origWidthField, expWidthField);
            if (mpp == null) {
                warn("Geçerli bir µm/px değeri girin (0'dan büyük bir sayı).");
                return;
            }
            try {
                ImageServerMetadata updated = new ImageServerMetadata.Builder(imageData.getServer().getMetadata())
                        .pixelSizeMicrons(mpp, mpp)
                        .build();
                imageData.updateServerMetadata(updated);
                viewer.repaint();
                state.setText(String.format(Locale.US, "Şu an kalibre: %.4f µm/px (bu oturum için)", mpp));
                consequence.setText("Uygulandı. Görüntüyü yeniden açtığınızda bu değer kaybolur —\n"
                        + "kalıcı olması için ?mpp= URL'sini kullanın.");
                logger.info("Applied session pixel size {} µm/px to open image", mpp);
            } catch (Exception ex) {
                logger.error("Could not apply pixel size", ex);
                warn("Piksel boyutu uygulanamadı: " + ex.getMessage());
            }
        });

        copyUrl.setOnAction(e -> {
            Double mpp = resolveValue(directMode.isSelected(), directField, origMppField, origWidthField, expWidthField);
            URI uri = mppUri(imageData, mpp);
            if (uri == null) {
                warn("Kalıcı URL üretilemedi — geçerli bir değer girin ve görüntünün bir DZI adresi olduğundan emin olun.");
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(uri.toString());
            Clipboard.getSystemClipboard().setContent(content);
            durability.setText("Kopyalandı: " + uri);
        });

        reopen.setOnAction(e -> {
            Double mpp = resolveValue(directMode.isSelected(), directField, origMppField, origWidthField, expWidthField);
            URI uri = mppUri(imageData, mpp);
            if (uri == null) {
                warn("Yeniden açılamadı — geçerli bir değer girin ve görüntünün bir DZI adresi olduğundan emin olun.");
                return;
            }
            if (qupath.getProject() != null) {
                warn("Bu görüntü bir proje girdisi olabilir. Projedeki girdinin adresi değiştirilmez;\n"
                     + "kalıcı düzeltme için vakayı Atlas kataloğundan ?mpp= değeriyle yeniden ekleyin,\n"
                     + "ya da \"Bu oturum için uygula\" ile devam edin.");
                return;
            }
            try {
                DziImageServer server = new DziImageServer(uri);
                ImageData<BufferedImage> reopened = new ImageData<>(server, imageData.getImageType());
                viewer.setImageData(reopened);
                logger.info("Reopened atlas slide with calibration: {}", uri);
                stage.close();
            } catch (Exception ex) {
                logger.error("Could not reopen with ?mpp=", ex);
                warn("Yeniden açılamadı: " + ex.getMessage());
            }
        });

        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, applySession, copyUrl, reopen, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10,
                state, consequence, new Separator(),
                directMode, directField,
                calcMode, calcBox, new Separator(),
                durability, buttons);
        root.setPadding(new Insets(14));
        root.setPrefWidth(620);

        stage.setScene(new Scene(root));
        if (qupath != null && qupath.getStage() != null)
            stage.initOwner(qupath.getStage());
        return stage;
    }

    /** Resolve the value the user actually intends, from whichever entry mode is active. */
    private static Double resolveValue(boolean direct, TextField directField,
                                       TextField origMpp, TextField origWidth, TextField expWidth) {
        if (direct)
            return positiveOrNull(directField.getText());
        return computeFromRatio(origMpp.getText(), origWidth.getText(), expWidth.getText());
    }

    /**
     * {@code mpp_export = mpp_original × (width_original / width_export)} — the formula from
     * docs/pixel-size-mpp.md. Widths may equally be magnifications (40 and 20 give the same ratio).
     */
    private static Double computeFromRatio(String origMpp, String origWidth, String expWidth) {
        Double m = positiveOrNull(origMpp);
        Double wo = positiveOrNull(origWidth);
        Double we = positiveOrNull(expWidth);
        if (m == null || wo == null || we == null)
            return null;
        double result = m * (wo / we);
        return result > 0 && Double.isFinite(result) ? result : null;
    }

    /** Parse a positive, finite number; accepts both {@code 0.5} and {@code 0,5}. */
    private static Double positiveOrNull(String text) {
        if (text == null)
            return null;
        String t = text.trim().replace(',', '.');
        if (t.isEmpty())
            return null;
        try {
            double v = Double.parseDouble(t);
            return v > 0 && Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Build the durable {@code ?mpp=} form of the open image's URI — the same mechanism
     * {@link AtlasCase#getDziURI()} uses, re-parsed by {@link DziImageServer} on every open.
     * Any existing {@code mpp} query parameter is replaced rather than appended twice.
     */
    private static URI mppUri(ImageData<BufferedImage> imageData, Double mpp) {
        if (mpp == null)
            return null;
        try {
            var uris = imageData.getServer().getURIs();
            if (uris == null || uris.isEmpty())
                return null;
            String base = uris.iterator().next().toString();
            int q = base.indexOf('?');
            if (q >= 0) {
                StringBuilder kept = new StringBuilder();
                for (String kv : base.substring(q + 1).split("&")) {
                    int i = kv.indexOf('=');
                    String key = i > 0 ? kv.substring(0, i) : kv;
                    if (!key.equalsIgnoreCase("mpp") && !kv.isEmpty()) {
                        if (kept.length() > 0)
                            kept.append('&');
                        kept.append(kv);
                    }
                }
                base = kept.length() > 0
                        ? base.substring(0, q) + "?" + kept
                        : base.substring(0, q);
            }
            String sep = base.indexOf('?') >= 0 ? "&" : "?";
            return URI.create(base + sep + "mpp=" + String.format(Locale.US, "%s", mpp));
        } catch (Exception e) {
            logger.warn("Could not build ?mpp= URI", e);
            return null;
        }
    }

    private static void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setTitle("Piksel boyutu ayarla");
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
