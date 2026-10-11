package com.modocument.infrastructure.housekeeping;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code modocument.housekeeping.*} (ws27): wiring of the daily
 * {@link HousekeepingJob}. The per-bank integration history window is the
 * editable setting {@code integration.historyRetention}, not a property.
 */
@ConfigurationProperties(prefix = "modocument.housekeeping")
public class HousekeepingProperties {

    /** Gates only the schedule; {@link HousekeepingJob#runOnce()} always runs. */
    private boolean enabled = true;

    /** Rows deleted per statement (one short transaction each). */
    private int batchSize = 500;

    /** Statements per rule per bank per run: bounds one run's work and lock time. */
    private int maxBatches = 100;

    /** A refresh token expired or revoked longer ago than this is deleted. */
    private Duration refreshTokenGrace = Duration.ofDays(30);

    /**
     * A revoked access-token jti is kept this long past the token's own
     * expiry (covers the JWT validator's clock skew); after that the token is
     * dead anyway and the denylist row is useless.
     */
    private Duration revokedTokenGrace = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("modocument.housekeeping.batch-size must be >= 1");
        }
        this.batchSize = batchSize;
    }

    public int getMaxBatches() {
        return maxBatches;
    }

    public void setMaxBatches(int maxBatches) {
        if (maxBatches < 1) {
            throw new IllegalArgumentException("modocument.housekeeping.max-batches must be >= 1");
        }
        this.maxBatches = maxBatches;
    }

    public Duration getRefreshTokenGrace() {
        return refreshTokenGrace;
    }

    public void setRefreshTokenGrace(Duration refreshTokenGrace) {
        this.refreshTokenGrace = refreshTokenGrace;
    }

    public Duration getRevokedTokenGrace() {
        return revokedTokenGrace;
    }

    public void setRevokedTokenGrace(Duration revokedTokenGrace) {
        this.revokedTokenGrace = revokedTokenGrace;
    }
}
