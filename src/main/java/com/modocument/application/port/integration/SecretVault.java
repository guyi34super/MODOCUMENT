package com.modocument.application.port.integration;

/**
 * Encrypted storage for DIRECT-mode connection credentials (ws25, SE1).
 * AES-256-GCM under {@code MODOCUMENT_CONNECTOR_KEY} (separate from the MFA
 * key) with a per-bank data key derived by HKDF(bankId) and a key version for
 * rotation. Secrets are write-only through the API/UI: sealed on save, opened
 * only by the flow engine for one call, never returned.
 */
public interface SecretVault {

    Sealed seal(int bankId, SecretMaterial material);

    /** @throws IllegalStateException if the ciphertext is not this bank's or is tampered with */
    SecretMaterial open(int bankId, Sealed sealed);

    /** Stored as {@code SecretCiphertext} + {@code SecretKeyVersion}. */
    record Sealed(String ciphertext, int keyVersion) {
        @Override
        public String toString() {
            return "Sealed[keyVersion=" + keyVersion + "]";
        }
    }
}
