package com.modocument.application.dto;

import com.modocument.domain.entity.AppUser;

/**
 * API view of an application user (never exposes the password hash). Role and
 * bank travel here, in the response body — deliberately not inside the JWT's
 * profile claims. {@code mustChangePassword} tells the SPA to route the user
 * to the forced password change before anything else (ws09).
 */
public record UserDto(int userId, String email, String displayName, String settings,
                      String role, int bankId, boolean mustChangePassword) {

    public static UserDto from(AppUser user) {
        return new UserDto(user.getAppUserId(), user.getEmail(), user.getDisplayName(),
                user.getSettingsJson(), user.getRole() == null ? null : user.getRole().name(),
                user.getBankId(), user.isMustChangePassword());
    }
}
