package com.modocument.infrastructure.security;

import com.modocument.application.port.BankDirectory;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.ClientTokenService;
import com.modocument.infrastructure.security.client.MtlsClientCertificates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Server-side revocation for otherwise-stateless access tokens, registered on
 * the NimbusJwtDecoder so it runs for every authenticated request. A token is
 * rejected when:
 * <ul>
 *   <li>its jti is on the RevokedToken denylist (logout), or</li>
 *   <li>its token_ver claim is older than AppUsers.TokenVersion (password
 *       change bumps the version, killing every outstanding token), or</li>
 *   <li>its user is disabled (ws09 leaver switch — the flag alone makes the
 *       account inert on the very next request, independent of the version
 *       bump the disable also performs), or</li>
 *   <li>its user no longer exists, or</li>
 *   <li>its user's bank is SUSPENDED (ws21; the suspension also bumps every
 *       user's TokenVersion, so this is defense in depth — answered with
 *       the same generic description as a revocation).</li>
 * </ul>
 *
 * <p>Deliberately ONE cheap primary-key-driven DB read per request and no
 * cache: a revocation must take effect on the very next request, and the
 * query is two unique-index probes. The bank status comes from the
 * {@link BankDirectory} (short-TTL cache; the Bank table is RLS-protected
 * and no tenant is bound while the token is being validated).
 *
 * <p><b>Client branch (ws26).</b> User and machine tokens share the signing
 * key and are kept apart by the {@code typ} claim — both ways: a token with
 * {@code typ=client} is validated ONLY as a client token (by
 * {@link ApiClientAuthenticator#validateToken}: the ApiClient must exist, be
 * ACTIVE, unexpired, its bank not suspended, its creator still an enabled
 * user of that bank (AGENT clients exempt), the method still allowed, the
 * caller's IP inside AllowedCidrs, {@code token_ver} not older than
 * ApiClient.TokenVersion — a revocation is a 401 on the very next request —
 * and a certificate-bound token must come with the same certificate); any
 * other {@code typ} is refused; a token without {@code typ} is a user token
 * and goes through the checks above (its subject must be a user id).
 */
@Component
public class RevocationValidator implements OAuth2TokenValidator<Jwt> {

    /** jti that can never be in the denylist, used when a token has none. */
    private static final UUID NIL_JTI = new UUID(0L, 0L);

    private static final String QUERY =
            "SELECT u.TokenVersion AS currentVersion, u.Disabled AS disabled, "
                    + "u.BankId AS bankId, "
                    + "EXISTS (SELECT 1 FROM RevokedToken r WHERE r.Jti = ?) AS revoked "
                    + "FROM AppUsers u WHERE u.AppUserId = ?";

    private final JdbcTemplate jdbc;
    private final BankDirectory bankDirectory;

    /** Null = no client tokens accepted (unit-test wiring). */
    private final ObjectProvider<ApiClientAuthenticator> clientAuthenticator;
    private final ObjectProvider<MtlsClientCertificates> certificates;

    public RevocationValidator(JdbcTemplate jdbc, BankDirectory bankDirectory) {
        this(jdbc, bankDirectory, null, null);
    }

    @Autowired
    public RevocationValidator(JdbcTemplate jdbc, BankDirectory bankDirectory,
                               ObjectProvider<ApiClientAuthenticator> clientAuthenticator,
                               ObjectProvider<MtlsClientCertificates> certificates) {
        this.jdbc = jdbc;
        this.bankDirectory = bankDirectory;
        this.clientAuthenticator = clientAuthenticator;
        this.certificates = certificates;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (ClientTokenService.isClientToken(token)) {
            return validateClientToken(token);
        }
        if (token.getClaims().get(ClientTokenService.CLAIM_TYPE) != null) {
            // typ is reserved for machine tokens; nothing else may carry it.
            return failure("Unknown token type.");
        }
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
                    new Status(rs.getInt("currentVersion"), rs.getBoolean("disabled"),
                            rs.getInt("bankId"), rs.getBoolean("revoked")), jti, userId);
        } catch (EmptyResultDataAccessException userGone) {
            return failure("Token user no longer exists.");
        }
        if (status == null) {
            return failure("Token user no longer exists.");
        }
        if (status.revoked()) {
            return failure("Token has been revoked.");
        }
        if (status.disabled()) {
            return failure("Token user is disabled.");
        }
        // Tokens from before the claim existed count as version 0.
        Object claim = token.getClaims().get(JwtService.CLAIM_TOKEN_VERSION);
        int tokenVersion = claim instanceof Number number ? number.intValue() : 0;
        if (tokenVersion < status.currentVersion()) {
            return failure("Token was invalidated by a credential or role change.");
        }
        if (bankDirectory.isSuspended(status.bankId())) {
            // Deliberately the generic revocation wording: the response
            // must not tell a caller that their bank was suspended.
            return failure("Token has been revoked.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** ws26: a machine token is good only while its ApiClient is. */
    private OAuth2TokenValidatorResult validateClientToken(Jwt token) {
        ApiClientAuthenticator authenticator =
                clientAuthenticator == null ? null : clientAuthenticator.getIfAvailable();
        MtlsClientCertificates certs = certificates == null ? null : certificates.getIfAvailable();
        if (authenticator == null || certs == null) {
            return failure("Client tokens are not accepted here.");
        }
        HttpServletRequest request =
                RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                        ? a.getRequest() : null;
        ApiClientAuthenticator.Result result = authenticator.validateToken(token, request, certs);
        // Deliberately one generic wording: the caller learns nothing about
        // which check failed (the API_CLIENT_AUTH_FAILED audit row says).
        return result.succeeded() ? OAuth2TokenValidatorResult.success()
                : failure("Token has been revoked.");
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

    private record Status(int currentVersion, boolean disabled, int bankId, boolean revoked) {
    }
}
