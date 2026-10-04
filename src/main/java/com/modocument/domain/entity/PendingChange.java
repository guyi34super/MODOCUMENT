package com.modocument.domain.entity;

import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A critical change awaiting dual control (V80, ws10, SEC-023): the maker
 * submits, a DIFFERENT user approves or rejects, and only approval executes
 * the change. {@code PayloadJson} is the full request as JSON — never with
 * plaintext secrets (USER_CREATE stores the temporary password's hash).
 */
@Entity
@Table(name = "PendingChange")
// SCHEMA PARITY with V80 (ck_pendingchange_status) on the H2 test schema.
@Check(name = "ck_pendingchange_status", constraints = "Status in "
        + "('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'APPLIED', 'FAILED')")
public class PendingChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PendingChangeId")
    private int pendingChangeId;

    /** The maker's bank, stamped on submit; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ChangeType", length = 40, nullable = false)
    private ChangeType changeType;

    @Column(name = "TargetEntityType", length = 40)
    private String targetEntityType;

    @Column(name = "TargetEntityId", length = 64)
    private String targetEntityId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "PayloadJson", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20, nullable = false)
    private PendingChangeStatus status = PendingChangeStatus.REQUESTED;

    @Column(name = "MakerUserId", nullable = false)
    private int makerUserId;

    @Column(name = "MakerComment", length = 500)
    private String makerComment;

    @Column(name = "CheckerUserId")
    private Integer checkerUserId;

    @Column(name = "CheckerComment", length = 500)
    private String checkerComment;

    @Column(name = "RequestedAt", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "DecidedAt")
    private LocalDateTime decidedAt;

    @Column(name = "AppliedAt")
    private LocalDateTime appliedAt;

    public PendingChange() {
    }

    public int getPendingChangeId() {
        return pendingChangeId;
    }

    public void setPendingChangeId(int pendingChangeId) {
        this.pendingChangeId = pendingChangeId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public String getTargetEntityType() {
        return targetEntityType;
    }

    public void setTargetEntityType(String targetEntityType) {
        this.targetEntityType = targetEntityType;
    }

    public String getTargetEntityId() {
        return targetEntityId;
    }

    public void setTargetEntityId(String targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public PendingChangeStatus getStatus() {
        return status;
    }

    public void setStatus(PendingChangeStatus status) {
        this.status = status;
    }

    public int getMakerUserId() {
        return makerUserId;
    }

    public void setMakerUserId(int makerUserId) {
        this.makerUserId = makerUserId;
    }

    public String getMakerComment() {
        return makerComment;
    }

    public void setMakerComment(String makerComment) {
        this.makerComment = makerComment;
    }

    public Integer getCheckerUserId() {
        return checkerUserId;
    }

    public void setCheckerUserId(Integer checkerUserId) {
        this.checkerUserId = checkerUserId;
    }

    public String getCheckerComment() {
        return checkerComment;
    }

    public void setCheckerComment(String checkerComment) {
        this.checkerComment = checkerComment;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }
}
