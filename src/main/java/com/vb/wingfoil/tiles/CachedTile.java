package com.vb.wingfoil.tiles;

import java.io.Serializable;

/**
 * A cached map tile entry stored in the file-system-backed {@code osm-tiles} EhCache. Validity is
 * managed by the application (the EhCache provider exposes no TTL property), so the expiry instant
 * travels with the bytes.
 *
 * @param png                  raw PNG bytes of the upstream tile
 * @param fetchedAtEpochMillis when the bytes were last fetched from upstream
 * @param expiresAtEpochMillis end of the validity window derived from upstream Cache-Control/Expires
 *                             headers, or the configured minimum-TTL fallback
 * @param etag                 upstream Etag (may be null), used for conditional refetches
 */
public record CachedTile(byte[] png, long fetchedAtEpochMillis, long expiresAtEpochMillis, String etag)
        implements Serializable {}
