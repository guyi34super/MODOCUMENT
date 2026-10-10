package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SMTP alert channel (also an SMS path through an operator's
 * e-mail-to-SMS gateway). CONFIG-GATED twice: it is enabled only when
 * {@code modocument.alerts.email.to} names at least one recipient AND Spring
 * Boot created a {@link JavaMailSender} (which it does only when
 * {@code spring.mail.host} is set). Unconfigured, it is a silent no-op — the
 * app boots and alerts flow through the other channels exactly as before.
 *
 * <p>Plain text, no HTML: subject = the alert's one-line text, body = the
 * structured fields. Like every channel it only ever carries the
 * PII-scrubbed summary, never audit value payloads.
 */
@Component
public class EmailAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertChannel.class);

    private final AlertProperties.Email config;
    private final ObjectProvider<JavaMailSender> mailSender;

    public EmailAlertChannel(AlertProperties properties, ObjectProvider<JavaMailSender> mailSender) {
        this.config = properties.getEmail();
        this.mailSender = mailSender;
        if (!recipients().isEmpty() && mailSender.getIfAvailable() == null) {
            log.warn("modocument.alerts.email.to is set but no mail sender is configured "
                    + "(spring.mail.host) - the e-mail alert channel stays OFF.");
        }
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return !recipients().isEmpty() && mailSender.getIfAvailable() != null;
    }

    @Override
    public AlertSeverity minSeverity() {
        return config.getMinSeverity();
    }

    @Override
    public void deliver(AlertMessage alert) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No mail sender configured.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.getFrom());
        message.setTo(recipients().toArray(String[]::new));
        message.setSubject(alert.text().length() > 200
                ? alert.text().substring(0, 200) : alert.text());
        message.setText("Security alert raised by MoDocument\n\n"
                + "Rule:        " + alert.rule() + "\n"
                + "Severity:    " + alert.severity() + "\n"
                + "Bank:        " + alert.bankId() + "\n"
                + "Alert id:    " + alert.alertId() + "\n"
                + "Raised at:   " + alert.createdAt() + "\n"
                + "Events:      " + alert.eventCount() + "\n"
                + "Audit event: " + alert.auditEventId() + "\n\n"
                + alert.summary() + "\n\n"
                + "Review it under Administration > Security alerts; the full trail is in "
                + "the bank's audit log / SIEM feed.\n");
        sender.send(message);
    }

    private List<String> recipients() {
        return config.getTo() == null ? List.of()
                : config.getTo().stream().filter(t -> t != null && !t.isBlank())
                        .map(String::trim).toList();
    }
}
