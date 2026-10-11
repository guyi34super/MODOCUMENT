package com.modocument.infrastructure.alerting;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AlertLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The WINDOWED trigger path (ws18, gate D1, SEC-032): a sweep every minute
 * ({@code modocument.alerts.sweep.interval-ms}, registered like the
 * retention/SoD jobs; {@code @EnableScheduling} comes from
 * ExtractionSchedulingConfig) that, per windowed rule, counts the matching
 * audit rows of the last {@code window} grouped by bank + actor/ip/entity
 * and raises an alert for every group at or above the threshold — e.g.
 * >= 5 LOGIN_FAILED for one user within 5 minutes. Dedupe (in
 * {@link AlertService}) turns a sustained burst into ONE alert per window.
 * Worst-case detection latency = one sweep interval, well inside the
 * 5-minute target.
 *
 * <p>The same sweep then retries alert deliveries still owed (PENDING
 * rows) and runs the {@link MinuteSweepParticipant}s (the SIEM push
 * forwarder's retry).
 *
 * <p>Tenancy (call-site registry in {@link TenantContext}): the aggregate
 * READ of AuditEvent runs {@code runAsSystem} — a brute-force burst must be
 * seen wherever it lands, across banks; each raised alert is then recorded
 * strictly under its own bank by {@link AlertService}.
 *
 * <p>{@code modocument.alerts.sweep.enabled} gates only the schedule;
 * {@link #runOnce()} always works (the gate tests drive it directly).
 */
@Component
public class AlertSweepJob {

    private static final Logger log = LoggerFactory.getLogger(AlertSweepJob.class);

    private final AlertProperties properties;
    private final AlertRuleCatalogue catalogue;
    private final AuditWindowReader reader;
    private final AlertService alerts;
    private final List<MinuteSweepParticipant> participants;

    public AlertSweepJob(AlertProperties properties, AlertRuleCatalogue catalogue,
                         AuditWindowReader reader, AlertService alerts,
                         ObjectProvider<MinuteSweepParticipant> participants) {
        this.properties = properties;
        this.catalogue = catalogue;
        this.reader = reader;
        this.alerts = alerts;
        this.participants = participants.orderedStream().toList();
    }

    @Scheduled(fixedDelayString = "${modocument.alerts.sweep.interval-ms:60000}",
            initialDelayString = "${modocument.alerts.sweep.interval-ms:60000}")
    public void sweep() {
        if (!properties.isEnabled() || !properties.getSweep().isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Alert sweep failed", e);
        }
    }

    /** One sweep; returns the alerts raised by the windowed rules. */
    public List<AlertLog> runOnce() {
        Instant now = Instant.now();
        List<AlertLog> raised = new ArrayList<>();
        if (properties.isEnabled()) {
            for (AlertRule rule : catalogue.windowed()) {
                try {
                    raised.addAll(evaluate(rule, now));
                } catch (Exception e) {
                    log.error("Windowed alert rule {} could not be evaluated (continuing)",
                            rule.name(), e);
                }
            }
        }
        int retried = 0;
        try {
            retried = alerts.retryPendingDeliveries(now);
        } catch (RuntimeException e) {
            log.error("Alert delivery retry failed", e);
        }
        for (MinuteSweepParticipant participant : participants) {
            try {
                participant.onSweep(now);
            } catch (RuntimeException e) {
                log.error("Sweep participant {} failed", participant.name(), e);
            }
        }
        if (!raised.isEmpty() || retried > 0) {
            log.info("AlertSweep completed: {} windowed alert(s) raised, {} delivery retr(y/ies)",
                    raised.size(), retried);
        }
        return raised;
    }

    private List<AlertLog> evaluate(AlertRule rule, Instant now) throws Exception {
        Instant since = now.minus(rule.window());
        // runAsSystem: the cross-bank aggregate read (see class javadoc).
        List<AuditFacts> rows = TenantContext.runAsSystem(() -> reader.matching(rule, since, now));
        if (rows.size() >= AuditWindowReader.MAX_ROWS) {
            log.warn("Windowed rule {} hit the {}-row read cap; counts are lower bounds",
                    rule.name(), AuditWindowReader.MAX_ROWS);
        }
        // Rows arrive newest first, so each group's first row is its newest.
        Map<String, List<AuditFacts>> groups = new LinkedHashMap<>();
        for (AuditFacts row : rows) {
            if (!rule.matches(row)) {
                continue;
            }
            groups.computeIfAbsent(row.bankId() + "|" + rule.groupKey(row), k -> new ArrayList<>())
                    .add(row);
        }
        List<AlertLog> raised = new ArrayList<>();
        for (List<AuditFacts> group : groups.values()) {
            if (group.size() < rule.threshold()) {
                continue;
            }
            AuditFacts newest = group.get(0);
            Optional<AlertLog> alert = alerts.raise(new AlertCandidate(rule, newest.bankId(),
                    rule.groupKey(newest), AlertService.windowedSummary(rule, newest, group.size()),
                    newest.auditEventId(), group.size()));
            alert.ifPresent(raised::add);
        }
        return raised;
    }
}
