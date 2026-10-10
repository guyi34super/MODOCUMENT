package com.modocument.domain.model.settings;

/**
 * The settings screen's tabs (ws20), in display order. The id is the stable
 * API identifier ({@code groups[].id}); the label is the default UI text.
 */
public enum SettingGroup {
    SIGN_IN("signIn", "Sign-in & passwords"),
    RATE_LIMITS("rateLimits", "Rate limits"),
    DATA_PROTECTION("dataProtection", "Data protection"),
    SECURITY_ALERTS("securityAlerts", "Security alerts"),
    AI_EXTRACTION("aiExtraction", "AI & extraction"),
    INTEGRATIONS("integrations", "Integrations");

    private final String id;
    private final String label;

    SettingGroup(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
