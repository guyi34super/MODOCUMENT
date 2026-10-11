package com.modocument.connector.core.spi;

/** Outcome of "Test connection"; {@code message} is safe to show an admin. */
public record ConnectionTestResult(boolean ok, String message, long latencyMillis) {

    public static ConnectionTestResult ok(String message, long latencyMillis) {
        return new ConnectionTestResult(true, message, latencyMillis);
    }

    public static ConnectionTestResult failed(String message, long latencyMillis) {
        return new ConnectionTestResult(false, message, latencyMillis);
    }
}
