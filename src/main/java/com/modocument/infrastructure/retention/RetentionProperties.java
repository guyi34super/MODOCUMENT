package com.modocument.infrastructure.retention;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>Per-bank period (ws20):</b> the days are the setting
 * {@code retention.customerDataDays} of each bank — 1 to 30, the gate C5
 * ceiling, so a bank may only SHORTEN retention — read per sweep and per
 * call ({@link #customerDataDays(int)}; the no-argument form is the
 * caller's bank, else the platform layer). With nothing stored it is the
 * yml value. The on/off switch is locked on and stays yml-only.
 */
@Component
public class RetentionProperties {

    private final boolean enabled;
    private final int customerDataDays;
    /** Null = the fixed yml period (unit-test wiring). */
    private final SettingsReader settings;

    public RetentionProperties(boolean enabled, int customerDataDays) {
        this(enabled, customerDataDays, null);
    }

    @Autowired
    public RetentionProperties(
            @Value("${modocument.retention.enabled:true}") boolean enabled,
            @Value("${modocument.retention.customer-data-days:30}") int customerDataDays,
            SettingsReader settings) {
        if (customerDataDays < 1) {
            throw new IllegalArgumentException(
                    "modocument.retention.customer-data-days must be at least 1.");
        }
        this.enabled = enabled;
        this.customerDataDays = customerDataDays;
        this.settings = settings;
    }

    /** The retention period of one bank's customer data, in days. */
    public int customerDataDays(int bankId) {
        return settings == null ? customerDataDays
                : settings.getInt(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, bankId);
    }

    public boolean enabled() {
        return enabled;
    }

    /** The platform layer's period (what a bank without its own value gets), in days. */
    public int customerDataDaysForPlatform() {
        return settings == null ? customerDataDays
                : settings.getIntForPlatform(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS);
    }

    /** The caller's bank's retention period (else the platform layer), in days. */
    public int customerDataDays() {
        return settings == null ? customerDataDays
                : settings.getIntForCurrentBank(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS);
    }

    public Duration customerDataPeriod() {
        return Duration.ofDays(customerDataDays());
    }
}
