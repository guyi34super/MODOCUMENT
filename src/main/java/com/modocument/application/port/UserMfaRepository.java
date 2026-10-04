package com.modocument.application.port;

import com.modocument.domain.entity.UserMfa;

import java.util.Optional;

/** Persistence port for TOTP enrolments (one row per user). */
public interface UserMfaRepository {

    Optional<UserMfa> getByUserId(int userId);

    /** True only when the user has an ACTIVATED enrolment (EnabledAt set). */
    boolean isEnabled(int userId);

    UserMfa save(UserMfa entity);

    void deleteByUserId(int userId);
}
