package com.modocument.infrastructure.security.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registrations are built from modocument.oidc.providers.* alone — no
 * network discovery for the two blessed issuer shapes — and NOT AT ALL when
 * nothing is configured (the optionality half of gate B2).
 */
class OidcRegistrationFactoryTest {

    @Test
    void noConfiguredProvidersBuildNoRegistrations() {
        assertThat(OidcRegistrationFactory.build(new OidcProperties())).isEmpty();
    }

    @Test
    void entraAndKeycloakBuildTwoRegistrationsWithDerivedEndpoints() {
        OidcProperties properties = new OidcProperties();
        properties.getProviders().put("entra", entra());
        properties.getProviders().put("keycloak", keycloak());

        List<ClientRegistration> registrations = OidcRegistrationFactory.build(properties);
        assertThat(registrations).hasSize(2);

        ClientRegistration entra = registrations.get(0);
        assertThat(entra.getRegistrationId()).isEqualTo("entra");
        assertThat(entra.getProviderDetails().getIssuerUri())
                .isEqualTo("https://login.microsoftonline.com/tenant-123/v2.0");
        assertThat(entra.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://login.microsoftonline.com/tenant-123/oauth2/v2.0/authorize");
        assertThat(entra.getProviderDetails().getTokenUri())
                .isEqualTo("https://login.microsoftonline.com/tenant-123/oauth2/v2.0/token");
        assertThat(entra.getProviderDetails().getJwkSetUri())
                .isEqualTo("https://login.microsoftonline.com/tenant-123/discovery/v2.0/keys");
        // The v2.0 token endpoint wants the secret in the POST body.
        assertThat(entra.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(entra.getClientName()).isEqualTo("Microsoft");

        ClientRegistration keycloak = registrations.get(1);
        assertThat(keycloak.getRegistrationId()).isEqualTo("keycloak");
        assertThat(keycloak.getProviderDetails().getIssuerUri())
                .isEqualTo("http://localhost:8081/realms/modocument");
        assertThat(keycloak.getProviderDetails().getAuthorizationUri())
                .isEqualTo("http://localhost:8081/realms/modocument/protocol/openid-connect/auth");
        assertThat(keycloak.getProviderDetails().getTokenUri())
                .isEqualTo("http://localhost:8081/realms/modocument/protocol/openid-connect/token");
        assertThat(keycloak.getProviderDetails().getJwkSetUri())
                .isEqualTo("http://localhost:8081/realms/modocument/protocol/openid-connect/certs");
        assertThat(keycloak.getClientName()).isEqualTo("Keycloak");

        for (ClientRegistration registration : registrations) {
            assertThat(registration.getAuthorizationGrantType())
                    .isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
            assertThat(registration.getScopes())
                    .containsExactlyInAnyOrder("openid", "profile", "email");
            // Spring's default callback template — matches the registered
            // redirect URIs at both IdPs AND the public-route allowlist.
            assertThat(registration.getRedirectUri())
                    .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
        }
    }

    @Test
    void trailingSlashOnKeycloakIssuerIsNormalized() {
        OidcProperties.Provider provider = keycloak();
        provider.setIssuerUri("http://localhost:8081/realms/modocument/");
        ClientRegistration registration = OidcRegistrationFactory.build("keycloak", provider);
        assertThat(registration.getProviderDetails().getIssuerUri())
                .isEqualTo("http://localhost:8081/realms/modocument");
        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo("http://localhost:8081/realms/modocument/protocol/openid-connect/token");
    }

    @Test
    void displayNameOverrideWins() {
        OidcProperties.Provider provider = keycloak();
        provider.setDisplayName("ACME SSO");
        assertThat(OidcRegistrationFactory.build("keycloak", provider).getClientName())
                .isEqualTo("ACME SSO");
    }

    @Test
    void incompleteProviderFailsWithTheMissingKeyNamed() {
        OidcProperties.Provider provider = entra();
        provider.setClientSecret(" ");
        assertThatThrownBy(() -> OidcRegistrationFactory.build("entra", provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modocument.oidc.providers.entra.client-secret");
    }

    private static OidcProperties.Provider entra() {
        OidcProperties.Provider provider = new OidcProperties.Provider();
        provider.setIssuerUri("https://login.microsoftonline.com/tenant-123/v2.0");
        provider.setClientId("entra-client");
        provider.setClientSecret("entra-secret");
        provider.setBankCode("CONTOSO");
        return provider;
    }

    private static OidcProperties.Provider keycloak() {
        OidcProperties.Provider provider = new OidcProperties.Provider();
        provider.setIssuerUri("http://localhost:8081/realms/modocument");
        provider.setClientId("modocument-app");
        provider.setClientSecret("kc-secret");
        provider.setBankCode("DEFAULT");
        return provider;
    }
}
