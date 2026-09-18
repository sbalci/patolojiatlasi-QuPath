package com.patolojiatlasi.qupath.regions;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * Menu commands for {@code <slide>.regions.json}: writing the named teaching regions of the open
 * slide out beside its Deep Zoom pyramid, and reading one back in to revise it.
 *
 * @see RegionsExport for the file format and the coordinate contract
 */
public final class RegionsCommands {

    private static final Logger logger = LoggerFactory.getLogger(RegionsCommands.class);

    private RegionsCommands() {}

    // --- export ----------------------------------------------------------------------

    public static void export(QuPathGUI qupath) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            info(qupath, "Önce bir slayt açın.");
            return;
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        List<PathObject> annotations = hierarchy.getAnnotationObjects().stream()
                .filter(a -> a.getROI() != null)
                .toList();
        if (annotations.isEmpty()) {
            info(qupath, "Bu slaytta dışa aktarılacak anotasyon yok.\n\n"
                    + "Bölge adları web sitesinde ve MCP sunucusunda görüneceği için, her anotasyona "
                    + "bir ad verin. İki dilli ad için \"Lenfoid agregat | Lymphoid aggregate\" "
                    + "biçimini kullanabilirsiniz.");
            return;
        }

        ImageServer<BufferedImage> server = imageData.getServer();
        URI uri = firstUri(server);
        String slideName = slideName(uri);

        FileChooser fc = new FileChooser();
        fc.setTitle("Bölgeleri dışa aktar");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Atlas bölgeleri (*.json)", "*.json"));
        fc.setInitialFileName((slideName == null ? "regions" : slideName) + RegionsExport.SUFFIX);
        // A local DZI keeps its regions file beside the pyramid, which is where every consumer
        // (web viewer, catalogue build, this extension) looks for it.
        Path beside = localFolder(uri);
        if (beside != null)
            fc.setInitialDirectory(beside.toFile());

        File target = fc.showSaveDialog(qupath.getStage());
        if (target == null)
            return;

        var cal = server.getPixelCalibration();
        Double mpp = cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : null;

        String json = RegionsExport.toJson(annotations, slideId(uri),
                server.getWidth(), server.getHeight(), mpp,
                "QuPath " + GeneralTools.getVersion(), LocalDate.now().toString(), true);

        try {
            Files.writeString(target.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not write regions file: {}", e.getMessage(), e);
            error(qupath, "Bölge dosyası yazılamadı:\n\n" + e.getMessage());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(annotations.size()).append(" bölge dışa aktarıldı:\n")
          .append(target.getAbsolutePath()).append("\n\n")
          .append(String.format(Locale.US, "Koordinatlar tam çözünürlükte piksel (%d x %d).",
                  server.getWidth(), server.getHeight()));
        if (mpp == null) {
            sb.append("\n\nNot: Görüntü kalibre edilmemiş, bu yüzden dosyaya µm/piksel yazılmadı.");
        }
        sb.append("\n\nDosyayı slaytın .dzi dosyasının yanına koyarsanız, OpenSeadragon görüntüleyici "
                + "ve atlas MCP sunucusu bölgeleri otomatik olarak bulur.");
        info(qupath, sb.toString());
    }

    // --- import ----------------------------------------------------------------------

    public static void importRegions(QuPathGUI qupath) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            info(qupath, "Önce bir slayt açın.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Bölgeleri içe aktar");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Atlas bölgeleri (*.json)", "*.json"));
        Path beside = localFolder(firstUri(imageData.getServer()));
        if (beside != null)
            fc.setInitialDirectory(beside.toFile());
        File source = fc.showOpenDialog(qupath.getStage());
        if (source == null)
            return;

        RegionsExport.Parsed parsed;
        try {
            parsed = RegionsExport.fromJson(Files.readString(source.toPath(), StandardCharsets.UTF_8),
                    null);
        } catch (Exception e) {
            logger.error("Could not read regions file: {}", e.getMessage(), e);
            error(qupath, "Bölge dosyası okunamadı:\n\n" + e.getMessage());
            return;
        }

        ImageServer<BufferedImage> server = imageData.getServer();
        // Regions are plain pixel coordinates, so placing them on a differently-sized export would
        // put every one of them on the wrong tissue -- silently. Make the user confirm.
        if (parsed.width() > 0 && parsed.height() > 0
                && (parsed.width() != server.getWidth() || parsed.height() != server.getHeight())) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, String.format(Locale.US,
                    "Bu bölge dosyası %d x %d piksellik bir görüntü için hazırlanmış, "
                    + "ancak açık slayt %d x %d piksel.\n\n"
                    + "Koordinatlar ölçeklenmez; bölgeler yanlış yere düşer. Yine de içe aktarılsın mı?",
                    parsed.width(), parsed.height(), server.getWidth(), server.getHeight()),
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Boyut uyuşmuyor");
            alert.getDialogPane().setPrefWidth(520);
            if (qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES)
                return;
        }

