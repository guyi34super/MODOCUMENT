package com.modocument.application.usecase;

import com.modocument.application.dto.AuthResponse;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.infrastructure.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/** Register, login and profile management for application users. */
@Service
public class AuthUseCase {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthUseCase(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(String email, String password, String displayName) {
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
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        user.setUpdatedDate(LocalDateTime.now());
        user = users.save(user);
        return new AuthResponse(jwtService.issueToken(user), UserDto.from(user));
    }

    public AuthResponse login(String email, String password) {
        AppUser user = users.getByEmail(email == null ? "" : email.trim())
                .filter(u -> passwordEncoder.matches(password == null ? "" : password, u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));
        return new AuthResponse(jwtService.issueToken(user), UserDto.from(user));
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
        user.setUpdatedDate(LocalDateTime.now());
        users.save(user);
    }

    private AppUser getUser(int userId) {
        return users.getById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found."));
    }

    /** Thrown when login credentials don't match; mapped to 401 by the controller. */
    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException(String message) {
            super(message);
        }
    }
}
