package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.EgressRuleView;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * EGRESS_RULE_APPROVE under dual control (ws25, platform). SUBMIT, as the
 * platform maker: the rule must exist (404), be PENDING and have no approval
 * already waiting (409). The persisted payload is {@code {ruleId}} plus, for
 * the checker's review only, {@code bankId, bankCode, host|cidr, port,
 * reason}. APPLY re-reads the rule (the payload's descriptive fields are
 * never trusted) and delegates to {@link EgressRuleUseCase#applyApproval},
 * which re-checks PENDING and writes the approval in the rule's own bank.
 * Revoking is direct, by the bank.
 */
@Component
public class EgressRuleApproveChangeApplier implements PendingChangeApplier {

    private final EgressRuleUseCase egressRules;
    private final ObjectMapper objectMapper;

    public EgressRuleApproveChangeApplier(EgressRuleUseCase egressRules,
                                          ObjectMapper objectMapper) {
        this.egressRules = egressRules;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.EGRESS_RULE_APPROVE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int ruleId = ChangePayloads.requireInt(payload, "ruleId");
        EgressRuleView rule = egressRules.requireApprovable(ruleId);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("ruleId", ruleId);
        stored.put("bankId", rule.bankId());
        if (rule.bankCode() != null) {
            stored.put("bankCode", rule.bankCode());
        }
        if (rule.host() != null) {
            stored.put("host", rule.host());
        }
        if (rule.cidr() != null) {
            stored.put("cidr", rule.cidr());
        }
        stored.put("port", rule.port());
        stored.put("reason", rule.reason());
        return new Prepared(stored, String.valueOf(ruleId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        egressRules.applyApproval(ChangePayloads.requireInt(payload, "ruleId"),
                change.getBankId(), change.getMakerUserId());
    }
}
