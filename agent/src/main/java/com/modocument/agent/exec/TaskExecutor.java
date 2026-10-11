package com.modocument.agent.exec;

import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.allowlist.AllowListViolation;
import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.cloud.CloudException;
import com.modocument.agent.cloud.Protocol;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.agent.secrets.LocalSecrets.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs one task from MoDocument: picks the connector, checks the task
 * against the bank's ALLOW-LIST before anything is opened, resolves the
 * local secret, executes READ / WRITE / TEST and reports the outcome
 * ({@code POST /runs/{id}/records} pages, then {@code /result}).
 *
 * <p>A refused or failed task is reported as {@code success=false} with a
 * message that has every local secret value masked; nothing secret is
 * logged. The last outcome per connection feeds the heartbeat.
 */
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);
    private static final int MAX_ERROR = 1000;
    private static final int MAX_PAGES = 10_000;

    private final Map<String, Connector> connectors;
    private final AllowList allowList;
    private final LocalSecrets secrets;
    private final CloudClient cloud;
    private final Map<Long, Protocol.ConnectionHealth> health = new ConcurrentHashMap<>();

    public TaskExecutor(List<Connector> connectors, AllowList allowList, LocalSecrets secrets,
                        CloudClient cloud) {
        this.connectors = connectors.stream()
                .collect(Collectors.toMap(Connector::type, Function.identity()));
        this.allowList = allowList;
        this.secrets = secrets;
        this.cloud = cloud;
    }

    /** Executes and reports one task. Cloud errors while reporting propagate (the loop backs off). */
    public void execute(Protocol.AgentTask task) {
        Secret secret = Secret.EMPTY;
        String operation = task.operation() == null ? "" : task.operation().toUpperCase(Locale.ROOT);
        try {
            Connector connector = connectors.get(task.type());
            if (connector == null) {
                throw new IllegalArgumentException("Connection type " + task.type()
                        + " is not supported by the agent.");
            }
            Connector.TaskContext context = new Connector.TaskContext(task, Secret.EMPTY);
            // Allow-list FIRST: no secret is even looked up for a refused task.
            connector.authorize(context, allowList);
            secret = secrets.resolve(task.secretRef());
            // The allow-list is asked again right before every connection.
            context = new Connector.TaskContext(task, secret, CoreConnector.guard(allowList));
            switch (operation) {
                case "TEST" -> {
                    connector.test(context);
                    report(task, new Protocol.ResultRequest(true, 0, 0, null, null), "connection OK");
                }
                case "READ" -> read(connector, context);
                case "WRITE" -> {
                    Protocol.OutboundBatch batch = cloud.payload(task.runId());
                    Connector.WriteOutcome outcome = connector.write(context, batch);
                    report(task, new Protocol.ResultRequest(true, 0, outcome.recordsWritten(), null,
                            outcome.remoteReference()), "wrote " + outcome.recordsWritten());
                }
                default -> throw new IllegalArgumentException("Unknown operation " + operation + ".");
            }
            log.info("Run {} ({} {} on connection {}) succeeded", task.runId(), operation, task.type(),
                    task.connectionId());
        } catch (CloudException e) {
            throw e;
        } catch (AllowListViolation e) {
            log.warn("Run {} REFUSED by the local allow-list: {}", task.runId(), e.getMessage());
            fail(task, "Refused by the agent's local allow-list: " + e.getMessage(), secret);
        } catch (Exception e) {
            String message = describe(e);
            log.warn("Run {} ({} {} on connection {}) failed: {}", task.runId(), operation, task.type(),
                    task.connectionId(), mask(message, secret.all()));
            fail(task, message, secret);
        }
    }

    /** The last known health of every connection this agent ran a task for. */
    public List<Protocol.ConnectionHealth> health() {
        return new ArrayList<>(health.values());
    }

    private void read(Connector connector, Connector.TaskContext context) throws Exception {
        Protocol.AgentTask task = context.task();
        int remaining = task.maxRecords() > 0 ? task.maxRecords() : 10_000;
        String cursor = task.cursor();
        int total = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Connector.ReadPage read = connector.read(context, cursor, remaining);
            for (String note : read.notes()) {
                log.warn("Run {}: {}", task.runId(), note);
            }
            List<Map<String, Object>> records = read.records().size() > remaining
                    ? read.records().subList(0, remaining) : read.records();
            total += records.size();
            remaining -= records.size();
            boolean last = !read.hasMore() || remaining <= 0 || records.isEmpty();
            cloud.records(task.runId(), new Protocol.RecordsRequest(records, read.nextCursor(), last));
            read.afterAccepted().run();
            cursor = read.nextCursor();
            if (last) {
                break;
            }
        }
        report(task, new Protocol.ResultRequest(true, total, 0, null, null), "read " + total);
    }

    private void report(Protocol.AgentTask task, Protocol.ResultRequest result, String healthMessage) {
        cloud.result(task.runId(), result);
        health.put(task.connectionId(), new Protocol.ConnectionHealth(task.connectionId(),
                result.success(), healthMessage));
    }

    private void fail(Protocol.AgentTask task, String message, Secret secret) {
        String safe = truncate(mask(message, secret.all()));
        report(task, new Protocol.ResultRequest(false, 0, 0, safe, null), safe);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException && message != null) {
            return message;
        }
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Masks every local secret value (and its URL-encoded form) in a message. */
    static String mask(String message, Collection<String> secretValues) {
        if (message == null) {
            return null;
        }
        String masked = message;
        for (String value : secretValues) {
            if (value != null && value.length() >= 3) {
                masked = masked.replace(value, "***");
                masked = masked.replace(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8),
                        "***");
            }
        }
        return masked;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message;
    }
}
