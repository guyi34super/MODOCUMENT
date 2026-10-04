package com.modocument.application.usecase;

import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.application.security.LoginLockoutPolicy;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUseCaseTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    // Plain BCrypt keeps the long-standing tests fast and rehash-free
    // (BCrypt→BCrypt never upgrades); the SEC-018 tests below use the real
    // delegating encoder explicitly.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final PasswordPolicy passwordPolicy = new PasswordPolicy(12);
    private final LoginLockoutPolicy lockoutPolicy = new LoginLockoutPolicy(5, 1, 30);
    private final UserMfaRepository userMfa = mock(UserMfaRepository.class);
    private final MfaChallengeTokens challengeTokens = mock(MfaChallengeTokens.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final RevokedTokenRepository revokedTokens = mock(RevokedTokenRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    // The real B5 seam over the same mocks: the bump and its audit are part
    // of what these tests assert.
    private final UserSecurityService userSecurity = new UserSecurityService(repo, audit);
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(12);
    private final AuthUseCase useCase = new AuthUseCase(repo, encoder, passwordPolicy,
            lockoutPolicy, userMfa, challengeTokens, jwtService, refreshTokens,
            revokedTokens, userSecurity, audit, true, IDLE_TIMEOUT);

    /** The production encoder wiring (SecurityConfig): argon2 default, bcrypt legacy. */
    private static PasswordEncoder delegatingEncoder() {
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                        "bcrypt", new BCryptPasswordEncoder()));
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return delegating;
    }

    private AppUser demoUser() {
        AppUser user = new AppUser(1, "demo@modocument.local", encoder.encode("Demo123!"), "Demo User");
        user.setSettingsJson("{}");
        user.setBankId(Bank.DEFAULT_BANK_ID);
        user.setRole(Role.READ_ONLY);
        return user;
    }

    // ------------------------------------------------------- user creation

    @Test
    void createUserStoresBankAndRole() {
        when(repo.existsByEmail("new@example.com")).thenReturn(false);
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto created = useCase.createUser(
                "new@example.com", "Rotor-Kestrel-91x", "New User", 1, Role.ADMIN, true);

        assertThat(created.email()).isEqualTo("new@example.com");
        assertThat(created.displayName()).isEqualTo("New User");
        assertThat(created.role()).isEqualTo("ADMIN");
        assertThat(created.bankId()).isEqualTo(1);
        // Admin-issued temporary password: the first login must be followed
        // by a password change before anything else (ws09).
        assertThat(created.mustChangePassword()).isTrue();
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        when(repo.existsByEmail("demo@modocument.local")).thenReturn(true);

        assertThatThrownBy(() -> useCase.createUser(
                "demo@modocument.local", "Rotor-Kestrel-91x", "X", 1, Role.READ_ONLY, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createUserRejectsShortPassword() {
        assertThatThrownBy(() -> useCase.createUser(
                "a@b.com", "short", "X", 1, Role.READ_ONLY, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** B4/SEC-021: the policy guards creation too — breached and email-derived. */
    @Test
    void createUserRejectsBreachedAndEmailDerivedPasswords() {
        assertThatThrownBy(() -> useCase.createUser(
                "a@b.com", "Password123", "X", 1, Role.READ_ONLY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commonly used or breached");
        assertThatThrownBy(() -> useCase.createUser(
                "marguerite@b.com", "xx-Marguerite-2026", "X", 1, Role.READ_ONLY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    // ----------------------------------------------------------------- login

    @Test
    void loginReturnsTokenRefreshValueAndStampsLastLogin() {
        AppUser user = demoUser();
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthUseCase.AuthSession session = (AuthUseCase.AuthSession)
                useCase.login("demo@modocument.local", "Demo123!", "10.0.0.1", "JUnit");

        assertThat(session.response().token()).isEqualTo("jwt-token");
        assertThat(session.response().user().userId()).isEqualTo(1);
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(user.getLastLoginAt()).isNotNull();

        ArgumentCaptor<RefreshToken> stored = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(stored.capture());
        // Only the SHA-256 hash is persisted, never the raw cookie value.
        assertThat(stored.getValue().getTokenHash()).hasSize(64)
                .isNotEqualTo(session.refreshToken());
        assertThat(stored.getValue().getFamilyId()).isNotNull();

        // C7/B1: the login is audited with the person and their bank.
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGIN_SUCCESS");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.actorEmail()).isEqualTo("demo@modocument.local");
        assertThat(recorded.bankId()).isEqualTo(1);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(demoUser()));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> useCase.login("demo@modocument.local", "wrong-password", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        // Known account + wrong password: audited WITH the user id.
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGIN_FAILED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.bankId()).isEqualTo(1);
    }

    /**
     * PRIV-017 leaver: a disabled account gets the SAME generic 401 as a
     * wrong password — even with the correct password — and the refusal
     * neither issues tokens nor feeds the lockout counters; only the audit
     * row carries the reason.
     */
    @Test
    void loginRefusesADisabledAccountWithTheGenericMessage() {
        AppUser user = demoUser();
        user.setDisabled(true);
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.login("demo@modocument.local", "Demo123!", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        verify(refreshTokens, never()).save(any(RefreshToken.class));
        assertThat(user.getFailedLoginCount()).isZero();
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGIN_FAILED");
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.newValues()).isEqualTo(Map.of("reason", "account_disabled"));
    }

    /** The MFA second phase must not outlive a disable issued in between. */
    @Test
    void completeMfaLoginRefusesADisabledUser() {
        AppUser user = demoUser();
        user.setDisabled(true);

        assertThatThrownBy(() -> useCase.completeMfaLogin(user, null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        verify(refreshTokens, never()).save(any(RefreshToken.class));
    }

    @Test
    void loginRejectsUnknownEmailWithSameMessage() {
        when(repo.getByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login("nobody@example.com", "Demo123!", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        // The response hides whether the email exists; the audit row records
        // the truth - attempted email, no user id.
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGIN_FAILED");
        assertThat(recorded.actorUserId()).isNull();
        assertThat(recorded.actorEmail()).isEqualTo("nobody@example.com");
    }

    /**
     * The account-enumeration timing fix: an unknown email must still burn one
     * password-hash comparison so its response time matches a wrong password.
     */
    @Test
    void loginForUnknownEmailStillRunsOneHashMatch() {
        PasswordEncoder spyEncoder = mock(PasswordEncoder.class);
        when(spyEncoder.encode(anyString())).thenReturn("dummy-hash");
        AuthUseCase timed = new AuthUseCase(repo, spyEncoder, passwordPolicy, lockoutPolicy,
                userMfa, challengeTokens, jwtService, refreshTokens, revokedTokens,
                userSecurity, audit, true, IDLE_TIMEOUT);
        when(repo.getByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> timed.login("nobody@example.com", "whatever", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        // Exactly one dummy match ran, against a fixed (non-null) hash.
        verify(spyEncoder, times(1)).matches(eq("whatever"), anyString());
    }

    // --------------------------------------------------------- lockout (B4)

    @Test
    void sixthConsecutiveFailureLocksAndIsAuditedOnce() {
        AppUser user = demoUser();
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThatThrownBy(() ->
                    useCase.login("demo@modocument.local", "wrong-password", null, null))
                    .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                    .hasMessage("Invalid email or password.");
        }

        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(user.getLockoutCount()).isEqualTo(1);
        List<AuditEventSpec> recorded = allRecordedAudits();
        assertThat(recorded).extracting(AuditEventSpec::action)
                .containsOnlyOnce("ACCOUNT_LOCKED")
                .filteredOn("LOGIN_FAILED"::equals).hasSize(6);
        AuditEventSpec locked = recorded.stream()
                .filter(spec -> spec.action().equals("ACCOUNT_LOCKED")).findFirst().orElseThrow();
        assertThat(locked.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(locked.actorUserId()).isEqualTo(1);
    }

    @Test
    void correctPasswordWhileLockedIsTheSameGeneric401AndNeverVerifies() {
        AppUser user = demoUser();
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.login("demo@modocument.local", "Demo123!", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        // No session artifacts, and the row keeps its lock untouched.
        verify(refreshTokens, never()).save(any(RefreshToken.class));
        assertThat(user.getLockedUntil()).isNotNull();
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGIN_FAILED");
        assertThat(recorded.actorUserId()).isEqualTo(1);
    }

    @Test
    void expiredLockLetsTheCorrectPasswordInAndResetsTheCounters() {
        AppUser user = demoUser();
        user.setFailedLoginCount(3);
        user.setLockoutCount(2);
        user.setLockedUntil(LocalDateTime.now().minusSeconds(1)); // lock already over
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthUseCase.LoginResult result =
                useCase.login("demo@modocument.local", "Demo123!", null, null);

        assertThat(result).isInstanceOf(AuthUseCase.AuthSession.class);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockoutCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    // -------------------------------------------------------- SEC-018 rehash

    /** Upgrade-on-login: a legacy raw-BCrypt hash becomes Argon2id. */
    @Test
    void successfulLoginRehashesALegacyBcryptHashAndAuditsIt() {
        PasswordEncoder delegating = delegatingEncoder();
        AuthUseCase upgraded = new AuthUseCase(repo, delegating, passwordPolicy, lockoutPolicy,
                userMfa, challengeTokens, jwtService, refreshTokens, revokedTokens,
                userSecurity, audit, true, IDLE_TIMEOUT);
        AppUser user = demoUser(); // raw BCrypt hash, no {id} prefix
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        upgraded.login("demo@modocument.local", "Demo123!", null, null);

        assertThat(user.getPasswordHash()).startsWith("{argon2}");
        assertThat(delegating.matches("Demo123!", user.getPasswordHash())).isTrue();
        assertThat(allRecordedAudits()).extracting(AuditEventSpec::action)
                .containsExactly("PASSWORD_REHASHED", "LOGIN_SUCCESS");
    }

    /** An Argon2id hash is already current — no rewrite, no audit noise. */
    @Test
    void argonHashIsNotRehashedAgain() {
        PasswordEncoder delegating = delegatingEncoder();
        AuthUseCase upgraded = new AuthUseCase(repo, delegating, passwordPolicy, lockoutPolicy,
                userMfa, challengeTokens, jwtService, refreshTokens, revokedTokens,
                userSecurity, audit, true, IDLE_TIMEOUT);
        AppUser user = demoUser();
        user.setPasswordHash(delegating.encode("Demo123!"));
        String original = user.getPasswordHash();
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        upgraded.login("demo@modocument.local", "Demo123!", null, null);

        assertThat(user.getPasswordHash()).isEqualTo(original);
        assertThat(allRecordedAudits()).extracting(AuditEventSpec::action)
                .containsExactly("LOGIN_SUCCESS");
    }

    // ------------------------------------------------------------- MFA (B3)

    @Test
    void loginWithMfaEnabledReturnsAChallengeAndNoTokens() {
        AppUser user = demoUser();
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMfa.isEnabled(1)).thenReturn(true);
        when(challengeTokens.issue(user)).thenReturn("challenge-jwt");

        AuthUseCase.LoginResult result =
                useCase.login("demo@modocument.local", "Demo123!", null, null);

        assertThat(result).isEqualTo(new AuthUseCase.MfaChallenge("challenge-jwt", false));
        // No session: no refresh token, no access token, no LOGIN_SUCCESS yet.
        verify(refreshTokens, never()).save(any(RefreshToken.class));
        verify(jwtService, never()).issueToken(any(AppUser.class));
        verify(audit, never()).record(any(AuditEventSpec.class));
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void adminWithoutMfaIsForcedIntoEnrollment() {
        AppUser admin = demoUser();
        admin.setRole(Role.ADMIN);
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(admin));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMfa.isEnabled(1)).thenReturn(false);
        when(challengeTokens.issue(admin)).thenReturn("challenge-jwt");

        AuthUseCase.LoginResult result =
                useCase.login("demo@modocument.local", "Demo123!", null, null);

        assertThat(result).isEqualTo(new AuthUseCase.MfaChallenge("challenge-jwt", true));
        verify(jwtService, never()).issueToken(any(AppUser.class));
    }

    @Test
    void adminWithoutMfaLogsStraightInWhenRequireForAdminIsOff() {
        AuthUseCase relaxed = new AuthUseCase(repo, encoder, passwordPolicy, lockoutPolicy,
                userMfa, challengeTokens, jwtService, refreshTokens, revokedTokens,
                userSecurity, audit, false, IDLE_TIMEOUT);
        AppUser admin = demoUser();
        admin.setRole(Role.ADMIN);
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(admin));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        assertThat(relaxed.login("demo@modocument.local", "Demo123!", null, null))
                .isInstanceOf(AuthUseCase.AuthSession.class);
    }

    // --------------------------------------------------------------- refresh

    @Test
    void refreshingARevokedTokenRevokesItsWholeFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken rotatedAway = new RefreshToken();
        rotatedAway.setJti(UUID.randomUUID());
        rotatedAway.setUserId(1);
        rotatedAway.setFamilyId(family);
        rotatedAway.setTokenHash("x".repeat(64));
        rotatedAway.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        rotatedAway.setReplacedByJti(UUID.randomUUID()); // already rotated
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(rotatedAway));

        assertThatThrownBy(() -> useCase.refresh("stolen-value", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);

        verify(refreshTokens).revokeFamily(eq(family), any(Instant.class));
        verify(refreshTokens, never()).save(any(RefreshToken.class));

        // SEC-034: reuse is a security signal - audited with the killed family.
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("TOKEN_REFRESH_REUSE_DETECTED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.entityType()).isEqualTo("RefreshTokenFamily");
        assertThat(recorded.entityId()).isEqualTo(family.toString());
    }

    @Test
    void refreshRejectsAnUnknownToken() {
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refresh("unknown", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
    }

    /** A live (not rotated, not revoked) refresh token for user 1. */
    private static RefreshToken liveToken() {
        RefreshToken token = new RefreshToken();
        token.setJti(UUID.randomUUID());
        token.setUserId(1);
        token.setFamilyId(UUID.randomUUID());
        token.setTokenHash("x".repeat(64));
        token.setIssuedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        token.setExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));
        return token;
    }

    /** SUP-S02 absolute bound: rotation inherits ExpiresAt, never extends it. */
    @Test
    void rotationInheritsTheFamilyExpiryAndNeverExtendsIt() {
        RefreshToken current = liveToken();
        Instant familyExpiry = current.getExpiresAt();
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        useCase.refresh("value", null, null);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens, times(2)).save(saved.capture()); // new token, then the rotated-away row
        RefreshToken next = saved.getAllValues().get(0);
        assertThat(next.getFamilyId()).isEqualTo(current.getFamilyId());
        assertThat(next.getExpiresAt()).isEqualTo(familyExpiry);
        assertThat(current.getReplacedByJti()).isEqualTo(next.getJti());
    }

    /** SUP-S02 absolute bound: a family past its ExpiresAt is refused and audited. */
    @Test
    void refreshRefusesAnExpiredFamilyAndAuditsTheAbsoluteExpiry() {
        RefreshToken current = liveToken();
        current.setExpiresAt(Instant.now().minusSeconds(1)); // 7 days are over
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.refresh("value", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Refresh token has expired.");

        verify(refreshTokens, never()).save(any(RefreshToken.class));
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("SESSION_ABSOLUTE_EXPIRED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.entityType()).isEqualTo("RefreshTokenFamily");
        assertThat(recorded.entityId()).isEqualTo(current.getFamilyId().toString());
    }

    /** SUP-S02 idle bound: unused past the idle timeout is over, however young the family. */
    @Test
    void refreshRefusesAnIdleSessionAndAuditsTheIdleExpiry() {
        RefreshToken current = liveToken();
        current.setIssuedAt(Instant.now().minus(13, ChronoUnit.HOURS)); // idle > 12h
        current.setExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));   // family still young
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.refresh("value", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Refresh token has expired.");

        // Refused but NOT revoked: a retry hits the same refusal, not the
        // reuse (theft) alarm; and no rotation happened.
        verify(refreshTokens, never()).save(any(RefreshToken.class));
        verify(refreshTokens, never()).revokeFamily(any(UUID.class), any(Instant.class));
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("SESSION_IDLE_EXPIRED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.actorUserId()).isEqualTo(1);
    }

    /** A session used within the idle timeout keeps rotating normally. */
    @Test
    void refreshWithinTheIdleTimeoutStillRotates() {
        RefreshToken current = liveToken();
        current.setIssuedAt(Instant.now().minus(11, ChronoUnit.HOURS)); // under 12h
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(current));
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthUseCase.AuthSession session = useCase.refresh("value", null, null);

        assertThat(session.refreshToken()).isNotBlank();
        verify(refreshTokens, times(2)).save(any(RefreshToken.class));
    }

    /** Belt and braces for a Disabled flag set out-of-band (PRIV-017). */
    @Test
    void refreshRefusesADisabledUser() {
        RefreshToken live = new RefreshToken();
        live.setJti(UUID.randomUUID());
        live.setUserId(1);
        live.setFamilyId(UUID.randomUUID());
        live.setTokenHash("x".repeat(64));
        live.setIssuedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // inside the idle bound (NOT NULL in the real schema)
        live.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(refreshTokens.getByTokenHash(anyString())).thenReturn(Optional.of(live));
        AppUser user = demoUser();
        user.setDisabled(true);
        when(repo.getById(1)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.refresh("still-have-the-cookie", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        verify(refreshTokens, never()).save(any(RefreshToken.class));
    }

    // --------------------------------------------------------------- profile

    @Test
    void updateProfileChangesDisplayNameAndSettings() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto updated = useCase.updateProfile(1, "Renamed", "{\"theme\":\"dark\"}");

        assertThat(updated.displayName()).isEqualTo("Renamed");
        assertThat(updated.settings()).isEqualTo("{\"theme\":\"dark\"}");
    }

    @Test
    void changePasswordRequiresCorrectCurrentPassword() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.changePassword(1, "wrong", "Rotor-Kestrel-91x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** B4/SEC-021 on the change path: breached, email-derived, short all die. */
    @Test
    void changePasswordEnforcesThePolicy() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        assertThatThrownBy(() -> useCase.changePassword(1, "Demo123!", "Password123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commonly used or breached");
        assertThatThrownBy(() -> useCase.changePassword(1, "Demo123!", "xx-demo-password-xx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
        assertThatThrownBy(() -> useCase.changePassword(1, "Demo123!", "tiny-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12");
        verify(repo, never()).save(any(AppUser.class));
    }

    /** ws09: a successful change discharges the forced-change requirement. */
    @Test
    void changePasswordClearsTheMustChangeFlag() {
        AppUser user = demoUser();
        user.setMustChangePassword(true);
        int versionBefore = user.getTokenVersion();
        when(repo.getById(1)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.changePassword(1, "Demo123!", "Rotor-Kestrel-91x");

        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(versionBefore + 1);
        verify(refreshTokens).revokeAllForUser(eq(1), any(Instant.class));
    }

    /** B4: long passphrases are first-class — 64+ chars sail through. */
    @Test
    void changePasswordAcceptsALongPassphrase() {
        AppUser user = demoUser();
        when(repo.getById(1)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        String passphrase = "correct horse battery staple rides again over the misty mountain pass";
        assertThat(passphrase.length()).isGreaterThanOrEqualTo(64);

        useCase.changePassword(1, "Demo123!", passphrase);

        assertThat(encoder.matches(passphrase, user.getPasswordHash())).isTrue();
    }

    @Test
    void changePasswordStoresNewHashBumpsTokenVersionAndRevokesRefreshTokens() {
        AppUser user = demoUser();
        when(repo.getById(1)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.changePassword(1, "Demo123!", "Rotor-Kestrel-91x");

        verify(repo, times(2)).save(user); // hash save + the B5 seam's bump
        assertThat(encoder.matches("Rotor-Kestrel-91x", user.getPasswordHash())).isTrue();
        // Every outstanding access token dies (token_ver < TokenVersion) ...
        assertThat(user.getTokenVersion()).isEqualTo(1);
        // ... and so does every refresh session.
        verify(refreshTokens).revokeAllForUser(eq(1), any(Instant.class));

        // The bump goes through the shared UserSecurityService seam, which
        // audits itself (with the reason) before the password-change row.
        List<AuditEventSpec> recorded = allRecordedAudits();
        assertThat(recorded).extracting(AuditEventSpec::action)
                .containsExactly("TOKEN_VERSION_BUMPED", "PASSWORD_CHANGED");
        AuditEventSpec bump = recorded.get(0);
        assertThat(bump.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(bump.actorUserId()).isEqualTo(1);
        assertThat(String.valueOf(bump.newValues())).contains("password-change");
        AuditEventSpec changed = recorded.get(1);
        assertThat(changed.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(changed.actorUserId()).isEqualTo(1);
    }

    @Test
    void logoutIsAudited() {
        when(repo.getById(1)).thenReturn(Optional.of(demoUser()));

        useCase.logout(1, UUID.randomUUID().toString(), Instant.now().plusSeconds(60));

        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("LOGOUT");
        assertThat(recorded.actorUserId()).isEqualTo(1);
        assertThat(recorded.actorEmail()).isEqualTo("demo@modocument.local");
    }

    /** The single spec the use case handed to the audit seam. */
    private AuditEventSpec recordedAudit() {
        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    /** Every spec handed to the audit seam, in order. */
    private List<AuditEventSpec> allRecordedAudits() {
        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }
}
