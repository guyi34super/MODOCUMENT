package com.modocument.application.dto;

import java.util.List;

/**
 * {@code GET /api/platform/overview} (ws21, docs/phase2/contracts.md §3):
 * every CUSTOMER bank as a counts-only {@link BankSummary} plus the totals
 * across them. The platform bank itself is not a customer; it is listed by
 * {@code GET /api/platform/banks} only.
 */
public record PlatformOverview(List<BankSummary> banks, Totals totals) {

    /**
     * @param banks          customer banks
     * @param activeBanks    of those, ACTIVE
     * @param suspendedBanks of those, SUSPENDED
     * @param users          enabled accounts across all customer banks
     */
    public record Totals(long banks, long activeBanks, long suspendedBanks, long users) {
    }
}
