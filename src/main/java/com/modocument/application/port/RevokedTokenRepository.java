package com.modocument.application.port;

import java.time.Instant;
import java.util.UUID;

/** Persistence port for the access-token jti denylist (logout). */
public interface RevokedTokenRepository {

    /** Records {@code jti} as revoked until the token's own expiry. */
    void add(UUID jti, Instant expiresAt);

    boolean isRevoked(UUID jti);
}
