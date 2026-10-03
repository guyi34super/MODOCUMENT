package com.modocument.application.usecase;

import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUseCaseTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final RevokedTokenRepository revokedTokens = mock(RevokedTokenRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final AuthUseCase useCase =
            new AuthUseCase(repo, encoder, jwtService, refreshTokens, revokedTokens, audit);

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

        UserDto created = useCase.createUser("new@example.com", "Password1!", "New User", 1, Role.ADMIN);

        assertThat(created.email()).isEqualTo("new@example.com");
        assertThat(created.displayName()).isEqualTo("New User");
        assertThat(created.role()).isEqualTo("ADMIN");
        assertThat(created.bankId()).isEqualTo(1);
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        when(repo.existsByEmail("demo@modocument.local")).thenReturn(true);

        assertThatThrownBy(() -> useCase.createUser(
                "demo@modocument.local", "Password1!", "X", 1, Role.READ_ONLY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createUserRejectsShortPassword() {
        assertThatThrownBy(() -> useCase.createUser("a@b.com", "short", "X", 1, Role.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- login

    @Test
    void loginReturnsTokenRefreshValueAndStampsLastLogin() {
        AppUser user = demoUser();
        when(repo.getByEmail("demo@modocument.local")).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueToken(any(AppUser.class))).thenReturn("jwt-token");

        AuthUseCase.AuthSession session =
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
     * BCrypt comparison so its response time matches a wrong password.
     */
    @Test
    void loginForUnknownEmailStillRunsOneBcryptMatch() {
        PasswordEncoder spyEncoder = mock(PasswordEncoder.class);
        AuthUseCase timed = new AuthUseCase(repo, spyEncoder, jwtService, refreshTokens,
                revokedTokens, audit);
        when(repo.getByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> timed.login("nobody@example.com", "whatever", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid email or password.");

        // Exactly one dummy match ran, against a fixed (non-null) hash.
        verify(spyEncoder, times(1)).matches(eq("whatever"), anyString());
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

        assertThatThrownBy(() -> useCase.changePassword(1, "wrong", "NewPassword1!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changePasswordStoresNewHashBumpsTokenVersionAndRevokesRefreshTokens() {
        AppUser user = demoUser();
        when(repo.getById(1)).thenReturn(Optional.of(user));
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.changePassword(1, "Demo123!", "NewPassword1!");

        verify(repo).save(user);
        assertThat(encoder.matches("NewPassword1!", user.getPasswordHash())).isTrue();
        // Every outstanding access token dies (token_ver < TokenVersion) ...
        assertThat(user.getTokenVersion()).isEqualTo(1);
        // ... and so does every refresh session.
        verify(refreshTokens).revokeAllForUser(eq(1), any(Instant.class));

        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("PASSWORD_CHANGED");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(recorded.actorUserId()).isEqualTo(1);
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
}
