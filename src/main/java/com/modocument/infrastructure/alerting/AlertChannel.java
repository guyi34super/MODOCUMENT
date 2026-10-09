package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AlertSeverity;

/**
 * A way an alert reaches a person or a system (ws18, gate D1). Channels are
 * Spring beans; {@link AlertService} delivers every alert to each channel
 * that is {@link #enabled()} and whose {@link #minSeverity()} the alert
 * meets, on the alert executor — never on a business thread.
 *
 * <p>Shipped channels: {@link LogAlertChannel} (always on; the SIEM sees it
 * through structured logs), {@link WebhookAlertChannel} (generic JSON POST —
 * the phone path via Slack/WhatsApp bridge/PagerDuty) and
 * {@link EmailAlertChannel} (SMTP; e-mail-to-SMS gateways are another phone
 * path). A new channel is one more bean implementing this interface.
 */
public interface AlertChannel {

    /** Stable short name recorded in AlertLog.Channel and the audit trail ("log", "webhook"). */
    String name();

    /** Whether the channel is configured at all (a blank webhook url means no). */
    boolean enabled();

    /** Alerts below this severity skip the channel. */
    AlertSeverity minSeverity();

    /**
     * Delivers one alert. Throwing means "not accepted" — the dispatcher
     * retries, the sweep retries later, and the failure is audited.
     */
    void deliver(AlertMessage alert) throws Exception;

    default boolean appliesTo(AlertSeverity severity) {
        return enabled() && severity.atLeast(minSeverity());
    }
}
