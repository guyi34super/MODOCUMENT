package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Denylisted access-token jti (logout). A row only matters until ExpiresAt —
 * the token's own exp — after which the token is dead anyway and the row can
 * be swept.
 */
@Entity
@Table(name = "RevokedToken")
public class RevokedToken {

    @Id
    @Column(name = "Jti")
    private UUID jti;

    @Column(name = "ExpiresAt", nullable = false)
    private Instant expiresAt;

    public RevokedToken() {
    }

    public RevokedToken(UUID jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }

    public UUID getJti() {
        return jti;
    }

    public void setJti(UUID jti) {
        this.jti = jti;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
