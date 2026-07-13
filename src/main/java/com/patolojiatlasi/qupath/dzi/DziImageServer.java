package com.patolojiatlasi.qupath.dzi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * A QuPath {@link qupath.lib.images.servers.ImageServer} for Deep Zoom Image (DZI) tile
 * sources served over HTTP, as produced by {@code vips dzsave} and displayed with
 * OpenSeadragon (e.g. the images on patolojiatlasi.com).
 * <p>
 * The server parses the {@code .dzi} descriptor to learn the full image dimensions,
 * tile size, overlap and tile format, then streams individual JPEG/PNG tiles on demand.
 * Deep Zoom tiles carry an overlap border on interior edges; that border is cropped so
 * each returned tile lines up on QuPath's tile grid.
 */
public class DziImageServer extends AbstractTileableImageServer {

    private static final Logger logger = LoggerFactory.getLogger(DziImageServer.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final URI uri;
    private final String[] args;

    private final int width;
    private final int height;
    private final int tileSize;
    private final int overlap;
    private final String format;      // tile extension, e.g. "jpeg"
    private final String tilesBase;   // ".../HE_files"
    private final int maxDziLevel;    // DZI level index of the full-resolution image
    private final Double mpp;         // microns-per-pixel, if supplied via ?mpp=

    private final ImageServerMetadata originalMetadata;

    // Small in-memory guard against re-decoding the very same tile repeatedly.
    private final Map<String, BufferedImage> tinyCache = new ConcurrentHashMap<>();

    public DziImageServer(URI uri, String... args) throws IOException {
        super();
        this.uri = uri;
        this.args = args == null ? new String[0] : args;

        // Optional pixel size via query, e.g. https://.../HE.dzi?mpp=0.25
        this.mpp = parseMpp(uri);

        // Build tile base URL from the .dzi path (strip query + ".dzi").
        String full = uri.toString();
        int q = full.indexOf('?');
        String noQuery = q >= 0 ? full.substring(0, q) : full;
        if (!noQuery.toLowerCase().endsWith(".dzi"))
            throw new IOException("Not a .dzi URL: " + uri);
        String base = noQuery.substring(0, noQuery.length() - ".dzi".length());
        this.tilesBase = base + "_files";

        // Fetch + parse the .dzi descriptor.
        DziDescriptor d = fetchDescriptor(noQuery);
        this.width = d.width;
        this.height = d.height;
        this.tileSize = d.tileSize;
        this.overlap = d.overlap;
        this.format = d.format;
        this.maxDziLevel = (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2));

        this.originalMetadata = buildMetadata();
    }

    private ImageServerMetadata buildMetadata() {
        // Resolution levels: full-res (downsample 1) then halving until the whole
        // level fits within a single tile. QuPath level i has downsample 2^i and
        // corresponds to DZI level (maxDziLevel - i).
        var levelBuilder = new ImageServerMetadata.ImageResolutionLevel.Builder(width, height);
        levelBuilder.addLevel(1.0, width, height);
        double ds = 1.0;
        int lw = width, lh = height;
        while (lw > tileSize || lh > tileSize) {
            ds *= 2.0;
            lw = (int) Math.ceil(width / ds);
            lh = (int) Math.ceil(height / ds);
            levelBuilder.addLevel(ds, Math.max(1, lw), Math.max(1, lh));
        }

        var builder = new ImageServerMetadata.Builder()
                .width(width)
                .height(height)
                .name(deriveName())
                .rgb(true)
                .pixelType(PixelType.UINT8)
                .channels(ImageChannel.getDefaultRGBChannels())
                .preferredTileSize(tileSize, tileSize)
                .levels(levelBuilder.build());

        if (mpp != null && mpp > 0)
            builder.pixelSizeMicrons(mpp, mpp);

        return builder.build();
    }

