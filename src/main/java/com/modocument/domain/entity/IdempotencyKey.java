package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Replay store of the integration API's {@code Idempotency-Key} header
 * (ws26, V260), one row per (bank, client, key). {@code ResponseStatus} is
 * null while the first request is still in flight.
 *
 * <p>SCHEMA PARITY with V260 (the foreign keys are PostgreSQL-only).
 */
@Entity
@Table(name = "IdempotencyKey",
        uniqueConstraints = @UniqueConstraint(name = "uq_idempotencykey_bank_client_key",
                columnNames = {"BankId", "ApiClientId", "IdemKey"}),
        indexes = @Index(name = "ix_idempotencykey_bankid_expiresat",
                columnList = "BankId, ExpiresAt"))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IdempotencyKeyId")
    private long idempotencyKeyId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "ApiClientId", nullable = false)
    private int apiClientId;

    @Column(name = "IdemKey", length = 255, nullable = false)
    private String idemKey;

    /** SHA-256 (hex) of method + route + body. */
    @Column(name = "RequestHash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "ResponseStatus")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ResponseBody")
    private String responseBody;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "ExpiresAt", nullable = false)
    private Instant expiresAt;

    public long getIdempotencyKeyId() {
        return idempotencyKeyId;
    }

    public void setIdempotencyKeyId(long idempotencyKeyId) {
        this.idempotencyKeyId = idempotencyKeyId;
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

    public String getIdemKey() {
        return idemKey;
    }

    public void setIdemKey(String idemKey) {
        this.idemKey = idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
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
