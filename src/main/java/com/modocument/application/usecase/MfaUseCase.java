package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RecoveryCodeRepository;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.RecoveryCode;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import com.modocument.infrastructure.security.MfaSecretCipher;
import com.modocument.infrastructure.ratelimit.MfaRateLimiter;
import com.modocument.infrastructure.security.totp.Base32;
import com.modocument.infrastructure.security.totp.Totp;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * TOTP second factor (SEC-014/022, gate B3): enrolment, activation, disable
 * and the challenge→session conversion. Secrets are AES-GCM-encrypted at
 * rest ({@link MfaSecretCipher}); recovery codes are shown once and stored
 * only as password-encoder hashes; accepted TOTP steps are remembered
 * (LastUsedCounter) so a code can never be replayed.
 */
@Service
public class MfaUseCase {

    private static final String OTPAUTH_ISSUER = "MoDocument";
    private static final int SECRET_BYTES = 20; // 160-bit, RFC 4226's recommendation
    private static final int RECOVERY_CODE_COUNT = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Crockford-ish alphabet: no 0/O/1/I lookalikes in hand-typed codes. */
    private static final char[] RECOVERY_ALPHABET =
            "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();

    private final UserMfaRepository userMfa;
    private final RecoveryCodeRepository recoveryCodes;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MfaSecretCipher cipher;
    private final MfaChallengeTokens challengeTokens;
    private final AuthUseCase auth;
    private final AuditRecorder audit;
    private final MfaRateLimiter rateLimiter;
    private final TransactionTemplate transactions;

