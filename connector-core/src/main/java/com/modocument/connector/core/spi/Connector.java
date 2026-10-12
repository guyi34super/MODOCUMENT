package com.modocument.connector.core.spi;

import java.util.Map;

/**
 * One connector implementation, used by the server for DIRECT connections
 * and by the agent for AGENT connections. Rules every implementation keeps:
 * <ul>
 *   <li>every destination passes {@link DestinationGuard#checkHost} right
 *       before connecting;</li>
 *   <li>no free SQL, paths or URLs: only identifiers and relative paths from
 *       the validated spec;</li>
 *   <li>every network call has a connect and a read timeout
 *       ({@code ConnectorLimits}); reads honour {@code maxRecords}; nothing is
 *       retried here (the caller retries);</li>
 *   <li>secrets never appear in exceptions, logs or results.</li>
 * </ul>
 */
public interface Connector {

    ConnectionType type();

    /**
     * Rejects a spec this connector cannot run (unknown keys, bad
     * identifiers, paths escaping the configured folder) with an
     * IllegalArgumentException whose message is safe to show an admin.
     */
    void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound);

    /**
     * Fail-closed pre-flight for a caller that must check before it resolves
     * the secret (the agent): asks the {@link DestinationGuard} about the
     * destination host and the resource {@code operation} would touch, then
     * validates the connection (and the spec, for READ / WRITE). Never reads
     * the secret, never connects.
     */
    void authorize(ConnectionContext connection, Map<String, Object> spec, Operation operation);

    /** "Test connection": connect, authenticate, touch the configured resource, disconnect. */
    ConnectionTestResult test(ConnectionContext connection);

    /** Inbound: one page of records (REST/DATABASE) or files (SFTP) after {@code cursor}. */
    ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec, String cursor,
                   int maxRecords) throws Exception;

    /**
     * Outbound: one batch all-or-nothing (one DB transaction, one request, one
     * atomically renamed file), idempotent on the batch's key where the target
     * allows it.
     */
    WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                      OutboundBatch batch) throws Exception;

    /** Milliseconds since {@code startedNanos} ({@link System#nanoTime()}). */
    static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
