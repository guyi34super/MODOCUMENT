package com.modocument.domain.entity;

import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Where a bank system is (ws25, V250). Non-secret settings live in
 * {@code ConfigJson}; the credential is write-only — the SecretVault
 * ciphertext (DIRECT) or the name of a secret held by the bank's agent
 * (AGENT, {@code SecretRef}). Nothing here is ever returned with a secret
 * value: views carry {@code secretSet} only.
 *
 * <p>SCHEMA PARITY: mirrors V250__ws25_integration_core.sql on the H2 test
 * schema (ddl-auto: create) — the CHECKs and defaults below; the Bank
 * foreign key is PostgreSQL-only.
 */
@Entity
@Table(name = "IntegrationConnection", uniqueConstraints = @UniqueConstraint(
        name = "uq_integrationconnection_bankid_name", columnNames = {"BankId", "Name"}))
@Check(name = "ck_integrationconnection_type",
        constraints = "Type in ('REST_API', 'DATABASE', 'SFTP', 'WEBHOOK')")
@Check(name = "ck_integrationconnection_mode", constraints = "Mode in ('DIRECT', 'AGENT')")
@Check(name = "ck_integrationconnection_authtype", constraints = "AuthType in ('NONE', 'BASIC', "
        + "'BEARER', 'API_KEY_HEADER', 'OAUTH2_CLIENT_CREDENTIALS', 'MTLS', 'HMAC_SIGNATURE', "
        + "'PASSWORD', 'PRIVATE_KEY')")
@Check(name = "ck_integrationconnection_status",
        constraints = "Status in ('UNTESTED', 'OK', 'FAILED', 'DISABLED')")
@Check(name = "ck_integrationconnection_agent", constraints = "(Mode = 'AGENT' AND AgentId IS NOT "
        + "NULL) OR (Mode = 'DIRECT' AND AgentId IS NULL)")
public class IntegrationConnection {

    public static final String STATUS_UNTESTED = "UNTESTED";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationConnectionId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "Name", length = 100, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", length = 20, nullable = false)
    private ConnectionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "Mode", length = 10, nullable = false)
    private ConnectionMode mode;

    @Column(name = "AgentId")
    private Integer agentId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @ColumnDefault("'{}'")
    @Column(name = "ConfigJson", nullable = false)
    private String configJson = "{}";

    @ColumnDefault("'NONE'")
    @Column(name = "AuthType", length = 30, nullable = false)
    private String authType = "NONE";

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "SecretCiphertext")
    private String secretCiphertext;

    @Column(name = "SecretKeyVersion")
    private Integer secretKeyVersion;

    @Column(name = "SecretRef", length = 200)
    private String secretRef;

    @ColumnDefault("'UNTESTED'")
    @Column(name = "Status", length = 20, nullable = false)
    private String status = STATUS_UNTESTED;

    @Column(name = "LastTestAt")
    private Instant lastTestAt;

    @Column(name = "LastTestResult", length = 500)
    private String lastTestResult;

    @Version
    @ColumnDefault("0")
    @Column(name = "Version", nullable = false)
    private int version;

    @Column(name = "CreatedBy", nullable = false)
    private int createdBy;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "UpdatedAt")
    private Instant updatedAt;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ConnectionType getType() {
        return type;
    }

    public void setType(ConnectionType type) {
        this.type = type;
    }

    public ConnectionMode getMode() {
        return mode;
    }

    public void setMode(ConnectionMode mode) {
        this.mode = mode;
    }

    public Integer getAgentId() {
        return agentId;
    }

    public void setAgentId(Integer agentId) {
        this.agentId = agentId;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public Integer getSecretKeyVersion() {
        return secretKeyVersion;
    }

    public void setSecretKeyVersion(Integer secretKeyVersion) {
        this.secretKeyVersion = secretKeyVersion;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastTestAt() {
        return lastTestAt;
    }

    public void setLastTestAt(Instant lastTestAt) {
        this.lastTestAt = lastTestAt;
    }

    public String getLastTestResult() {
        return lastTestResult;
    }

    public void setLastTestResult(String lastTestResult) {
        this.lastTestResult = lastTestResult;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Whether a credential is stored (vault ciphertext or an agent secret reference). */
    public boolean isSecretSet() {
        return secretCiphertext != null || (secretRef != null && !secretRef.isBlank());
    }

    @Override
    public String toString() {
        return "IntegrationConnection[id=" + id + ", bankId=" + bankId + ", type=" + type
                + ", mode=" + mode + "]";
    }
}
