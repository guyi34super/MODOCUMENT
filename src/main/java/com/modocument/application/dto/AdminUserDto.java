package com.modocument.application.dto;

import com.modocument.domain.entity.AppUser;

import java.time.LocalDateTime;

/**
 * One row of the ws09 user-administration listing (gate B1: every user
 * individually identified, with last login). Never carries the password
 * hash; {@code authProvider} is null for local password accounts.
 *
 * <p>Phase 2 (ws22) adds the account state the admin actions need:
 * {@code locked} (a login lockout is in force right now, see
 * LoginLockoutPolicy) with its {@code lockedUntil} horizon, and
 * {@code mustChangePassword} (a temporary password is still outstanding).
 */
public record AdminUserDto(int id, String email, String displayName, String role,
                           boolean disabled, LocalDateTime lastLoginAt, boolean mfaEnabled,
                           String authProvider, LocalDateTime createdDate, boolean locked,
                           LocalDateTime lockedUntil, boolean mustChangePassword) {

    public static AdminUserDto from(AppUser user, boolean mfaEnabled) {
        LocalDateTime lockedUntil = user.getLockedUntil();
        boolean locked = lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
        return new AdminUserDto(user.getAppUserId(), user.getEmail(), user.getDisplayName(),
                user.getRole() == null ? null : user.getRole().name(), user.isDisabled(),
                user.getLastLoginAt(), mfaEnabled, user.getAuthProvider(), user.getCreatedDate(),
                locked, locked ? lockedUntil : null, user.isMustChangePassword());
    }
}
