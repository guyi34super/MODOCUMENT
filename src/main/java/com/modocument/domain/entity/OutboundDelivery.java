package com.modocument.domain.entity;

import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.PayloadKind;
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

import java.time.Instant;

/**
 * The transactional outbox (ws25, V250): one payload owed to a bank system.
 * {@code PayloadRef} names what to build ({@code job:<id>}, {@code event:<id>},
 * {@code doc:<id>}, {@code events:<from>-<to>}); the payload itself is built
 * at dispatch time (masked, capped) and never stored. A null
 * {@code NextAttemptAt} on a PENDING row means "waiting for the agent".
 *
 * <p>SCHEMA PARITY: mirrors V250 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "OutboundDelivery", indexes = {
        @Index(name = "ix_outbounddelivery_bankid_status_next",
                columnList = "BankId, Status, NextAttemptAt"),
        @Index(name = "ix_outbounddelivery_runid", columnList = "RunId"),
        @Index(name = "ix_outbounddelivery_flowid", columnList = "FlowId")})
@Check(name = "ck_outbounddelivery_status", constraints = "Status in ('PENDING', 'DELIVERED', 'DEAD')")
@Check(name = "ck_outbounddelivery_payloadkind", constraints = "PayloadKind in ('VALIDATED_RECORDS', "
        + "'REJECTED_ROWS', 'JOB_STATUS', 'RELEASED_DOCUMENTS', 'EVENTS')")
public class OutboundDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "OutboundDeliveryId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "FlowId", nullable = false)
    private long flowId;

    @Column(name = "RunId")
    private Long runId;

    @Column(name = "EventId")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "PayloadKind", length = 30, nullable = false)
    private PayloadKind payloadKind;

    @Column(name = "PayloadRef", length = 200, nullable = false)
    private String payloadRef;

    @Column(name = "IdempotencyKey", length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 12, nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @ColumnDefault("0")
    @Column(name = "Attempts", nullable = false)
    private int attempts;

    @Column(name = "NextAttemptAt")
    private Instant nextAttemptAt;

    @Column(name = "LastError", length = 1000)
    private String lastError;

    @Column(name = "RemoteReference", length = 300)
    private String remoteReference;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "DeliveredAt")
    private Instant deliveredAt;

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

    public long getFlowId() {
        return flowId;
    }

    public void setFlowId(long flowId) {
        this.flowId = flowId;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public PayloadKind getPayloadKind() {
        return payloadKind;
    }

    public void setPayloadKind(PayloadKind payloadKind) {
        this.payloadKind = payloadKind;
    }

    public String getPayloadRef() {
        return payloadRef;
    }

    public void setPayloadRef(String payloadRef) {
        this.payloadRef = payloadRef;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getRemoteReference() {
        return remoteReference;
    }

    public void setRemoteReference(String remoteReference) {
        this.remoteReference = remoteReference;
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
}
