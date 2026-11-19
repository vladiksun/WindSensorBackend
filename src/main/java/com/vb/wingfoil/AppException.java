package com.vb.wingfoil;

/**
 * A failure whose intended client response is known: {@code GlobalExceptionHandler} renders exactly the status this
 * exception carries, with this exception's own message as the only client-visible text. The original failure stays as
 * the standard cause — visible in logs and to any caller that inspects the exception chain, never rendered into the
 * response body — so a provider URL, its query string or a raw upstream payload can be kept for diagnosis without
 * reaching the client.
 */
public class AppException extends RuntimeException {

    private final int statusCode;

    public AppException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The HTTP status the client should receive for this failure. */
    public int statusCode() {
        return statusCode;
    }
}
