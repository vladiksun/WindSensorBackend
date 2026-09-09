package com.vb.wingfoil.tiles;

import io.micronaut.http.HttpStatus;

/**
 * Carries the HTTP status that {@code GlobalExceptionHandler} should render for a tile-proxy failure.
 */
public class TileProxyException extends RuntimeException {

    private final HttpStatus status;

    public TileProxyException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public TileProxyException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
