package com.modocument.infrastructure.alerting;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.application.privacy.PiiPatterns;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.model.AlertStatus;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Hashing;
import com.modocument.infrastructure.async.TenantTaskDecorator;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raises, records and delivers security alerts (ws18, gate D1/D2, SEC-032).
 *
 * <p>Life of an alert:
 * <ol>
 *   <li><b>Dedupe</b> — one alert per rule/bank/group per rule window: an
 *       in-memory first line plus a persisted check on
 *       AlertLog.DedupeKey (SHA-256 of rule|bank|group), so a burst raises
 *       ONE alert even across restarts;</li>
 *   <li><b>Record</b> — an AlertLog row (PENDING) under
 *       {@code TenantContext.runAs(alert bank)} in its own transaction;</li>
 *   <li><b>Deliver</b> — to every applicable {@link AlertChannel} on the
 *       alert executor (bounded, {@link TenantTaskDecorator}-wrapped so the
 *       request's MDC — requestId — follows the alert), with
 *       {@code delivery.attempts} tries per channel; each acceptance is
 *       audited ALERT_SENT, each failure ALERT_DELIVERY_FAILED; the row
 *       becomes SENT (DeliveredAt stamped) once every applicable channel
 *       accepted it;</li>
 *   <li><b>Retry</b> — the minute sweep re-delivers PENDING rows to the
 *       channels still owed, until {@code delivery.retry-window} passes
 *       (then FAILED + a final ALERT_DELIVERY_FAILED).</li>
 * </ol>
 * Nothing here ever runs inside a business transaction: the immediate path
 * enters through {@link #raiseAsync} from a post-commit listener and returns
 * at once; a saturated executor degrades to logging the alert (the log
 * channel is the SIEM path) rather than blocking the caller.
 *
 * <p>Tenancy (see the call-site registry in {@link TenantContext}): every
 * AlertLog write and every ALERT_* audit row runs under
 * {@code runAs(alert bank)} — the alert lands in, and is only visible to,
 * the bank it is about. Only the sweep's retry SCAN reads across banks, as
 * SYSTEM.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** A first dispatch gets this long before the sweep may retry the row. */
    static final Duration RETRY_MIN_AGE = Duration.ofSeconds(60);
    private static final int RETRY_BATCH = 50;
    private static final int SUMMARY_MAX = 500;

    private final AlertProperties properties;
    private final List<AlertChannel> channels;
    private final AlertLogJpaRepository alertLogs;
    private final AuditRecorder audit;
    private final TransactionTemplate requiresNew;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, Instant> lastRaised = new ConcurrentHashMap<>();

    public AlertService(AlertProperties properties, List<AlertChannel> channels,
                        AlertLogJpaRepository alertLogs, AuditRecorder audit,
                        PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.channels = List.copyOf(channels);
        this.alertLogs = alertLogs;
        this.audit = audit;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A private pool, deliberately NOT a bean: an Executor bean would make
        // Boot back off its applicationTaskExecutor (MVC async uses it).
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("alert-");
        executor.setCorePoolSize(Math.max(1, properties.getDelivery().getThreads()));
        executor.setMaxPoolSize(Math.max(1, properties.getDelivery().getThreads()));
        executor.setQueueCapacity(Math.max(1, properties.getDelivery().getQueueCapacity()));
        executor.setTaskDecorator(new TenantTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ---- entry points ------------------------------------------------------

    /**
     * Immediate path: called post-commit by {@link ImmediateAlertListener} on
     * the business thread; returns at once. Dedupe, persistence and delivery
     * all happen on the alert executor.
     */
    public void raiseAsync(AlertRule rule, AuditFacts facts) {
        AlertCandidate candidate = new AlertCandidate(rule, facts.bankId(), rule.groupKey(facts),
                immediateSummary(rule, facts), facts.auditEventId(), 1);
        try {
            executor.execute(() -> {
                try {
                    raise(candidate);
                } catch (Exception e) {
                    log.error("Could not raise alert {} for bank {}", rule.name(), facts.bankId(), e);
                }
            });
        } catch (TaskRejectedException saturated) {
            // Never block the caller: the alert still reaches the log/SIEM path.
            log.atError().addMarker(LogAlertChannel.SECURITY_ALERT)
                    .log("SECURITY ALERT (not persisted - alert executor saturated) [{}] {}: {}",
                            rule.severity(), rule.name(), PiiPatterns.scrub(candidate.summary()));
        }
    }

    /**
     * Dedupes, records and dispatches one alert; empty when the burst already
     * raised an alert inside the rule window. Delivery itself is handed to the
     * alert executor (the windowed sweep must not wait on a slow webhook).
     */
    public Optional<AlertLog> raise(AlertCandidate candidate) {
        Optional<AlertLog> created = record(candidate);
        created.ifPresent(this::dispatchAsync);
        return created;
    }

    /**
     * The sweep's delivery retry: re-delivers PENDING rows older than a
     * minute to the channels still owed; gives up (FAILED, audited) on rows
     * older than the retry window. Returns how many rows were retried.
     */
    public int retryPendingDeliveries(Instant now) {
        Duration retryWindow = properties.getDelivery().getRetryWindow();
        Instant giveUpBefore = now.minus(retryWindow);
        List<AlertLog> due = systemRead(() -> alertLogs.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                AlertStatus.PENDING, giveUpBefore, now.minus(RETRY_MIN_AGE),
                PageRequest.of(0, RETRY_BATCH)));
        for (AlertLog row : due) {
            try {
                deliver(row, true);
            } catch (Exception e) {
                log.error("Alert #{} delivery retry failed", row.getAlertLogId(), e);
            }
        }
        List<AlertLog> expired = systemRead(() -> alertLogs.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                AlertStatus.PENDING, Instant.EPOCH, giveUpBefore, PageRequest.of(0, RETRY_BATCH)));
        for (AlertLog row : expired) {
            try {
                giveUp(row);
            } catch (Exception e) {
                log.error("Could not mark alert #{} FAILED", row.getAlertLogId(), e);
            }
        }
        // Keep the in-memory dedupe map bounded: nothing older than the
        // longest possible rule window can still suppress anything.
        Instant horizon = now.minus(AlertRuleCatalogue.MAX_WINDOW);
        lastRaised.values().removeIf(at -> at.isBefore(horizon));
        return due.size();
    }

    // ---- record ------------------------------------------------------------

    /** Synchronized: alerts are rare, and check-then-insert must not race. */
    synchronized Optional<AlertLog> record(AlertCandidate candidate) {
        AlertRule rule = candidate.rule();
        Instant now = Instant.now();
        String dedupeKey = Hashing.sha256(rule.name() + "|" + candidate.bankId() + "|"
                + candidate.groupKey());
        Instant windowStart = now.minus(rule.window());

        Instant last = lastRaised.get(dedupeKey);
        if (last != null && last.isAfter(windowStart)) {
            return Optional.empty();
        }
        boolean persistedDuplicate = inBank(candidate.bankId(), () ->
                alertLogs.existsByBankIdAndDedupeKeyAndCreatedAtAfter(
                        candidate.bankId(), dedupeKey, windowStart));
        if (persistedDuplicate) {
            // The persisted row is authoritative (raised before a restart or
            // by another instance); its own CreatedAt bounds the suppression.
            return Optional.empty();
        }

        AlertLog row = new AlertLog();
        row.setBankId(candidate.bankId());
        row.setRuleName(rule.name());
        row.setSeverity(rule.severity());
        row.setAuditEventId(candidate.auditEventId());
        row.setDedupeKey(dedupeKey);
        row.setEventCount(Math.max(1, candidate.eventCount()));
        row.setSummary(scrubbedSummary(candidate.summary()));
        row.setCreatedAt(now);
        row.setStatus(AlertStatus.PENDING);
        AlertLog saved = inBank(candidate.bankId(),
                () -> requiresNew.execute(status -> alertLogs.save(row)));
        lastRaised.put(dedupeKey, now);
        return Optional.of(saved);
    }

    // ---- deliver -----------------------------------------------------------

    private void dispatchAsync(AlertLog row) {
        try {
            executor.execute(() -> {
                try {
                    deliver(row, false);
                } catch (Exception e) {
                    log.error("Alert #{} delivery failed", row.getAlertLogId(), e);
                }
            });
        } catch (TaskRejectedException saturated) {
            // The row stays PENDING: the minute sweep delivers it.
            log.warn("Alert executor saturated; alert #{} left for the sweep", row.getAlertLogId());
        }
    }

    /**
     * Delivers to every applicable channel not yet in AlertLog.Channel,
     * updates the row and audits each outcome. {@code retry} marks a sweep
     * re-delivery: its failures are logged, not re-audited (one
     * ALERT_DELIVERY_FAILED per channel on first failure, one on give-up).
     */
    void deliver(AlertLog row, boolean retry) {
        AlertMessage message = AlertMessage.of(row);
        Set<String> accepted = channelSet(row.getChannel());
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> newlyAccepted = new ArrayList<>();
        for (AlertChannel channel : applicableChannels(row)) {
            if (accepted.contains(channel.name())) {
                continue;
            }
            Exception lastError = attempt(channel, message);
            if (lastError == null) {
                accepted.add(channel.name());
                newlyAccepted.add(channel.name());
            } else {
                failures.put(channel.name(), lastError.getClass().getSimpleName());
                log.warn("Alert #{} not accepted by channel {} ({}): {}", row.getAlertLogId(),
                        channel.name(), retry ? "sweep retry" : "first dispatch",
                        PiiPatterns.scrub(String.valueOf(lastError.getMessage())));
            }
        }
        Instant now = Instant.now();
        boolean complete = failures.isEmpty();
        row.setChannel(accepted.isEmpty() ? null : truncate(String.join(",", accepted), 100));
        if (complete) {
            row.setStatus(AlertStatus.SENT);
            row.setDeliveredAt(now);
        }
        inBank(row.getBankId(), () -> requiresNew.execute(status -> alertLogs.save(row)));

        inBank(row.getBankId(), () -> {
            for (String channel : newlyAccepted) {
                Map<String, Object> values = deliveryValues(row, channel);
                values.put("latencyMs", Duration.between(row.getCreatedAt(), now).toMillis());
                // SUCCESS joins a transaction; none is open on the alert
                // executor, so AuditEventWriter.append opens its own.
                audit.record(AuditEventSpec.builder("ALERT_SENT", AuditOutcome.SUCCESS)
                        .bankId(row.getBankId())
                        .entity("AlertLog", String.valueOf(row.getAlertLogId()))
                        .newValues(values)
                        .build());
            }
            if (!retry) {
                for (Map.Entry<String, String> failure : failures.entrySet()) {
                    Map<String, Object> values = deliveryValues(row, failure.getKey());
                    values.put("error", failure.getValue());
                    values.put("final", false);
                    audit.record(AuditEventSpec.builder("ALERT_DELIVERY_FAILED", AuditOutcome.FAILED)
                            .bankId(row.getBankId())
                            .entity("AlertLog", String.valueOf(row.getAlertLogId()))
                            .newValues(values)
                            .build());
                }
            }
            return null;
        });
    }

    private void giveUp(AlertLog row) {
        Set<String> accepted = channelSet(row.getChannel());
        List<String> owed = applicableChannels(row).stream().map(AlertChannel::name)
                .filter(name -> !accepted.contains(name)).toList();
        row.setStatus(owed.isEmpty() ? AlertStatus.SENT : AlertStatus.FAILED);
        if (owed.isEmpty() && row.getDeliveredAt() == null) {
            row.setDeliveredAt(Instant.now());
        }
        inBank(row.getBankId(), () -> {
            requiresNew.execute(status -> alertLogs.save(row));
            for (String channel : owed) {
                Map<String, Object> values = deliveryValues(row, channel);
                values.put("final", true);
                audit.record(AuditEventSpec.builder("ALERT_DELIVERY_FAILED", AuditOutcome.FAILED)
                        .bankId(row.getBankId())
                        .entity("AlertLog", String.valueOf(row.getAlertLogId()))
                        .newValues(values)
                        .build());
            }
            return null;
        });
        if (!owed.isEmpty()) {
            log.atError().addMarker(LogAlertChannel.SECURITY_ALERT)
                    .log("Alert #{} ({}) could not be delivered to {} within {} - giving up",
                            row.getAlertLogId(), row.getRuleName(), owed,
                            properties.getDelivery().getRetryWindow());
        }
    }

    private Exception attempt(AlertChannel channel, AlertMessage message) {
        int attempts = Math.max(1, properties.getDelivery().getAttempts());
        Exception lastError = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                channel.deliver(message);
                return null;
            } catch (Exception e) {
                lastError = e;
                if (i < attempts) {
                    sleepQuietly(500L * i);
                }
            }
        }
        return lastError;
    }

    private List<AlertChannel> applicableChannels(AlertLog row) {
        return channels.stream().filter(c -> c.appliesTo(row.getSeverity())).toList();
    }

    // ---- summaries ---------------------------------------------------------

    static String immediateSummary(AlertRule rule, AuditFacts facts) {
        StringBuilder text = new StringBuilder();
        if (!rule.description().isBlank()) {
            text.append(rule.description().trim()).append(" - ");
        }
        text.append(facts.action()).append(" (").append(facts.outcome()).append(") by ")
                .append(AlertRule.actorLabel(facts));
        if (facts.ip() != null && !facts.ip().isBlank()) {
            text.append(" from ip ").append(facts.ip());
        }
        if (facts.entityType() != null) {
            text.append(" on ").append(facts.entityType()).append(':').append(facts.entityId());
        }
        return text.toString();
    }

    static String windowedSummary(AlertRule rule, AuditFacts newest, int count) {
        StringBuilder text = new StringBuilder();
        if (!rule.description().isBlank()) {
            text.append(rule.description().trim()).append(" - ");
        }
        text.append(count).append(' ')
                .append(rule.matchesAnyAction() ? "audited actions" : String.join("/", rule.actions()))
                .append(" event(s) within ").append(humanWindow(rule.window()))
                .append(" for ").append(rule.groupLabel(newest))
                .append(" (threshold ").append(rule.threshold()).append(')');
        return text.toString();
    }

    private static String humanWindow(Duration window) {
        long minutes = window.toMinutes();
        return minutes % 60 == 0 ? (minutes / 60) + "h" : minutes + "m";
    }

    static String scrubbedSummary(String summary) {
        String scrubbed = PiiPatterns.scrub(summary == null ? "" : summary);
        return truncate(scrubbed, SUMMARY_MAX);
    }

    // ---- helpers -----------------------------------------------------------

    private static Map<String, Object> deliveryValues(AlertLog row, String channel) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rule", row.getRuleName());
        values.put("severity", row.getSeverity().name());
        values.put("channel", channel);
        return values;
    }

    private static Set<String> channelSet(String channel) {
        Set<String> set = new LinkedHashSet<>();
        if (channel != null && !channel.isBlank()) {
            Arrays.stream(channel.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(set::add);
        }
        return set;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static <T> T inBank(int bankId, Callable<T> action) {
        try {
            return TenantContext.runAs(bankId, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T systemRead(Callable<T> action) {
        try {
            return TenantContext.runAsSystem(action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
