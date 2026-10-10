package com.modocument.domain.entity;

import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One execution of a flow, or an agent-routed connection test (ws25, V250).
 * UNIQUE (FlowId, ScheduledFor) is the cross-task run lock: every task's
 * scheduler inserts the run of a due slot and only the insert winner
 * executes it.
 *
 * <p>SCHEMA PARITY: mirrors V250 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "IntegrationRun",
        uniqueConstraints = @UniqueConstraint(name = "uq_integrationrun_flowid_scheduledfor",
                columnNames = {"FlowId", "ScheduledFor"}),
        indexes = {
                @Index(name = "ix_integrationrun_bankid_status", columnList = "BankId, Status"),
                @Index(name = "ix_integrationrun_bankid_createdat", columnList = "BankId, CreatedAt"),
                @Index(name = "ix_integrationrun_connectionid", columnList = "ConnectionId")})
@Check(name = "ck_integrationrun_operation", constraints = "Operation in ('READ', 'WRITE', 'TEST')")
@Check(name = "ck_integrationrun_triggertype",
        constraints = "TriggerType in ('MANUAL', 'SCHEDULE', 'EVENT')")
@Check(name = "ck_integrationrun_status", constraints = "Status in ('QUEUED', 'RUNNING', "
        + "'AWAITING_AGENT', 'RUNNING_AGENT', 'SUCCEEDED', 'FAILED', 'CANCELLED')")
public class IntegrationRun {

    public static final String OP_READ = "READ";
    public static final String OP_WRITE = "WRITE";
    public static final String OP_TEST = "TEST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationRunId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "FlowId")
    private Long flowId;

    @Column(name = "ConnectionId", nullable = false)
    private long connectionId;

    @Column(name = "Operation", length = 10, nullable = false)
    private String operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "TriggerType", length = 10, nullable = false)
    private FlowTrigger triggerType;

    @Column(name = "ScheduledFor", nullable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20, nullable = false)
    private RunStatus status;

    @ColumnDefault("1")
    @Column(name = "Attempt", nullable = false)
    private int attempt = 1;

    @ColumnDefault("0")
    @Column(name = "RecordsIn", nullable = false)
    private int recordsIn;

    @ColumnDefault("0")
    @Column(name = "RecordsOut", nullable = false)
    private int recordsOut;

    @Column(name = "IngestionJobId")
    private Integer ingestionJobId;

    @Column(name = "Error", length = 2000)
    private String error;

    @Column(name = "CursorValue", length = 500)
    private String cursorValue;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "StagedRecordsJson")
    private String stagedRecordsJson;

    @Column(name = "TriggeredBy")
    private Integer triggeredBy;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "StartedAt")
    private Instant startedAt;

    @Column(name = "FinishedAt")
    private Instant finishedAt;

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

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(long connectionId) {
        this.connectionId = connectionId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public FlowTrigger getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(FlowTrigger triggerType) {
        this.triggerType = triggerType;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public void setScheduledFor(Instant scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getRecordsIn() {
        return recordsIn;
    }

    public void setRecordsIn(int recordsIn) {
        this.recordsIn = recordsIn;
    }

    public int getRecordsOut() {
        return recordsOut;
    }

    public void setRecordsOut(int recordsOut) {
        this.recordsOut = recordsOut;
    }

    public Integer getIngestionJobId() {
        return ingestionJobId;
    }

    public void setIngestionJobId(Integer ingestionJobId) {
        this.ingestionJobId = ingestionJobId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getCursorValue() {
        return cursorValue;
    }

    public void setCursorValue(String cursorValue) {
        this.cursorValue = cursorValue;
    }

    public String getStagedRecordsJson() {
        return stagedRecordsJson;
    }

    public void setStagedRecordsJson(String stagedRecordsJson) {
        this.stagedRecordsJson = stagedRecordsJson;
    }

    public Integer getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(Integer triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
