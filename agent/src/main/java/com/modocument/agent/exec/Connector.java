package com.modocument.agent.exec;

import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets.Secret;

import java.util.List;
import java.util.Map;

/**
 * The agent's minimal, self-contained connector set (DATABASE, REST_API,
 * SFTP). It mirrors the rules of the server's IntegrationConnector SPI —
 * identifiers only (never free SQL), paths confined, timeouts on every
 * call, secrets never in messages — without depending on the main
 * application jar. Sharing one implementation with the server's connectors
 * is a later refactor.
 */
public interface Connector {

    /** DATABASE, REST_API or SFTP. */
    String type();

    /**
     * Validates the task's config/spec and checks every destination, table,
     * folder and path it would touch against the bank's allow-list — BEFORE
     * anything is opened.
     */
    void authorize(TaskContext task, AllowList allowList);

    /** Connect, authenticate, touch the configured resource, disconnect. */
    void test(TaskContext task) throws Exception;

    /** One page of at most {@code max} records after {@code cursor}. */
    ReadPage read(TaskContext task, String cursor, int max) throws Exception;

    /** Writes one outbound batch all-or-nothing. */
    WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch) throws Exception;

    /** A task with its resolved local secret. */
    record TaskContext(Protocol.AgentTask task, Secret secret) {

        public String config(String key) {
            String value = task.config().get(key);
            return value == null || value.isBlank() ? null : value.strip();
        }

        public String requireConfig(String key) {
            String value = config(key);
            if (value == null) {
                throw new IllegalArgumentException("The connection has no '" + key + "'.");
            }
            return value;
        }

        public Object spec(String key) {
            return task.spec().get(key);
        }

        public String specText(String key) {
            Object value = task.spec().get(key);
            return value == null ? null : String.valueOf(value).strip();
        }

        public String requireSpecText(String key) {
            String value = specText(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("The task spec has no '" + key + "'.");
            }
            return value;
        }

        public List<String> specList(String key) {
            Object value = task.spec().get(key);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException("The task spec '" + key + "' must be a list.");
            }
            return list.stream().map(item -> String.valueOf(item).strip()).toList();
        }

        public int port(int fallback) {
            String value = config("port");
            if (value == null) {
                return fallback;
            }
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("The connection port is not a port number.");
            }
        }
    }

    /**
     * One page read. {@code afterAccepted} runs once MoDocument accepted the
     * page (e.g. moving pulled SFTP files to {@code processed/}).
     */
    record ReadPage(List<Map<String, Object>> records, String nextCursor, boolean hasMore,
                    Runnable afterAccepted) {
        public ReadPage {
            records = records == null ? List.of() : records;
            afterAccepted = afterAccepted == null ? () -> { } : afterAccepted;
        }
    }

    record WriteOutcome(int recordsWritten, String remoteReference) {
    }
}
