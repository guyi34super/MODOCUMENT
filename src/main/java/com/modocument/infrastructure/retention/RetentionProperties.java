package com.modocument.infrastructure.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Retention policy knobs (ws14, gate C5, PRIV-013):
 * <ul>
 *   <li>{@code modocument.retention.customer-data-days} (env
 *       {@code MODOCUMENT_RETENTION_DAYS}, default 30) — how long uploaded
 *       customer data lives before the daily purge removes it;</li>
 *   <li>{@code modocument.retention.enabled} (env
 *       {@code MODOCUMENT_RETENTION_ENABLED}, default true) — gates the
 *       SCHEDULED sweep only. The manual admin trigger
 *       (POST /api/admin/retention/run) always works: an admin explicitly
 *       asking for the purge is never the case the flag protects against
 *       (it exists so test/dev profiles don't purge behind the suite's
 *       back).</li>
 * </ul>
 * The retention schedule itself (category × period × destruction method) is
 * documented in docs/security/retention-and-dsr.md.
 */
@Component
public class RetentionProperties {

    private final boolean enabled;
    private final int customerDataDays;

    public RetentionProperties(
            @Value("${modocument.retention.enabled:true}") boolean enabled,
            @Value("${modocument.retention.customer-data-days:30}") int customerDataDays) {
        if (customerDataDays < 1) {
            throw new IllegalArgumentException(
                    "modocument.retention.customer-data-days must be at least 1.");
        }
        this.enabled = enabled;
        this.customerDataDays = customerDataDays;
    }

    public boolean enabled() {
        return enabled;
    }

    public int customerDataDays() {
        return customerDataDays;
    }

    public Duration customerDataPeriod() {
        return Duration.ofDays(customerDataDays);
    }
}
