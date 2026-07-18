package com.modocument.application.dto;

import com.modocument.domain.entity.AppUser;

/** API view of an application user (never exposes the password hash). */
public record UserDto(int userId, String email, String displayName, String settings) {

    public static UserDto from(AppUser user) {
        return new UserDto(user.getAppUserId(), user.getEmail(), user.getDisplayName(),
                user.getSettingsJson());
    }
}
