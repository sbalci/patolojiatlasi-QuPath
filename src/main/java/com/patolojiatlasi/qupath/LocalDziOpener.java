package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Opens a Deep Zoom pyramid that lives in a local folder (a {@code .dzi} file next to its
 * {@code <name>_files} directory), rather than on the atlas server.
 * <p>
 * This exists as an explicit command because QuPath's drag-and-drop and File&nbsp;&rarr;&nbsp;Open
 * paths may filter by known image extensions before any {@link qupath.lib.images.servers.ImageServerBuilder}
 * is consulted, so a high support level alone does not guarantee a {@code .dzi} reaches
 * {@link com.patolojiatlasi.qupath.dzi.DziImageServerBuilder}. Going through
 * {@link DziImageServer} directly always works.
 * <p>
 * When a project is open the slide is added to it, because <b>annotations drawn on a slide opened
 * without a project live only in memory and are lost on close</b>. That is the single most common
 * way to lose annotation work, so the dialog says so rather than failing quietly.
 */
public final class LocalDziOpener {

    private static final Logger logger = LoggerFactory.getLogger(LocalDziOpener.class);

    private LocalDziOpener() {}

    public static void show(QuPathGUI qupath) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Yerel Deep Zoom (.dzi) dosyası seç");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Deep Zoom (*.dzi)", "*.dzi"));
        File file = fc.showOpenDialog(qupath.getStage());
        if (file == null)
            return;
        open(qupath, file.toURI());
    }

    /** Open a local {@code .dzi} URI, adding it to the current project when there is one. */
    public static void open(QuPathGUI qupath, URI uri) {
        Thread t = new Thread(() -> {
            try {
                DziImageServer server = new DziImageServer(uri);
                String name = server.getMetadata().getName();
                boolean calibrated = server.getPixelCalibration().hasPixelSizeMicrons();
                double mpp = server.getPixelCalibration().getAveragedPixelSizeMicrons();

                Project<BufferedImage> project = qupath.getProject();
                if (project != null) {
                    ProjectImageEntry<BufferedImage> entry = AtlasProjectService.addServerToProject(
                            project, server, name, ImageData.ImageType.UNSET);
                    project.syncChanges();
                    Platform.runLater(() -> {
                        try {
                            qupath.refreshProject();
                        } catch (Throwable ignore) {
                            // API differences across versions; the project is still updated on disk.
                        }
                        try {
                            if (qupath.openImageEntry(entry))
                                info(qupath, calibrationMessage(name, calibrated, mpp, true));
                            else
                                info(qupath, name + " projeye eklendi ancak görüntü açılmadı.");
                        } catch (Exception ex) {
                            logger.error("Could not open project entry: {}", ex.getMessage(), ex);
                            error(qupath, ex);
                        }
                    });
                } else {
                    ImageData<BufferedImage> imageData =
                            new ImageData<>(server, ImageData.ImageType.UNSET);
                    Platform.runLater(() -> {
                        try {
                            qupath.getViewer().setImageData(imageData);
                            info(qupath, calibrationMessage(name, calibrated, mpp, false));
                        } catch (Exception ex) {
                            logger.error("Failed to display local DZI {}: {}", uri, ex.getMessage(), ex);
                            error(qupath, ex);
                        }
                    });
                }
            } catch (Exception ex) {
                logger.error("Failed to open local DZI {}: {}", uri, ex.getMessage(), ex);
                Platform.runLater(() -> error(qupath, ex));
            }
        }, "atlas-local-dzi-open");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Report what was opened and, crucially, whether it is calibrated -- an uncalibrated image
     * still reports a pixel size of 1.0, which micron-based tools consume without complaint.
     */
    static String calibrationMessage(String name, boolean calibrated, double mpp, boolean inProject) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(inProject ? " projeye eklendi ve açıldı." : " açıldı.");
        sb.append("\n\n");
        if (calibrated) {
            sb.append(String.format(java.util.Locale.US,
                    "Piksel boyutu: %.4f µm/piksel (vips-properties.xml dosyasından).", mpp));
        } else {
            sb.append("Piksel boyutu bulunamadı — görüntü kalibre edilmemiş durumda. "
                    + "Ölçümler mikron yerine piksel cinsinden olur. "
                    + "\"Piksel boyutu ayarla…\" ile elle girebilirsiniz.");
        }
        if (!inProject) {
            sb.append("\n\nUYARI: Proje açık değil. Bu slayta çizeceğiniz anotasyonlar yalnızca "
                    + "bellekte tutulur ve slayt kapanınca kaybolur. Anotasyonları saklamak için "
                    + "önce bir proje açın veya oluşturun, sonra slaytı yeniden ekleyin.");
        }
        return sb.toString();
    }

    private static void info(QuPathGUI qupath, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText("Yerel DZI");
        alert.setTitle("Patoloji Atlası");
        alert.getDialogPane().setPrefWidth(520);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }

    private static void error(QuPathGUI qupath, Exception ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR,
                "Yerel DZI açılamadı:\n\n" + ex.getMessage()
                + "\n\n.dzi dosyasının yanında aynı adlı \"_files\" klasörünün bulunduğundan emin olun.");
        alert.setHeaderText("Yerel DZI");
        alert.getDialogPane().setPrefWidth(520);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }
}
