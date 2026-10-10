package com.modocument.testsupport;

import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * In-memory {@link BankDirectory} + {@link PlatformBankLookup} for unit
 * tests (ws21): banks are registered by id; unknown ids answer like the
 * real directory (empty, i.e. CUSTOMER and not suspended).
 */
public final class StubBankDirectory implements BankDirectory, PlatformBankLookup {

    private final Map<Integer, BankRef> banks = new HashMap<>();

    public StubBankDirectory customer(int bankId) {
        banks.put(bankId, new BankRef(bankId, "BANK" + bankId, "Bank " + bankId,
                BankKind.CUSTOMER, BankStatus.ACTIVE));
        return this;
    }

    public StubBankDirectory platform(int bankId) {
        banks.put(bankId, new BankRef(bankId, "MOI-PLATFORM", "MO Intelligence Platform",
                BankKind.PLATFORM, BankStatus.ACTIVE));
        return this;
    }

    @Override
    public Optional<BankRef> find(int bankId) {
        return Optional.ofNullable(banks.get(bankId));
    }

    @Override
    public void refresh(int bankId) {
        // Nothing cached.
    }

    @Override
    public OptionalInt platformBankId() {
        return banks.values().stream()
                .filter(BankRef::isPlatform)
                .mapToInt(BankRef::bankId)
                .findFirst();
    }
}
