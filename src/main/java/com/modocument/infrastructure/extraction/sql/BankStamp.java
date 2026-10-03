package com.modocument.infrastructure.extraction.sql;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;

/**
 * Resolves the BankId to stamp on an extraction row. Request threads carry
 * the caller's bank in {@link TenantContext} (populated from the JWT by the
 * TenantContextFilter); worker threads carry the job's bank (the extraction
 * task runner wraps itself in TenantContext.runAs). Anything genuinely
 * tenant-less lands in the default bank rather than failing the V11 NOT NULL
 * constraint.
 */
final class BankStamp {

    private BankStamp() {
    }

    /** The domain object's own bank when set, else the thread's, else default. */
    static int effective(int domainBankId) {
        if (domainBankId > 0) {
            return domainBankId;
        }
        return current();
    }

    /** The thread's bank, else the default bank. */
    static int current() {
        return TenantContext.currentBankId().orElse(Bank.DEFAULT_BANK_ID);
    }
}
