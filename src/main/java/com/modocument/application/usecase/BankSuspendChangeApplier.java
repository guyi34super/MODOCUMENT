package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.BankDirectory.BankRef;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import org.springframework.stereotype.Component;

/**
 * BANK_SUSPEND under dual control (ws21). SUBMIT, as the maker: the bank
 * must exist (404), must not be the platform bank and must not already be
 * suspended (409), and a reason is required. The persisted payload is
 * {@code {bankId, reason, bankCode, bankName}} — the code and name only so
 * the checker reviews a bank, not an id. APPLY delegates to
 * {@link PlatformBankLifecycleUseCase#applySuspension}, which re-checks the
 * state and kills every session of the bank's users. Reactivation is direct
 * ({@code POST /api/platform/banks/{id}/reactivate}).
 */
@Component
public class BankSuspendChangeApplier implements PendingChangeApplier {

    private final PlatformBankLifecycleUseCase lifecycle;
    private final ObjectMapper objectMapper;

    public BankSuspendChangeApplier(PlatformBankLifecycleUseCase lifecycle,
                                    ObjectMapper objectMapper) {
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.BANK_SUSPEND;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int bankId = ChangePayloads.requireInt(payload, "bankId");
        String reason = ChangePayloads.requireText(payload, "reason", 500).trim();
        BankRef bank = lifecycle.requireCustomerBank(bankId,
                "The platform bank cannot be suspended.");
        if (bank.isSuspended()) {
            throw new IllegalStateException("Bank " + bank.code() + " is already suspended.");
        }
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("bankId", bankId);
        stored.put("reason", reason);
        stored.put("bankCode", bank.code());
        stored.put("bankName", bank.name());
        return new Prepared(stored, String.valueOf(bankId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        lifecycle.applySuspension(ChangePayloads.requireInt(payload, "bankId"),
                ChangePayloads.requireText(payload, "reason", 500),
                change.getBankId(), change.getMakerUserId());
    }
}
