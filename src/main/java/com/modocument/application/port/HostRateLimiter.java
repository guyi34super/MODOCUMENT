package com.modocument.application.port;

/** Enforces a minimum interval between requests to the same host. */
public interface HostRateLimiter {

    /**
     * Blocks until a request to the host is polite, enforcing at least
     * minIntervalMillis since the previous request to it.
     */
    void acquire(String host, long minIntervalMillis);
}
