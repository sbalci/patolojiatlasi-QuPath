package com.patolojiatlasi.qupath.dzi;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.UriImageSupport;

/**
 * Registers Deep Zoom (.dzi) URLs as an image type QuPath can open.
 * <p>
 * Discovered via {@code META-INF/services/qupath.lib.images.servers.ImageServerBuilder},
 * so any {@code .dzi} URL (dragged onto QuPath, added to a project, or opened
 * programmatically) is handled by {@link DziImageServer}.
 */
public class DziImageServerBuilder implements ImageServerBuilder<BufferedImage> {

    private static final Logger logger = LoggerFactory.getLogger(DziImageServerBuilder.class);

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }

    @Override
    public String getName() {
        return "Deep Zoom (DZI) builder";
    }

    @Override
    public String getDescription() {
        return "Reads Deep Zoom (.dzi) tiled whole-slide images served over HTTP, "
                + "such as those on patolojiatlasi.com.";
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
        float support = supportLevel(uri);
        List<ServerBuilder<BufferedImage>> builders = new ArrayList<>();
        if (support > 0) {
            try {
                DziImageServer server = new DziImageServer(uri, args);
                builders.add(server.getBuilder());
                server.close();
            } catch (Exception e) {
                logger.debug("Unable to open {} as DZI: {}", uri, e.getMessage());
                support = 0;
            }
        }
        return UriImageSupport.createInstance(this.getClass(), support, builders);
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
        return new DziImageServer(uri, args);
    }

    private static float supportLevel(URI uri) {
        if (uri == null)
            return 0;
        String path = uri.getPath();
        String scheme = uri.getScheme();
        if (path == null || scheme == null)
            return 0;
        boolean http = scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
        // High confidence for .dzi URLs so this builder is preferred.
        return (http && path.toLowerCase().endsWith(".dzi")) ? 4f : 0f;
    }
}
