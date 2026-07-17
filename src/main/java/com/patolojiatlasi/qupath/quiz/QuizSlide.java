package com.patolojiatlasi.qupath.quiz;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.AtlasCase;
import com.patolojiatlasi.qupath.dzi.DziImageServer;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

/**
 * Read-only slide opener shared by the quiz author and runner windows: given a slide URL
 * (the atlas's DZI url, exactly as stored in a {@link QuizQuestion}), stream it into the
 * active viewer the same way {@code AtlasBrowser}'s no-project open path does — but without
 * any project bookkeeping, since a quiz slide is opened purely for viewing.
 * <p>
 * The network build (parsing the {@code .dzi} descriptor) runs on a background daemon thread;
 * the viewer is only ever touched on the JavaFX application thread via {@link Platform#runLater}.
 */
public final class QuizSlide {

    private static final Logger logger = LoggerFactory.getLogger(QuizSlide.class);

    private QuizSlide() {}

    /**
     * Open {@code slideUrl} read-only into {@code qupath}'s active viewer. Builds the
     * {@link DziImageServer} + {@link ImageData} off the FX thread, then hands the result to the
     * viewer inside {@link Platform#runLater}. Calls {@code onDone} on success or
     * {@code onError} on failure — both always on the FX thread.
     */
    public static void openAsync(QuPathGUI qupath, String slideUrl, Runnable onDone, Consumer<Exception> onError) {
        Thread t = new Thread(() -> {
            try {
                DziImageServer server = new DziImageServer(URI.create(slideUrl));
                ImageData<BufferedImage> imageData = new ImageData<>(server, inferType(slideUrl));
                Platform.runLater(() -> {
                    try {
                        qupath.getViewer().setImageData(imageData);
                        onDone.run();
                    } catch (Exception ex) {
                        logger.error("Failed to display quiz slide {}: {}", slideUrl, ex.getMessage(), ex);
                        onError.accept(ex);
                    }
                });
            } catch (Exception ex) {
                logger.error("Failed to open quiz slide {}: {}", slideUrl, ex.getMessage(), ex);
                Platform.runLater(() -> onError.accept(ex));
            }
        }, "atlas-quiz-open");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Infer a QuPath image type from a slide URL's stain, reusing {@link AtlasCase}'s
     * stain→type heuristic ({@link AtlasCase#imageTypeForImageName(String)}) rather than
     * duplicating it. The image basename is taken from the last path segment before
     * {@code .dzi}, with any query string (e.g. {@code ?mpp=0.25}) stripped first.
     */
    public static ImageData.ImageType inferType(String slideUrl) {
        return AtlasCase.imageTypeForImageName(basename(slideUrl));
    }

    /**
     * The viewer's current server's first URI as a string, or {@code ""} if the viewer, its
     * image data, its server, or its URIs are unavailable. Used by the quiz author to bind a
     * question to the slide currently open in the viewer, and by the runner to decide whether
     * a question's slide is already open (and so a reload can be skipped).
     */
    public static String currentSlideUrl(QuPathViewer viewer) {
        try {
            if (viewer == null)
                return "";
            ImageData<BufferedImage> imageData = viewer.getImageData();
            if (imageData == null)
                return "";
            ImageServer<BufferedImage> server = imageData.getServer();
            if (server == null)
                return "";
            var uris = server.getURIs();
            if (uris == null || uris.isEmpty())
                return "";
            return uris.iterator().next().toString();
        } catch (Exception e) {
            logger.debug("Could not read current slide URL: {}", e.getMessage());
            return "";
        }
    }

    /** Last path segment of {@code slideUrl}, minus any query string and a trailing {@code .dzi}. */
    private static String basename(String slideUrl) {
        if (slideUrl == null || slideUrl.isBlank())
            return "";
        String s = slideUrl;
        int q = s.indexOf('?');
        if (q >= 0)
            s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        if (name.toLowerCase(Locale.ROOT).endsWith(".dzi"))
            name = name.substring(0, name.length() - 4);
        return name;
    }
}
