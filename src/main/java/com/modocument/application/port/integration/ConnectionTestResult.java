package com.modocument.application.port.integration;

/** Outcome of "Test connection" (ws25); {@code message} is safe to show the admin. */
public record ConnectionTestResult(boolean ok, String message, long latencyMillis) {

    public static ConnectionTestResult ok(String message, long latencyMillis) {
        return new ConnectionTestResult(true, message, latencyMillis);
    }

    public static ConnectionTestResult failed(String message, long latencyMillis) {
        return new ConnectionTestResult(false, message, latencyMillis);
    }
}
