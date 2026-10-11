package com.modocument.application.port.integration;

import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;

import java.util.Set;

/**
 * An authenticated machine caller (ws26): an ApiClient of one bank with its
 * granted scopes. {@code createdByUserId} owns the jobs the client creates.
 * {@code agentId} is set for Kind AGENT.
 */
public record ClientPrincipal(int apiClientId, int bankId, String clientId, ApiClientKind kind,
                              ApiAuthMethod authMethod, Set<Permission> scopes,
                              int createdByUserId, Integer agentId) {

    public ClientPrincipal {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
