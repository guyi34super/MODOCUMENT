package com.modocument.infrastructure.security.oidc;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds Spring Security {@link ClientRegistration}s from
 * {@code modocument.oidc.providers.*} (see {@link OidcProperties}).
 *
 * <p>For the two blessed issuer shapes the OIDC endpoints are DERIVED, not
 * discovered, so the app boots offline and deterministically:
 * <ul>
 *   <li><b>Entra ID</b> — {@code https://login.microsoftonline.com/{tenant}/v2.0}
 *       (documented, stable v2.0 endpoint layout);</li>
 *   <li><b>Keycloak</b> — any issuer containing {@code /realms/} (the fixed
 *       {@code /protocol/openid-connect/*} layout).</li>
 * </ul>
 * Any other issuer falls back to standard OIDC discovery
 * ({@code /.well-known/openid-configuration}) at startup.
 *
 * <p>Every registration uses the authorization-code grant with scopes
 * {@code openid,profile,email} and Spring's default redirect template
 * {@code {baseUrl}/login/oauth2/code/{registrationId}}.
 */
final class OidcRegistrationFactory {

    static final List<String> SCOPES = List.of("openid", "profile", "email");

    private static final Pattern ENTRA_ISSUER = Pattern.compile(
            "^https://login\\.microsoftonline\\.com/([^/]+)/v2\\.0$");

    private OidcRegistrationFactory() {
    }

    static List<ClientRegistration> build(OidcProperties properties) {
        List<ClientRegistration> registrations = new ArrayList<>();
        for (Map.Entry<String, OidcProperties.Provider> entry
                : properties.getProviders().entrySet()) {
            registrations.add(build(entry.getKey(), entry.getValue()));
        }
        return registrations;
    }

    static ClientRegistration build(String registrationId, OidcProperties.Provider provider) {
        require(registrationId, "issuer-uri", provider.getIssuerUri());
        require(registrationId, "client-id", provider.getClientId());
        require(registrationId, "client-secret", provider.getClientSecret());
        require(registrationId, "bank-code", provider.getBankCode());

        String issuer = trimTrailingSlash(provider.getIssuerUri().trim());
        ClientRegistration.Builder builder;
        Matcher entra = ENTRA_ISSUER.matcher(issuer);
        if (entra.matches()) {
            String base = "https://login.microsoftonline.com/" + entra.group(1);
            builder = ClientRegistration.withRegistrationId(registrationId)
                    .issuerUri(issuer)
                    .authorizationUri(base + "/oauth2/v2.0/authorize")
                    .tokenUri(base + "/oauth2/v2.0/token")
                    .jwkSetUri(base + "/discovery/v2.0/keys")
                    .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
                    // The v2.0 token endpoint wants the secret in the POST body.
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else if (issuer.contains("/realms/")) {
            builder = ClientRegistration.withRegistrationId(registrationId)
                    .issuerUri(issuer)
                    .authorizationUri(issuer + "/protocol/openid-connect/auth")
                    .tokenUri(issuer + "/protocol/openid-connect/token")
                    .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                    .userInfoUri(issuer + "/protocol/openid-connect/userinfo")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        } else {
            // Unknown issuer shape: standard OIDC discovery (network call at
            // startup; the issuer must be reachable when the app boots).
            builder = ClientRegistrations.fromIssuerLocation(issuer)
                    .registrationId(registrationId)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        }
        return builder
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(SCOPES)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName(displayName(registrationId, provider))
                .build();
    }

    /** Button label for /api/auth/providers; overridable per provider. */
    static String displayName(String registrationId, OidcProperties.Provider provider) {
        if (provider.getDisplayName() != null && !provider.getDisplayName().isBlank()) {
            return provider.getDisplayName().trim();
        }
        return switch (registrationId) {
            case "entra" -> "Microsoft";
            case "keycloak" -> "Keycloak";
            default -> registrationId.substring(0, 1).toUpperCase(Locale.ROOT)
                    + registrationId.substring(1);
        };
    }

    private static void require(String registrationId, String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OIDC provider '" + registrationId
                    + "' is configured without modocument.oidc.providers."
                    + registrationId + "." + key
                    + " — either complete the provider or remove it.");
        }
    }

    private static String trimTrailingSlash(String issuer) {
        // The registration's issuer is matched against the ID token's iss
        // claim, which neither Entra nor Keycloak emits with a trailing
        // slash — and derived endpoint paths must not double the '/'.
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }
}
