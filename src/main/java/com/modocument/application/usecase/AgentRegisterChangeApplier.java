package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * AGENT_REGISTER under dual control (ws26, docs/phase2/contracts.md §2):
 * registering an on-prem Connector Agent creates a credential path into the
 * bank's network, so it earns the four-eyes gate. SUBMIT validates the name
 * as the maker (blank/odd characters → 400, a non-revoked agent of the same
 * name → 409) and persists {@code {name}}; APPLY delegates to
 * {@link IntegrationAgentUseCase#applyRegistration}, which re-checks the
 * name, creates the agent PENDING_ENROLMENT with its one-time enrolment
 * token (readable once by the MAKER) and writes AGENT_REGISTERED.
 */
@Component
public class AgentRegisterChangeApplier implements PendingChangeApplier {

    private final IntegrationAgentUseCase agents;
    private final ObjectMapper objectMapper;

    public AgentRegisterChangeApplier(IntegrationAgentUseCase agents, ObjectMapper objectMapper) {
        this.agents = agents;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.AGENT_REGISTER;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        String name = agents.requireRegistrableName(
                ChangePayloads.requireText(payload, "name", IntegrationAgent.NAME_MAX + 50));
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("name", name);
        return new Prepared(stored, null);
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        agents.applyRegistration(
                ChangePayloads.requireText(payload, "name", IntegrationAgent.NAME_MAX + 50),
                change.getMakerUserId());
    }
}
