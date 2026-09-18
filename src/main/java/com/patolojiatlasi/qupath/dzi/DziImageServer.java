package com.patolojiatlasi.qupath.dzi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * A QuPath {@link qupath.lib.images.servers.ImageServer} for Deep Zoom Image (DZI) tile
 * sources, as produced by {@code vips dzsave} and displayed with OpenSeadragon (e.g. the
 * images on patolojiatlasi.com). Tiles are read either over HTTP(S) or from a local folder,
 * depending on the scheme of the {@code .dzi} URI.
 * <p>
 * The server parses the {@code .dzi} descriptor to learn the full image dimensions,
 * tile size, overlap and tile format, then streams individual JPEG/PNG tiles on demand.
 * Deep Zoom tiles carry an overlap border on interior edges; that border is cropped so
 * each returned tile lines up on QuPath's tile grid.
 * <p>
 * Pixel size is resolved in two steps: an explicit {@code ?mpp=} query wins, otherwise the
 * {@code vips-properties.xml} sidecar that {@code vips dzsave} writes beside the tiles is
 * consulted. That sidecar carries the scanner's real calibration (e.g. {@code aperio.MPP}),
 * so atlas slides calibrate themselves without the stored URI ever needing to be rewritten.
 */
public class DziImageServer extends AbstractTileableImageServer {

    private static final Logger logger = LoggerFactory.getLogger(DziImageServer.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Name of the sidecar {@code vips dzsave} writes inside the {@code _files} directory. */
    static final String VIPS_PROPERTIES = "vips-properties.xml";

    private final URI uri;
    private final String[] args;

    private final int width;
    private final int height;
    private final int tileSize;
    private final int overlap;
    private final String format;      // tile extension, e.g. "jpeg"
    private final int maxDziLevel;    // DZI level index of the full-resolution image
    private final Double mpp;         // microns-per-pixel, from ?mpp= or the vips sidecar

    private final DziSource source;
    private final ImageServerMetadata originalMetadata;

    // Small in-memory guard against re-decoding the very same tile repeatedly.
    private final Map<String, BufferedImage> tinyCache = new ConcurrentHashMap<>();

    public DziImageServer(URI uri, String... args) throws IOException {
        super();
        this.uri = uri;
        this.args = args == null ? new String[0] : args;

        // Strip the query (e.g. "?mpp=0.25") before deriving paths: a local File cannot be
        // constructed from a URI that carries one.
        String full = uri.toString();
        int q = full.indexOf('?');
        String noQuery = q >= 0 ? full.substring(0, q) : full;
        if (!noQuery.toLowerCase().endsWith(".dzi"))
            throw new IOException("Not a .dzi URL: " + uri);

        String tilesBase = noQuery.substring(0, noQuery.length() - ".dzi".length()) + "_files";
        this.source = isLocal(uri) ? new LocalSource(noQuery) : new HttpSource(noQuery, tilesBase);

        // Fetch + parse the .dzi descriptor.
        DziDescriptor d = fetchDescriptor(noQuery);
        this.width = d.width;
        this.height = d.height;
        this.tileSize = d.tileSize;
        this.overlap = d.overlap;
        this.format = d.format;
        this.maxDziLevel = (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2));

        // Explicit ?mpp= wins; otherwise fall back to the scanner calibration in the sidecar.
        Double resolved = parseMpp(uri);
        if (resolved == null)
            resolved = readSidecarMpp();
        this.mpp = resolved;

        this.originalMetadata = buildMetadata();
    }

