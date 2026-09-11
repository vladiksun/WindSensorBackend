package com.vb.wingfoil.tiles;

import io.micronaut.cache.SyncCache;
import io.micronaut.http.HttpStatus;
import io.vavr.control.Option;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.ehcache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a single composited PNG covering a requested viewport. It computes the slippy-map tile
 * neighbourhood that covers the {@code width × height} rectangle centred on {@code (lat, lon)} at
 * zoom {@code z} (Web-Mercator math), fetches those tiles in parallel on virtual threads through
 * {@link OsmTileService}, stitches them onto an exact-size {@link BufferedImage} canvas, and caches
 * the result in the file-system-backed {@code osm-composites} EhCache. Failed or out-of-world tile
 * regions are filled with a neutral gray so a partial upstream outage still yields a displayable
 * image rather than a hard failure.
 */
@Singleton
public class TileCompositeService {

    public static final String CACHE_NAME = "osm-composites";

    /** Native slippy-map tile edge length in pixels. */
    public static final int TILE_SIZE = 256;

    /** Maximum accepted viewport edge length in pixels (guards against excessive allocation). */
    public static final int MAX_DIMENSION = 1024;

    /** Fill colour for tile regions that failed to load (matches the watch app's failed-tile gray). */
    public static final Color FAILED_TILE_COLOR = new Color(192, 192, 192);

    /** Web-Mercator latitude limit beyond which the projection is undefined. */
    private static final double MAX_LATITUDE = 85.05112878;

    private static final Logger log = LoggerFactory.getLogger(TileCompositeService.class);

    /**
     * Outcome of a composite request.
     *
     * @param png                    encoded PNG of exactly the requested {@code width × height}
     * @param partial                true when at least one component tile was missing (gray-filled)
     * @param expiresAtEpochMillis   minimum expiry across the component tiles (composite validity)
     * @param status                 cache status reported via the client-facing X-Cache header
     */
    public record CompositeResult(
            byte[] png, boolean partial, long expiresAtEpochMillis, OsmTileService.CacheStatus status) {}

    private final SyncCache<Cache> compositeCache;

    private final OsmTileService tileService;

    private final OsmTilesConfiguration config;

    private final MapImageOptimizer optimizer;

    public TileCompositeService(
            @Named(CACHE_NAME) SyncCache<Cache> compositeCache,
            OsmTileService tileService,
            OsmTilesConfiguration config,
            MapImageOptimizer optimizer) {
        this.compositeCache = compositeCache;
        this.tileService = tileService;
        this.config = config;
        this.optimizer = optimizer;
    }

    /**
     * Compose a {@code width × height} PNG centred on {@code (lat, lon)} at zoom {@code z}.
     *
     * @param skipCache when {@code true}, the composite cache is bypassed (component tile caches are
     *        still consulted); the rebuilt composite is stored either way
     * @throws TileProxyException with {@code BAD_GATEWAY} when every component tile fails
     */
    public CompositeResult compose(int z, double lat, double lon, int width, int height, boolean skipCache) {
        var optimization = config.getOptimization();
        var key = compositeKey(
                z, lat, lon, width, height, modeSegment(optimization.isEnabled(), optimization.getColorsMode()));
        var now = System.currentTimeMillis();

        if (!skipCache) {
            var cached = compositeCache.get(key, CachedComposite.class);
            if (cached.isPresent() && cached.get().expiresAtEpochMillis() > now) {
                return new CompositeResult(
                        cached.get().png(), false, cached.get().expiresAtEpochMillis(), OsmTileService.CacheStatus.HIT);
            }
        }

        var viewport = Viewport.of(z, lat, lon, width, height);
        var tileRefs = coveredTiles(viewport);
        var fetched = fetchInParallel(z, viewport.n(), tileRefs);

        var present = new ArrayList<TileData>();
        for (var data : fetched) {
            data.peek(present::add);
        }
        if (present.isEmpty()) {
            throw new TileProxyException(
                    HttpStatus.BAD_GATEWAY, "All component tiles failed for composite " + key, null);
        }

        var partial = present.size() != tileRefs.size();
        var minExpiry =
                present.stream().mapToLong(TileData::expiresAtEpochMillis).min().orElse(now);

        var image = render(viewport, tileRefs, fetched, width, height);
        // Disabled path keeps the legacy encoder (byte-compatible with pre-change output); enabled
        // routes through the optimizer for the configured color mode.
        var png = optimization.isEnabled() ? optimizer.optimize(image, optimization.getColorsMode()) : encodePng(image);
        compositeCache.put(key, new CachedComposite(png, now, minExpiry));
        log.debug("Composed {} ({} tiles, {} present, partial={})", key, tileRefs.size(), present.size(), partial);
        return new CompositeResult(png, partial, minExpiry, OsmTileService.CacheStatus.MISS);
    }

    /**
     * Normalized composite cache key:
     * {@code composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/{mode}}, where {@code mode} is the
     * effective optimization-mode segment (see {@link #modeSegment(boolean, MapTileColorMode)}) so
     * different representations of the same viewport never collide.
     */
    public static String compositeKey(int z, double lat, double lon, int width, int height, String mode) {
        return String.format(Locale.ROOT, "composite/%d/%.6f/%.6f/%dx%d/%s", z, lat, lon, width, height, mode);
    }