        if (parsed.annotations().isEmpty()) {
            info(qupath, "Dosyada içe aktarılacak bölge bulunamadı.");
            return;
        }

        // Importing a file that was exported from this same slide is the normal way to revise
        // regions, so the common case is that most of them are already here. Adding blindly would
        // silently double every one of them, with no obvious way back.
        var hierarchy = imageData.getHierarchy();
        Set<String> incoming = new LinkedHashSet<>();
        for (PathObject a : parsed.annotations())
            incoming.add(RegionsExport.shortId(a));
        List<PathObject> existingDuplicates = hierarchy.getAnnotationObjects().stream()
                .filter(a -> incoming.contains(RegionsExport.shortId(a)))
                .toList();

        if (!existingDuplicates.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    existingDuplicates.size() + " bölge bu slaytta zaten var.\n\n"
                    + "\"Evet\": mevcut olanlar dosyadaki sürümle değiştirilir.\n"
                    + "\"Hayır\": içe aktarma iptal edilir (kopya oluşmaz).",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Bu bölgeler zaten var");
            alert.getDialogPane().setPrefWidth(520);
            if (qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES)
                return;
            hierarchy.removeObjects(existingDuplicates, true);
        }

        hierarchy.addObjects(parsed.annotations());
        info(qupath, parsed.annotations().size() + " bölge içe aktarıldı"
                + (existingDuplicates.isEmpty() ? "." : " (" + existingDuplicates.size() + " tanesi güncellendi)."));
    }

    // --- helpers ---------------------------------------------------------------------

    static URI firstUri(ImageServer<BufferedImage> server) {
        try {
            var uris = server.getURIs();
            return uris.isEmpty() ? null : uris.iterator().next();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code .../lymphocytic-gastritis/HE.dzi} -> {@code HE}. */
    static String slideName(URI uri) {
        if (uri == null || uri.getPath() == null)
            return null;
        String[] parts = uri.getPath().split("/");
        if (parts.length == 0)
            return null;
        String last = parts[parts.length - 1];
        int dot = last.lastIndexOf('.');
        String name = dot > 0 ? last.substring(0, dot) : last;
        return name.isBlank() ? null : name;
    }

    /** {@code .../lymphocytic-gastritis/HE.dzi} -> {@code lymphocytic-gastritis/HE}. */
    static String slideId(URI uri) {
        String name = slideName(uri);
        if (name == null || uri.getPath() == null)
            return null;
        String[] parts = uri.getPath().split("/");
        return parts.length >= 2 ? parts[parts.length - 2] + "/" + name : name;
    }

    /** Folder holding a local {@code .dzi}, or null when the slide is remote. */
    static Path localFolder(URI uri) {
        if (uri == null || uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("file"))
            return null;
        try {
            Path parent = new File(uri).toPath().getParent();
            return parent != null && Files.isDirectory(parent) ? parent : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void info(QuPathGUI qupath, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText("Bölgeler");
        alert.setTitle("Patoloji Atlası");
        alert.getDialogPane().setPrefWidth(540);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }

    private static void error(QuPathGUI qupath, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText("Bölgeler");
        alert.getDialogPane().setPrefWidth(540);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }
}
