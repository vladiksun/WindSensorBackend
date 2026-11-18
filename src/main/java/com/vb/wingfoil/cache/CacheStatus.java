package com.vb.wingfoil.cache;

/**
 * Whether a {@code /sensor-data} response was answered from the local cache. Reported to clients as the
 * {@code X-Cache} header, mirroring what the tile proxy already reports, so an operator can tell "the provider is
 * broken" from "we are serving a cached reading".
 */
public enum CacheStatus {

    /** A valid entry answered the request; no upstream call was made. */
    HIT,

    /** No valid entry existed when the request arrived, so this response came from a provider call. */
    MISS
}
