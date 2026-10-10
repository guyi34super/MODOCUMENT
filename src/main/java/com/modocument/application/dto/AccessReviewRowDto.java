package com.modocument.application.dto;

import java.time.LocalDateTime;

/**
 * One line of the quarterly recertification worksheet (SEC-013 seam):
 * everything a reviewer needs to confirm "this person still needs this
 * access". {@code daysSinceLastLogin} is null when the user never logged
 * in; {@code inactive90d} flags accounts with no login for 90 days (a
 * never-used account older than 90 days counts as inactive too).
 */
public record AccessReviewRowDto(int id, String email, String displayName, String role,
                                 boolean disabled, boolean mfaEnabled,
                                 LocalDateTime lastLoginAt, Long daysSinceLastLogin,
                                 boolean inactive90d) {
}
