package com.modocument.infrastructure.security;

import com.modocument.application.port.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Derives the caller's id from the validated JWT in the security context. The
 * subject is the AppUser id (see JwtService.issueToken), mirroring the existing
 * AuthController.userId(Jwt) helper — kept in one place so ownership scoping has
 * a single source of truth.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public int requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return Integer.parseInt(jwt.getSubject());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Authenticated token has a non-numeric subject.", e);
            }
        }
        // Every domain endpoint is behind authentication, so reaching here means
        // a filter was misconfigured — fail loud rather than leak another user.
        throw new IllegalStateException("No authenticated user in the security context.");
    }
}
