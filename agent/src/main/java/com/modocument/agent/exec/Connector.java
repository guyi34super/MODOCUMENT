package com.modocument.agent.exec;

import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets.Secret;
import com.modocument.connector.core.spi.DestinationGuard;

import java.util.List;
import java.util.Map;

/**
 * The agent's connectors (DATABASE, REST_API, SFTP), as the task executor
 * drives them. Each is a thin adapter over connector-core — the same
 * implementation the server runs for DIRECT connections
 * (docs/integration/connector-sharing.md) — with the bank's allow-list as
 * the destination guard and the local secret store as the secret source.
 */
public interface Connector {

    /** DATABASE, REST_API or SFTP. */
    String type();

    /**
     * Checks every destination, table, folder and path the task would touch
     * against the bank's allow-list, then validates the task's config and
     * spec — BEFORE anything is opened and before the secret is resolved.
     */
    void authorize(TaskContext task, AllowList allowList);

    /** Connect, authenticate, touch the configured resource, disconnect; throws when that fails. */
    void test(TaskContext task) throws Exception;

    /** One page of at most {@code max} records after {@code cursor}. */
    ReadPage read(TaskContext task, String cursor, int max) throws Exception;

    /** Writes one outbound batch (in the connection's write chunks, each all-or-nothing). */
    WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch) throws Exception;

    /**
     * A task with its resolved local secret and the guard asked again right
     * before every connection. The task executor passes the bank's
     * allow-list; the two-argument form (unit tests of a connector) re-checks
     * nothing at connect time — {@link #authorize} is the fail-closed check.
     */
    record TaskContext(Protocol.AgentTask task, Secret secret, DestinationGuard guard) {

        public TaskContext(Protocol.AgentTask task, Secret secret) {
            this(task, secret, (host, port) -> { });
        }
    }

    /**
     * One page read. {@code afterAccepted} runs once MoDocument accepted the
     * page (moving pulled SFTP files to {@code processed/}); {@code notes}
     * name what the read skipped (a file over the size cap).
     */
    record ReadPage(List<Map<String, Object>> records, String nextCursor, boolean hasMore,
                    Runnable afterAccepted, List<String> notes) {
        public ReadPage {
            records = records == null ? List.of() : records;
            afterAccepted = afterAccepted == null ? () -> { } : afterAccepted;
            notes = notes == null ? List.of() : notes;
        }
    }

    record WriteOutcome(int recordsWritten, String remoteReference) {
    }
}
