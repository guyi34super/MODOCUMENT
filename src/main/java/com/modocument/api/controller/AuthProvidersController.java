package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * SSO discovery for the login page (ws07): which OIDC front doors exist, so
 * the SPA can render a "Sign in with …" button per provider. PUBLIC by
 * design (the login page is anonymous) and deliberately free of secrets —
 * only the registrationId (which the browser needs anyway to navigate to
 * {@code /oauth2/authorization/{id}}) and a display name. An empty list means
 * no OIDC is configured and the SPA shows the password form alone.
 */
@RestController
@Tag(name = "Authentication")
public class AuthProvidersController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public AuthProvidersController(ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        // ObjectProvider: the repository bean only exists when
        // modocument.oidc.providers is configured (OidcClientConfig).
        this.clientRegistrations = clientRegistrations;
    }

    @Operation(summary = "List the configured SSO (OIDC) providers for the login page")
    @GetMapping("/api/auth/providers")
    public List<ProviderView> providers() {
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        List<ProviderView> providers = new ArrayList<>();
        if (repository instanceof Iterable<?> registrations) {
            for (Object registration : registrations) {
                if (registration instanceof ClientRegistration client) {
                    providers.add(new ProviderView(client.getRegistrationId(),
                            client.getClientName()));
                }
            }
        }
        return providers;
    }

    /** One login button: /oauth2/authorization/{id} with label {name}. */
    public record ProviderView(String id, String name) {
    }
}
