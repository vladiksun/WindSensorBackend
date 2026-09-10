package com.vb.wingfoil.tiles;

import java.io.Serializable;

/**
 * A cached composite map image stored in the file-system-backed {@code osm-composites} EhCache. As
 * with {@link CachedTile}, validity is managed by the application (the EhCache provider exposes no
 * TTL property), so the expiry instant travels with the bytes.
 *
 * @param png                  raw PNG bytes of the composited {@code width × height} image
 * @param fetchedAtEpochMillis when the composite was last built
 * @param expiresAtEpochMillis end of the validity window, the minimum expiry among all component
 *                             tiles used to build the composite
 */
public record CachedComposite(byte[] png, long fetchedAtEpochMillis, long expiresAtEpochMillis)
        implements Serializable {}
