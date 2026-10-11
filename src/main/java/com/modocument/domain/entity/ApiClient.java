package com.modocument.domain.entity;

import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ApiClientStatus;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A machine caller of ONE bank (ws26, V260): a bank system (Kind SYSTEM) or
 * an on-prem Connector Agent (Kind AGENT). Authenticates with exactly one
 * {@link ApiAuthMethod}; its secrets live in {@link ApiClientSecret} (Argon2id
 * hashes), its certificate as {@code CertFingerprintSha256}.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create), so it mirrors V260__ws26_machine_identity.sql by hand —
 * the CHECKs, the unique keys and the index (the Bank foreign key is
 * PostgreSQL-only, a plain int here like AlertLog's).
 */
@Entity
@Table(name = "ApiClient",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_apiclient_clientid", columnNames = "ClientId"),
                @UniqueConstraint(name = "uq_apiclient_keyprefix", columnNames = "KeyPrefix"),
                @UniqueConstraint(name = "uq_apiclient_certfingerprint",
                        columnNames = "CertFingerprintSha256")},
        indexes = @Index(name = "ix_apiclient_bankid", columnList = "BankId"))
@Check(name = "ck_apiclient_kind", constraints = "Kind in ('SYSTEM', 'AGENT')")
@Check(name = "ck_apiclient_authmethod",
        constraints = "AuthMethod in ('OAUTH2_CLIENT_CREDENTIALS', 'API_KEY', 'MTLS')")
@Check(name = "ck_apiclient_status", constraints = "Status in ('ACTIVE', 'REVOKED')")
@Check(name = "ck_apiclient_keyprefix",
        constraints = "(AuthMethod = 'API_KEY') = (KeyPrefix IS NOT NULL)")
@Check(name = "ck_apiclient_certificate",
        constraints = "(AuthMethod = 'MTLS') = (CertFingerprintSha256 IS NOT NULL)")
public class ApiClient {

    public static final int NAME_MAX = 100;
    public static final int CERT_SUBJECT_MAX = 500;
    public static final int ALLOWED_CIDRS_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ApiClientId")
    private int apiClientId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "Name", length = NAME_MAX, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "Kind", length = 10, nullable = false)
    private ApiClientKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "AuthMethod", length = 30, nullable = false)
    private ApiAuthMethod authMethod;

    @Column(name = "ClientId", length = 64, nullable = false)
    private String clientId;

    @Column(name = "KeyPrefix", length = 32)
    private String keyPrefix;

    @Column(name = "CertFingerprintSha256", length = 64)
    private String certFingerprintSha256;

    @Column(name = "CertSubject", length = CERT_SUBJECT_MAX)
    private String certSubject;

    /** Comma-separated Permission names. */
    @Column(name = "Scopes", length = 500, nullable = false)
    private String scopes;

    /** Comma-separated CIDRs; null/empty = any address. */
    @Column(name = "AllowedCidrs", length = ALLOWED_CIDRS_MAX)
    private String allowedCidrs;

    @Column(name = "ExpiresAt")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ACTIVE'")
    @Column(name = "Status", length = 10, nullable = false)
    private ApiClientStatus status = ApiClientStatus.ACTIVE;

    @ColumnDefault("0")
    @Column(name = "TokenVersion", nullable = false)
    private int tokenVersion;

    @Column(name = "LastUsedAt")
    private Instant lastUsedAt;

    /** The maker of the API_CLIENT_CREATE change: owns the jobs the client creates. */
    @Column(name = "CreatedByUserId")
    private Integer createdByUserId;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "RevokedAt")
    private Instant revokedAt;

    @Column(name = "RevokedByUserId")
    private Integer revokedByUserId;

    /** Kind AGENT: the IntegrationAgent (V261) this client belongs to. */
    @Column(name = "AgentId")
    private Integer agentId;

    // ------------------------------------------------------------ behaviour

    /** The granted scopes (unknown names are ignored — fail closed). */
    public Set<Permission> scopeSet() {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        for (String name : splitList(scopes)) {
            try {
                set.add(Permission.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // A renamed permission must never widen anything.
            }
        }
        return set;
    }

    public void setScopeSet(Set<Permission> permissions) {
        this.scopes = permissions.stream().map(Permission::name).sorted()
                .collect(Collectors.joining(","));
    }

    public List<String> allowedCidrList() {
        return splitList(allowedCidrs);
    }

    public void setAllowedCidrList(List<String> cidrs) {
        this.allowedCidrs = cidrs == null || cidrs.isEmpty() ? null : String.join(",", cidrs);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(part -> !part.isEmpty()).toList();
    }

    // ------------------------------------------------------------ accessors

    public int getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(int apiClientId) {
        this.apiClientId = apiClientId;
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

    public ApiClientKind getKind() {
        return kind;
    }

    public void setKind(ApiClientKind kind) {
        this.kind = kind;
    }

    public ApiAuthMethod getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(ApiAuthMethod authMethod) {
        this.authMethod = authMethod;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getCertFingerprintSha256() {
        return certFingerprintSha256;
    }

    public void setCertFingerprintSha256(String certFingerprintSha256) {
        this.certFingerprintSha256 = certFingerprintSha256;
    }

    public String getCertSubject() {
        return certSubject;
    }

    public void setCertSubject(String certSubject) {
        this.certSubject = certSubject;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getAllowedCidrs() {
        return allowedCidrs;
    }

    public void setAllowedCidrs(String allowedCidrs) {
        this.allowedCidrs = allowedCidrs;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public ApiClientStatus getStatus() {
        return status;
    }

    public void setStatus(ApiClientStatus status) {
        this.status = status;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Integer getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Integer createdByUserId) {
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

    public Integer getAgentId() {
        return agentId;
    }

    public void setAgentId(Integer agentId) {
        this.agentId = agentId;
    }
}