    public MfaUseCase(UserMfaRepository userMfa, RecoveryCodeRepository recoveryCodes,
                      AppUserRepository users, PasswordEncoder passwordEncoder,
                      MfaSecretCipher cipher, MfaChallengeTokens challengeTokens,
                      AuthUseCase auth, AuditRecorder audit, MfaRateLimiter rateLimiter,
                      PlatformTransactionManager transactionManager) {
        this.userMfa = userMfa;
        this.recoveryCodes = recoveryCodes;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.challengeTokens = challengeTokens;
        this.auth = auth;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public MfaStatus status(int userId) {
        return userMfa.getByUserId(userId)
                .map(row -> new MfaStatus(true, row.isEnabled()))
                .orElse(new MfaStatus(false, false));
    }

    /**
     * Starts (or restarts) enrolment: a fresh secret stored DISABLED
     * (EnabledAt null) plus ten fresh recovery codes. The Base32 secret, the
     * otpauth:// URI and the plaintext codes leave the server exactly here,
     * exactly once. Re-running before activation rotates everything; once
     * MFA is active, enrolment is refused — disable first (with a code).
     */
    @Transactional
    public Enrollment enroll(int userId) {
        AppUser user = getUser(userId);
        UserMfa existing = userMfa.getByUserId(userId).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw new IllegalStateException(
                    "MFA is already enabled. Disable it before re-enrolling.");
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        String base32Secret = Base32.encode(secret);

        UserMfa row = existing == null ? new UserMfa() : existing;
        row.setUserId(userId);
        row.setSecretCiphertext(cipher.encrypt(base32Secret));
        row.setEnabledAt(null);
        row.setLastUsedCounter(null);
        row.setCreatedAt(Instant.now());
        userMfa.save(row);

        recoveryCodes.deleteByUserId(userId);
        List<String> plaintextCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = randomRecoveryCode();
            plaintextCodes.add(code);
            // The hash covers the normalized (hyphen-free) form, matching
            // what consumeRecoveryCode compares against.
            recoveryCodes.save(new RecoveryCode(userId,
                    passwordEncoder.encode(code.replace("-", ""))));
        }
        audit.record(AuditEventSpec.builder("MFA_ENROLLED", AuditOutcome.SUCCESS)
                .actorUserId(userId)
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(userId))
                .build());
        return new Enrollment(base32Secret, otpauthUri(user.getEmail(), base32Secret),
                List.copyOf(plaintextCodes));
    }

    /**
     * Proves possession: the first valid TOTP code flips the enrolment to
     * ENABLED. LastUsedCounter deliberately stays null here so the very same
     * code may immediately complete the pending login via
     * {@code /api/auth/mfa/verify} (activate+verify are one logical step for
     * the user); the first verify records its counter and replay protection
     * is in force from then on.
     */
    @Transactional
    public void activate(int userId, String code) {
        AppUser user = getUser(userId);
        UserMfa row = userMfa.getByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("MFA enrolment has not been started."));
        if (row.isEnabled()) {
            throw new IllegalStateException("MFA is already enabled.");
        }
        long matched = verifyTotp(row, code);
        if (matched < 0) {
            throw new AuthUseCase.BadCredentialsException("Invalid verification code.");
        }
        row.setEnabledAt(Instant.now());
        userMfa.save(row);
        audit.record(AuditEventSpec.builder("MFA_ENABLED", AuditOutcome.SUCCESS)
                .actorUserId(userId)
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(userId))
                .build());
    }

    /**
     * Disables MFA after a fresh proof — a current TOTP code or an unused
     * recovery code. Deletes the enrolment and every recovery code;
     * re-enabling means a full re-enrolment (which is also the documented
     * way to regenerate recovery codes: disable + enroll).
     */
    @Transactional
    public void disable(int userId, String code) {
        AppUser user = getUser(userId);
        UserMfa row = userMfa.getByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("MFA is not enabled."));
        if (!verifySecondFactor(user, row, code)) {
            throw new AuthUseCase.BadCredentialsException("Invalid verification code.");
        }
        userMfa.deleteByUserId(userId);
        recoveryCodes.deleteByUserId(userId);
        audit.record(AuditEventSpec.builder("MFA_DISABLED", AuditOutcome.SUCCESS)
                .actorUserId(userId)
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(userId))
                .build());
    }

    /**
     * The second phase of login (gate B3): a live challenge token plus a
     * valid TOTP or recovery code buys the normal access+refresh pair
     * through the same issuance path as a password-only login —
     * LOGIN_SUCCESS is recorded here for MFA users. Every failure is the
     * same generic 401.
     *
     * <p>Not one {@code @Transactional} method on purpose: the caller is
     * anonymous until this succeeds, so no tenant is bound when the request
     * arrives, and the tenant is stamped on a transaction's connection when
     * the transaction BEGINS (TenantAwareJpaTransactionManager). The user is
     * resolved first (AppUsers is outside RLS), then every write — the TOTP
     * counter, the refresh family, LOGIN_SUCCESS — runs in a transaction
     * opened inside the user's own bank, so the PostgreSQL RLS WITH CHECK
     * accepts them. (One transaction opened before the bank was known made
     * every MFA sign-in fail at commit under the production DB role.)
     */
    public AuthUseCase.AuthSession verify(String challenge, String code,
                                          String ip, String userAgent) {
        Jwt parsed;
        try {
            parsed = challengeTokens.parse(challenge == null ? "" : challenge);
        } catch (JwtException invalid) {
            throw new AuthUseCase.BadCredentialsException("Invalid or expired MFA challenge.");
        }
        int userId;
        try {
            userId = Integer.parseInt(parsed.getSubject());
        } catch (NumberFormatException e) {
            throw new AuthUseCase.BadCredentialsException("Invalid or expired MFA challenge.");
        }
        // Per-user throttle AFTER the (cheap) signature check named the
        // subject — a forged challenge can't poison someone else's budget —
        // and BEFORE any code is compared (the per-IP gate already ran in
        // the controller).
        rateLimiter.checkUser(String.valueOf(userId));
        AppUser user = users.getById(userId)
                .orElseThrow(() -> new AuthUseCase.BadCredentialsException(
                        "Invalid or expired MFA challenge."));
        // A password change between challenge and verify bumps TokenVersion:
        // the stale challenge dies with the stale access tokens.
        Object versionClaim = parsed.getClaims().get(JwtService.CLAIM_TOKEN_VERSION);
        int challengeVersion = versionClaim instanceof Number number ? number.intValue() : -1;
        if (challengeVersion != user.getTokenVersion()) {
            throw new AuthUseCase.BadCredentialsException("Invalid or expired MFA challenge.");
        }
        return inUsersBank(user, () -> verifySecondFactorAndOpenSession(user, code, ip, userAgent));
    }

    private AuthUseCase.AuthSession verifySecondFactorAndOpenSession(AppUser user, String code,
                                                                     String ip, String userAgent) {
        int userId = user.getAppUserId();
        UserMfa row = userMfa.getByUserId(userId)
                .orElseThrow(() -> new AuthUseCase.BadCredentialsException(
                        "Invalid verification code."));
        if (!row.isEnabled()) {
            // Enrolment was required but activation hasn't happened yet.
            throw new AuthUseCase.BadCredentialsException("Invalid verification code.");
        }
        if (!verifySecondFactor(user, row, code)) {
            audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                    .actorUserId(user.getAppUserId())
                    .actorEmail(user.getEmail())
                    .actorRole(user.getRole() == null ? null : user.getRole().name())
                    .bankId(user.getBankId())
                    .build());
            throw new AuthUseCase.BadCredentialsException("Invalid verification code.");
        }
        return auth.completeMfaLogin(user, ip, userAgent);
    }

    /** Runs {@code work} in a new transaction that begins inside the user's bank. */
    private <T> T inUsersBank(AppUser user, Supplier<T> work) {
        try {
            return TenantContext.runAs(user.getBankId(),
                    () -> transactions.execute(status -> work.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A 6-digit string is tried as TOTP (with the replay check and
     * LastUsedCounter advance); anything else as a recovery code (burned on
     * success + RECOVERY_CODE_USED).
     */
    private boolean verifySecondFactor(AppUser user, UserMfa row, String code) {
        String presented = code == null ? "" : code.trim();
        if (presented.matches("\\d{" + Totp.DIGITS + "}")) {
            long matched = verifyTotp(row, presented);
            if (matched < 0) {
                return false;
            }
            row.setLastUsedCounter(matched);
            userMfa.save(row);
            return true;
        }
        return consumeRecoveryCode(user, presented);
    }

    /**
     * @return the matched RFC 6238 step, or -1 (wrong code, or a replay of a
     *     step at/before LastUsedCounter)
     */
    private long verifyTotp(UserMfa row, String code) {
        byte[] secret = Base32.decode(cipher.decrypt(row.getSecretCiphertext()));
        long matched = Totp.verify(secret, code, Instant.now().getEpochSecond());
        if (matched < 0) {
            return -1;
        }
        Long lastUsed = row.getLastUsedCounter();
        if (lastUsed != null && matched <= lastUsed) {
            return -1; // replay (SEC-022): same-or-older step never verifies twice
        }
        return matched;
    }

    private boolean consumeRecoveryCode(AppUser user, String presented) {
        // Hyphens/spacing are presentation; the hash covers the bare code.
        String normalized = presented.replace("-", "").replace(" ", "")
                .toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        for (RecoveryCode candidate : recoveryCodes.getUnusedByUserId(user.getAppUserId())) {
            if (passwordEncoder.matches(normalized, candidate.getCodeHash())) {
                candidate.setUsedAt(Instant.now());
                recoveryCodes.save(candidate);
                audit.record(AuditEventSpec.builder("RECOVERY_CODE_USED", AuditOutcome.SUCCESS)
                        .actorUserId(user.getAppUserId())
                        .actorEmail(user.getEmail())
                        .bankId(user.getBankId())
                        .entity("RecoveryCode", String.valueOf(candidate.getRecoveryCodeId()))
                        .build());
                return true;
            }
        }
        return false;
    }

    private static String otpauthUri(String email, String base32Secret) {
        String label = URLEncoder.encode(OTPAUTH_ISSUER + ":" + email, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + OTPAUTH_ISSUER
                + "&algorithm=SHA1&digits=" + Totp.DIGITS
                + "&period=" + Totp.STEP_SECONDS;
    }

    /** XXXXX-XXXXX, ~48 bits from a lookalike-free alphabet. */
    private static String randomRecoveryCode() {
        StringBuilder code = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return code.toString();
    }

    private AppUser getUser(int userId) {
        return users.getById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found."));
    }

    /** Enrolment payload — the one and only plaintext hand-over. */
    public record Enrollment(String secret, String otpauthUri, List<String> recoveryCodes) {
    }

    public record MfaStatus(boolean enrolled, boolean enabled) {
    }
}
