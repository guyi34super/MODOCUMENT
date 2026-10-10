package com.modocument.infrastructure.ratelimit;

/**
 * Thrown when a caller exceeds a rate limit. Mapped to HTTP 429 by the global
 * {@code ApiExceptionHandler}, which also advertises {@link #retryAfterSeconds}
 * as the {@code Retry-After} header — the throwing limiter knows how much of
 * its window remains, so well-behaved clients can back off for exactly that
 * long instead of guessing. The message is safe to show the user.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Seconds the caller should wait before retrying (never below 1). */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
