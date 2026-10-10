package com.modocument.infrastructure.security;

import com.modocument.application.port.CurrentUserProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers "must the caller change their password before doing anything
 * else?" (ws09 forced password change). Deliberately ONE extra primary-key
 * probe per authorization decision rather than piggybacking on the
 * RevocationValidator's per-request query: the validator runs inside the
 * JWT decoder with no clean channel to the authorization layer, and this
 * read only happens for permissions outside PROFILE_SELF, after the role
 * check already granted — a single unique-index probe, same cost class as
 * the validator's own read.
 *
 * <p>No cache on purpose, mirroring the RevocationValidator's stance: the
 * flag must take effect (and clear) on the very next request.
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
