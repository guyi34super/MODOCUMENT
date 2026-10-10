package com.modocument.domain.entity;

import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AlertStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.util.UUID;

/**
 * One security alert raised by the ws18 alert framework (V160; gate D1/D2,
 * SEC-032) and the evidence of its delivery: which rule fired, for which
 * bank, from which audit row, when it was created (detection) and when the
 * channels accepted it (notification). Rows are written only by
 * {@code AlertService}; the admin panel reads them bank-scoped.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it must be kept in sync with
 * V160__ws18_alert_log.sql by hand. The Bank foreign key is PostgreSQL-only
 * (a plain int here, like PendingChange).
 */
@Entity
@Table(name = "AlertLog", indexes = {
        @Index(name = "ix_alertlog_bankid_createdat", columnList = "BankId, CreatedAt"),
        @Index(name = "ix_alertlog_dedupekey_createdat", columnList = "DedupeKey, CreatedAt"),
        @Index(name = "ix_alertlog_status_createdat", columnList = "Status, CreatedAt")})
// SCHEMA PARITY with V160 (ck_alertlog_severity / ck_alertlog_status) on H2.
@Check(name = "ck_alertlog_severity",
        constraints = "Severity in ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')")
@Check(name = "ck_alertlog_status", constraints = "Status in ('PENDING', 'SENT', 'FAILED')")
public class AlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AlertLogId")
    private int alertLogId;

    /** The bank the alert belongs to; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "RuleName", length = 80, nullable = false)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "Severity", length = 10, nullable = false)
    private AlertSeverity severity;

    /** The triggering audit row (immediate) or the newest row of the burst (windowed). */
    @Column(name = "AuditEventId")
    private UUID auditEventId;

    /** SHA-256 hex of rule|bank|group key — one alert per burst per window. */
    @Column(name = "DedupeKey", length = 64, nullable = false)
    private String dedupeKey;

    @Column(name = "EventCount", nullable = false)
    private int eventCount = 1;

    /** PII-scrubbed one-liner; never audit value payloads. */
    @Column(name = "Summary", length = 500, nullable = false)
    private String summary;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "DeliveredAt")
    private Instant deliveredAt;

    /** Channels that accepted the alert so far, comma-separated (e.g. "log,webhook"). */
    @Column(name = "Channel", length = 100)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 10, nullable = false)
    private AlertStatus status = AlertStatus.PENDING;

    public AlertLog() {
    }

    public int getAlertLogId() {
        return alertLogId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    public UUID getAuditEventId() {
        return auditEventId;
    }

    public void setAuditEventId(UUID auditEventId) {
        this.auditEventId = auditEventId;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public int getEventCount() {
        return eventCount;
    }

    public void setEventCount(int eventCount) {
        this.eventCount = eventCount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }
}
