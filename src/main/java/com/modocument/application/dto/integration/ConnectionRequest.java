package com.modocument.application.dto.integration;

import java.util.Map;

/**
 * Create/update body of a connection (ws25, contracts §7.3). {@code secret}
 * holds the SecretMaterial keys (password, token, apiKey, clientSecret,
 * clientCertPem, clientKeyPem, privateKeyPem, passphrase, hmacSecret, ...);
 * omitted on PUT it keeps the stored secret. It is sealed by the
 * SecretVault at submit and never echoed — {@link #toString()} redacts it.
 */
public record ConnectionRequest(String name, String type, String mode, Integer agentId,
                                Map<String, Object> config, String authType,
                                Map<String, String> secret, String secretRef,
                                Integer expectedVersion) {

    @Override
    public String toString() {
        return "ConnectionRequest[name=" + name + ", type=" + type + ", mode=" + mode
                + ", authType=" + authType + ", secret=" + (secret == null ? "absent" : "(redacted)")
                + "]";
    }
}