    private static boolean isLocal(URI uri) {
        String scheme = uri == null ? null : uri.getScheme();
        return scheme != null && scheme.equalsIgnoreCase("file");
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
        // Prefer the case folder name (.../<case>/HE.dzi) over the file name.
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

    /**
     * Read the pixel size from the {@code vips-properties.xml} sidecar beside the tiles.
     * Best-effort: a missing or unreadable sidecar simply leaves the image uncalibrated,
     * exactly as before this was added.
     */
    private Double readSidecarMpp() {
        try {
            byte[] bytes = source.read(VIPS_PROPERTIES);
            if (bytes == null)
                return null;
            Double value = mppFromVipsProperties(bytes);
            if (value != null)
                logger.info("Calibrated {} from {}: {} microns/pixel", deriveName(), VIPS_PROPERTIES, value);
            return value;
        } catch (Exception e) {
            logger.debug("Could not read {} for {}: {}", VIPS_PROPERTIES, uri, e.getMessage());
            return null;
        }
    }

    /**
     * Extract microns-per-pixel from a {@code vips-properties.xml} document.
     * <p>
     * {@code xres} is preferred, because vips computes it <i>for the image it actually wrote</i>
     * and it therefore stays correct even when a slide was downsampled on export. The scanner tags
     * {@code openslide.mpp-x} / {@code aperio.MPP} describe the <i>original</i> slide and are used
     * as a fallback; when the two disagree by more than 5% that is logged rather than hidden.
     * Implausible values (a placeholder resolution) are discarded at each step.
     *
     * @return the pixel size in microns, or {@code null} if the document carries none
     */
    static Double mppFromVipsProperties(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        Map<String, String> props = new HashMap<>();
        NodeList list = doc.getElementsByTagName("property");
        for (int i = 0; i < list.getLength(); i++) {
            Element prop = (Element) list.item(i);
            NodeList names = prop.getElementsByTagName("name");
            NodeList values = prop.getElementsByTagName("value");
            if (names.getLength() > 0 && values.getLength() > 0) {
                String name = names.item(0).getTextContent();
                String value = values.item(0).getTextContent();
                if (name != null && value != null)
                    props.put(name.trim(), value.trim());
            }
        }

        // xres is pixels per millimetre.
        Double xres = positiveDouble(props.get("xres"));
        Double fromXres = plausible(xres == null ? null : 1000.0 / xres);
        Double recorded = positiveDouble(props.get("openslide.mpp-x"));
        if (recorded == null)
            recorded = positiveDouble(props.get("aperio.MPP"));
        recorded = plausible(recorded);

        if (fromXres != null && recorded != null
                && Math.abs(fromXres - recorded) / Math.max(fromXres, recorded) > 0.05) {
            // The scanner tag describes the ORIGINAL slide; xres describes the image vips actually
            // wrote. They diverge when a slide was downsampled between the two (the documented
            // ImageScope-export-then-dzsave path), and trusting the stale scanner value there would
            // silently scale every measurement. Prefer xres, but never do it quietly.
            logger.warn("{} disagrees about pixel size: xres implies {} um/px but the scanner tag says"
                    + " {} um/px. Using {} (it describes the exported image). Check the export if"
                    + " measurements look wrong.", VIPS_PROPERTIES, fromXres, recorded, fromXres);
        }
        if (fromXres != null)
            return fromXres;
        return recorded;
    }

    /**
     * Guard against a placeholder resolution. A pyramid written from a source with no resolution
     * information can carry {@code xres=1} (one pixel per millimetre), which would present as
     * 1000 um/px -- far outside anything a slide scanner produces.
     */
    private static Double plausible(Double micronsPerPixel) {
        if (micronsPerPixel == null)
            return null;
        return (micronsPerPixel >= 0.01 && micronsPerPixel <= 100.0) ? micronsPerPixel : null;
    }

    private static Double positiveDouble(String text) {
        if (text == null || text.isBlank())
            return null;
        try {
            double v = Double.parseDouble(text.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DziDescriptor fetchDescriptor(String dziUrl) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source.descriptor()));

            Element image = (Element) doc.getElementsByTagName("Image").item(0);
            Element size = (Element) doc.getElementsByTagName("Size").item(0);
            if (image == null || size == null)
                throw new IOException("Malformed .dzi (missing Image/Size): " + dziUrl);

            DziDescriptor d = new DziDescriptor();
            d.tileSize = Integer.parseInt(image.getAttribute("TileSize"));
            d.overlap = image.hasAttribute("Overlap") ? Integer.parseInt(image.getAttribute("Overlap")) : 0;
            String fmt = image.getAttribute("Format");
            d.format = (fmt == null || fmt.isBlank()) ? "jpeg" : fmt.toLowerCase();
            // Format is concatenated into a tile path, and LocalSource resolves that path segment
            // by segment -- so an descriptor carrying "../../etc/passwd" would escape the tile
            // directory. A real tile extension is alphanumeric; anything else is a broken or
            // hostile descriptor, not something to guess around.
            if (!d.format.matches("[a-z0-9]+"))
                throw new IOException("Invalid .dzi tile Format \"" + fmt + "\": " + dziUrl);
            d.width = Integer.parseInt(size.getAttribute("Width"));
            d.height = Integer.parseInt(size.getAttribute("Height"));
            // A malformed descriptor with a non-positive TileSize would make buildMetadata()'s
            // resolution-level loop spin forever; reject it up front.
            if (d.tileSize <= 0 || d.width <= 0 || d.height <= 0)
                throw new IOException("Invalid .dzi descriptor (TileSize/Width/Height must be positive): " + dziUrl);
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
        String relative = dziLevel + "/" + col + "_" + row + "." + format;
        BufferedImage cached = tinyCache.get(relative);
        if (cached != null)
            return cached;
        try {
            byte[] bytes = source.read(relative);
            if (bytes == null) {
                // Missing tiles are normal for sparse pyramids; render blank.
                return null;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null && tinyCache.size() < 8)
                tinyCache.put(relative, img);
            return img;
        } catch (Exception e) {
            logger.debug("Failed to read tile {}: {}", relative, e.getMessage());
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

    /**
     * Reads the bytes a DZI is made of. Two implementations cover the two places a Deep Zoom
     * pyramid can live: an HTTP(S) server, or a local folder.
     */
    private interface DziSource {

        /** Bytes of the {@code .dzi} descriptor itself. */
        byte[] descriptor() throws IOException;

        /**
         * Bytes of an entry inside the {@code <name>_files} directory, addressed with
         * {@code /}-separated segments (e.g. {@code "17/0_0.jpeg"}).
         *
         * @return the bytes, or {@code null} when the entry is absent -- normal both for
         *         sparse pyramids and for a missing {@code vips-properties.xml}
         */
        byte[] read(String relativeToTiles);
    }

    /** Streams a DZI from an HTTP(S) server. */
    private static final class HttpSource implements DziSource {

        private final String dziUrl;
        private final String tilesBase;

        HttpSource(String dziUrl, String tilesBase) {
            this.dziUrl = dziUrl;
            this.tilesBase = tilesBase;
        }

        @Override
        public byte[] descriptor() throws IOException {
            // Reported with the status code rather than via get(), because a bad descriptor URL is
            // the single most common failure here and "HTTP 404" is what makes it diagnosable.
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(dziUrl))
                        .header("User-Agent", "QuPath-Atlas-Extension")
                        .GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200)
                    throw new IOException("HTTP " + resp.statusCode() + " fetching " + dziUrl);
                return resp.body();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Could not fetch " + dziUrl + ": " + e.getMessage(), e);
            }
        }

        @Override
        public byte[] read(String relativeToTiles) {
            return get(tilesBase + "/" + relativeToTiles);
        }

        private static byte[] get(String url) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "QuPath-Atlas-Extension")
                        .GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                return resp.statusCode() == 200 ? resp.body() : null;
            } catch (Exception e) {
                logger.debug("Failed to fetch {}: {}", url, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Reads a DZI from a local folder. Paths are resolved with {@link Path} rather than by
     * concatenating URL strings, so Windows drive letters and spaces survive intact.
     */
    private static final class LocalSource implements DziSource {

        private final Path dziPath;
        private final Path tilesDir;

        LocalSource(String dziUriNoQuery) throws IOException {
            this.dziPath = toPath(dziUriNoQuery);
            String fileName = dziPath.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - ".dzi".length());
            this.tilesDir = dziPath.resolveSibling(baseName + "_files");
            if (!Files.isRegularFile(dziPath))
                throw new IOException("No such .dzi file: " + dziPath);
        }

        private static Path toPath(String uriNoQuery) throws IOException {
            try {
                return new File(URI.create(uriNoQuery)).toPath();
            } catch (Exception e) {
                throw new IOException("Not a usable local .dzi path: " + uriNoQuery, e);
            }
        }

        @Override
        public byte[] descriptor() throws IOException {
            return Files.readAllBytes(dziPath);
        }

        @Override
        public byte[] read(String relativeToTiles) {
            try {
                Path p = tilesDir;
                for (String segment : relativeToTiles.split("/")) {
                    if (!segment.isEmpty())
                        p = p.resolve(segment);
                }
                return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
            } catch (Exception e) {
                logger.debug("Failed to read {} under {}: {}", relativeToTiles, tilesDir, e.getMessage());
                return null;
            }
        }
    }
}
