package com.modocument.infrastructure.security;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * The caller's AppUsers row as the {@link RevocationValidator} read it at the
 * start of THIS request (ws27): one primary-key probe answers both the
 * revocation checks and the forced-password-change gate
 * ({@link PasswordChangeRequiredGate}), which before read AppUsers a second
 * time on every authorized request.
 *
 * <p>Request-scoped by construction: the state lives in a servlet request
 * attribute ({@link RequestAttributes#SCOPE_REQUEST}), so it is never shared
 * between requests and there is no cache to invalidate — a revocation, a
 * disable or a forced password change still takes effect on the very next
 * request, exactly as before. Outside a web request (scheduled work, tests
 * calling the gate directly) nothing is stored and the gate reads the row
 * itself.
 *
 * @param userId             the token subject the row belongs to
 * @param tokenVersion       AppUsers.TokenVersion
 * @param disabled           AppUsers.Disabled
 * @param bankId             AppUsers.BankId
 * @param revoked            the token's jti is on the RevokedToken denylist
 * @param mustChangePassword AppUsers.MustChangePassword
 */
public record RequestPrincipalState(int userId, int tokenVersion, boolean disabled, int bankId,
                                    boolean revoked, boolean mustChangePassword) {

    /** Request attribute holding the state of the request's authenticated user. */
    static final String ATTRIBUTE = RequestPrincipalState.class.getName();

    /** Remembers the state for the rest of the current request (no-op outside one). */
    static void store(RequestPrincipalState state) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, state, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The state this request already loaded for {@code userId}, if any. */
    static Optional<RequestPrincipalState> current(int userId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object state = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return state instanceof RequestPrincipalState loaded && loaded.userId() == userId
                ? Optional.of(loaded) : Optional.empty();
    }
}
