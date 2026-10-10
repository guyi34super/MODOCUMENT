package com.modocument.testsupport;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The H2 test schema's platform bank (ws21). PlatformBankDirectory resolves
 * it in its own transaction, so the row must be COMMITTED (call from a
 * non-transactional test) and is never deleted: every test class that needs
 * it finds the same row by the default code. (The test profile turns the
 * directory's cache off: a context with other properties recreates the
 * shared schema, so ids get reused.)
 */
public final class PlatformBankFixture {

    /** {@code modocument.platform.bank-code}'s default, unset in the test profile. */
    public static final String CODE = "MOI-PLATFORM";

    private PlatformBankFixture() {
    }

    public static Bank ensurePlatformBank(BankJpaRepository banks) {
        // The H2 schema starts without banks, and most tests park their
        // users in Bank.DEFAULT_BANK_ID (1) without creating it: claim the
        // first identity value for the default bank, so the platform bank
        // can never be bank 1. Checked even when the platform bank already
        // exists: another fixture (SettingsIntegrationSupport) may have
        // created it, by a fixed id, in a schema a new context had just
        // recreated — and callers rely on the DEFAULT code existing.
        if (banks.findByCode(Bank.DEFAULT_BANK_CODE).isEmpty()) {
            banks.save(new Bank(Bank.DEFAULT_BANK_CODE, "Default Bank"));
        }
        return banks.findByCode(CODE).orElseGet(() -> {
            Bank platform = banks.save(new Bank(CODE, "MO Intelligence Platform", BankKind.PLATFORM));
            if (platform.getBankId() == Bank.DEFAULT_BANK_ID) {
                throw new IllegalStateException("The test platform bank must not be bank 1.");
            }
            return platform;
        });
    }

    /** A committed user (the caller deletes it); {@code {noop}} hash, no forced change. */
    public static AppUser user(AppUserJpaRepository users, int bankId, Role role, String tag) {
        AppUser entity = new AppUser();
        entity.setEmail(tag + "-" + role.name().toLowerCase() + "-" + UUID.randomUUID()
                + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName(tag + " " + role);
        entity.setBankId(bankId);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        return users.save(entity);
    }
}
