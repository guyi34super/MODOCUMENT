package com.modocument.testsupport;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;

/**
 * Deterministic {@link CurrentUserProvider} for unit tests: a fixed caller in
 * the default bank. Replaces the old {@code () -> userId} lambdas now that
 * create paths also ask for the caller's bank and role.
 */
public record FixedUser(int userId, int bankId, Role userRole) implements CurrentUserProvider {

    /**
     * The default fixture is a READ_WRITE user — the ordinary authoring role.
     * Tests that exercise admin surfaces (shared-lookup mutations, metrics)
     * use {@link #admin(int)} explicitly, so a test that accidentally relies
     * on admin powers is visible at the fixture.
     */
    public static CurrentUserProvider provider(int userId) {
        return new FixedUser(userId, Bank.DEFAULT_BANK_ID, Role.READ_WRITE);
    }

    public static CurrentUserProvider admin(int userId) {
        return new FixedUser(userId, Bank.DEFAULT_BANK_ID, Role.ADMIN);
    }

    @Override
    public int requireUserId() {
        return userId;
    }

    @Override
    public int requireBankId() {
        return bankId;
    }

    @Override
    public Role role() {
        return userRole;
    }
}
