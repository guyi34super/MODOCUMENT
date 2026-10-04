package com.modocument.api.controller;

import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.ChangePasswordRequest;
import com.modocument.domain.model.Permission;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.UpdateProfileRequest;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import com.modocument.infrastructure.security.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * JWT authentication. Login is open; it answers with a short-lived access
 * token (JSON) and a rotating refresh token in an httpOnly cookie scoped to
 * /api/auth/refresh. Refresh is open too (the cookie is its credential;
 * SameSite=Strict keeps cross-site callers out, CSRF protection for a
 * cookie-only endpoint). Logout revokes the current access token's jti and
 * every refresh token of the user. Public registration is gone — users are
 * admin-created.
 */
@RestController
public class AuthController {

    /** Refresh cookie: httpOnly + Secure + SameSite=Strict, path-scoped. */
    static final String REFRESH_COOKIE = "modoc_refresh";
    static final String REFRESH_PATH = "/api/auth/refresh";
    private static final Duration REFRESH_COOKIE_AGE = Duration.ofDays(7);

    private final AuthUseCase auth;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthUseCase auth, AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {
        // Throttled per IP and per email to blunt password guessing (F-004).
        rateLimiter.check(clientIp(httpRequest), request.getEmail());
        AuthUseCase.LoginResult result = auth.login(request.getEmail(), request.getPassword(),
                clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        if (result instanceof AuthUseCase.MfaChallenge challenge) {
            // Password OK but a second factor is owed (B3): no tokens, no
            // refresh cookie — only the short-lived challenge, which solely
            // POST /api/auth/mfa/verify (plus the enrolment endpoints when
            // enrollmentRequired) accepts.
            return ResponseEntity.ok(Map.of(
                    "mfaRequired", true,
                    "enrollmentRequired", challenge.enrollmentRequired(),
                    "challenge", challenge.challenge()));
        }
        AuthUseCase.AuthSession session = (AuthUseCase.AuthSession) result;
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.response());
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        // Same throttle as login: this endpoint is anonymous and mints tokens.
        rateLimiter.check(clientIp(httpRequest), null);
        AuthUseCase.AuthSession session = auth.refresh(refreshToken,
                clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.response());
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal Jwt jwt) {
        auth.logout(userId(jwt), jwt.getId(), jwt.getExpiresAt());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    /**
     * Client address for rate-limit keys and session rows: the forwarded-aware
     * remote address ({@link ClientIps} — the ForwardedHeaderFilter already
     * applied the trusted proxy's X-Forwarded-For rewrite, so no header is
     * parsed here).
     */
    private static String clientIp(HttpServletRequest request) {
        return ClientIps.clientIp(request);
    }

    /** Package-visible: MfaController sets the same cookie after verify. */
    static ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_PATH)
                .maxAge(REFRESH_COOKIE_AGE)
                .build();
    }

    private static ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(auth.me(userId(jwt)));
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @PutMapping("/api/auth/profile")
    public ResponseEntity<?> updateProfile(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                auth.updateProfile(userId(jwt), request.getDisplayName(), request.getSettings()));
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @PutMapping("/api/auth/password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        // Ends every session (TokenVersion bump + refresh revocation inside
        // the use case): the user signs in again with the new password.
        auth.changePassword(userId(jwt), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    private static int userId(Jwt jwt) {
        return Integer.parseInt(jwt.getSubject());
    }

    @ExceptionHandler(AuthUseCase.BadCredentialsException.class)
    public ResponseEntity<?> unauthorized(AuthUseCase.BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
