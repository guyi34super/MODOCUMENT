package com.modocument.infrastructure.integration.secrets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault.Sealed;
import com.modocument.infrastructure.security.AesGcm;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ws25 connector secret vault: AES-256-GCM, per-bank HKDF keys, bank-bound
 * associated data, key versions for rotation, refusal without a key.
 */
class ConnectorSecretVaultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecretMaterial MATERIAL =
            new SecretMaterial(Map.of("password", "hunter2hunter2", "username", "svc_modoc"));

    /** Test keys are generated patterns, not literals that look like credentials. */
    private static String key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static ConnectorSecretVault vault(String active, int version, String retired) {
        return new ConnectorSecretVault(active, version, retired, false, false, MAPPER);
    }

    @Test
    void sealsAndOpensForTheSameBank() {
        ConnectorSecretVault vault = vault(key(7), 1, "");
        Sealed sealed = vault.seal(42, MATERIAL);

        assertThat(sealed.keyVersion()).isEqualTo(1);
        assertThat(sealed.ciphertext()).doesNotContain("hunter2").doesNotContain("password");
        assertThat(vault.open(42, sealed).values()).isEqualTo(MATERIAL.values());
        // Sealed never prints the ciphertext.
        assertThat(sealed.toString()).doesNotContain(sealed.ciphertext());
    }

    @Test
    void ciphertextIsBase64OfNonceCiphertextAndTag() throws Exception {
        ConnectorSecretVault vault = vault(key(7), 1, "");
        byte[] raw = Base64.getDecoder().decode(vault.seal(42, MATERIAL).ciphertext());
        int json = MAPPER.writeValueAsBytes(new java.util.TreeMap<>(MATERIAL.values())).length;

        assertThat(raw).hasSize(AesGcm.NONCE_BYTES + json + AesGcm.TAG_BITS / 8);
    }

    @Test
    void everySealUsesAFreshNonce() {
        ConnectorSecretVault vault = vault(key(7), 1, "");

        assertThat(vault.seal(42, MATERIAL).ciphertext())
                .isNotEqualTo(vault.seal(42, MATERIAL).ciphertext());
    }

    @Test
    void aCiphertextOfBankACannotBeOpenedAsBankB() {
        ConnectorSecretVault vault = vault(key(7), 1, "");
        Sealed sealedForA = vault.seal(10, MATERIAL);

        assertThatThrownBy(() -> vault.open(11, sealedForA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void aRelabelledKeyVersionDoesNotOpen() {
        ConnectorSecretVault vault = vault(key(7), 2, "1:" + key(7));
        Sealed sealed = vault.seal(10, MATERIAL);
        // Same master bytes under version 1 — the version is bound as AAD.
        assertThatThrownBy(() -> vault.open(10, new Sealed(sealed.ciphertext(), 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedOrTruncatedCiphertextIsRefused() {
        ConnectorSecretVault vault = vault(key(7), 1, "");
        Sealed sealed = vault.seal(10, MATERIAL);
        byte[] raw = Base64.getDecoder().decode(sealed.ciphertext());
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> vault.open(10,
                new Sealed(Base64.getEncoder().encodeToString(raw), 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.open(10, new Sealed("AAAA", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.open(10, new Sealed("%%not base64%%", 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotationKeepsOldCiphertextsReadableAndSealsWithTheActiveVersion() {
        ConnectorSecretVault before = vault(key(1), 1, "");
        Sealed old = before.seal(5, MATERIAL);

        ConnectorSecretVault after = vault(key(2), 2, "1:" + key(1));
        assertThat(after.open(5, old).values()).isEqualTo(MATERIAL.values());
        Sealed fresh = after.seal(5, MATERIAL);
        assertThat(fresh.keyVersion()).isEqualTo(2);
        assertThat(after.open(5, fresh).values()).isEqualTo(MATERIAL.values());

        // Once the retired key is dropped, version-1 ciphertexts no longer open.
        ConnectorSecretVault dropped = vault(key(2), 2, "");
        assertThatThrownBy(() -> dropped.open(5, old))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 1");
    }

    @Test
    void anotherMasterKeyCannotOpen() {
        Sealed sealed = vault(key(1), 1, "").seal(5, MATERIAL);

        assertThatThrownBy(() -> vault(key(3), 1, "").open(5, sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeySealAndOpenRefuseClearly() {
        ConnectorSecretVault vault = new ConnectorSecretVault("", 1, "", true, false, MAPPER);

        assertThat(vault.isConfigured()).isFalse();
        assertThatThrownBy(() -> vault.seal(5, MATERIAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_CONNECTOR_KEY");
        assertThatThrownBy(() -> vault.open(5, new Sealed("AAAA", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_CONNECTOR_KEY");
        // Production-like (permit-dev-paths off) still boots: it only warns.
        assertThat(new ConnectorSecretVault("", 1, "", false, false, MAPPER).isConfigured()).isFalse();
    }

    @Test
    void stagingAndProductionRefuseToStartWithoutAKey() {
        // MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true (staging/production):
        // no active key is a refused boot, with or without retired keys.
        for (String retired : new String[] {"", "1:" + key(3)}) {
            int activeVersion = retired.isEmpty() ? 1 : 2;
            assertThatThrownBy(() -> new ConnectorSecretVault(
                    "", activeVersion, retired, false, true, MAPPER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Refusing to start: MODOCUMENT_CONNECTOR_KEY is not set")
                    .hasMessageContaining("enforce-guardrails")
                    .hasMessageContaining("openssl rand -base64 32");
        }
        // A blank value is the same as unset.
        assertThatThrownBy(() -> new ConnectorSecretVault("  ", 1, "", false, true, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ConnectorSecretVault.MISSING_KEY_REFUSAL);
        // With a valid key the guardrail is satisfied.
        assertThat(new ConnectorSecretVault(key(4), 1, "", false, true, MAPPER).isConfigured())
                .isTrue();
        // Malformed keys still fail on their own, more specific message.
        assertThatThrownBy(() -> new ConnectorSecretVault("short", 1, "", false, true, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Refusing to start");
    }

    @Test
    void malformedKeysFailAtStartup() {
        assertThatThrownBy(() -> vault("not base64 !!", 1, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_CONNECTOR_KEY");
        assertThatThrownBy(() -> vault(Base64.getEncoder().encodeToString(new byte[16]), 1, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> vault(key(1), 0, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault(key(1), 1, "1:" + key(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> vault(key(1), 2, "x:" + key(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault(key(1), 2, key(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEmptyMaterialRoundTrips() {
        ConnectorSecretVault vault = vault(key(7), 1, "");

        assertThat(vault.open(3, vault.seal(3, SecretMaterial.EMPTY)).isEmpty()).isTrue();
    }

    /** RFC 5869 test case 3 (no salt = HashLen zero bytes, empty info). */
    @Test
    void hkdfMatchesTheRfc5869Vector() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);

        assertThat(HexFormat.of().formatHex(AesGcm.hkdfSha256(ikm, new byte[0], 42)))
                .isEqualTo("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"
                        + "9d201395faa4b61a96c8");
    }
}
