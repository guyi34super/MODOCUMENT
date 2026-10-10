package com.modocument.infrastructure.security;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.client.ClientAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Derives the caller's id, bank and role from the validated JWT in the
 * security context. The subject is the AppUser id, bank_id and roles are the
 * custom claims JwtService stamps (see JwtService.issueToken) — kept in one
 * place so ownership/tenant scoping has a single source of truth.
 *
 * <p>ws26: for a machine caller ({@link ClientAuthentication}) the bank is the
 * ApiClient's, the role {@link Role#API_CLIENT}, and the user id the user
 * who created the client — the owner of the jobs it creates.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public int requireUserId() {
        Optional<ClientPrincipal> client = ClientAuthentication.current();
        if (client.isPresent()) {
            // ws26: a machine caller acts for the user who created its
            // ApiClient (contracts §7.1 — machine-created jobs stay
            // owner-scoped). SecurityConfig admits clients to the machine
            // routes only, so this never opens a user route to a client.
            if (client.get().createdByUserId() <= 0) {
                throw new IllegalStateException("This API client acts for no user.");
            }
            return client.get().createdByUserId();
        }
        Jwt jwt = requireJwt();
        try {
            return Integer.parseInt(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Authenticated token has a non-numeric subject.", e);
        }
    }

    @Override
    public int requireBankId() {
        Optional<ClientPrincipal> client = ClientAuthentication.current();
        if (client.isPresent()) {
            return client.get().bankId();
        }
        Object bankId = requireJwt().getClaims().get(JwtService.CLAIM_BANK_ID);
        if (bankId instanceof Number number) {
            return number.intValue();
        }
        // Every token issued since the bank rollout carries the claim, so a
        // missing one means a stale/foreign token — fail loud, never guess a
        // tenant.
        throw new IllegalStateException("Authenticated token carries no bank_id claim.");
    }

    @Override
    public Role role() {
        if (ClientAuthentication.current().isPresent()) {
            return Role.API_CLIENT;
        }
        Object roles = requireJwt().getClaims().get(JwtService.CLAIM_ROLES);
        if (roles instanceof List<?> list && !list.isEmpty()) {
            try {
                return Role.valueOf(String.valueOf(list.get(0)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Authenticated token carries an unknown role: " + list.get(0), e);
            }
        }
        throw new IllegalStateException("Authenticated token carries no roles claim.");
    }

    private static Jwt requireJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        // Every domain endpoint is behind authentication, so reaching here means
        // a filter was misconfigured — fail loud rather than leak another user.
        throw new IllegalStateException("No authenticated user in the security context.");
    }
}
