package com.modocument.application.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Privacy controls for the LLM features. Fail closed: PII tokenization is on
 * unless explicitly disabled, so sensitive cell values never leave the
 * backend in plaintext by default.
 */
@ConfigurationProperties(prefix = "aifiles.privacy")
public class PrivacyProperties {

    /** Replace sensitive values with placeholders before any LLM prompt. */
    private boolean tokenizePii = true;

    /**
     * Extra field names to always treat as sensitive, matched
     * case/punctuation-insensitively as substrings (e.g. "badge" matches
     * "BadgeNumber").
     */
    private List<String> extraSensitiveFields = new ArrayList<>();

    public boolean isTokenizePii() {
        return tokenizePii;
    }

    public void setTokenizePii(boolean tokenizePii) {
        this.tokenizePii = tokenizePii;
    }

    public List<String> getExtraSensitiveFields() {
        return extraSensitiveFields;
    }

    public void setExtraSensitiveFields(List<String> extraSensitiveFields) {
        this.extraSensitiveFields = extraSensitiveFields;
    }
}
