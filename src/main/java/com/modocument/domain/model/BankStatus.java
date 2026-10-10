package com.modocument.domain.model;

/**
 * Lifecycle of a {@code Bank} (ws21, V210). Stored by name in Bank.Status.
 * A {@link #SUSPENDED} bank keeps its data, but its users can no longer sign
 * in or refresh (enforced from Wave 2 by the BANK_SUSPEND applier and the
 * login/refresh paths through {@code BankDirectory#isSuspended}).
 */
public enum BankStatus {
    ACTIVE,
    SUSPENDED
}
