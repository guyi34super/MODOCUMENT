package com.modocument.connector.core.spi;

/**
 * The destination seam (connector-sharing.md §4), asked before a connector
 * opens anything. The server adapts its {@code EgressGuard} (bank egress
 * rules + the global outbound host policy: host checks only); the agent
 * adapts its bank-owned {@code allowlist.yml} (hosts, tables, SFTP folders,
 * REST path prefixes). A refusal is a {@link RuntimeException} whose message
 * names the destination, never a credential.
 *
 * <ul>
 *   <li>{@link #checkHost} runs right before every connection (database,
 *       SFTP, every HTTP request including an OAuth2 token call) and from
 *       {@link Connector#authorize};</li>
 *   <li>the resource checks run from {@link Connector#authorize}, before the
 *       secret is resolved, with the names as the spec gives them (a name
 *       that is not a valid identifier or path fails validation right
 *       after).</li>
 * </ul>
 */
@FunctionalInterface
public interface DestinationGuard {

    /** Refuses a host:port the connection may not reach. */
    void checkHost(String host, int port);

    /** Refuses a table: {@code table}, or {@code schema.table} when the connection names a schema. */
    default void checkTable(String qualifiedName) {
    }

    /** Refuses an SFTP folder: absolute and normalized ({@code /base/sub}). */
    default void checkFolder(String folder) {
    }

    /** Refuses a REST request path: absolute (the base URL's path + the spec's path). */
    default void checkPath(String path) {
    }
}
