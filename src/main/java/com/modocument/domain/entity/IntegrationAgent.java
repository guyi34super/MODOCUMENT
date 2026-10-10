package com.modocument.domain.entity;

import com.modocument.domain.model.integration.AgentStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * An on-prem Connector Agent of a bank (ws26, V261). Created PENDING_ENROLMENT
 * when a checker approves AGENT_REGISTER, enrolled by the agent's
 * {@code POST /api/agent/v1/enroll} with the one-time token, revoked by an
 * INTEGRATION_ADMIN. BankId, so row level security applies.
 *
 * <p>The enrolment token never sits here in plaintext: the SHA-256 of its
 * secret part ({@code EnrolmentTokenHash}) authenticates the enrolment, and
 * the token itself is sealed for the maker with the connector SecretVault
 * ({@code EnrolmentTokenCiphertext} + key version) until the maker's single
 * read. {@link #toString()} prints neither.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it mirrors
 * V261__ws26_integration_agents.sql by hand (the Bank foreign key and the
 * IntegrationConnection key are PostgreSQL-only).
 */
@Entity
@Table(name = "IntegrationAgent", indexes = {
        @Index(name = "ix_integrationagent_bankid_name", columnList = "BankId, Name")})
@Check(name = "ck_integrationagent_status",
        constraints = "Status in ('PENDING_ENROLMENT', 'ONLINE', 'OFFLINE', 'REVOKED')")
public class IntegrationAgent {

    /** Longest agent name the API accepts (the column's own limit). */
    public static final int NAME_MAX = 100;

    /** Longest version string an agent may report (the column's own limit). */
    public static final int VERSION_MAX = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationAgentId")
    private int integrationAgentId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "Name", length = NAME_MAX, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20, nullable = false)
    private AgentStatus status;

    @Column(name = "Version", length = VERSION_MAX)
    private String version;

    @Column(name = "LastHeartbeatAt")
    private Instant lastHeartbeatAt;

    /** [{connectionId, ok, message}] from the last heartbeat (capped by the API). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "LastHeartbeatJson")
    private String lastHeartbeatJson;

    /** The agent's ApiClient (Kind AGENT), set at enrolment. */
    @Column(name = "ApiClientId")
    private Integer apiClientId;

    /** SHA-256 hex of the enrolment token's secret part; null once used or revoked. */
    @Column(name = "EnrolmentTokenHash", length = 64)
    private String enrolmentTokenHash;

    /** The token sealed for the maker; null after the single read, enrolment or revocation. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "EnrolmentTokenCiphertext")
    private String enrolmentTokenCiphertext;

    @Column(name = "EnrolmentTokenKeyVersion")
    private Integer enrolmentTokenKeyVersion;

    @Column(name = "EnrolmentExpiresAt")
    private Instant enrolmentExpiresAt;

    @Column(name = "EnrolmentReadAt")
    private Instant enrolmentReadAt;

    @Column(name = "EnrolledAt")
    private Instant enrolledAt;

    /** The maker of the AGENT_REGISTER change. */
    @Column(name = "CreatedByUserId", nullable = false)
    private int createdByUserId;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "RevokedAt")
    private Instant revokedAt;

    @Column(name = "RevokedByUserId")
    private Integer revokedByUserId;

    public IntegrationAgent() {
    }

    /** Forgets every trace of the enrolment token (used, revoked or read). */
    public void clearEnrolmentSecret() {
        this.enrolmentTokenHash = null;
        this.enrolmentTokenCiphertext = null;
        this.enrolmentTokenKeyVersion = null;
    }

    public int getIntegrationAgentId() {
        return integrationAgentId;
    }

    public void setIntegrationAgentId(int integrationAgentId) {
        this.integrationAgentId = integrationAgentId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public String getLastHeartbeatJson() {
        return lastHeartbeatJson;
    }

    public void setLastHeartbeatJson(String lastHeartbeatJson) {
        this.lastHeartbeatJson = lastHeartbeatJson;
    }

    public Integer getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(Integer apiClientId) {
        this.apiClientId = apiClientId;
    }

    public String getEnrolmentTokenHash() {
        return enrolmentTokenHash;
    }

    public void setEnrolmentTokenHash(String enrolmentTokenHash) {
        this.enrolmentTokenHash = enrolmentTokenHash;
    }

    public String getEnrolmentTokenCiphertext() {
        return enrolmentTokenCiphertext;
    }

    public void setEnrolmentTokenCiphertext(String enrolmentTokenCiphertext) {
        this.enrolmentTokenCiphertext = enrolmentTokenCiphertext;
    }

    public Integer getEnrolmentTokenKeyVersion() {
        return enrolmentTokenKeyVersion;
    }

    public void setEnrolmentTokenKeyVersion(Integer enrolmentTokenKeyVersion) {
        this.enrolmentTokenKeyVersion = enrolmentTokenKeyVersion;
    }

    public Instant getEnrolmentExpiresAt() {
        return enrolmentExpiresAt;
    }

    public void setEnrolmentExpiresAt(Instant enrolmentExpiresAt) {
        this.enrolmentExpiresAt = enrolmentExpiresAt;
    }

    public Instant getEnrolmentReadAt() {
        return enrolmentReadAt;
    }

    public void setEnrolmentReadAt(Instant enrolmentReadAt) {
        this.enrolmentReadAt = enrolmentReadAt;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public void setEnrolledAt(Instant enrolledAt) {
        this.enrolledAt = enrolledAt;
    }

    public int getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(int createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Integer getRevokedByUserId() {
        return revokedByUserId;
    }

    public void setRevokedByUserId(Integer revokedByUserId) {
        this.revokedByUserId = revokedByUserId;
    }

    @Override
    public String toString() {
        return "IntegrationAgent[id=" + integrationAgentId + ", bankId=" + bankId + ", status="
                + status + "]";
    }
}
