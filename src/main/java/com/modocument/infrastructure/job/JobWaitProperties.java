package com.modocument.infrastructure.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Long-poll settings for job completion endpoints. */
@ConfigurationProperties(prefix = "modocument.job")
public class JobWaitProperties {

    private int waitTimeoutSeconds = 900;
    private int pollIntervalMs = 500;

    public int getWaitTimeoutSeconds() {
        return waitTimeoutSeconds;
    }

    public void setWaitTimeoutSeconds(int waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

    public int getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
