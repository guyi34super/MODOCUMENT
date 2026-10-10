package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A user's TOTP enrolment (at most one row per user). SecretCiphertext is the
 * Base32 secret encrypted with AES-256-GCM (MfaSecretCipher) — the plaintext
 * never reaches the database. EnabledAt NULL means enrolled-but-not-activated:
 * login does not demand a second factor until the user has proven possession
 * with a first code. LastUsedCounter stores the RFC 6238 step of the last
 * accepted code; same-or-older steps are rejected (replay prevention).
 *
 * <p>SCHEMA PARITY with V41 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "UserMfa")
public class UserMfa {

    @Id
    @Column(name = "UserId")
    private int userId;

    @Column(name = "SecretCiphertext", length = 512, nullable = false)
    private String secretCiphertext;

    @Column(name = "EnabledAt")
    private Instant enabledAt;

    @Column(name = "LastUsedCounter")
    private Long lastUsedCounter;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    public UserMfa() {
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public void setEnabledAt(Instant enabledAt) {
        this.enabledAt = enabledAt;
    }

    public Long getLastUsedCounter() {
        return lastUsedCounter;
    }

    public void setLastUsedCounter(Long lastUsedCounter) {
        this.lastUsedCounter = lastUsedCounter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isEnabled() {
        return enabledAt != null;
    }
}
