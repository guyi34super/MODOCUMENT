package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * INTEGRATION_FLOW_ENABLE under dual control (ws25): enabling an OUTBOUND
 * flow — or re-enabling one an admin widened (masking off, more payload
 * kinds, another connection/target/mapping) — lets data leave the bank's
 * tenant, so a SECOND admin approves it ({@code PendingChangeUseCase} and
 * {@code SodPolicy} refuse self-approval with 409). The payload
 * {@code {flowId, flowVersion}} pins the exact flow version the checker
 * saw: a flow edited after the request is refused at apply.
 */
@Component
public class IntegrationFlowEnableApplier implements PendingChangeApplier {

    private final IntegrationFlowJpaRepository flows;
    private final IntegrationFlowUseCase flowUseCase;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public IntegrationFlowEnableApplier(IntegrationFlowJpaRepository flows,
                                        @Lazy IntegrationFlowUseCase flowUseCase,
                                        CurrentUserProvider currentUser, ObjectMapper objectMapper) {
        this.flows = flows;
        this.flowUseCase = flowUseCase;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.INTEGRATION_FLOW_ENABLE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        long flowId = ChangePayloads.requireInt(payload, "flowId");
        IntegrationFlow flow = flows.findByIdAndBankId(flowId, currentUser.requireBankId())
                .orElseThrow(() -> new NoSuchElementException("Flow " + flowId + " not found."));
        if (flow.getDirection() != FlowDirection.OUTBOUND) {
            throw new IllegalArgumentException("Only OUTBOUND flows need approval to be enabled.");
        }
        int version = payload.hasNonNull("flowVersion") ? ChangePayloads.requireInt(payload, "flowVersion")
                : flow.getVersion();
        if (version != flow.getVersion()) {
            throw new IllegalStateException("The flow changed; reload it and request again.");
        }
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("flowId", flowId);
        stored.put("flowVersion", version);
        stored.put("flowName", flow.getName());
        return new Prepared(stored, String.valueOf(flowId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        long flowId = ChangePayloads.requireInt(payload, "flowId");
        int version = ChangePayloads.requireInt(payload, "flowVersion");
        flowUseCase.applyApprovedEnable(change.getBankId(), flowId, version);
    }
}
