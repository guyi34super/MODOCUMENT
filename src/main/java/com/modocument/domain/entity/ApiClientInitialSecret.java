package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * The plaintext secret of a newly approved {@link ApiClient}, SEALED with
 * the {@code SecretVault} (ws26, V260): readable once by the maker
 * ({@code RecipientUserId}) before {@code ExpiresAt} (24 h), then deleted.
 *
 * <p>SCHEMA PARITY with V260 (the foreign keys are PostgreSQL-only).
 */
@Entity
@Table(name = "ApiClientInitialSecret",
        indexes = @Index(name = "ix_apiclientinitialsecret_bankid", columnList = "BankId"))
public class ApiClientInitialSecret {

    @Id
    @Column(name = "ApiClientId")
    private int apiClientId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "RecipientUserId", nullable = false)
    private int recipientUserId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "SecretCiphertext", nullable = false)
    private String secretCiphertext;

    @Column(name = "SecretKeyVersion", nullable = false)
    private int secretKeyVersion;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "ExpiresAt", nullable = false)
    private Instant expiresAt;

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

    public int getRecipientUserId() {
        return recipientUserId;
    }

    public void setRecipientUserId(int recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public int getSecretKeyVersion() {
        return secretKeyVersion;
    }

    public void setSecretKeyVersion(int secretKeyVersion) {
        this.secretKeyVersion = secretKeyVersion;
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
