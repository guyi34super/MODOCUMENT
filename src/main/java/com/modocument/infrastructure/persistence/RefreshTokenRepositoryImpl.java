package com.modocument.infrastructure.persistence;

import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.domain.entity.RefreshToken;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        return jpa.save(token);
    }

    @Override
    public Optional<RefreshToken> getByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, Instant when) {
        jpa.revokeFamily(familyId, when);
    }

    @Override
    @Transactional
    public void revokeAllForUser(int userId, Instant when) {
        jpa.revokeAllForUser(userId, when);
    }
}
