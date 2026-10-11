package com.modocument.domain.model;

/**
 * What a {@code Bank} row is (ws21, V210). Stored by name in Bank.Kind.
 * <ul>
 *   <li>{@link #CUSTOMER} — a tenant bank: its users hold the four bank
 *       roles and its rows are customer data.</li>
 *   <li>{@link #PLATFORM} — the single platform-owner bank (MO
 *       Intelligence): only {@code PLATFORM_ADMIN} users live here, and it
 *       owns the platform settings, announcements and egress approvals.
 *       Exactly one may exist (V210 unique partial index), and a bank's kind
 *       never changes once created (V210 trigger).</li>
 * </ul>
 */
public enum BankKind {
    CUSTOMER,
    PLATFORM
}
