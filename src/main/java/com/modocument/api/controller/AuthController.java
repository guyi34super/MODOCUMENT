package com.modocument.api.controller;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.ChangePasswordRequest;
import com.modocument.application.dto.LoginRequest;
import com.modocument.application.dto.RegisterRequest;
import com.modocument.application.dto.UpdateProfileRequest;
import com.modocument.application.usecase.AuthUseCase;
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

    public AuthController(AuthUseCase auth) {
        this.auth = auth;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        AuthResponse response = auth.register(
                request.getEmail(), request.getPassword(), request.getDisplayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(auth.login(request.getEmail(), request.getPassword()));
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
