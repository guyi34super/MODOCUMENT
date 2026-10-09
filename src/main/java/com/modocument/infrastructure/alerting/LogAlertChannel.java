package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

/**
 * The always-on channel: one WARN line per alert on the dedicated
 * {@code modocument.security.alert} logger, tagged with the
 * {@value #MARKER_NAME} marker and carrying the alert fields as SLF4J
 * key-value pairs. Under the structured-logs profile Boot's ECS encoder
 * renders the marker as {@code tags:["SECURITY_ALERT"]} and the key-values as
 * fields ({@code alert.id}, {@code alert.rule}, {@code alert.severity},
 * {@code alert.bank_id}, {@code alert.audit_event_id}) — so a SIEM ingesting
 * the application logs gets every alert even when no webhook/e-mail channel
 * is configured. The message passes the ws13 redaction like every other line.
 */
@Component
public class LogAlertChannel implements AlertChannel {

    static final String MARKER_NAME = "SECURITY_ALERT";
    static final Marker SECURITY_ALERT = MarkerFactory.getMarker(MARKER_NAME);

    /** Dedicated logger name so operators can route alerts (e.g. a separate appender). */
    private static final Logger log = LoggerFactory.getLogger("modocument.security.alert");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public AlertSeverity minSeverity() {
        return AlertSeverity.INFO;
    }

    @Override
    public void deliver(AlertMessage alert) {
        log.atWarn()
                .addMarker(SECURITY_ALERT)
                .addKeyValue("alert.id", alert.alertId())
                .addKeyValue("alert.rule", alert.rule())
                .addKeyValue("alert.severity", alert.severity().name())
                .addKeyValue("alert.bank_id", alert.bankId())
                .addKeyValue("alert.audit_event_id",
                        alert.auditEventId() == null ? null : alert.auditEventId().toString())
                .addKeyValue("alert.event_count", alert.eventCount())
                .log("SECURITY ALERT {}", alert.text());
    }
}
