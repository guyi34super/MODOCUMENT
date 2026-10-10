package com.modocument.application.port;

import com.modocument.domain.entity.UserMfa;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** Persistence port for TOTP enrolments (one row per user). */
public interface UserMfaRepository {

    Optional<UserMfa> getByUserId(int userId);

    /** True only when the user has an ACTIVATED enrolment (EnabledAt set). */
    boolean isEnabled(int userId);

    /**
     * Of the given users, the ids with an ACTIVATED enrolment. The default
     * probes one by one (fine for fakes); the JPA implementation overrides
     * it with a single query for the ws09 user-admin listing.
     */
    default Set<Integer> enabledUserIds(Collection<Integer> userIds) {
        if (userIds == null) {
            return Set.of();
        }
        return userIds.stream().filter(this::isEnabled)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    UserMfa save(UserMfa entity);

    void deleteByUserId(int userId);
}
