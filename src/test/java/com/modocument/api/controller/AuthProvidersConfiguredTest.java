package com.modocument.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same application booted WITH two OIDC providers (ws07/B2): the whole
 * context comes up (registrations built offline from the issuer shapes, no
 * discovery call), the public endpoint lists exactly the configured ones, and
 * /oauth2/authorization/{id} starts the authorization-code dance at the
 * right provider.
 */
@SpringBootTest(properties = {
        "modocument.oidc.providers.entra.issuer-uri="
                + "https://login.microsoftonline.com/tenant-123/v2.0",
        "modocument.oidc.providers.entra.client-id=entra-client",
        "modocument.oidc.providers.entra.client-secret=entra-secret",
        "modocument.oidc.providers.entra.bank-code=CONTOSO",
        "modocument.oidc.providers.keycloak.issuer-uri="
                + "http://localhost:8081/realms/modocument",
        "modocument.oidc.providers.keycloak.client-id=modocument-app",
        "modocument.oidc.providers.keycloak.client-secret=kc-secret",
        "modocument.oidc.providers.keycloak.bank-code=DEFAULT",
        "modocument.oidc.providers.keycloak.role-claim=modocument_roles"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthProvidersConfiguredTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousDiscoveryListsTheConfiguredProviders() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == 'entra')].name").value("Microsoft"))
                .andExpect(jsonPath("$[?(@.id == 'keycloak')].name").value("Keycloak"));
    }

    @Test
    void authorizationEndpointRedirectsToTheProvider() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/entra"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location)
                .startsWith("https://login.microsoftonline.com/tenant-123/oauth2/v2.0/authorize")
                .contains("client_id=entra-client")
                .contains("response_type=code")
                .contains("scope=openid%20profile%20email")
                .contains("redirect_uri=");
    }

    @Test
    void apiRequestsStillAnswer401NotALoginRedirect() throws Exception {
        // oauth2Login must not replace the Bearer entry point: unauthenticated
        // API calls keep their 401 contract instead of a 302 to an IdP.
        mvc.perform(get("/Templates/GetAll"))
                .andExpect(status().isUnauthorized());
    }
}
