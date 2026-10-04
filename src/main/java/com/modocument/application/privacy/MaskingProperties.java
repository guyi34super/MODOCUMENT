package com.modocument.application.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment switches for the masking matrix (ws12, PRIV-005). Only the
 * SCREEN_WORKING channel is configurable — every other channel's stance is
 * fixed in {@link MaskingPolicy} (reports, exports and read-only screens are
 * always masked; DSR disclosure is never masked).
 */
@ConfigurationProperties(prefix = "modocument.privacy.masking")
public class MaskingProperties {

    /**
     * Force-mask the owner's own editing wizard (the SCREEN_WORKING channel).
     * Off by default: cell editing needs the true values — the product's
     * core workflow (pre-release justification documented in
     * docs/security/masking-matrix.md). A deployment that reviews only
     * pre-masked data can turn it on.
     */
    private boolean screenWorking = false;

    public boolean isScreenWorking() {
        return screenWorking;
    }

    public void setScreenWorking(boolean screenWorking) {
        this.screenWorking = screenWorking;
    }
}
