package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapImageOptimizer}: palette-size bounds, indexed-PNG output with no alpha,
 * dither-free flat regions, determinism, true-RGB original mode, metadata-free output, and that the
 * reduced modes perform real nearest-mean quantization rather than per-channel truncation.
 */
class MapImageOptimizerTest {

    private final MapImageOptimizer optimizer = new MapImageOptimizer();

    @Test
    void originalModeKeepsTrueRgbColors() throws Exception {
        var src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                src.setRGB(x, y, new Color(x * 30, y * 20, (x + y) * 10).getRGB());
            }
        }

        var out = optimizer.optimize(src, MapTileColorMode.ORIGINAL);
        var decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertFalse(decoded.getColorModel() instanceof IndexColorModel, "ORIGINAL must stay true RGB");
        assertEquals(src.getWidth(), decoded.getWidth());
        assertEquals(src.getHeight(), decoded.getHeight());
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                assertEquals(src.getRGB(x, y) & 0xFFFFFF, decoded.getRGB(x, y) & 0xFFFFFF);
            }
        }
    }

    @Test
    void reducedModesEmitIndexedPngWithBoundedPaletteAndNoAlpha() throws Exception {
        for (int n : new int[] {16, 32, 64}) {
            var src = multiColorImage(40); // more distinct colors than any palette bound
            var mode = modeFor(n);

            var out = optimizer.optimize(src, mode);
            // The JDK PNG reader normalises the decoded colour model to a 256-entry palette, so the
            // bounded palette is verified against the actual PLTE chunk written to the file.
            assertTrue(plteEntries(out) <= n, "PLTE must hold at most " + n + " entries");
            var decoded = ImageIO.read(new ByteArrayInputStream(out));
            assertTrue(decoded.getColorModel() instanceof IndexColorModel, "reduced mode must be indexed");
            assertFalse(
                    ((IndexColorModel) decoded.getColorModel()).hasAlpha(),
                    "composite must remain fully opaque (no alpha)");
            assertEquals(src.getWidth(), decoded.getWidth());
            assertEquals(src.getHeight(), decoded.getHeight());
        }
    }

    @Test
    void flatRegionsStayFlatWithoutDithering() throws Exception {
        var src = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        fill(src, new Color(200, 30, 30), 0, 0, 32, 64); // left half solid red-ish
        fill(src, new Color(30, 30, 200), 32, 0, 32, 64); // right half solid blue-ish

        var out = optimizer.optimize(src, MapTileColorMode.COLORS_16);
        assertEquals(2, plteEntries(out), "two flat colours must yield exactly two PLTE entries");
        var decoded = ImageIO.read(new ByteArrayInputStream(out));

        int leftIndex = decoded.getRaster().getSample(5, 5, 0);
        int rightIndex = decoded.getRaster().getSample(58, 5, 0);
        assertTrue(leftIndex != rightIndex, "the two regions must map to different palette entries");

        // Every pixel in each region maps to the same single index -> no dither speckle.
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 64; y++) {
                assertEquals(leftIndex, decoded.getRaster().getSample(x, y, 0), "left region must stay flat");
            }
        }
        for (int x = 32; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                assertEquals(rightIndex, decoded.getRaster().getSample(x, y, 0), "right region must stay flat");
            }
        }
    }

    @Test
    void identicalInputAndModeYieldsByteIdenticalOutput() {
        var src = multiColorImage(60);
        assertArrayEquals(
                optimizer.optimize(src, MapTileColorMode.COLORS_32),
                optimizer.optimize(src, MapTileColorMode.COLORS_32),
                "optimization must be deterministic");
    }

    @Test
    void quantizationIsRealNotChannelTruncation() throws Exception {
        int k = 40;
        var colors = new Color[k];
        var src = new BufferedImage(k * 8, 8, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < k; i++) {
            colors[i] = new Color((i * 53) % 256, (i * 97) % 256, (i * 151) % 256);
            fill(src, colors[i], i * 8, 0, 8, 8);
        }

        var out = optimizer.optimize(src, MapTileColorMode.COLORS_16);
        assertTrue(plteEntries(out) <= 16, "a bounded shared palette is impossible with per-channel truncation");
        var palette = plteColors(out);
        var decoded = ImageIO.read(new ByteArrayInputStream(out));

        // Each source stripe maps to the nearest palette entry, and distinct sources merge together.
        var usedIndices = new java.util.HashSet<Integer>();
        for (int i = 0; i < k; i++) {
            int idx = decoded.getRaster().getSample(i * 8 + 4, 4, 0);
            usedIndices.add(idx);
            int pc = palette[idx];
            int pr = (pc >> 16) & 0xFF, pg = (pc >> 8) & 0xFF, pb = pc & 0xFF;
            int r = colors[i].getRed(), g = colors[i].getGreen(), b = colors[i].getBlue();
            long assigned = dist(r, g, b, pr, pg, pb);
            long best = Long.MAX_VALUE;
            for (int p : palette) {
                best = Math.min(best, dist(r, g, b, (p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF));
            }
            assertEquals(best, assigned, "each pixel must map to its nearest palette entry");
        }
        assertTrue(usedIndices.size() < k, "distinct source colours must be merged into fewer entries");
    }

    @Test
    void outputContainsNoMetadataChunks() throws Exception {
        for (int n : new int[] {16, 64}) {
            var out = optimizer.optimize(multiColorImage(40), modeFor(n));
            var chunks = pngChunkTypes(out);
            assertFalse(chunks.contains("tEXt"), "no tEXt chunk");
            assertFalse(chunks.contains("zTXt"), "no zTXt chunk");
            assertFalse(chunks.contains("iTXt"), "no iTXt chunk");
            assertFalse(chunks.contains("eXIf"), "no eXIf chunk");
            assertTrue(chunks.contains("IHDR") && chunks.contains("IEND"));
        }
    }

    private static MapTileColorMode modeFor(int n) {
        return switch (n) {
            case 16 -> MapTileColorMode.COLORS_16;
            case 32 -> MapTileColorMode.COLORS_32;
            default -> MapTileColorMode.COLORS_64;
        };
    }

    private static long dist(int r, int g, int b, int pr, int pg, int pb) {
        long dr = r - pr, dg = g - pg, db = b - pb;
        return dr * dr + dg * dg + db * db;
    }

    /** A wide image made of {@code count} vertical stripes, each a distinct solid colour. */
    private static BufferedImage multiColorImage(int count) {
        var img = new BufferedImage(count * 8, 8, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < count; i++) {
            fill(img, new Color((i * 53) % 256, (i * 97) % 256, (i * 151) % 256), i * 8, 0, 8, 8);
        }
        return img;
    }

    private static void fill(BufferedImage img, Color c, int x, int y, int w, int h) {
        var g = img.createGraphics();
        g.setColor(c);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    /** Number of entries in the PNG {@code PLTE} chunk, or -1 when absent. */
    private static int plteEntries(byte[] png) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = ((png[pos] & 0xFF) << 24)
                    | ((png[pos + 1] & 0xFF) << 16)
                    | ((png[pos + 2] & 0xFF) << 8)
                    | (png[pos + 3] & 0xFF);
            var type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("PLTE")) {
                return len / 3;
            }
            if (type.equals("IEND")) {
                break;
            }
            pos += 12 + len;
        }
        return -1;
    }

    /** Packed RGB values of every entry in the PNG {@code PLTE} chunk, in order. */
    private static int[] plteColors(byte[] png) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = ((png[pos] & 0xFF) << 24)
                    | ((png[pos + 1] & 0xFF) << 16)
                    | ((png[pos + 2] & 0xFF) << 8)
                    | (png[pos + 3] & 0xFF);
            var type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("PLTE")) {
                var colors = new int[len / 3];
                for (int i = 0; i < colors.length; i++) {
                    int o = pos + 8 + i * 3;
                    colors[i] = ((png[o] & 0xFF) << 16) | ((png[o + 1] & 0xFF) << 8) | (png[o + 2] & 0xFF);
                }
                return colors;
            }
            if (type.equals("IEND")) {
                break;
            }
            pos += 12 + len;
        }
        throw new IllegalStateException("PNG has no PLTE chunk");
    }

    /** The PNG chunk type names in order (skips the 8-byte signature). */
    private static List<String> pngChunkTypes(byte[] png) {
        var types = new ArrayList<String>();
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = ((png[pos] & 0xFF) << 24)
                    | ((png[pos + 1] & 0xFF) << 16)
                    | ((png[pos + 2] & 0xFF) << 8)
                    | (png[pos + 3] & 0xFF);
            var type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            types.add(type);
            if (type.equals("IEND")) {
                break;
            }
            pos += 12 + len;
        }
        return types;
    }
}