    /**
     * Effective optimization-mode segment for a composite cache key. Disabled always maps to
     * {@code off} regardless of the configured color count.
     */
    public static String modeSegment(boolean enabled, MapTileColorMode colors) {
        if (!enabled) {
            return "off";
        }
        return switch (colors) {
            case ORIGINAL -> "original";
            case COLORS_16 -> "c16";
            case COLORS_32 -> "c32";
            case COLORS_64 -> "c64";
        };
    }

    /** Fractional slippy-map x tile index for a longitude (mirrors the watch app's formula). */
    static double mercatorXTile(double lon, int z) {
        return (lon + 180.0) / 360.0 * (1L << z);
    }

    /** Fractional slippy-map y tile index for a latitude (mirrors the watch app's formula). */
    static double mercatorYTile(double lat, int z) {
        var clamped = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
        var latRad = Math.toRadians(clamped);
        var cosLat = Math.cos(latRad);
        var tanLat = Math.sin(latRad) / cosLat;
        return (1.0 - Math.log(tanLat + 1.0 / cosLat) / Math.PI) / 2.0 * (1L << z);
    }

    /** The integer slippy-map tile containing the given coordinate at the given zoom. */
    static long[] centreTileIndex(int z, double lat, double lon) {
        return new long[] {(long) Math.floor(mercatorXTile(lon, z)), (long) Math.floor(mercatorYTile(lat, z))};
    }

    private List<TileRef> coveredTiles(Viewport vp) {
        var refs = new ArrayList<TileRef>();
        for (long x = vp.minX(); x <= vp.maxX(); x++) {
            for (long y = vp.minY(); y <= vp.maxY(); y++) {
                refs.add(new TileRef(x, y));
            }
        }
        return refs;
    }

    private List<Option<TileData>> fetchInParallel(int z, long n, List<TileRef> tileRefs) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Option<TileData>>>(tileRefs.size());
            for (var ref : tileRefs) {
                futures.add(CompletableFuture.supplyAsync(() -> fetchTile(z, n, ref), executor));
            }
            var results = new ArrayList<Option<TileData>>(futures.size());
            for (var future : futures) {
                results.add(future.join());
            }
            return results;
        }
    }

    private Option<TileData> fetchTile(int z, long n, TileRef ref) {
        if (ref.x() < 0 || ref.x() >= n || ref.y() < 0 || ref.y() >= n) {
            log.debug("Skipping out-of-world tile {}/{}", ref.x(), ref.y());
            return Option.none();
        }
        // Component tile caches are always consulted (skipCache only bypasses the composite cache),
        // so the per-tile lookup runs in its normal mode.
        return tileService
                .getTile(z, (int) ref.x(), (int) ref.y(), false)
                .map(result -> new TileData(result.png(), result.expiresAtEpochMillis()))
                .toOption();
    }

    private BufferedImage render(
            Viewport vp, List<TileRef> tileRefs, List<Option<TileData>> fetched, int width, int height) {
        var canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = canvas.createGraphics();
        try {
            // Pre-fill the whole canvas with the failed-tile gray; successful tiles overdraw it, so
            // any region without a loaded tile is left gray.
            g.setColor(FAILED_TILE_COLOR);
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < tileRefs.size(); i++) {
                var ref = tileRefs.get(i);
                fetched.get(i).peek(data -> readImage(data.png()).peek(img -> {
                    var dx = (int) (ref.x() * TILE_SIZE - vp.leftPx());
                    var dy = (int) (ref.y() * TILE_SIZE - vp.topPx());
                    g.drawImage(img, dx, dy, null);
                }));
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static Option<BufferedImage> readImage(byte[] png) {
        return Try.of(() -> ImageIO.read(new ByteArrayInputStream(png))).toOption();
    }

    private static byte[] encodePng(BufferedImage image) {
        return Try.of(() -> {
                    var out = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", out);
                    return out.toByteArray();
                })
                .get();
    }

    /** A slippy-map tile coordinate to be fetched and placed on the canvas. */
    private record TileRef(long x, long y) {}

    /** A successfully fetched tile plus its validity window. */
    private record TileData(byte[] png, long expiresAtEpochMillis) {}

    /**
     * The set of tiles intersecting the viewport, together with the integer top-left canvas offset
     * ({@code leftPx}/{@code topPx}) used to place each tile. Offsets are rounded so adjacent tiles
     * join seamlessly on the integer pixel grid and the requested centre lands within half a pixel
     * of the image centre.
     */
    private record Viewport(long minX, long maxX, long minY, long maxY, long leftPx, long topPx, long n) {

        static Viewport of(int z, double lat, double lon, int width, int height) {
            var n = 1L << z;
            var centreXPix = mercatorXTile(lon, z) * TILE_SIZE;
            var centreYPix = mercatorYTile(lat, z) * TILE_SIZE;
            var leftPx = Math.round(centreXPix - width / 2.0);
            var topPx = Math.round(centreYPix - height / 2.0);
            var rightPx = leftPx + width;
            var bottomPx = topPx + height;
            var minX = Math.floorDiv(leftPx, TILE_SIZE);
            var maxX = (long) Math.ceil(rightPx / (double) TILE_SIZE) - 1;
            var minY = Math.floorDiv(topPx, TILE_SIZE);
            var maxY = (long) Math.ceil(bottomPx / (double) TILE_SIZE) - 1;
            return new Viewport(minX, maxX, minY, maxY, leftPx, topPx, n);
        }
    }
}
