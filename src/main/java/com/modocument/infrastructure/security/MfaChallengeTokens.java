package com.modocument.infrastructure.security;

import com.modocument.domain.entity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The MFA challenge token (gate B3): what a correct password buys when a
 * second factor is still owed. Signed with the same HS256 key as access
 * tokens but marked with {@code mfa_challenge: true} and short-lived
 * (default 5 minutes). It is NOT an access token:
 * {@link MfaChallengeScopeValidator} rejects it on every route except the
 * two enrolment endpoints, and {@code POST /api/auth/mfa/verify} is the only
 * place it converts into a real session — after the TOTP (or recovery) code
 * checks out.
 *
 * <p>Claims: sub/jti/roles/bank_id/token_ver like a normal token (so the
 * revocation checks and the enrolment endpoints' permission check work),
 * plus the marker claim. Parsing here validates signature, expiry and the
 * marker; the use case re-checks token_ver against the user row so a
 * password change between challenge and verify kills the challenge too.
 */
@Service
public class MfaChallengeTokens {

    public static final String CLAIM_MFA_CHALLENGE = "mfa_challenge";

    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final long ttlMinutes;

    public MfaChallengeTokens(JwtEncoder encoder,
                              @Value("${modocument.security.jwt.secret}") String secret,
                              @Value("${modocument.security.mfa.challenge-ttl-minutes:5}") long ttlMinutes) {
        this.encoder = encoder;
        this.ttlMinutes = ttlMinutes;
        this.decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                token -> Boolean.TRUE.equals(token.getClaim(CLAIM_MFA_CHALLENGE))
                        ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                        : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                                new org.springframework.security.oauth2.core.OAuth2Error(
                                        "invalid_token", "Not an MFA challenge token.", null))));
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getAppUserId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttlMinutes, ChronoUnit.MINUTES))
                .claim(JwtService.CLAIM_ROLES, List.of(user.getRole().name()))
                .claim(JwtService.CLAIM_BANK_ID, user.getBankId())
                .claim(JwtService.CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_MFA_CHALLENGE, true)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * @return the validated challenge Jwt (signature + expiry + marker claim)
     * @throws JwtException when the token is not a live, well-formed challenge
     */
    public Jwt parse(String challenge) {
        return decoder.decode(challenge);
    }
}
