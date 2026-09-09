package com.vb.wingfoil.tiles;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.hc.core5.http.ClassicHttpResponse;

/**
 * Derives the local validity window for a cached tile from upstream response headers. Honours
 * {@code Cache-Control: max-age/s-maxage}, then the {@code Expires} header; when neither yields a
 * usable value the configured minimum TTL applies. The result is always at least {@code minTtl}
 * into the future (OSM policy floor).
 */
final class TileExpiryParser {

    private TileExpiryParser() {}

    static long resolveExpiresAtEpochMillis(ClassicHttpResponse response, Duration minTtl) {
        var now = System.currentTimeMillis();
        var fallback = now + minTtl.toMillis();

        var cacheControlHeader = response.getFirstHeader("Cache-Control");
        var maxAgeSeconds = parseMaxAgeSeconds(cacheControlHeader == null ? null : cacheControlHeader.getValue());
        if (maxAgeSeconds >= 0) {
            return Math.max(now + maxAgeSeconds * 1000L, fallback);
        }

        var expiresHeader = response.getFirstHeader("Expires");
        if (expiresHeader != null) {
            var parsed = parseHttpDate(expiresHeader.getValue());
            if (parsed != null) {
                return Math.max(parsed, fallback);
            }
        }

        return fallback;
    }

    private static int parseMaxAgeSeconds(String cacheControl) {
        if (cacheControl == null || cacheControl.isBlank()) {
            return -1;
        }
        for (var directive : cacheControl.split(",")) {
            var parts = directive.split("=", 2);
            var name = parts[0].trim().toLowerCase();
            if (!"max-age".equals(name) && !"s-maxage".equals(name)) {
                continue;
            }
            if (parts.length < 2) {
                continue;
            }
            try {
                return Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                // unreadable directive value -> ignore this directive
            }
        }
        return -1;
    }

    private static Long parseHttpDate(String value) {
        try {
            // HTTP dates are RFC 1123 ("Wed, 21 Oct 2015 07:28:00 GMT"); accept ISO-8601 too.
            var trimmed = value.trim();
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(value.trim()).toInstant().toEpochMilli();
            } catch (Exception isoAlsoUnreadable) {
                return null;
            }
        }
    }
}
