package com.modocument.application.port;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Counts-only aggregates per bank for the platform console (ws21). Every
 * query is an aggregate (COUNT/MAX/SUM grouped by BankId) — no row content
 * is ever loaded. The caller decides the tenant: the platform use cases run
 * it as SYSTEM in their own read-only transaction (registered call sites,
 * see {@link TenantContext}).
 */
public interface PlatformBankStats {

    /** The aggregates of one bank; zeros/nulls when it has no activity. */
    record BankCounts(long activeUsers, long mfaEnrolledUsers, long jobsSince,
                      long failedJobsSince, LocalDateTime lastSignInAt) {

        public static final BankCounts EMPTY = new BankCounts(0, 0, 0, 0, null);
    }

    /**
     * Aggregates keyed by BankId; banks without any counted row are absent.
     *
     * @param bankId   one bank only, or null for every bank
     * @param jobsSince the start of the job window (inclusive)
     */
    Map<Integer, BankCounts> countsByBank(Integer bankId, LocalDateTime jobsSince);
}
