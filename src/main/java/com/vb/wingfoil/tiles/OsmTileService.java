package com.vb.wingfoil.tiles;

import io.micronaut.cache.SyncCache;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.ehcache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches OSM tiles from the upstream source and serves them through the file-system-backed
 * {@code osm-tiles} EhCache. Validity windows are derived from upstream Cache-Control/Expires/Etag
 * headers with a configured minimum-TTL fallback; failed fetches never poison the cache.
 */
@Singleton
public class OsmTileService {

    public static final String CACHE_NAME = "osm-tiles";

    /** Outcome of a tile lookup, mirroring what the client-facing X-Cache header reports. */
    public enum CacheStatus {
        HIT,
        MISS,
        REVALIDATED
    }

    public record TileResult(byte[] png, CacheStatus status, long expiresAtEpochMillis) {}

    /** Upstream returned a non-2xx status or was unreachable; nothing was cached. */
    public static class TileFetchException extends RuntimeException {
        public TileFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OsmTileService.class);

    private final SyncCache<Cache> tileCache;

    private final OsmTilesConfiguration config;

    private final TileUpstreamRequestFactory requestFactory;

    private final CloseableHttpClient httpClient;

    public OsmTileService(@Named(CACHE_NAME) SyncCache<Cache> tileCache, OsmTilesConfiguration config) {
        this.tileCache = tileCache;
        this.config = config;
        this.requestFactory = new TileUpstreamRequestFactory(config);
        this.httpClient = HttpClients.createDefault();
    }

    /**
     * @return a {@link Try} containing the tile bytes plus cache status and validity window;
     *         a {@code Failure} carrying a {@link TileFetchException} when the upstream fetch
     *         fails (non-2xx or IO error)
     */
    public Try<TileResult> getTile(int z, int x, int y) {
        var key = tileKey(z, x, y);
        var now = System.currentTimeMillis();

        var cached = tileCache.get(key, CachedTile.class);
        if (cached.isPresent() && cached.get().expiresAtEpochMillis() > now) {
            return Try.success(new TileResult(
                    cached.get().png(), CacheStatus.HIT, cached.get().expiresAtEpochMillis()));
        }

        return fetchFromUpstream(z, x, y, key, cached.orElse(null));
    }

    private Try<TileResult> fetchFromUpstream(int z, int x, int y, String key, CachedTile previous) {
        var url = buildTileUrl(z, x, y);
        var etag = previous == null ? null : previous.etag();
        var request = requestFactory.createRequest(url, etag);

        log.info(
                "Fetching upstream tile {} (user-agent='{}', referer='{}', if-none-match={})",
                url,
                config.getUserAgent(),
                config.getReferer(),
                etag);

        var attempt = Try.of(() -> httpClient.execute(request, response -> handleResponse(key, response, previous)));
        if (attempt.isFailure()) {
            var cause = attempt.getCause();
            if (cause instanceof TileFetchException expected) {
                return Try.failure(expected);
            }
            log.error("Failed to fetch upstream tile {}", url, cause);
            return Try.failure(new TileFetchException("Upstream tile fetch failed: " + url, cause));
        }
        return attempt;
    }

    private TileResult handleResponse(String key, ClassicHttpResponse response, CachedTile previous)
            throws IOException {
        var statusCode = response.getCode();

        if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
            var refreshed = new CachedTile(
                    previous.png(),
                    System.currentTimeMillis(),
                    TileExpiryParser.resolveExpiresAtEpochMillis(response, config.getMinTtl()),
                    extractEtag(response));
            tileCache.put(key, refreshed);
            return new TileResult(refreshed.png(), CacheStatus.REVALIDATED, refreshed.expiresAtEpochMillis());
        }

        if (statusCode < 200 || statusCode >= 300) {
            EntityUtils.consumeQuietly(response.getEntity());
            throw new TileFetchException("Upstream returned status " + statusCode + " for tile " + key, null);
        }

        var png = EntityUtils.toByteArray(response.getEntity());
        var fresh = new CachedTile(
                png,
                System.currentTimeMillis(),
                TileExpiryParser.resolveExpiresAtEpochMillis(response, config.getMinTtl()),
                extractEtag(response));
        tileCache.put(key, fresh);
        return new TileResult(fresh.png(), CacheStatus.MISS, fresh.expiresAtEpochMillis());
    }

    private String extractEtag(ClassicHttpResponse response) {
        var header = response.getFirstHeader("ETag");
        return header == null ? null : header.getValue();
    }

    public String buildTileUrl(int z, int x, int y) {
        var base = config.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + z + "/" + x + "/" + y + ".png";
    }

    public static String tileKey(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }
}
