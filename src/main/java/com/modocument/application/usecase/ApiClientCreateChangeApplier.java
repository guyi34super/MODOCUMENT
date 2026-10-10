package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * API_CLIENT_CREATE under dual control (ws26, contracts §2). SUBMIT
 * validates the request as the maker (method allowed for the bank, SYSTEM
 * scopes only, CIDR syntax, expiry window, certificate parse + global
 * uniqueness) and stores {@code {name, authMethod, scopes, allowedCidrs,
 * expiresAt, certFingerprint?, certSubject?}} — no secret exists yet. APPLY
 * (a second INTEGRATION-capable admin approved) generates the client id and
 * secret, stores the Argon2id hash and seals the plaintext for the maker's
 * one-time read ({@code GET …/api-clients/{id}/initial-secret}); the
 * change's target becomes the new client's id.
 */
@Component
public class ApiClientCreateChangeApplier implements PendingChangeApplier {

    private final ApiClientProvisioning provisioning;

    public ApiClientCreateChangeApplier(ApiClientProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public ChangeType type() {
        return ChangeType.API_CLIENT_CREATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        return new Prepared(provisioning.prepareCreate(payload), null);
    }

    @Override
    public void apply(PendingChange change) {
        provisioning.applyCreate(change);
    }
}
