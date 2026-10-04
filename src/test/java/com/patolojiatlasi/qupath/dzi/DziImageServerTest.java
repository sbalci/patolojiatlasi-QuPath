package com.patolojiatlasi.qupath.dzi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the two things that make a local Deep Zoom pyramid usable in QuPath: the builder
 * accepting a {@code file:} URI, and pixel size being recovered from the {@code vips-properties.xml}
 * sidecar rather than requiring a {@code ?mpp=} query (which cannot be rewritten later without
 * risking annotation loss on a project entry).
 */
class DziImageServerTest {

    private static final String DZI = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
              Format="jpeg" Overlap="1" TileSize="254">
              <Size Height="80" Width="100"/>
            </Image>
            """;

    private static String vipsProperties(String... nameValuePairs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><image><properties>");
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            sb.append("<property><name>").append(nameValuePairs[i])
              .append("</name><value type=\"VipsRefString\">").append(nameValuePairs[i + 1])
              .append("</value></property>");
        }
        return sb.append("</properties></image>").toString();
    }

    private static byte[] xml(String... nameValuePairs) {
        return vipsProperties(nameValuePairs).getBytes(StandardCharsets.UTF_8);
    }

    // --- sidecar parsing -------------------------------------------------------------

    @Test
    void prefersOpenslideMppOverEverythingElse() throws Exception {
        Double mpp = DziImageServer.mppFromVipsProperties(
                xml("xres", "3802.0211544457038", "aperio.MPP", "0.5", "openslide.mpp-x", "0.263018"));
        assertEquals(0.263018, mpp, 1e-9);
    }

    @Test
    void fallsBackToAperioMppWhenOpenslideAbsent() throws Exception {
        Double mpp = DziImageServer.mppFromVipsProperties(
                xml("xres", "3802.0211544457038", "aperio.MPP", "0.263018"));
        assertEquals(0.263018, mpp, 1e-9);
    }

    /**
     * vips writes xres in pixels per millimetre. The real lymphocytic-gastritis slide carries
     * xres=3802.0211544457038, which must come back as the same 0.263018 the scanner recorded.
     */
    @Test
    void convertsXresPixelsPerMillimetreToMicronsPerPixel() throws Exception {
        Double mpp = DziImageServer.mppFromVipsProperties(xml("xres", "3802.0211544457038"));
        assertEquals(0.263018, mpp, 1e-6);
    }

    @Test
    void returnsNullWhenSidecarCarriesNoPixelSize() throws Exception {
        assertNull(DziImageServer.mppFromVipsProperties(xml("aperio.AppMag", "40", "bands", "4")));
    }

    @Test
    void ignoresNonPositiveValues() throws Exception {
        assertNull(DziImageServer.mppFromVipsProperties(xml("openslide.mpp-x", "0", "aperio.MPP", "-1")));
    }

    // --- builder scheme gate ---------------------------------------------------------

    @Test
    void rejectsNonDziAndUnsupportedSchemes() {
        var builder = new DziImageServerBuilder();
        assertEquals(0f, builder.checkImageSupport(URI.create("https://x/slide.svs")).getSupportLevel());
        assertEquals(0f, builder.checkImageSupport(URI.create("ftp://x/slide.dzi")).getSupportLevel());
    }

    // --- local pyramid, end to end ---------------------------------------------------

    private static Path writePyramid(Path dir, String sidecar) throws IOException {
        Path dzi = dir.resolve("HE.dzi");
        Files.writeString(dzi, DZI);
        Path tiles = dir.resolve("HE_files").resolve("7");
        Files.createDirectories(tiles);
        ImageIO.write(new BufferedImage(101, 81, BufferedImage.TYPE_INT_RGB), "jpeg",
                tiles.resolve("0_0.jpeg").toFile());
        if (sidecar != null)
            Files.writeString(dir.resolve("HE_files").resolve("vips-properties.xml"), sidecar);
        return dzi;
    }

    @Test
    void opensLocalDziAndCalibratesFromSidecar(@TempDir Path dir) throws Exception {
        Path dzi = writePyramid(dir, vipsProperties("openslide.mpp-x", "0.263018"));

        var builder = new DziImageServerBuilder();
        var support = builder.checkImageSupport(dzi.toUri());
        assertEquals(4f, support.getSupportLevel(), "a local .dzi must be claimed by this builder");
        assertTrue(!support.getBuilders().isEmpty());

        try (var server = new DziImageServer(dzi.toUri())) {
            assertEquals(100, server.getWidth());
            assertEquals(80, server.getHeight());
            var cal = server.getPixelCalibration();
            assertTrue(cal.hasPixelSizeMicrons(), "sidecar should have calibrated the server");
            assertEquals(0.263018, cal.getAveragedPixelSizeMicrons(), 1e-9);
        }
    }

    @Test
    void localDziWithoutSidecarStaysUncalibrated(@TempDir Path dir) throws Exception {
        Path dzi = writePyramid(dir, null);
        try (var server = new DziImageServer(dzi.toUri())) {
            assertTrue(!server.getPixelCalibration().hasPixelSizeMicrons());
        }
    }

    @Test
    void explicitMppQueryWinsOverSidecar(@TempDir Path dir) throws Exception {
        Path dzi = writePyramid(dir, vipsProperties("openslide.mpp-x", "0.263018"));
        URI withQuery = URI.create(dzi.toUri() + "?mpp=0.5");
        try (var server = new DziImageServer(withQuery)) {
            assertEquals(0.5, server.getPixelCalibration().getAveragedPixelSizeMicrons(), 1e-9);
        }
    }

    /**
     * Opens the real lymphocytic-gastritis pyramid when it is present on this machine. Synthetic
     * fixtures cannot catch a mistake in the level mapping or the overlap crop, because they have
     * only one level and no overlap; this slide has 18 levels, Overlap=1 and 52605 tiles at level 17.
     * Self-skips wherever the folder is absent, so it never breaks CI or another checkout.
     */
    @Test
    void opensTheRealAtlasSlideWhenAvailable() throws Exception {
        Path real = Path.of("E:", "atlas", "lymphocytic-gastritis", "HE.dzi");
        Assumptions.assumeTrue(Files.isRegularFile(real), "real atlas slide not present here");

        try (var server = new DziImageServer(real.toUri())) {
            assertEquals(79923, server.getWidth());
            assertEquals(42180, server.getHeight());
            assertEquals("lymphocytic-gastritis", server.getMetadata().getName());

            var cal = server.getPixelCalibration();
            assertTrue(cal.hasPixelSizeMicrons(), "vips-properties.xml should calibrate this slide");
            assertEquals(0.263018, cal.getAveragedPixelSizeMicrons(), 1e-6,
                    "should match aperio.MPP recorded by the GT450");

            // A real region, read through the overlap-cropping path at a coarse level.
            BufferedImage img = server.readRegion(
                    qupath.lib.regions.RegionRequest.createInstance(
                            server.getPath(), 64.0, 20000, 10000, 2048, 2048));
            assertNotNull(img);
            assertEquals(32, img.getWidth());
            assertEquals(32, img.getHeight());
        }
    }

    /**
     * Deep Zoom writes a 1px overlap border onto every interior tile edge, and it must be cropped
     * back off or each interior tile lands one pixel out of place. None of the single-tile fixtures
     * above can catch that, because they only ever exercise col/row 0 where no crop applies.
     * <p>
     * This builds a real 3x2 grid with {@code Overlap=1} and paints every pixel with its own true
     * position in the full image, encoded as a lossless PNG colour. Reading the whole image back
     * and decoding each pixel therefore detects a misalignment of even one pixel, anywhere.
     */
    @Test
    void cropsTheOverlapBorderOnInteriorTiles(@TempDir Path dir) throws Exception {
        final int w = 600, h = 400, tile = 256, overlap = 1;
        final int level = 10; // ceil(log2(600)) == 10, the full-resolution DZI level
        Files.writeString(dir.resolve("HE.dzi"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
                  Format="png" Overlap="1" TileSize="256">
                  <Size Height="400" Width="600"/>
                </Image>
                """);
        Path tiles = dir.resolve("HE_files").resolve(String.valueOf(level));
        Files.createDirectories(tiles);

        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 2; row++) {
                int ox = col > 0 ? overlap : 0;
                int oy = row > 0 ? overlap : 0;
                int x0 = col * tile - ox;
                int y0 = row * tile - oy;
                int tw = Math.min(tile + ox + (col < 2 ? overlap : 0), w - x0);
                int th = Math.min(tile + oy + (row < 1 ? overlap : 0), h - y0);
                BufferedImage img = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < tw; x++)
                    for (int y = 0; y < th; y++)
                        img.setRGB(x, y, encodePosition(x0 + x, y0 + y));
                // PNG, not JPEG: the encoding must survive the round trip exactly.
                ImageIO.write(img, "png", tiles.resolve(col + "_" + row + ".png").toFile());
            }
        }

        try (var server = new DziImageServer(dir.resolve("HE.dzi").toUri())) {
            BufferedImage out = server.readRegion(
                    qupath.lib.regions.RegionRequest.createInstance(server.getPath(), 1.0, 0, 0, w, h));
            assertEquals(w, out.getWidth());
            assertEquals(h, out.getHeight());

            // Both sides of every tile seam, plus the far corner.
            int[][] probes = {
                {0, 0}, {255, 10}, {256, 10}, {257, 10}, {511, 10}, {512, 10},
                {10, 255}, {10, 256}, {10, 257}, {599, 399}, {512, 256},
            };
            for (int[] p : probes) {
                int rgb = out.getRGB(p[0], p[1]) & 0xFFFFFF;
                assertEquals(encodePosition(p[0], p[1]) & 0xFFFFFF, rgb,
                        "pixel at " + p[0] + "," + p[1] + " came from the wrong place -- "
                        + "decoded as " + ((rgb >> 12) & 0xFFF) + "," + (rgb & 0xFFF));
            }
        }
    }

    /** Pack a pixel's own (x, y) into a 24-bit RGB value so it can be checked after a round trip. */
    private static int encodePosition(int x, int y) {
        return ((x & 0xFFF) << 12) | (y & 0xFFF);
    }

    // --- descriptor hardening --------------------------------------------------------

    /** Format is concatenated into a file path, so a traversal payload must not be accepted. */
    @Test
    void rejectsADescriptorWhoseFormatIsAPath(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("HE.dzi"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
                  Format="../../../../secrets" Overlap="1" TileSize="254">
                  <Size Height="80" Width="100"/>
                </Image>
                """);
        Files.createDirectories(dir.resolve("HE_files"));
        var thrown = assertThrows(IOException.class,
                () -> new DziImageServer(dir.resolve("HE.dzi").toUri()));
        assertTrue(thrown.getMessage().contains("Format"), thrown.getMessage());
    }

    @Test
    void rejectsDoctypeInDescriptor(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("HE.dzi"), """
                <?xml version="1.0"?>
                <!DOCTYPE Image [<!ENTITY data SYSTEM "file:///should-not-be-read">]>
                <Image TileSize="254" Overlap="1" Format="jpeg"><Size Width="100" Height="80"/></Image>
                """);
        assertThrows(IOException.class, () -> new DziImageServer(dir.resolve("HE.dzi").toUri()));
    }

    @Test
    void rejectsDoctypeInSidecar() {
        byte[] xml = """
                <?xml version="1.0"?>
                <!DOCTYPE image [<!ENTITY data SYSTEM "file:///should-not-be-read">]>
                <image><properties><property><name>xres</name><value>&data;</value></property></properties></image>
                """.getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> DziImageServer.mppFromVipsProperties(xml));
    }

    // --- calibration precedence ------------------------------------------------------

    /**
     * The scanner tag describes the original slide; xres describes what vips actually wrote. When a
     * slide was downsampled on export the two disagree, and trusting the stale scanner value would
     * scale every measurement without warning.
     */
    @Test
    void prefersXresOverAStaleScannerTag() throws Exception {
        // xres 1901.01 px/mm == 0.526 um/px, i.e. the same slide exported at half size.
        Double mpp = DziImageServer.mppFromVipsProperties(
                xml("xres", "1901.0105772228519", "openslide.mpp-x", "0.263018"));
        assertEquals(0.526036, mpp, 1e-5);
    }

    /** A placeholder resolution (1 px/mm) implies 1000 um/px and must not be believed. */
    @Test
    void discardsAnImplausibleResolutionAndFallsBack() throws Exception {
        assertEquals(0.263018,
                DziImageServer.mppFromVipsProperties(xml("xres", "1", "aperio.MPP", "0.263018")),
                1e-9);
        assertNull(DziImageServer.mppFromVipsProperties(xml("xres", "1")));
    }

    @Test
    void readsATileFromDisk(@TempDir Path dir) throws Exception {
        Path dzi = writePyramid(dir, null);
        try (var server = new DziImageServer(dzi.toUri())) {
            BufferedImage img = server.readRegion(
                    qupath.lib.regions.RegionRequest.createInstance(server, 1.0));
            assertNotNull(img);
            assertEquals(100, img.getWidth());
            assertEquals(80, img.getHeight());
        }
    }

    @Test
    void corruptTileFailsInsteadOfRenderingWhite(@TempDir Path dir) throws Exception {
        Path dzi = writePyramid(dir, null);
        Files.writeString(dir.resolve("HE_files/7/0_0.jpeg"), "not a JPEG");
        try (var server = new DziImageServer(dzi.toUri())) {
            assertThrows(IOException.class, () -> server.readRegion(
                    qupath.lib.regions.RegionRequest.createInstance(server, 1.0)));
        }
    }
}
