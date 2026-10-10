package com.modocument.application.port;

import com.modocument.domain.entity.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for refresh-token rotation (see AuthUseCase). */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> getByTokenHash(String tokenHash);

    /** Marks every live token of the family revoked (reuse detection/logout). */
    void revokeFamily(UUID familyId, Instant when);

    /** Marks every live token of the user revoked (logout, password change). */
    void revokeAllForUser(int userId, Instant when);
}
