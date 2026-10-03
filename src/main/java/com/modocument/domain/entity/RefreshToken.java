package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token. The opaque value itself is never stored — only
 * its SHA-256 hash — and it travels solely in an httpOnly cookie scoped to
 * /api/auth/refresh. FamilyId ties a login's rotation chain together:
 * rotation stamps RevokedAt/ReplacedByJti on the old row, and presenting an
 * already-rotated token revokes the whole family (reuse detection).
 */
@Entity
@Table(name = "RefreshToken")
public class RefreshToken {

    @Id
    @Column(name = "Jti")
    private UUID jti;

    @Column(name = "UserId", nullable = false)
    private int userId;

    @Column(name = "FamilyId", nullable = false)
    private UUID familyId;

    @Column(name = "TokenHash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "IssuedAt", nullable = false)
    private Instant issuedAt;

    @Column(name = "ExpiresAt", nullable = false)
    private Instant expiresAt;

    @Column(name = "RevokedAt")
    private Instant revokedAt;

    @Column(name = "ReplacedByJti")
    private UUID replacedByJti;

    @Column(name = "Ip", length = 64)
    private String ip;

    @Column(name = "UserAgent", length = 400)
    private String userAgent;

    public RefreshToken() {
    }

    public UUID getJti() {
        return jti;
    }

    public void setJti(UUID jti) {
        this.jti = jti;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void setFamilyId(UUID familyId) {
        this.familyId = familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public UUID getReplacedByJti() {
        return replacedByJti;
    }

    public void setReplacedByJti(UUID replacedByJti) {
        this.replacedByJti = replacedByJti;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
