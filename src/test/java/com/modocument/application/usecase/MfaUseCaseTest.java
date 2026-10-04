package com.modocument.application.usecase;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RecoveryCodeRepository;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.RecoveryCode;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.ratelimit.MfaRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import com.modocument.infrastructure.security.MfaSecretCipher;
import com.modocument.infrastructure.security.totp.Base32;
import com.modocument.infrastructure.security.totp.Totp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaUseCaseTest {

    private static final String JWT_SECRET =
            "modocument-local-dev-secret-change-in-production-0123456789";

    private final InMemoryUserMfa userMfa = new InMemoryUserMfa();
    private final InMemoryRecoveryCodes recoveryCodes = new InMemoryRecoveryCodes();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // fast for tests
    private final MfaSecretCipher cipher = new MfaSecretCipher("", JWT_SECRET, true);
    private final MfaChallengeTokens challengeTokens = mock(MfaChallengeTokens.class);
    private final AuthUseCase auth = mock(AuthUseCase.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final MfaRateLimiter rateLimiter = new MfaRateLimiter(1000, 1000, 60_000, 1000);
    private final MfaUseCase useCase = new MfaUseCase(userMfa, recoveryCodes, users, encoder,
            cipher, challengeTokens, auth, audit, rateLimiter);

    private AppUser demoUser() {
        AppUser user = new AppUser(1, "demo@modocument.local", "irrelevant", "Demo User");
        user.setBankId(Bank.DEFAULT_BANK_ID);
        user.setRole(Role.READ_ONLY);
        user.setSettingsJson("{}");
        return user;
    }

    private static String currentCode(String base32Secret) {
        return Totp.codeAt(Base32.decode(base32Secret),
                Totp.counterAt(Instant.now().getEpochSecond()));
    }

    private Jwt challengeJwt(int tokenVersion) {
        return Jwt.withTokenValue("challenge")
                .header("alg", "HS256")
                .subject("1")
                .claim("token_ver", tokenVersion)
                .claim(MfaChallengeTokens.CLAIM_MFA_CHALLENGE, true)
                .build();
    }

    // ------------------------------------------------------------- enrolment

    @Test
    void enrollReturnsSecretUriAndTenRecoveryCodesStoredHashed() {
        when(users.getById(1)).thenReturn(Optional.of(demoUser()));

        MfaUseCase.Enrollment enrollment = useCase.enroll(1);

        assertThat(enrollment.secret()).isNotBlank();
        assertThat(enrollment.otpauthUri())
                .startsWith("otpauth://totp/MoDocument%3Ademo%40modocument.local?")
                .contains("secret=" + enrollment.secret())
                .contains("issuer=MoDocument")
                .contains("digits=6")
                .contains("period=30");
        assertThat(enrollment.recoveryCodes()).hasSize(10).allMatch(
                code -> code.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));

        // Stored DISABLED, secret encrypted (never the Base32 plaintext).
        UserMfa row = userMfa.getByUserId(1).orElseThrow();
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getSecretCiphertext()).isNotEqualTo(enrollment.secret());
        assertThat(cipher.decrypt(row.getSecretCiphertext())).isEqualTo(enrollment.secret());

        // Codes stored hashed only.
        List<RecoveryCode> stored = recoveryCodes.getUnusedByUserId(1);
        assertThat(stored).hasSize(10);
        String firstPlain = enrollment.recoveryCodes().get(0).replace("-", "");
        assertThat(stored).noneMatch(code -> code.getCodeHash().contains(firstPlain));
        assertThat(stored).anyMatch(code -> encoder.matches(firstPlain, code.getCodeHash()));
    }

    @Test
    void reEnrollBeforeActivationRotatesEverythingButEnabledRefuses() {
        when(users.getById(1)).thenReturn(Optional.of(demoUser()));
        MfaUseCase.Enrollment first = useCase.enroll(1);

        MfaUseCase.Enrollment second = useCase.enroll(1);
        assertThat(second.secret()).isNotEqualTo(first.secret());
        assertThat(recoveryCodes.getUnusedByUserId(1)).hasSize(10);

        useCase.activate(1, currentCode(second.secret()));
        assertThatThrownBy(() -> useCase.enroll(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already enabled");
    }

    @Test
    void activateNeedsAValidCodeThenAuditsMfaEnabled() {
        when(users.getById(1)).thenReturn(Optional.of(demoUser()));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);

        assertThatThrownBy(() -> useCase.activate(1, "000000"))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        assertThat(userMfa.getByUserId(1).orElseThrow().isEnabled()).isFalse();

        useCase.activate(1, currentCode(enrollment.secret()));

        assertThat(userMfa.getByUserId(1).orElseThrow().isEnabled()).isTrue();
        assertThat(recordedActions()).contains("MFA_ENABLED");
    }

    // ---------------------------------------------------------------- verify

    @Test
    void verifyConvertsChallengePlusTotpIntoASession() {
        AppUser user = demoUser();
        when(users.getById(1)).thenReturn(Optional.of(user));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));
        when(challengeTokens.parse("challenge")).thenReturn(challengeJwt(0));
        AuthUseCase.AuthSession session = new AuthUseCase.AuthSession(
                new AuthResponse("access", UserDto.from(user)), "refresh");
        when(auth.completeMfaLogin(eq(user), eq("10.0.0.1"), eq("JUnit"))).thenReturn(session);

        AuthUseCase.AuthSession result =
                useCase.verify("challenge", currentCode(enrollment.secret()), "10.0.0.1", "JUnit");

        assertThat(result).isSameAs(session);
        // Replay prevention: the accepted step is remembered.
        assertThat(userMfa.getByUserId(1).orElseThrow().getLastUsedCounter()).isNotNull();
    }

    /** SEC-022: the same code never buys two sessions. */
    @Test
    void verifyRejectsAReplayedCode() {
        AppUser user = demoUser();
        when(users.getById(1)).thenReturn(Optional.of(user));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));
        when(challengeTokens.parse("challenge")).thenReturn(challengeJwt(0));
        when(auth.completeMfaLogin(any(), any(), any())).thenReturn(
                new AuthUseCase.AuthSession(new AuthResponse("access", UserDto.from(user)), "r"));

        String code = currentCode(enrollment.secret());
        useCase.verify("challenge", code, null, null);

        assertThatThrownBy(() -> useCase.verify("challenge", code, null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid verification code.");
    }

    @Test
    void verifyAcceptsAnUnusedRecoveryCodeExactlyOnce() {
        AppUser user = demoUser();
        when(users.getById(1)).thenReturn(Optional.of(user));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));
        when(challengeTokens.parse("challenge")).thenReturn(challengeJwt(0));
        when(auth.completeMfaLogin(any(), any(), any())).thenReturn(
                new AuthUseCase.AuthSession(new AuthResponse("access", UserDto.from(user)), "r"));

        String recovery = enrollment.recoveryCodes().get(3);
        useCase.verify("challenge", recovery, null, null);

        assertThat(recordedActions()).contains("RECOVERY_CODE_USED");
        assertThat(recoveryCodes.getUnusedByUserId(1)).hasSize(9);
        // Burned: the same code never works again.
        assertThatThrownBy(() -> useCase.verify("challenge", recovery, null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
    }

    @Test
    void verifyRejectsAStaleChallengeAfterAPasswordChange() {
        AppUser user = demoUser();
        user.setTokenVersion(1); // bumped since the challenge was issued
        when(users.getById(1)).thenReturn(Optional.of(user));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));
        when(challengeTokens.parse("challenge")).thenReturn(challengeJwt(0));

        assertThatThrownBy(() ->
                useCase.verify("challenge", currentCode(enrollment.secret()), null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid or expired MFA challenge.");
        verify(auth, never()).completeMfaLogin(any(), any(), any());
    }

    @Test
    void verifyRejectsAnInvalidChallengeToken() {
        when(challengeTokens.parse(anyString())).thenThrow(new JwtException("bad"));

        assertThatThrownBy(() -> useCase.verify("garbage", "123456", null, null))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessage("Invalid or expired MFA challenge.");
    }

    /** 5/min per user: the 6th guess inside the window answers 429, not 401. */
    @Test
    void verifyIsRateLimitedPerUser() {
        AppUser user = demoUser();
        when(users.getById(1)).thenReturn(Optional.of(user));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));
        MfaUseCase throttled = new MfaUseCase(userMfa, recoveryCodes, users, encoder, cipher,
                challengeTokens, auth, audit, new MfaRateLimiter(1000, 5, 60_000, 1000));
        when(challengeTokens.parse("challenge")).thenReturn(challengeJwt(0));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> throttled.verify("challenge", "000000", null, null))
                    .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        }
        assertThatThrownBy(() -> throttled.verify("challenge", "000000", null, null))
                .isInstanceOf(TooManyRequestsException.class);
    }

    // --------------------------------------------------------------- disable

    @Test
    void disableNeedsAValidSecondFactorAndDropsEverything() {
        when(users.getById(1)).thenReturn(Optional.of(demoUser()));
        MfaUseCase.Enrollment enrollment = useCase.enroll(1);
        useCase.activate(1, currentCode(enrollment.secret()));

        assertThatThrownBy(() -> useCase.disable(1, "000000"))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class);
        assertThat(userMfa.getByUserId(1)).isPresent();

        useCase.disable(1, enrollment.recoveryCodes().get(0));

        assertThat(userMfa.getByUserId(1)).isEmpty();
        assertThat(recoveryCodes.getUnusedByUserId(1)).isEmpty();
        assertThat(recordedActions()).contains("MFA_DISABLED");
        assertThat(useCase.status(1)).isEqualTo(new MfaUseCase.MfaStatus(false, false));
    }

    private List<String> recordedActions() {
        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().map(AuditEventSpec::action).toList();
    }

    // ------------------------------------------------------------- test fakes

    private static final class InMemoryUserMfa implements UserMfaRepository {
        private final Map<Integer, UserMfa> rows = new HashMap<>();

        @Override
        public Optional<UserMfa> getByUserId(int userId) {
            return Optional.ofNullable(rows.get(userId));
        }

        @Override
        public boolean isEnabled(int userId) {
            UserMfa row = rows.get(userId);
            return row != null && row.isEnabled();
        }

        @Override
        public UserMfa save(UserMfa entity) {
            rows.put(entity.getUserId(), entity);
            return entity;
        }

        @Override
        public void deleteByUserId(int userId) {
            rows.remove(userId);
        }
    }

    private static final class InMemoryRecoveryCodes implements RecoveryCodeRepository {
        private final List<RecoveryCode> rows = new ArrayList<>();
        private int nextId = 1;

        @Override
        public List<RecoveryCode> getUnusedByUserId(int userId) {
            return rows.stream()
                    .filter(code -> code.getUserId() == userId && code.getUsedAt() == null)
                    .toList();
        }

        @Override
        public RecoveryCode save(RecoveryCode entity) {
            if (entity.getRecoveryCodeId() == 0) {
                entity.setRecoveryCodeId(nextId++);
                rows.add(entity);
            }
            return entity;
        }

        @Override
        public void deleteByUserId(int userId) {
            rows.removeIf(code -> code.getUserId() == userId);
        }
    }
}
