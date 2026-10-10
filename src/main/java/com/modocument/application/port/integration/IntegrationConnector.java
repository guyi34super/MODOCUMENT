package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.ConnectionType;

import java.util.Map;

/**
 * Connector SPI of the bank integration hub (ws25, SE4). One Spring bean per
 * {@link ConnectionType}; the flow engine picks it by {@link #type()}. The
 * same implementations run inside the on-prem agent for AGENT-mode
 * connections (the agent resolves secrets from its own local store).
 *
 * <p>Rules every implementation keeps:
 * <ul>
 *   <li>every destination is checked with {@link EgressGuard} immediately
 *       before connecting (DIRECT mode);</li>
 *   <li>no free SQL / paths / URLs from requests — only identifiers and
 *       relative paths from the stored, validated spec;</li>
 *   <li>every network call has a connect and a read timeout; reads honour
 *       {@code maxRecords}; nothing is retried here (the engine retries);</li>
 *   <li>secrets never appear in exceptions, logs or results.</li>
 * </ul>
 */
public interface IntegrationConnector {

    ConnectionType type();

    /**
     * Rejects a spec this connector cannot run (unknown keys, bad identifiers,
     * paths escaping the configured folder) with an IllegalArgumentException
     * whose message is safe to show the admin. Called on save and before
     * every run.
     */
    void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound);

    /** "Test connection": connect, authenticate, touch the configured resource, disconnect. */
    ConnectionTestResult test(ConnectionContext connection);

    /**
     * Inbound: one page of records (REST/DATABASE) or files (SFTP) after
     * {@code cursor} (null = from the start / the stored watermark).
     */
    ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec, String cursor,
                   int maxRecords) throws Exception;

    /**
     * Outbound: write one batch all-or-nothing (one DB transaction, one
     * request, one atomically renamed file). Must be idempotent on
     * {@link OutboundBatch#idempotencyKey()} where the target allows it
     * (UPSERT, Idempotency-Key header, deterministic file name).
     */
    WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                      OutboundBatch batch) throws Exception;
}
