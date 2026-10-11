package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * One line of a run's log (ws25, V250): what happened, never record values
 * or secrets. SCHEMA PARITY: mirrors V250 on the H2 test schema.
 */
@Entity
@Table(name = "IntegrationRunEvent",
        indexes = @Index(name = "ix_integrationrunevent_runid", columnList = "RunId"))
@Check(name = "ck_integrationrunevent_level", constraints = "Level in ('INFO', 'WARN', 'ERROR')")
public class IntegrationRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationRunEventId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "RunId", nullable = false)
    private long runId;

    @Column(name = "At", nullable = false)
    private Instant at;

    @Column(name = "Level", length = 10, nullable = false)
    private String level;

    @Column(name = "Message", length = 1000, nullable = false)
    private String message;

    public IntegrationRunEvent() {
    }

    public IntegrationRunEvent(int bankId, long runId, String level, String message) {
        this.bankId = bankId;
        this.runId = runId;
        this.level = level;
        this.at = Instant.now();
        this.message = message == null ? "" : message.length() <= 1000 ? message
                : message.substring(0, 1000);
    }

    public Long getId() {
        return id;
    }

    public int getBankId() {
        return bankId;
    }

    public long getRunId() {
        return runId;
    }

    public Instant getAt() {
        return at;
    }

    public String getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }
}
