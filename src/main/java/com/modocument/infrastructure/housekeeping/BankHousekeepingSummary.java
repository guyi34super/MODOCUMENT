package com.modocument.infrastructure.housekeeping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One bank's result of a {@link HousekeepingJob} run: the counts of the
 * HOUSEKEEPING_COMPLETED audit event (no values, counts only).
 * {@code revokedTokens} is the platform-wide denylist sweep; it is reported
 * on the platform bank's row and 0 elsewhere.
 */
public record BankHousekeepingSummary(
        int bankId,
        long historyRetentionDays,
        int refreshTokens,
        int revokedTokens,
        int integrationRuns,
        int integrationRunEvents,
        int outboundDeliveries,
        int integrationEvents,
        int idempotencyKeys) {

    /** Ordered category map for the audit row. */
    public Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("refreshTokens", refreshTokens);
        counts.put("revokedTokens", revokedTokens);
        counts.put("integrationRuns", integrationRuns);
        counts.put("integrationRunEvents", integrationRunEvents);
        counts.put("outboundDeliveries", outboundDeliveries);
        counts.put("integrationEvents", integrationEvents);
        counts.put("idempotencyKeys", idempotencyKeys);
        return counts;
    }

    public int total() {
        return refreshTokens + revokedTokens + integrationRuns + integrationRunEvents
                + outboundDeliveries + integrationEvents + idempotencyKeys;
    }
}
