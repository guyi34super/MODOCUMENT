package com.modocument.application.usecase;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.application.security.LoginLockoutPolicy;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Login, token refresh/rotation, logout and profile management for
 * application users. Public self-registration is gone — users are created by
 * an administrator (see {@link #createUser}; the bootstrap admin comes from
 * BootstrapAdminRunner).
 *
 * <p>Login is two-phase when MFA is in play (gate B3): a correct password
 * yields a short-lived challenge token instead of a session, and
 * {@link MfaUseCase#verify} converts it into tokens once the TOTP (or
 * recovery) code checks out. Wrong passwords feed the lockout counters
 * (gate B4, {@link LoginLockoutPolicy}); the lock check runs BEFORE the
 * password is verified and still burns a dummy hash so timing stays uniform.
 */
@Service
public class AuthUseCase {

    /** Refresh-token family lifetime; rotation never extends past it. */
    private static final long REFRESH_FAMILY_DAYS = 7;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginLockoutPolicy lockoutPolicy;
    private final UserMfaRepository userMfa;
    private final MfaChallengeTokens challengeTokens;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokens;
    private final RevokedTokenRepository revokedTokens;
    private final AuditRecorder audit;
    private final boolean requireMfaForAdmin;

    /**
     * Fixed hash matched against when the email doesn't exist or the account
     * is locked, so those paths cost the same Argon2id work as a wrong
     * password and the response time can't be used to enumerate accounts.
     * Encoded through the SAME delegating encoder as real passwords, so the
     * dummy match always burns the current default (Argon2id) cost.
     */
    private final String dummyHash;

    public AuthUseCase(AppUserRepository users, PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy, LoginLockoutPolicy lockoutPolicy,
                       UserMfaRepository userMfa, MfaChallengeTokens challengeTokens,
                       JwtService jwtService, RefreshTokenRepository refreshTokens,
                       RevokedTokenRepository revokedTokens, AuditRecorder audit,
                       @Value("${modocument.security.mfa.require-for-admin:true}")
                       boolean requireMfaForAdmin) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.lockoutPolicy = lockoutPolicy;
        this.userMfa = userMfa;
        this.challengeTokens = challengeTokens;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.revokedTokens = revokedTokens;
        // Identity events are recorded HERE because login is a custom use
        // case, not Spring's AuthenticationManager - there is no
        // AuthenticationEventPublisher to listen to.
        this.audit = audit;
        this.requireMfaForAdmin = requireMfaForAdmin;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-dummy-password");
    }

    /**
     * Creates a user inside a bank (ws09: called by UserAdminUseCase with
     * the calling Admin's own bank — there is no public registration; the
     * bootstrap admin runner seeds the very first ADMIN separately). The
     * password policy applies to the (temporary) password like to any other;
     * {@code mustChangePassword} is set for admin-issued temporary passwords
     * so the account is forced through a password change on first login,
     * and stays false for accounts whose credential an IdP owns.
     */
    public UserDto createUser(String email, String password, String displayName, int bankId,
                              Role role, boolean mustChangePassword) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("A valid email is required.");
        }
        passwordPolicy.validate(password, email);
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmail(normalized)) {
            throw new IllegalStateException("An account with this email already exists.");
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? normalized : displayName.trim());
        user.setBankId(bankId);
        user.setRole(role == null ? Role.READ_ONLY : role);
        user.setMustChangePassword(mustChangePassword);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        user.setUpdatedDate(LocalDateTime.now());
        return UserDto.from(users.save(user));
    }

    /**
     * Verifies credentials. With no second factor owed this stamps
     * LastLoginAt and opens a session: a short access token (response body)
     * plus a new refresh-token family (cookie). When MFA is enabled — or the
     * user is an ADMIN who still has to enrol (require-for-admin) — the
     * password alone buys only an {@link MfaChallenge}; no tokens are issued
     * and LOGIN_SUCCESS is not recorded until {@link MfaUseCase#verify}.
     */
    public LoginResult login(String email, String password, String ip, String userAgent) {
        String attempted = email == null ? "" : email.trim();
        AppUser user = users.getByEmail(attempted).orElse(null);
        if (user == null) {
            // Same Argon2id cost and same message as a wrong password, so the
            // timing/response reveals nothing about which emails exist. The
            // audit row records what actually happened (C7): the attempted
            // email with no user id - an unknown account.
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                    .actorEmail(attempted)
                    .build());
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (user.isDisabled()) {
            // Leaver (PRIV-017): the answer is indistinguishable from a wrong
            // password — same dummy-hash timing, same generic message — and
            // runs BEFORE the password check so a disabled account offers no
            // oracle. Only the audit row says what actually happened.
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                    .actorUserId(user.getAppUserId())
                    .actorEmail(user.getEmail())
                    .actorRole(user.getRole() == null ? null : user.getRole().name())
                    .bankId(user.getBankId())
                    .newValues(Map.of("reason", "account_disabled"))
                    .build());
            throw new BadCredentialsException("Invalid email or password.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (lockoutPolicy.isLocked(user, now)) {
            // Locked accounts answer BEFORE the password is verified — a
            // guesser gets no oracle while the lock stands — but the dummy
            // hash still burns so the timing matches the other 401s and the
            // response never says "locked" (no enumeration either way).
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                    .actorUserId(user.getAppUserId())
                    .actorEmail(user.getEmail())
                    .actorRole(user.getRole() == null ? null : user.getRole().name())
                    .bankId(user.getBankId())
                    .build());
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            // Known account, wrong password - distinguishable from the
            // unknown-email row by the present ActorUserId. Feeds the lockout
            // counters (B4): the attempt after the allowance locks.
            boolean lockedNow = lockoutPolicy.registerFailure(user, now);
            users.save(user);
            audit.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                    .actorUserId(user.getAppUserId())
                    .actorEmail(user.getEmail())
                    .actorRole(user.getRole() == null ? null : user.getRole().name())
                    .bankId(user.getBankId())
                    .build());
            if (lockedNow) {
                // The first lock moment (and each escalation) is its own
                // security signal; no values beyond the lock horizon.
                audit.record(AuditEventSpec.builder("ACCOUNT_LOCKED", AuditOutcome.DENIED)
                        .actorUserId(user.getAppUserId())
                        .actorEmail(user.getEmail())
                        .bankId(user.getBankId())
                        .entity("AppUser", String.valueOf(user.getAppUserId()))
                        .build());
            }
            throw new BadCredentialsException("Invalid email or password.");
        }
        lockoutPolicy.registerSuccess(user);
        user = upgradeHashIfNeeded(user, password);

        boolean mfaEnabled = userMfa.isEnabled(user.getAppUserId());
        boolean enrollmentRequired = !mfaEnabled && requireMfaForAdmin
                && user.getRole() == Role.ADMIN;
        if (mfaEnabled || enrollmentRequired) {
            // Password OK but a second factor is owed: persist the counter
            // reset, hand out ONLY the challenge. LastLoginAt/LOGIN_SUCCESS
            // wait for the verified second factor.
            users.save(user);
            return new MfaChallenge(challengeTokens.issue(user), enrollmentRequired);
        }
        return openSession(user, ip, userAgent);
    }

    /**
     * Completes a login after the second factor verified (called by
     * {@link MfaUseCase} only — the TOTP/recovery check has already passed).
     */
    AuthSession completeMfaLogin(AppUser user, String ip, String userAgent) {
        if (user.isDisabled()) {
            // Disabled between the password phase and the second factor —
            // the short challenge window must not outlive the leaver switch.
            throw new BadCredentialsException("Invalid email or password.");
        }
        return openSession(user, ip, userAgent);
    }

    /** Stamps LastLoginAt, issues the token pair and records LOGIN_SUCCESS. */
    private AuthSession openSession(AppUser user, String ip, String userAgent) {
        user.setLastLoginAt(LocalDateTime.now());
        user = users.save(user);
        Instant familyExpiry = Instant.now().plus(REFRESH_FAMILY_DAYS, ChronoUnit.DAYS);
        IssuedRefreshToken refresh =
                issueRefreshToken(user, UUID.randomUUID(), familyExpiry, ip, userAgent);
        AuthSession session = new AuthSession(
                new AuthResponse(jwtService.issueToken(user), UserDto.from(user)), refresh.value());
        audit.record(AuditEventSpec.builder("LOGIN_SUCCESS", AuditOutcome.SUCCESS)
                .actorUserId(user.getAppUserId())
                .actorEmail(user.getEmail())
                .actorRole(user.getRole() == null ? null : user.getRole().name())
                .bankId(user.getBankId())
                .sessionJti(refresh.jti())
                .entity("AppUser", String.valueOf(user.getAppUserId()))
                .build());
        return session;
    }

    /**
     * Upgrade-on-login (SEC-018): a password that just verified against a
     * legacy BCrypt hash is re-hashed with the current default (Argon2id).
     * The only moment the plaintext is available, so the only moment the
     * upgrade can happen. Audited without values — hashes never reach the
     * audit trail.
     */
    private AppUser upgradeHashIfNeeded(AppUser user, String password) {
        if (!passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            return user;
        }
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setUpdatedDate(LocalDateTime.now());
        AppUser saved = users.save(user);
        audit.record(AuditEventSpec.builder("PASSWORD_REHASHED", AuditOutcome.SUCCESS)
                .actorUserId(saved.getAppUserId())
                .actorEmail(saved.getEmail())
                .bankId(saved.getBankId())
                .entity("AppUser", String.valueOf(saved.getAppUserId()))
                .build());
        return saved;
    }

    /**
     * Opens a session for a user an external OIDC provider has ALREADY
     * authenticated (ws07 bridge pattern — the IdP is only the front door;
     * MoDocument issues its own access token + refresh family, identical to
     * {@link #login}'s issuance path). No credential check happens here on
     * purpose: the only caller is the OIDC login success handler, which runs
     * strictly after Spring Security validated the provider's ID token.
     */
    public AuthSession openOidcSession(AppUser user, String provider, String ip, String userAgent) {
        user.setLastLoginAt(LocalDateTime.now());
        user = users.save(user);
        Instant familyExpiry = Instant.now().plus(REFRESH_FAMILY_DAYS, ChronoUnit.DAYS);
        IssuedRefreshToken refresh =
                issueRefreshToken(user, UUID.randomUUID(), familyExpiry, ip, userAgent);
        AuthSession session = new AuthSession(
                new AuthResponse(jwtService.issueToken(user), UserDto.from(user)), refresh.value());
        audit.record(AuditEventSpec.builder("LOGIN_SUCCESS", AuditOutcome.SUCCESS)
                .actorUserId(user.getAppUserId())
                .actorEmail(user.getEmail())
                .actorRole(user.getRole() == null ? null : user.getRole().name())
                .bankId(user.getBankId())
                .sessionJti(refresh.jti())
                .entity("AppUser", String.valueOf(user.getAppUserId()))
                // The oidc marker distinguishes these rows from password logins.
                .newValues(Map.of("method", "oidc", "provider", provider))
                .build());
        return session;
    }

    /**
     * Rotates a refresh token: the presented value (matched by SHA-256 hash)
     * must be live; it is then marked revoked/replaced and a new token of the
     * same family is issued alongside a fresh access token. Presenting a
     * token that was already rotated or revoked is treated as theft — the
     * whole family is revoked and the caller gets a 401.
     */
    public AuthSession refresh(String refreshTokenValue, String ip, String userAgent) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new BadCredentialsException("Refresh token is missing.");
        }
        RefreshToken current = refreshTokens.getByTokenHash(Hashing.sha256(refreshTokenValue))
                .orElseThrow(() -> new BadCredentialsException("Refresh token is not recognized."));
        Instant now = Instant.now();
        if (current.getRevokedAt() != null || current.getReplacedByJti() != null) {
            // Reuse detected: someone presented a token that was already
            // rotated away. Kill the whole family — whichever side is the
            // thief, nobody keeps the session.
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            // Security signal (SEC-034): record the owner and the killed
            // family; DENIED writes in its own transaction, surviving the 401.
            AuditEventSpec.Builder reuse = AuditEventSpec
                    .builder("TOKEN_REFRESH_REUSE_DETECTED", AuditOutcome.DENIED)
                    .actorUserId(current.getUserId())
                    .entity("RefreshTokenFamily", current.getFamilyId().toString());
            users.getById(current.getUserId()).ifPresent(owner -> reuse
                    .actorEmail(owner.getEmail())
                    .bankId(owner.getBankId()));
            audit.record(reuse.build());
            throw new BadCredentialsException("Refresh token has been revoked.");
        }
        if (!now.isBefore(current.getExpiresAt())) {
            throw new BadCredentialsException("Refresh token has expired.");
        }
        AppUser user = users.getById(current.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Refresh token has been revoked."));
        if (user.isDisabled()) {
            // Disabling revokes every refresh token already; this covers a
            // flag set out-of-band and keeps the answer the generic 401.
            throw new BadCredentialsException("Refresh token has been revoked.");
        }
        // Rotate within the family: the expiry is inherited, so a family never
        // lives longer than REFRESH_FAMILY_DAYS from the original login.
        IssuedRefreshToken next = issueRefreshToken(
                user, current.getFamilyId(), current.getExpiresAt(), ip, userAgent);
        current.setRevokedAt(now);
        current.setReplacedByJti(next.jti());
        refreshTokens.save(current);
        return new AuthSession(
                new AuthResponse(jwtService.issueToken(user), UserDto.from(user)), next.value());
    }

    /**
     * Logout: the current access token's jti joins the denylist until its own
     * expiry, and every refresh token of the user is revoked. (The refresh
     * cookie is path-scoped to /api/auth/refresh so it never reaches this
     * endpoint — revoking all of the user's families is the strict superset
     * of revoking "the" one.)
     */
    public void logout(int userId, String jti, Instant tokenExpiresAt) {
        if (jti != null && tokenExpiresAt != null) {
            try {
                revokedTokens.add(UUID.fromString(jti), tokenExpiresAt);
            } catch (IllegalArgumentException notAUuid) {
                // A token without a well-formed jti can't be denylisted; the
                // refresh revocation below still ends the session.
            }
        }
        refreshTokens.revokeAllForUser(userId, Instant.now());
        // Actor/bank/role/session jti are enriched from the caller's JWT by
        // the recorder; the email lookup just completes the "who" (B1).
        AuditEventSpec.Builder logout = AuditEventSpec
                .builder("LOGOUT", AuditOutcome.SUCCESS)
                .actorUserId(userId);
        users.getById(userId).ifPresent(user -> logout.actorEmail(user.getEmail()));
        audit.record(logout.build());
    }

    public UserDto me(int userId) {
        return UserDto.from(getUser(userId));
    }

    public UserDto updateProfile(int userId, String displayName, String settings) {
        AppUser user = getUser(userId);
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName.trim());
        }
        if (settings != null) {
            user.setSettingsJson(settings);
        }
        user.setUpdatedDate(LocalDateTime.now());
        return UserDto.from(users.save(user));
    }

    /**
     * Changes the password and ends every session: TokenVersion is bumped
     * (the RevocationValidator rejects all outstanding access tokens,
     * including the one that made this call) and every refresh family is
     * revoked. Deliberately the simplest correct policy — the user signs in
     * again with the new password rather than this path minting a fresh pair.
     */
    public void changePassword(int userId, String currentPassword, String newPassword) {
        AppUser user = getUser(userId);
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        passwordPolicy.validate(newPassword, user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        // A changed password discharges the forced-change requirement set
        // when an admin issued the temporary password (ws09).
        user.setMustChangePassword(false);
        user.setUpdatedDate(LocalDateTime.now());
        users.save(user);
        refreshTokens.revokeAllForUser(userId, Instant.now());
        // No old/new values on purpose: hashes never reach the audit trail.
        audit.record(AuditEventSpec.builder("PASSWORD_CHANGED", AuditOutcome.SUCCESS)
                .actorUserId(userId)
                .actorEmail(user.getEmail())
                .bankId(user.getBankId())
                .entity("AppUser", String.valueOf(userId))
                .build());
    }

    private IssuedRefreshToken issueRefreshToken(AppUser user, UUID familyId, Instant expiresAt,
                                                 String ip, String userAgent) {
        byte[] secret = new byte[32]; // 256-bit opaque value; only its hash is stored
        RANDOM.nextBytes(secret);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        RefreshToken token = new RefreshToken();
        token.setJti(UUID.randomUUID());
        token.setUserId(user.getAppUserId());
        token.setFamilyId(familyId);
        token.setTokenHash(Hashing.sha256(value));
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(expiresAt);
        token.setIp(truncate(ip, 64));
        token.setUserAgent(truncate(userAgent, 400));
        refreshTokens.save(token);
        return new IssuedRefreshToken(token.getJti(), value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AppUser getUser(int userId) {
        return users.getById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found."));
    }

    /** What a login attempt produced: a full session, or an MFA challenge. */
    public sealed interface LoginResult permits AuthSession, MfaChallenge {
    }

    /** A login/refresh outcome: the JSON body plus the raw cookie value. */
    public record AuthSession(AuthResponse response, String refreshToken) implements LoginResult {
    }

    /**
     * Password accepted but a second factor is owed. {@code challenge} is
     * accepted only by POST /api/auth/mfa/verify (and, when
     * {@code enrollmentRequired}, by the enroll/activate endpoints).
     */
    public record MfaChallenge(String challenge, boolean enrollmentRequired)
            implements LoginResult {
    }

    private record IssuedRefreshToken(UUID jti, String value) {
    }

    /** Thrown when login credentials don't match; mapped to 401 by the controller. */
    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException(String message) {
            super(message);
        }
    }
}