    private String deriveName() {
        String p = uri.getPath();
        if (p == null || p.isBlank())
            return "DZI image";
        String[] parts = p.split("/");
        // Prefer the case folder name (…/<case>/HE.dzi) over the file name.
        if (parts.length >= 2)
            return parts[parts.length - 2];
        return parts[parts.length - 1];
    }

    private static Double parseMpp(URI uri) {
        String query = uri.getQuery();
        if (query == null)
            return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equalsIgnoreCase("mpp")) {
                try {
                    return Double.parseDouble(kv.substring(i + 1));
                } catch (NumberFormatException e) {
                    logger.warn("Could not parse mpp from {}", uri);
                }
            }
        }
        return null;
    }

    private DziDescriptor fetchDescriptor(String dziUrl) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(dziUrl))
                    .header("User-Agent", "QuPath-Atlas-Extension")
                    .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200)
                throw new IOException("HTTP " + resp.statusCode() + " fetching " + dziUrl);

            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(resp.body()));

            Element image = (Element) doc.getElementsByTagName("Image").item(0);
            Element size = (Element) doc.getElementsByTagName("Size").item(0);
            if (image == null || size == null)
                throw new IOException("Malformed .dzi (missing Image/Size): " + dziUrl);

            DziDescriptor d = new DziDescriptor();
            d.tileSize = Integer.parseInt(image.getAttribute("TileSize"));
            d.overlap = image.hasAttribute("Overlap") ? Integer.parseInt(image.getAttribute("Overlap")) : 0;
            String fmt = image.getAttribute("Format");
            d.format = (fmt == null || fmt.isBlank()) ? "jpeg" : fmt.toLowerCase();
            d.width = Integer.parseInt(size.getAttribute("Width"));
            d.height = Integer.parseInt(size.getAttribute("Height"));
            return d;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read .dzi descriptor: " + dziUrl, e);
        }
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        int dziLevel = maxDziLevel - tileRequest.getLevel();

        RegionRequest rr = tileRequest.getRegionRequest();
        double ds = tileRequest.getDownsample();
        int levelX = (int) Math.round(rr.getX() / ds);
        int levelY = (int) Math.round(rr.getY() / ds);
        int col = levelX / tileSize;
        int row = levelY / tileSize;

        int tileW = tileRequest.getTileWidth();
        int tileH = tileRequest.getTileHeight();

        BufferedImage src = fetchTile(dziLevel, col, row);

        BufferedImage out = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tileW, tileH);
            if (src != null) {
                int ox = (col > 0) ? overlap : 0;
                int oy = (row > 0) ? overlap : 0;
                int cw = Math.min(tileW, src.getWidth() - ox);
                int ch = Math.min(tileH, src.getHeight() - oy);
                if (cw > 0 && ch > 0)
                    g.drawImage(src.getSubimage(ox, oy, cw, ch), 0, 0, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private BufferedImage fetchTile(int dziLevel, int col, int row) {
        String url = tilesBase + "/" + dziLevel + "/" + col + "_" + row + "." + format;
        BufferedImage cached = tinyCache.get(url);
        if (cached != null)
            return cached;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "QuPath-Atlas-Extension")
                    .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                // Missing tiles are normal for sparse pyramids; render blank.
                return null;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
            if (img != null && tinyCache.size() < 8)
                tinyCache.put(url, img);
            return img;
        } catch (Exception e) {
            logger.debug("Failed to fetch tile {}: {}", url, e.getMessage());
            return null;
        }
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return DefaultImageServerBuilder.createInstance(
                DziImageServerBuilder.class, getMetadata(), uri, args);
    }

    @Override
    protected String createID() {
        return getClass().getName() + ": " + uri.toString();
    }

    @Override
    public Collection<URI> getURIs() {
        return List.of(uri);
    }

    @Override
    public String getServerType() {
        return "Deep Zoom (DZI) image server";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return originalMetadata;
    }

    /** Simple holder for parsed .dzi attributes. */
    private static final class DziDescriptor {
        int width;
        int height;
        int tileSize;
        int overlap;
        String format;
    }
}
