package com.vb.wingfoil.tiles;

import io.vavr.control.Try;
import jakarta.inject.Singleton;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Reduces an opaque {@code TYPE_INT_RGB} composite to a small palette and encodes it as PNG.
 * {@link MapTileColorMode#ORIGINAL} encodes the RGB image directly (no palette); the reduced-color
 * modes build a deterministic median-cut palette over the whole viewport (dithering disabled) and
 * emit an indexed/paletted PNG. This component depends only on {@code java.awt.image} and
 * {@code javax.imageio} — it knows nothing about HTTP, OSM, tiles or caching.
 */
@Singleton
public class MapImageOptimizer {

    /**
     * Optimizes the given opaque RGB composite for the requested color mode and returns PNG bytes.
     *
     * @param rgb an opaque {@code TYPE_INT_RGB} composite
     * @param mode the target color mode
     * @return encoded PNG bytes (true RGB for {@link MapTileColorMode#ORIGINAL}, indexed otherwise)
     */
    public byte[] optimize(BufferedImage rgb, MapTileColorMode mode) {
        var out =
                switch (mode) {
                    case ORIGINAL -> rgb;
                    case COLORS_16, COLORS_32, COLORS_64 -> quantize(rgb, mode.paletteSize());
                };
        return encode(out);
    }

    private static BufferedImage quantize(BufferedImage src, int maxColors) {
        var w = src.getWidth();
        var h = src.getHeight();
        var pixels = src.getRGB(0, 0, w, h, null, 0, w);
        var palette = medianCutPalette(pixels, maxColors);
        var n = palette.length;
        var rmap = new byte[n];
        var gmap = new byte[n];
        var bmap = new byte[n];
        for (int i = 0; i < n; i++) {
            var c = palette[i];
            rmap[i] = (byte) ((c >> 16) & 0xFF);
            gmap[i] = (byte) ((c >> 8) & 0xFF);
            bmap[i] = (byte) (c & 0xFF);
        }
        // The (bits, size, ...) constructor stores exactly {@code size} palette entries, so the
        // emitted PLTE holds at most {@code n} colours (bounded). Indexed PNGs are required to be
        // 8-bit, so {@code bits} is fixed at 8; the palette bound comes from {@code size}, not from
        // the bit depth. Using a sub-byte bit depth would produce a non-conformant image whose
        // indices do not round-trip through the JDK PNG reader.
        var icm = new IndexColorModel(8, n, rmap, gmap, bmap);
        var indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
        var indices = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            indices[i] = nearestIndex(palette, pixels[i]);
        }
        // Write the palette indices straight into the raster. BufferedImage#setRGB does not store
        // index values reliably for TYPE_BYTE_INDEXED images, so the sample band is set directly.
        indexed.getRaster().setSamples(0, 0, w, h, 0, indices);
        return indexed;
    }

    private static byte[] encode(BufferedImage image) {
        return Try.of(() -> {
                    var out = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, "png", out)) {
                        throw new IllegalStateException("No PNG writer available");
                    }
                    return out.toByteArray();
                })
                .getOrElseThrow(e -> new IllegalStateException("Failed to encode composite PNG", e));
    }

    /**
     * Deterministic median-cut palette over the distinct colors of the composite. The result has at
     * most {@code maxColors} entries; each entry is the mean color of one bucket. No error
     * diffusion is applied, so flat regions map to a single palette entry.
     */
    private static int[] medianCutPalette(int[] pixels, int maxColors) {
        // Collect the distinct colors in first-seen (deterministic) order.
        var distinct = new ArrayList<Integer>();
        var seen = new HashSet<Integer>();
        for (var p : pixels) {
            var rgb = p & 0xFFFFFF;
            if (seen.add(rgb)) {
                distinct.add(rgb);
            }
        }
        if (distinct.size() <= maxColors) {
            return distinct.stream().mapToInt(Integer::intValue).toArray();
        }

        // Repeatedly split the bucket with the largest range along its widest channel at the median.
        var buckets = new ArrayList<List<Integer>>();
        buckets.add(new ArrayList<>(distinct));
        while (buckets.size() < maxColors) {
            var best = findWidestBucket(buckets);
            if (best == null || best.range() == 0) {
                break; // no further meaningful split possible
            }
            var bucket = buckets.remove(best.index());
            var channel = best.channel();
            bucket.sort((a, b) -> {
                var byChannel = Integer.compare(channelValue(a, channel), channelValue(b, channel));
                return byChannel != 0 ? byChannel : Integer.compare(a, b);
            });
            var mid = bucket.size() / 2;
            buckets.add(new ArrayList<>(bucket.subList(0, mid)));
            buckets.add(new ArrayList<>(bucket.subList(mid, bucket.size())));
        }

        // Bucket means form the palette; de-duplicate identical means preserving order.
        var unique = new ArrayList<Integer>();
        for (var bucket : buckets) {
            var mean = meanColor(bucket);
            if (!unique.contains(mean)) {
                unique.add(mean);
            }
        }
        return unique.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The index/range/channel of the bucket whose widest channel has the largest span, or none. */
    private static SplitTarget findWidestBucket(List<List<Integer>> buckets) {
        int bestIndex = -1;
        int bestRange = 0;
        int bestChannel = -1;
        for (int i = 0; i < buckets.size(); i++) {
            var bucket = buckets.get(i);
            if (bucket.size() < 2) {
                continue;
            }
            var ranges = channelRanges(bucket);
            var channel = 0;
            if (ranges[1] > ranges[channel]) {
                channel = 1;
            }
            if (ranges[2] > ranges[channel]) {
                channel = 2;
            }
            if (ranges[channel] > bestRange) {
                bestRange = ranges[channel];
                bestChannel = channel;
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? null : new SplitTarget(bestIndex, bestRange, bestChannel);
    }

    private record SplitTarget(int index, int range, int channel) {}

    private static int[] channelRanges(List<Integer> bucket) {
        int rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0;
        for (var c : bucket) {
            var r = (c >> 16) & 0xFF;
            var g = (c >> 8) & 0xFF;
            var b = c & 0xFF;
            if (r < rMin) rMin = r;
            if (r > rMax) rMax = r;
            if (g < gMin) gMin = g;
            if (g > gMax) gMax = g;
            if (b < bMin) bMin = b;
            if (b > bMax) bMax = b;
        }
        return new int[] {rMax - rMin, gMax - gMin, bMax - bMin};
    }

    private static int channelValue(int rgb, int channel) {
        return switch (channel) {
            case 0 -> (rgb >> 16) & 0xFF;
            case 1 -> (rgb >> 8) & 0xFF;
            default -> rgb & 0xFF;
        };
    }

    private static int meanColor(List<Integer> bucket) {
        long rs = 0, gs = 0, bs = 0;
        for (var c : bucket) {
            rs += (c >> 16) & 0xFF;
            gs += (c >> 8) & 0xFF;
            bs += c & 0xFF;
        }
        var n = bucket.size();
        var r = (int) (rs / n);
        var g = (int) (gs / n);
        var b = (int) (bs / n);
        return (r << 16) | (g << 8) | b;
    }

    /** Nearest palette entry by squared Euclidean RGB distance; ties broken by lower index. */
    private static int nearestIndex(int[] palette, int rgb) {
        var r = (rgb >> 16) & 0xFF;
        var g = (rgb >> 8) & 0xFF;
        var b = rgb & 0xFF;
        var best = 0;
        var bestDist = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            var pr = (palette[i] >> 16) & 0xFF;
            var pg = (palette[i] >> 8) & 0xFF;
            var pb = palette[i] & 0xFF;
            var dr = r - pr;
            var dg = g - pg;
            var db = b - pb;
            var dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
