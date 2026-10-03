package com.modocument.application.usecase;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Login, token refresh/rotation, logout and profile management for
 * application users. Public self-registration is gone — users are created by
 * an administrator (see {@link #createUser}; the bootstrap admin comes from
 * BootstrapAdminRunner).
 */
@Service
public class AuthUseCase {

    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Refresh-token family lifetime; rotation never extends past it. */
    private static final long REFRESH_FAMILY_DAYS = 7;

    /**
     * Fixed hash matched against when the email doesn't exist, so an unknown
     * email costs the same BCrypt work as a wrong password and the response
     * time can't be used to enumerate accounts.
     */
    private static final String DUMMY_HASH =
            new BCryptPasswordEncoder().encode("timing-equalizer-dummy-password");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokens;
    private final RevokedTokenRepository revokedTokens;

    public AuthUseCase(AppUserRepository users, PasswordEncoder passwordEncoder,
                       JwtService jwtService, RefreshTokenRepository refreshTokens,
                       RevokedTokenRepository revokedTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.revokedTokens = revokedTokens;
    }

    /**
     * Creates a user inside a bank. Package-visible on purpose: no public
     * endpoint reaches it anymore — the bootstrap admin runner seeds the first
     * ADMIN, and the admin user-management endpoint will call it.
     *
     * <p>TODO ws09: expose through the admin user-management endpoint
     * (ADMIN-only), with the caller's bank enforced.
     */
    UserDto createUser(String email, String password, String displayName, int bankId, Role role) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("A valid email is required.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
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
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        user.setUpdatedDate(LocalDateTime.now());
        return UserDto.from(users.save(user));
    }

    /**
     * Verifies credentials, stamps LastLoginAt and opens a session: a short
     * access token (response body) plus a new refresh-token family (cookie).
     */
    public AuthSession login(String email, String password, String ip, String userAgent) {
        AppUser user = users.getByEmail(email == null ? "" : email.trim()).orElse(null);
        if (user == null) {
            // Same BCrypt cost and same message as a wrong password, so the
            // timing/response reveals nothing about which emails exist.
            passwordEncoder.matches(password == null ? "" : password, DUMMY_HASH);
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password.");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user = users.save(user);
        Instant familyExpiry = Instant.now().plus(REFRESH_FAMILY_DAYS, ChronoUnit.DAYS);
        IssuedRefreshToken refresh =
                issueRefreshToken(user, UUID.randomUUID(), familyExpiry, ip, userAgent);
        return new AuthSession(
                new AuthResponse(jwtService.issueToken(user), UserDto.from(user)), refresh.value());
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
            throw new BadCredentialsException("Refresh token has been revoked.");
        }
        if (!now.isBefore(current.getExpiresAt())) {
            throw new BadCredentialsException("Refresh token has expired.");
        }
        AppUser user = users.getById(current.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Refresh token has been revoked."));
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
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "New password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedDate(LocalDateTime.now());
        users.save(user);
        refreshTokens.revokeAllForUser(userId, Instant.now());
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

    /** A login/refresh outcome: the JSON body plus the raw cookie value. */
    public record AuthSession(AuthResponse response, String refreshToken) {
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
