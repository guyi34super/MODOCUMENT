package com.modocument.infrastructure.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.RevokedToken;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.RevokedTokenJpaRepository;
import com.modocument.testsupport.StubBankDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side revocation against the real schema: the validator must kill a
 * token the moment its jti is denylisted (logout), the moment the user's
 * TokenVersion moves past the token's token_ver claim (the credential/role-
 * change gate), and when the user is gone — and must pass a clean token.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RevocationValidatorTest {

    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RevokedTokenJpaRepository revoked;
    @Autowired
    private JdbcTemplate jdbc;

    /** ws21: bank status per test; unknown banks read as active. */
    private final StubBankDirectory banks = new StubBankDirectory();

    private RevocationValidator validator() {
        return new RevocationValidator(jdbc, banks);
    }

    private AppUser storedUser() {
        AppUser user = new AppUser();
        user.setEmail("revocation-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash");
        user.setRole(Role.READ_ONLY);
        user.setBankId(1);
        return users.saveAndFlush(user);
    }

    private static Jwt token(int userId, String jti, int tokenVersion) {
        return Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(String.valueOf(userId))
                .jti(jti)
                .claim(JwtService.CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(JwtService.CLAIM_BANK_ID, 1)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    @Test
    void aCleanTokenPasses() {
        AppUser user = storedUser();

        OAuth2TokenValidatorResult result = validator().validate(
                token(user.getAppUserId(), UUID.randomUUID().toString(), 0));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void aDenylistedJtiIsRejected() {
        AppUser user = storedUser();
        UUID jti = UUID.randomUUID();
        revoked.saveAndFlush(new RevokedToken(jti, Instant.now().plusSeconds(600)));

        OAuth2TokenValidatorResult result =
                validator().validate(token(user.getAppUserId(), jti.toString(), 0));

        assertThat(result.hasErrors()).isTrue();
    }

    /**
     * The B5 gate: a token issued before a credential/role change (which bumps
     * AppUsers.TokenVersion) must be rejected on its very next use. No
     * role-change endpoint exists yet, so the bump is applied directly.
     */
    @Test
    void aTokenIssuedBeforeATokenVersionBumpIsRejected() {
        AppUser user = storedUser();
        Jwt issuedEarlier = token(user.getAppUserId(), UUID.randomUUID().toString(),
                user.getTokenVersion());
        assertThat(validator().validate(issuedEarlier).hasErrors()).isFalse();

        // Role change / password change bumps the version server-side.
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.saveAndFlush(user);

        assertThat(validator().validate(issuedEarlier).hasErrors()).isTrue();
    }

    /**
     * PRIV-017 leaver: the Disabled flag alone (no version bump, no
     * denylist) must kill every outstanding token on its next request.
     */
    @Test
    void aTokenOfADisabledUserIsRejected() {
        AppUser user = storedUser();
        Jwt issuedEarlier = token(user.getAppUserId(), UUID.randomUUID().toString(),
                user.getTokenVersion());
        assertThat(validator().validate(issuedEarlier).hasErrors()).isFalse();

        user.setDisabled(true);
        users.saveAndFlush(user);

        assertThat(validator().validate(issuedEarlier).hasErrors()).isTrue();
    }

    /**
     * ws21 suspension, defense in depth: even a token whose version still
     * matches dies once its user's bank is suspended — with the generic
     * revocation description, never "suspended".
     */
    @Test
    void aTokenOfASuspendedBankIsRejectedGenerically() {
        AppUser user = storedUser();
        Jwt issuedEarlier = token(user.getAppUserId(), UUID.randomUUID().toString(),
                user.getTokenVersion());
        assertThat(validator().validate(issuedEarlier).hasErrors()).isFalse();

        banks.suspended(user.getBankId());

        OAuth2TokenValidatorResult result = validator().validate(issuedEarlier);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).allSatisfy(error ->
                assertThat(error.getDescription()).doesNotContainIgnoringCase("suspend"));

        banks.customer(user.getBankId()); // reactivated
        assertThat(validator().validate(issuedEarlier).hasErrors()).isFalse();
    }

    @Test
    void aTokenForAMissingUserIsRejected() {
        OAuth2TokenValidatorResult result =
                validator().validate(token(99_999_999, UUID.randomUUID().toString(), 0));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void aLegacyTokenWithoutVersionClaimCountsAsVersionZero() {
        AppUser user = storedUser();
        Jwt legacy = Jwt.withTokenValue("legacy").header("alg", "none")
                .subject(String.valueOf(user.getAppUserId()))
                .claim("email", "legacy@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        assertThat(validator().validate(legacy).hasErrors()).isFalse();

        user.setTokenVersion(1);
        users.saveAndFlush(user);

        assertThat(validator().validate(legacy).hasErrors()).isTrue();
    }
}
