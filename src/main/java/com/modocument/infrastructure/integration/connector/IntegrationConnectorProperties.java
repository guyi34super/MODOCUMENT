package com.modocument.infrastructure.integration.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code modocument.integration.connectors.*} (ws25): the connectors' fixed
 * safety limits — infrastructure wiring, not editable settings. Every value
 * has a safe default, so the block may be absent.
 */
@ConfigurationProperties(prefix = "modocument.integration.connectors")
public class IntegrationConnectorProperties {

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long maxFileBytes = 50L * 1024 * 1024;
    private long maxResponseBytes = 20L * 1024 * 1024;
    /** Plain http:// targets; off in every deployed environment. */
    private boolean allowPlainHttp;

    public ConnectorLimits limits() {
        return new ConnectorLimits(connectTimeout, readTimeout, maxFileBytes, maxResponseBytes,
                allowPlainHttp);
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public boolean isAllowPlainHttp() {
        return allowPlainHttp;
    }

    public void setAllowPlainHttp(boolean allowPlainHttp) {
        this.allowPlainHttp = allowPlainHttp;
    }
}
