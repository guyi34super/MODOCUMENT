package com.modocument.domain.entity;

import com.modocument.domain.model.integration.EgressRuleStatus;
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
 * A private-network destination a bank's DIRECT connections may reach (ws25,
 * V251): a {@code host} OR a {@code cidr}, plus a {@code port}. Requested by
 * the bank (PENDING), approved by two platform admins (EGRESS_RULE_APPROVE),
 * revocable by the bank at any time. Consulted by {@code BankEgressGuard}.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it mirrors
 * V251__ws25_egress_rules.sql by hand: the CHECKs, the default and the index
 * below (the Bank foreign key is PostgreSQL-only).
 */
@Entity
@Table(name = "BankEgressRule", indexes = @Index(name = "ix_bankegressrule_bankid_status",
        columnList = "BankId, Status"))
@Check(name = "ck_bankegressrule_target", constraints = "(Host IS NOT NULL AND Cidr IS NULL)"
        + " OR (Host IS NULL AND Cidr IS NOT NULL)")
@Check(name = "ck_bankegressrule_port", constraints = "Port BETWEEN 1 AND 65535")
@Check(name = "ck_bankegressrule_status",
        constraints = "Status IN ('PENDING', 'APPROVED', 'REVOKED')")
public class BankEgressRule {

    public static final int HOST_MAX = 253;
    public static final int CIDR_MAX = 64;
    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BankEgressRuleId")
    private int bankEgressRuleId;

    /** The requesting bank; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    /** Lower-case DNS name or IP literal (exact match); null when {@link #cidr} is set. */
    @Column(name = "Host", length = HOST_MAX)
    private String host;

    /** Canonical CIDR block; null when {@link #host} is set. */
    @Column(name = "Cidr", length = CIDR_MAX)
    private String cidr;

    @Column(name = "Port", nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(name = "Status", length = 10, nullable = false)
    private EgressRuleStatus status = EgressRuleStatus.PENDING;

    @Column(name = "Reason", length = REASON_MAX, nullable = false)
    private String reason;

    @Column(name = "RequestedByUserId", nullable = false)
    private int requestedByUserId;

    @Column(name = "ApprovedByUserId")
    private Integer approvedByUserId;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "ApprovedAt")
    private Instant approvedAt;

    public int getBankEgressRuleId() {
        return bankEgressRuleId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getCidr() {
        return cidr;
    }

    public void setCidr(String cidr) {
        this.cidr = cidr;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public EgressRuleStatus getStatus() {
        return status;
    }

    public void setStatus(EgressRuleStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getRequestedByUserId() {
        return requestedByUserId;
    }

    public void setRequestedByUserId(int requestedByUserId) {
        this.requestedByUserId = requestedByUserId;
    }

    public Integer getApprovedByUserId() {
        return approvedByUserId;
    }

    public void setApprovedByUserId(Integer approvedByUserId) {
        this.approvedByUserId = approvedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    /** The destination as people read it: {@code host:port} or {@code cidr:port}. */
    public String destination() {
        String target = host != null ? host : cidr;
        return (target != null && target.indexOf(':') >= 0 ? "[" + target + "]" : target)
                + ":" + port;
    }
}
