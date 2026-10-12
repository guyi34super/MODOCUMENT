package com.modocument.infrastructure.security;

import com.modocument.application.port.CurrentUserProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Answers "must the caller change their password before doing anything
 * else?" (ws09 forced password change).
 *
 * <p>ws27: no extra read on an authenticated request. The
 * {@link RevocationValidator} already loads the caller's AppUsers row once
 * per request and keeps it as a request-scoped {@link RequestPrincipalState}
 * (MustChangePassword included); this gate answers from it. Only without
 * such a state — outside a web request, or for another user id — does it
 * fall back to its own single primary-key probe.
 *
 * <p>No cache across requests on purpose, mirroring the RevocationValidator's
 * stance: the flag must take effect (and clear) on the very next request.
 */
@Component
public class PasswordChangeRequiredGate {

    private static final String QUERY =
            "SELECT MustChangePassword FROM AppUsers WHERE AppUserId = ?";

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public PasswordChangeRequiredGate(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /** True when this user must change their password before anything else. */
    public boolean mustChange(int userId) {
        Optional<RequestPrincipalState> loaded = RequestPrincipalState.current(userId);
        if (loaded.isPresent()) {
            return loaded.get().mustChangePassword();
        }
        try {
            Boolean flag = jdbc.queryForObject(QUERY, Boolean.class, userId);
            return Boolean.TRUE.equals(flag);
        } catch (EmptyResultDataAccessException userGone) {
            // A vanished user is the RevocationValidator's problem (401);
            // this gate only decides the 403-vs-allowed question.
            return false;
        }
    }

    /**
     * True when the CURRENT caller must change their password; false for
     * anonymous callers (nothing to gate without an identity).
     */
    public boolean callerMustChange() {
        if (com.modocument.infrastructure.security.client.ClientAuthentication.current()
                .isPresent()) {
            // ws26: a machine caller has no password; its creator's
            // temporary password does not gate the integration API.
            return false;
        }
        int userId;
        try {
            userId = currentUser.requireUserId();
        } catch (IllegalStateException anonymous) {
            return false;
        }
        return mustChange(userId);
    }
}
