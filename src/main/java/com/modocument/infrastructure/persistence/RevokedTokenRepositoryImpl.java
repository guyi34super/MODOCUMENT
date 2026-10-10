package com.modocument.infrastructure.persistence;

import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.domain.entity.RevokedToken;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class RevokedTokenRepositoryImpl implements RevokedTokenRepository {

    private final RevokedTokenJpaRepository jpa;

    public RevokedTokenRepositoryImpl(RevokedTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void add(UUID jti, Instant expiresAt) {
        // Idempotent: revoking an already-revoked jti is a no-op, not an error.
        if (!jpa.existsById(jti)) {
            jpa.save(new RevokedToken(jti, expiresAt));
        }
    }

    @Override
    public boolean isRevoked(UUID jti) {
        return jpa.existsById(jti);
    }
}
