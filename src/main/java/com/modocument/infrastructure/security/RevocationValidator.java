package com.modocument.infrastructure.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Server-side revocation for otherwise-stateless access tokens, registered on
 * the NimbusJwtDecoder so it runs for every authenticated request. A token is
 * rejected when:
 * <ul>
 *   <li>its jti is on the RevokedToken denylist (logout), or</li>
 *   <li>its token_ver claim is older than AppUsers.TokenVersion (password
 *       change bumps the version, killing every outstanding token), or</li>
 *   <li>its user no longer exists.</li>
 * </ul>
 *
 * <p>Deliberately ONE cheap primary-key-driven DB read per request and no
 * cache: a revocation must take effect on the very next request, and the
 * query is two unique-index probes.
 */
@Component
public class RevocationValidator implements OAuth2TokenValidator<Jwt> {

    /** jti that can never be in the denylist, used when a token has none. */
    private static final UUID NIL_JTI = new UUID(0L, 0L);

    private static final String QUERY =
            "SELECT u.TokenVersion AS currentVersion, "
                    + "EXISTS (SELECT 1 FROM RevokedToken r WHERE r.Jti = ?) AS revoked "
                    + "FROM AppUsers u WHERE u.AppUserId = ?";

    private final JdbcTemplate jdbc;

    public RevocationValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        int userId;
        try {
            userId = Integer.parseInt(token.getSubject());
        } catch (NumberFormatException e) {
            return failure("Token subject is not a user id.");
        }
        UUID jti = parseJti(token.getId());

        Status status;
        try {
            status = jdbc.queryForObject(QUERY, (rs, rowNum) ->
                    new Status(rs.getInt("currentVersion"), rs.getBoolean("revoked")), jti, userId);
        } catch (EmptyResultDataAccessException userGone) {
            return failure("Token user no longer exists.");
        }
        if (status == null) {
            return failure("Token user no longer exists.");
        }
        if (status.revoked()) {
            return failure("Token has been revoked.");
        }
        // Tokens from before the claim existed count as version 0.
        Object claim = token.getClaims().get(JwtService.CLAIM_TOKEN_VERSION);
        int tokenVersion = claim instanceof Number number ? number.intValue() : 0;
        if (tokenVersion < status.currentVersion()) {
            return failure("Token was invalidated by a credential or role change.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static UUID parseJti(String jti) {
        if (jti == null) {
            return NIL_JTI;
        }
        try {
            return UUID.fromString(jti);
        } catch (IllegalArgumentException notAUuid) {
            return NIL_JTI;
        }
    }

    private static OAuth2TokenValidatorResult failure(String reason) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, reason, null));
    }

    private record Status(int currentVersion, boolean revoked) {
    }
}
