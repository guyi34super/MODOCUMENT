package com.modocument.infrastructure.alerting;

import com.modocument.infrastructure.audit.AuditRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The IMMEDIATE trigger path (ws18, gate D1/D2): every committed audit row is
 * matched against the immediate rules (ACCOUNT_LOCKED,
 * TOKEN_REFRESH_REUSE_DETECTED, EXPORT_BLOCKED, SOD_VIOLATION_FOUND,
 * AI_PROMPT_REFUSED, ...) and each hit is handed to the alert executor — so
 * the alert row exists within seconds of the audit row, without waiting for
 * the minute sweep.
 *
 * <p>{@code AFTER_COMMIT}: only durable rows alert (a rolled-back SUCCESS
 * event never happened). DENIED/FAILED rows commit in their own
 * REQUIRES_NEW transaction, so a refusal (EXPORT_BLOCKED, ACCOUNT_LOCKED)
 * alerts even though the request itself then fails. {@code fallbackExecution}
 * covers a write outside any transaction (none today — AuditEventWriter is
 * always transactional — but the trigger must not silently go dark if that
 * changes).
 *
 * <p>Runs on the business thread: the work here is an in-memory rule match
 * plus an executor hand-off, and it can never throw back into the caller
 * (on the fallback path an exception would surface from the audit write
 * itself).
 */
@Component
public class ImmediateAlertListener {

    private static final Logger log = LoggerFactory.getLogger(ImmediateAlertListener.class);

    private final AlertProperties properties;
    private final AlertRuleCatalogue catalogue;
    private final AlertService alerts;

    public ImmediateAlertListener(AlertProperties properties, AlertRuleCatalogue catalogue,
                                  AlertService alerts) {
        this.properties = properties;
        this.catalogue = catalogue;
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditRecorded(AuditRecordedEvent recorded) {
        if (!properties.isEnabled() || recorded == null || recorded.event() == null) {
            return;
        }
        try {
            AuditFacts facts = AuditFacts.of(recorded.event());
            for (AlertRule rule : catalogue.immediateMatches(facts)) {
                alerts.raiseAsync(rule, facts);
            }
        } catch (RuntimeException e) {
            log.error("Immediate alert evaluation failed for audit action {}",
                    recorded.event().getAction(), e);
        }
    }
}
