package com.modocument.infrastructure.ratelimit;

/**
 * Thrown when a caller exceeds a rate limit. Mapped to HTTP 429 by the global
 * {@code ApiExceptionHandler}. The message is safe to show the user.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
