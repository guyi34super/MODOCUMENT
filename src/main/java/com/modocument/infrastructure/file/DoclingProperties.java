package com.modocument.infrastructure.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for an optional docling-serve instance. When base-url is empty the
 * application parses documents with the built-in readers only.
 */
@ConfigurationProperties(prefix = "docling")
public class DoclingProperties {

    private String baseUrl = "";
    private String convertPath = "/v1/convert/file";
    private int timeoutSeconds = 120;

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getConvertPath() {
        return convertPath;
    }

    public void setConvertPath(String convertPath) {
        this.convertPath = convertPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
