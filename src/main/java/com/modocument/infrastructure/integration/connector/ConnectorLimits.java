package com.modocument.infrastructure.integration.connector;

import java.time.Duration;

/**
 * The fixed safety limits every connector applies (ws25): connect and read
 * timeouts on every network call, the largest file/response body accepted,
 * and whether plain {@code http://} is allowed for REST/WEBHOOK targets (off
 * in production — credentials never travel unencrypted; on in tests that
 * use a loopback JDK HTTP stub). The on-prem agent constructs the connectors
 * with its own instance.
 */
public record ConnectorLimits(Duration connectTimeout, Duration readTimeout, long maxFileBytes,
                              long maxResponseBytes, boolean allowPlainHttp) {

    public ConnectorLimits {
        connectTimeout = clamp(connectTimeout, Duration.ofSeconds(10), Duration.ofSeconds(60));
        readTimeout = clamp(readTimeout, Duration.ofSeconds(30), Duration.ofMinutes(10));
        maxFileBytes = maxFileBytes <= 0 ? 50L * 1024 * 1024 : maxFileBytes;
        maxResponseBytes = maxResponseBytes <= 0 ? 20L * 1024 * 1024 : maxResponseBytes;
    }

    /** Production defaults: 10 s connect, 30 s read, 50 MB files, 20 MB bodies, https only. */
    public static ConnectorLimits defaults() {
        return new ConnectorLimits(null, null, 0, 0, false);
    }

    public ConnectorLimits withPlainHttp(boolean allowed) {
        return new ConnectorLimits(connectTimeout, readTimeout, maxFileBytes, maxResponseBytes,
                allowed);
    }

    public ConnectorLimits withMaxFileBytes(long bytes) {
        return new ConnectorLimits(connectTimeout, readTimeout, bytes, maxResponseBytes,
                allowPlainHttp);
    }

    public ConnectorLimits withTimeouts(Duration connect, Duration read) {
        return new ConnectorLimits(connect, read, maxFileBytes, maxResponseBytes, allowPlainHttp);
    }

    private static Duration clamp(Duration value, Duration fallback, Duration max) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value.compareTo(max) > 0 ? max : value;
    }

    public int connectTimeoutSeconds() {
        return (int) Math.max(1, connectTimeout.toSeconds());
    }

    public int readTimeoutSeconds() {
        return (int) Math.max(1, readTimeout.toSeconds());
    }
}
