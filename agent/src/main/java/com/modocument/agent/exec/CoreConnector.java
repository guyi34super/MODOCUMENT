package com.modocument.agent.exec;

import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets.Secret;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionTestResult;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.DestinationGuard;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.spi.SecretMaterial;
import com.modocument.connector.core.spi.WriteResult;
import com.modocument.connector.core.support.ConnectorErrors;
import com.modocument.connector.core.support.ConnectorLimits;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The agent's adapter over one connector-core {@link
 * com.modocument.connector.core.spi.Connector}: maps the task to a
 * {@link ConnectionContext} whose {@link DestinationGuard} is the bank's
 * {@link AllowList} and whose secret is the local one, and the core's
 * results back to what the task executor reports.
 *
 * <p>The agent never requires secret keys up front (the bank's secret store
 * is not MoDocument's to validate): a key an auth type needs and the local
 * secret lacks fails at call time; a database password stays optional.
 */
public class CoreConnector implements Connector {

    /**
     * The agent's connector limits: 10 s connect, 120 s per call (the bank
     * database's query timeout), 50 MB files, 20 MB responses; plain
     * {@code http://} allowed for bank-internal APIs (the server's connection
     * validation decides what a flow may target).
     */
    public static final ConnectorLimits LIMITS = new ConnectorLimits(Duration.ofSeconds(10),
            Duration.ofSeconds(120), 50L * 1024 * 1024, 20L * 1024 * 1024, true);

    private final com.modocument.connector.core.spi.Connector core;

    protected CoreConnector(com.modocument.connector.core.spi.Connector core) {
        this.core = core;
    }

    @Override
    public String type() {
        return core.type().name();
    }

    @Override
    public void authorize(TaskContext task, AllowList allowList) {
        core.authorize(context(new TaskContext(task.task(), Secret.EMPTY, guard(allowList))),
                task.task().spec(), operation(task.task()));
    }

    @Override
    public void test(TaskContext task) throws Exception {
        ConnectionContext context = context(task);
        core.authorize(context, Map.of(), Operation.TEST);
        ConnectionTestResult result = core.test(context);
        if (!result.ok()) {
            throw new IllegalStateException(type() + " connection test failed: " + result.message());
        }
    }

    @Override
    public ReadPage read(TaskContext task, String cursor, int max) throws Exception {
        ReadBatch batch = core.read(context(task), task.task().spec(), cursor, pageLimit(task, max));
        Runnable afterAccepted = () -> {
            try {
                batch.acknowledgeAll();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(ConnectorErrors.safeMessage(e));
            }
        };
        return new ReadPage(records(batch), batch.nextCursor(), batch.hasMore(), afterAccepted,
                batch.notes());
    }

    /** One write call per chunk (the server's dispatcher chunks DIRECT deliveries the same way). */
    @Override
    public WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch) throws Exception {
        OutboundBatch whole = new OutboundBatch(batch.idempotencyKey(), batch.kind(), batch.format(),
                batch.records(), batch.fileName(), batch.contentType(), batch.fileContent());
        int written = 0;
        String reference = null;
        for (OutboundBatch chunk : whole.chunks(OutboundBatch.batchSize(core.type(), task.task().spec()))) {
            WriteResult result = core.write(context(task), task.task().spec(), chunk);
            written += result.recordsWritten();
            reference = result.remoteReference();
        }
        return new WriteOutcome(written, reference);
    }

    /** The {@code maxRecords} of one core read; connectors cap it to what one records POST takes. */
    protected int pageLimit(TaskContext task, int max) {
        return max;
    }

    /** The records MoDocument receives for a page. */
    protected List<Map<String, Object>> records(ReadBatch batch) {
        return batch.records();
    }

    static ConnectionContext context(TaskContext task) {
        Protocol.AgentTask agentTask = task.task();
        return new ConnectionContext("agent", agentTask.connectionId(), null,
                ConnectionType.valueOf(agentTask.type()), false, agentTask.config(),
                secret(task.secret()), task.guard());
    }

    private static Operation operation(Protocol.AgentTask task) {
        String operation = task.operation() == null ? "" : task.operation().toUpperCase(Locale.ROOT);
        return switch (operation) {
            case "READ" -> Operation.READ;
            case "WRITE" -> Operation.WRITE;
            default -> Operation.TEST;
        };
    }

    /** The bank's allow-list as the destination guard. */
    static DestinationGuard guard(AllowList allowList) {
        return new DestinationGuard() {
            @Override
            public void checkHost(String host, int port) {
                allowList.checkDestination(host, port);
            }

            @Override
            public void checkTable(String qualifiedName) {
                allowList.checkTable(qualifiedName);
            }

            @Override
            public void checkFolder(String folder) {
                allowList.checkFolder(folder);
            }

            @Override
            public void checkPath(String path) {
                allowList.checkRestPath(path);
            }
        };
    }

    /** The local secret as secret material: a single unnamed secret answers any key. */
    private static SecretMaterial secret(Secret secret) {
        return new SecretMaterial() {
            @Override
            public String get(String key) {
                return secret.get(key);
            }

            @Override
            public boolean contains(String key) {
                return secret.values().containsKey(key);
            }

            @Override
            public String toString() {
                return secret.toString();
            }
        };
    }
}
