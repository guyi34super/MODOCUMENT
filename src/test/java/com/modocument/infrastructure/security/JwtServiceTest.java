package com.modocument.infrastructure.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access-token claim contract: lean claims only (sub/jti/roles/bank_id/
 * token_ver), no profile data, short TTL. Anything downstream — the
 * RevocationValidator, the TenantContextFilter, RBAC — reads exactly these.
 */
class JwtServiceTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "modocument-local-dev-secret-change-in-production-0123456789"
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    private final NimbusJwtDecoder decoder =
            NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

    private static AppUser user() {
        AppUser user = new AppUser(7, "someone@example.com", "hash", "Some One");
        user.setBankId(3);
        user.setRole(Role.APPROVER);
        user.setTokenVersion(5);
        return user;
    }

    @Test
    void tokenCarriesLeanClaimsOnly() {
        JwtService service = new JwtService(encoder, 15, 0);

        Jwt jwt = decoder.decode(service.issueToken(user()));

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(UUID.fromString(jwt.getId())).isNotNull(); // jti is a random UUID
        assertThat(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES))
                .isEqualTo(List.of("APPROVER"));
        assertThat(jwt.<Number>getClaim(JwtService.CLAIM_BANK_ID).intValue()).isEqualTo(3);
        assertThat(jwt.<Number>getClaim(JwtService.CLAIM_TOKEN_VERSION).intValue()).isEqualTo(5);
        // Profile data stays in the login response body, never in the token.
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "displayName");
    }

    @Test
    void everyTokenGetsItsOwnJti() {
        JwtService service = new JwtService(encoder, 15, 0);

        Jwt first = decoder.decode(service.issueToken(user()));
        Jwt second = decoder.decode(service.issueToken(user()));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void defaultTtlIsMinutesNotHours() {
        JwtService service = new JwtService(encoder, 15, 0);

        Jwt jwt = decoder.decode(service.issueToken(user()));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void explicitLegacyTtlHoursIsStillHonored() {
        JwtService service = new JwtService(encoder, 15, 2);

        Jwt jwt = decoder.decode(service.issueToken(user()));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(Duration.ofHours(2));
    }
}
