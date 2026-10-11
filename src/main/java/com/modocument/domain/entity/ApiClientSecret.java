package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One secret of an {@link ApiClient} (ws26, V260): the Argon2id hash of an
 * OAuth2 client secret or of an API key's secret half. {@code ExpiresAt}
 * null = the current secret; a rotation stamps the previous one with the
 * overlap end so both authenticate until then.
 *
 * <p>SCHEMA PARITY with V260 (the foreign keys are PostgreSQL-only).
 */
@Entity
@Table(name = "ApiClientSecret",
        indexes = @Index(name = "ix_apiclientsecret_apiclientid", columnList = "ApiClientId"))
public class ApiClientSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ApiClientSecretId")
    private int apiClientSecretId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "ApiClientId", nullable = false)
    private int apiClientId;

    /** {@code {argon2}...} — the project's PasswordEncoder format. */
    @Column(name = "SecretHash", length = 255, nullable = false)
    private String secretHash;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "ExpiresAt")
    private Instant expiresAt;

    /** Current, or still inside its rotation overlap. */
    public boolean isValidAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public int getApiClientSecretId() {
        return apiClientSecretId;
    }

    public void setApiClientSecretId(int apiClientSecretId) {
        this.apiClientSecretId = apiClientSecretId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(int apiClientId) {
        this.apiClientId = apiClientId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
