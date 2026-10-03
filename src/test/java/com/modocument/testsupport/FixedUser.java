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

    public static CurrentUserProvider provider(int userId) {
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
