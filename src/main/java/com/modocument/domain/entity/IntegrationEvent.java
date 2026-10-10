package com.modocument.domain.entity;

import com.modocument.domain.model.integration.FlowEvent;
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
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One integration event of a bank (ws25, V252): appended in the producer's
 * transaction, paged by the integration API's event feed. {@code DataJson}
 * holds ids, counts and statuses only — never record values.
 *
 * <p>SCHEMA PARITY: mirrors V252 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "IntegrationEvent",
        indexes = @Index(name = "ix_integrationevent_bankid_id", columnList = "BankId, IntegrationEventId"))
@Check(name = "ck_integrationevent_type", constraints = "Type in ('INGESTION_VALIDATED', "
        + "'INGESTION_FAILED', 'DOC_RELEASED', 'EXTRACTION_COMPLETED')")
public class IntegrationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationEventId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", length = 30, nullable = false)
    private FlowEvent type;

    @Column(name = "EntityType", length = 40, nullable = false)
    private String entityType;

    @Column(name = "EntityId", length = 64, nullable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @ColumnDefault("'{}'")
    @Column(name = "DataJson", nullable = false)
    private String dataJson = "{}";

    @Column(name = "OccurredAt", nullable = false)
    private Instant occurredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public FlowEvent getType() {
        return type;
    }

    public void setType(FlowEvent type) {
        this.type = type;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
