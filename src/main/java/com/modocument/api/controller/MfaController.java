package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.MfaCodeRequest;
import com.modocument.application.dto.MfaVerifyRequest;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.application.usecase.MfaUseCase;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.ratelimit.MfaRateLimiter;
import com.modocument.infrastructure.security.ClientIps;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * TOTP second factor (SEC-014/022, gate B3). Enrolment/activation/disable
 * are self-service (PROFILE_SELF) on the authenticated account; verify is
 * the public second phase of login — it accepts ONLY an MFA challenge token
 * (plus a TOTP or recovery code) and answers exactly like a password-only
 * login: access token in the body, rotating refresh cookie.
 *
 * <p>Challenge tokens reach enroll/activate too — that is how an ADMIN who
 * is forced into enrolment (require-for-admin) bootstraps — but only while
 * MFA is not yet active: {@code requireEnrollmentStillOpen} refuses a
 * challenge token the moment EnabledAt is set, and the
 * {@code MfaChallengeScopeValidator} already rejects challenge tokens on
 * every other route.
 */
@RestController
public class MfaController {

    private final MfaUseCase mfa;
    private final MfaRateLimiter rateLimiter;

    public MfaController(MfaUseCase mfa, MfaRateLimiter rateLimiter) {
        this.mfa = mfa;
        this.rateLimiter = rateLimiter;
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @GetMapping("/api/auth/mfa")
    public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(mfa.status(userId(jwt)));
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @PostMapping("/api/auth/mfa/enroll")
    public ResponseEntity<?> enroll(@AuthenticationPrincipal Jwt jwt) {
        requireEnrollmentStillOpen(jwt);
        return ResponseEntity.ok(mfa.enroll(userId(jwt)));
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @PostMapping("/api/auth/mfa/activate")
    public ResponseEntity<?> activate(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody MfaCodeRequest request) {
        requireEnrollmentStillOpen(jwt);
        mfa.activate(userId(jwt), request.getCode());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(Permission.PROFILE_SELF)
    @DeleteMapping("/api/auth/mfa")
    public ResponseEntity<?> disable(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody MfaCodeRequest request) {
        mfa.disable(userId(jwt), request.getCode());
        return ResponseEntity.noContent().build();
    }

    /** PUBLIC (challenge-token-driven); throttled per IP here, per user inside. */
    @PostMapping("/api/auth/mfa/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody MfaVerifyRequest request,
                                    HttpServletRequest httpRequest) {
        rateLimiter.checkIp(clientIp(httpRequest));
        AuthUseCase.AuthSession session = mfa.verify(request.getChallenge(), request.getCode(),
                clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        AuthController.refreshCookie(session.refreshToken()).toString())
                .body(session.response());
    }

    /**
     * A challenge token on enroll/activate is the forced-enrolment path
     * (B3) and stays valid only while there is still something to enrol:
     * once MFA is active these two handlers demand a REAL access token,
     * i.e. a completed two-factor login.
     */
    private void requireEnrollmentStillOpen(Jwt jwt) {
        boolean isChallenge = Boolean.TRUE.equals(
                jwt.getClaim(MfaChallengeTokens.CLAIM_MFA_CHALLENGE));
        if (isChallenge && mfa.status(userId(jwt)).enabled()) {
            throw new AuthUseCase.BadCredentialsException(
                    "MFA is already enabled; complete the two-factor login first.");
        }
    }

    /** Same proxy-aware client address rule as AuthController ({@link ClientIps}). */
    private static String clientIp(HttpServletRequest request) {
        return ClientIps.clientIp(request);
    }

    private static int userId(Jwt jwt) {
        return Integer.parseInt(jwt.getSubject());
    }

    @ExceptionHandler(AuthUseCase.BadCredentialsException.class)
    public ResponseEntity<?> unauthorized(AuthUseCase.BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
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
