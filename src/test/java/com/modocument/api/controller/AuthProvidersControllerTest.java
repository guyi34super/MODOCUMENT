package com.modocument.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The optionality half of ws07: with NO modocument.oidc.providers configured
 * (the whole main suite runs this way) the discovery endpoint is public,
 * answers an empty list, and the OAuth2 login endpoints simply do not exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthProvidersControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousDiscoveryAnswersAnEmptyListWhenNoOidcIsConfigured() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void oidcHandshakeEndpointsDoNotExistWithoutConfiguration() throws Exception {
        // permitAll + no filter/handler behind it: the request falls through
        // to the error page as a 404, never a provider redirect.
        mvc.perform(get("/oauth2/authorization/entra"))
                .andExpect(status().isNotFound());
    }
}
