package com.modocument.infrastructure.platform;

import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Resolves the platform bank by its configured code
 * ({@code modocument.platform.bank-code}, default {@code MOI-PLATFORM}).
 * The Bank table is RLS-protected, so the lookup runs as system in its own
 * read-only transaction (a registered {@code runAsSystem} call site, see
 * {@link TenantContext}). A found id is cached for the life of the process
 * (a bank's id never changes); "not found" is re-checked on the next call,
 * because the platform bank is created at startup by the bootstrap runner.
 */
@Component
public class PlatformBankDirectory implements PlatformBankLookup {

    private final BankJpaRepository banks;
    private final TransactionTemplate readOnlyNewTx;
    private final String platformBankCode;
    private volatile Integer cachedId;

    public PlatformBankDirectory(BankJpaRepository banks,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${modocument.platform.bank-code:MOI-PLATFORM}")
                                 String platformBankCode) {
        this.banks = banks;
        this.platformBankCode = platformBankCode;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.readOnlyNewTx = template;
    }

    @Override
    public OptionalInt platformBankId() {
        Integer id = cachedId;
        if (id != null) {
            return OptionalInt.of(id);
        }
        Optional<Bank> bank;
        try {
            bank = TenantContext.runAsSystem(
                    () -> readOnlyNewTx.execute(status -> banks.findByCode(platformBankCode)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not resolve the platform bank", e);
        }
        if (bank == null || bank.isEmpty()) {
            return OptionalInt.empty();
        }
        cachedId = bank.get().getBankId();
        return OptionalInt.of(cachedId);
    }

    /** The configured platform bank code. */
    public String platformBankCode() {
        return platformBankCode;
    }
}
