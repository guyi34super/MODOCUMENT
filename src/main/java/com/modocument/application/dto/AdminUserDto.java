package com.modocument.application.dto;

import com.modocument.domain.entity.AppUser;

import java.time.LocalDateTime;

/**
 * One row of the ws09 user-administration listing (gate B1: every user
 * individually identified, with last login). Never carries the password
 * hash; {@code authProvider} is null for local password accounts.
 */
public record AdminUserDto(int id, String email, String displayName, String role,
                           boolean disabled, LocalDateTime lastLoginAt, boolean mfaEnabled,
                           String authProvider, LocalDateTime createdDate) {

    public static AdminUserDto from(AppUser user, boolean mfaEnabled) {
        return new AdminUserDto(user.getAppUserId(), user.getEmail(), user.getDisplayName(),
                user.getRole() == null ? null : user.getRole().name(), user.isDisabled(),
                user.getLastLoginAt(), mfaEnabled, user.getAuthProvider(), user.getCreatedDate());
    }
}
