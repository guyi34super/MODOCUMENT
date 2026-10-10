package com.modocument.infrastructure.security.client;

import com.modocument.application.port.integration.ClientPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

/**
 * The security-context form of an authenticated machine caller (ws26),
 * whatever the method — an OAuth2 client token (converted from the validated
 * JWT), an API key or a client certificate. Its principal is the
 * {@link ClientPrincipal}; there is deliberately no {@code Jwt} principal, so
 * every user-only code path that needs a user token refuses a client, and
 * SecurityConfig lets this authentication reach ONLY the integration and
 * agent routes.
 */
public final class ClientAuthentication extends AbstractAuthenticationToken {

    /** The single authority every client carries (path policy, logs). */
    public static final String AUTHORITY = "ROLE_API_CLIENT";

    private final ClientPrincipal principal;
    /** The access token's jti (OAuth2) — null for API keys and certificates. */
    private final String tokenId;

    public ClientAuthentication(ClientPrincipal principal, String tokenId) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.principal = principal;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    @Override
    public ClientPrincipal getPrincipal() {
        return principal;
    }

    /** Never the secret: credentials are verified before this object exists. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return principal.clientId();
    }

    public String tokenId() {
        return tokenId;
    }

    /** The current request's client, if the caller is a machine. */
    public static Optional<ClientPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ClientAuthentication client) {
            return Optional.of(client.getPrincipal());
        }
        return Optional.empty();
    }

    public static boolean isClient(Authentication authentication) {
        return authentication instanceof ClientAuthentication;
    }
}
