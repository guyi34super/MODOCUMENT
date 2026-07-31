package com.modocument.api.controller;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.ChangePasswordRequest;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.RegisterRequest;
import com.modocument.application.dto.UpdateProfileRequest;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * JWT authentication: register/login are open, the rest require a Bearer
 * token. Logout is client-side (the frontend discards the token).
 */
@RestController
public class AuthController {

    private final AuthUseCase auth;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthUseCase auth, AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request,
                                      HttpServletRequest httpRequest) {
        // Throttled per IP and per email so registration can't be used to
        // enumerate accounts or spam signups (F-004/F-005).
        rateLimiter.check(clientIp(httpRequest), request.getEmail());
        AuthResponse response = auth.register(
                request.getEmail(), request.getPassword(), request.getDisplayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {
        // Throttled per IP and per email to blunt password guessing (F-004).
        rateLimiter.check(clientIp(httpRequest), request.getEmail());
        return ResponseEntity.ok(auth.login(request.getEmail(), request.getPassword()));
    }

    /**
     * Best-effort client address. Behind the app's reverse proxy the socket
     * address is the proxy, so the first X-Forwarded-For hop is preferred; this
     * header is only trustworthy because the app always sits behind a proxy
     * that sets it — a direct-to-app deployment would need to stop honoring it.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(auth.me(userId(jwt)));
    }

    @PutMapping("/api/auth/profile")
    public ResponseEntity<?> updateProfile(@AuthenticationPrincipal Jwt jwt,
                                           @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                auth.updateProfile(userId(jwt), request.getDisplayName(), request.getSettings()));
    }

    @PutMapping("/api/auth/password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal Jwt jwt,
                                            @RequestBody ChangePasswordRequest request) {
        auth.changePassword(userId(jwt), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
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
