package com.modocument.infrastructure.integration.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.infrastructure.security.AesGcm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connection-secret vault (ws25, {@link SecretVault}): AES-256-GCM under
 * {@code MODOCUMENT_CONNECTOR_KEY}, a key separate from the MFA key.
 *
 * <ul>
 *   <li><b>Per-bank data key</b> — HKDF-SHA256(masterKey,
 *       info = {@code "modocument-connector:" + bankId}), so no two banks
 *       share an encryption key.</li>
 *   <li><b>Bank binding</b> — the associated data is
 *       {@code "modocument-connector:" + bankId + ":v" + keyVersion}: a
 *       ciphertext of bank A cannot be opened as bank B (nor under another
 *       key version), even by someone who could copy database rows between
 *       banks.</li>
 *   <li><b>Format</b> — {@link Sealed#ciphertext()} =
 *       {@code base64(nonce || ciphertext || tag)}; the plaintext is the
 *       {@link SecretMaterial} map as JSON. {@link Sealed#keyVersion()} names
 *       the master key that sealed it.</li>
 *   <li><b>Rotation</b> — {@code modocument.integration.connector-key}
 *       ({@code MODOCUMENT_CONNECTOR_KEY}) is the ACTIVE key, version
 *       {@code modocument.integration.connector-key-version} (default 1);
 *       retired keys stay readable through
 *       {@code modocument.integration.connector-retired-keys}
 *       ({@code "1:<base64>,2:<base64>"}). New seals always use the active
 *       key; {@link #open} uses the version stored with the ciphertext. The
 *       procedure is docs/integration/secrets.md.</li>
 * </ul>
 *
 * <p><b>No key configured</b>:
 * <ul>
 *   <li>staging/production ({@code modocument.settings.enforce-guardrails=true},
 *       {@code MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS}) — the context refuses
 *       to start, like an unset {@code MODOCUMENT_MFA_KEY}. Terraform injects
 *       the key from Secrets Manager there;</li>
 *   <li>otherwise the application still starts (a local stack without DIRECT
 *       connections never needs one), but {@link #seal} and {@link #open}
 *       refuse with an {@link IllegalStateException} naming the variable.
 *       Outside the dev/test profiles ({@code permit-dev-paths} off) that is
 *       logged as a WARN at startup.</li>
 * </ul>
 * A key that IS set but malformed (not base64 of exactly 32 bytes, a
 * duplicate or non-positive version) fails startup in every profile — like a
 * too-short {@code MODOCUMENT_MFA_KEY}.
 *
 * <p>Neither exceptions nor logs ever contain key material or secret values.
 */
@Component
public class ConnectorSecretVault implements SecretVault {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSecretVault.class);

    static final String INFO_PREFIX = "modocument-connector:";

    /** The startup refusal in staging/production; names the variable, never a value. */
    static final String MISSING_KEY_REFUSAL = "Refusing to start: MODOCUMENT_CONNECTOR_KEY is "
            + "not set (modocument.settings.enforce-guardrails=true). Bank integration "
            + "credentials cannot be sealed without it. Generate a key with "
            + "`openssl rand -base64 32`, store it in the secret store and set it in the "
            + "environment (or MODOCUMENT_CONNECTOR_KEY_FILE) before starting the app; see "
            + "docs/integration/secrets.md.";
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<Integer, byte[]> masterKeys;
    private final int activeVersion;
    private final ObjectMapper objectMapper;
    private final Map<Long, SecretKey> bankKeys = new ConcurrentHashMap<>();

    public ConnectorSecretVault(
            @Value("${modocument.integration.connector-key:}") String activeKey,
            @Value("${modocument.integration.connector-key-version:1}") int activeVersion,
            @Value("${modocument.integration.connector-retired-keys:}") String retiredKeys,
            @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths,
            @Value("${modocument.settings.enforce-guardrails:false}") boolean enforceGuardrails,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.activeVersion = activeVersion;
        Map<Integer, byte[]> keys = new TreeMap<>();
        if (activeKey != null && !activeKey.isBlank()) {
            requirePositive(activeVersion, "MODOCUMENT_CONNECTOR_KEY_VERSION");
            keys.put(activeVersion, decodeKey(activeKey, "MODOCUMENT_CONNECTOR_KEY"));
        }
        if (retiredKeys != null && !retiredKeys.isBlank()) {
            for (String entry : retiredKeys.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalStateException("MODOCUMENT_CONNECTOR_RETIRED_KEYS must be a "
                            + "comma-separated list of <version>:<base64 key> entries.");
                }
                int version;
                try {
                    version = Integer.parseInt(trimmed.substring(0, colon).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("MODOCUMENT_CONNECTOR_RETIRED_KEYS has a "
                            + "non-numeric key version.");
                }
                requirePositive(version, "MODOCUMENT_CONNECTOR_RETIRED_KEYS");
                if (keys.containsKey(version)) {
                    throw new IllegalStateException("Connector key version " + version
                            + " is configured twice (active and retired keys must have "
                            + "distinct versions).");
                }
                keys.put(version, decodeKey(trimmed.substring(colon + 1),
                        "MODOCUMENT_CONNECTOR_RETIRED_KEYS (version " + version + ")"));
            }
        }
        this.masterKeys = Collections.unmodifiableMap(keys);
        if (!keys.containsKey(activeVersion) && enforceGuardrails) {
            throw new IllegalStateException(MISSING_KEY_REFUSAL);
        }
        if (!keys.containsKey(activeVersion) && !permitDevPaths) {
            log.warn("MODOCUMENT_CONNECTOR_KEY is not set: integration connection secrets "
                    + "can neither be stored nor used until it is (see docs/integration/secrets.md).");
        }
    }

    /** Whether new secrets can be sealed (the active key is configured). */
    public boolean isConfigured() {
        return masterKeys.containsKey(activeVersion);
    }

    /** The version {@link #seal} stamps on new ciphertexts. */
    public int activeKeyVersion() {
        return activeVersion;
    }

    @Override
    public Sealed seal(int bankId, SecretMaterial material) {
        if (!isConfigured()) {
            throw new IllegalStateException("Connection secrets cannot be stored: "
                    + "MODOCUMENT_CONNECTOR_KEY is not configured.");
        }
        requireBank(bankId);
        byte[] plaintext;
        try {
            plaintext = objectMapper.writeValueAsBytes(
                    new TreeMap<>(material == null ? Map.of() : material.values()));
        } catch (IOException e) {
            throw new IllegalStateException("The connection secret could not be serialised.");
        }
        try {
            AesGcm.Box box = AesGcm.encrypt(bankKey(activeVersion, bankId), plaintext,
                    aad(bankId, activeVersion));
            return new Sealed(Base64.getEncoder().encodeToString(box.concatenated()),
                    activeVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Connection secret encryption failed.");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public SecretMaterial open(int bankId, Sealed sealed) {
        if (sealed == null || sealed.ciphertext() == null || sealed.ciphertext().isBlank()) {
            throw new IllegalStateException("No connection secret is stored.");
        }
        requireBank(bankId);
        if (!masterKeys.containsKey(sealed.keyVersion())) {
            throw new IllegalStateException(masterKeys.isEmpty()
                    ? "Connection secrets cannot be opened: MODOCUMENT_CONNECTOR_KEY is not "
                    + "configured."
                    : "Connector key version " + sealed.keyVersion() + " is not configured "
                    + "(see docs/integration/secrets.md, key rotation).");
        }
        byte[] plaintext = null;
        try {
            AesGcm.Box box = AesGcm.Box.split(Base64.getDecoder().decode(sealed.ciphertext()));
            plaintext = AesGcm.decrypt(bankKey(sealed.keyVersion(), bankId), box,
                    aad(bankId, sealed.keyVersion()));
            return new SecretMaterial(objectMapper.readValue(plaintext, MAP_TYPE));
        } catch (GeneralSecurityException | IllegalArgumentException | IOException e) {
            // Wrong bank, wrong version, tampered or truncated: one answer.
            throw new IllegalStateException("The connection secret could not be opened "
                    + "(it is not this bank's, or it was altered).");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private SecretKey bankKey(int version, int bankId) {
        long cacheKey = ((long) version << 32) | (bankId & 0xffffffffL);
        return bankKeys.computeIfAbsent(cacheKey, ignored -> {
            byte[] derived = AesGcm.hkdfSha256(masterKeys.get(version),
                    (INFO_PREFIX + bankId).getBytes(StandardCharsets.UTF_8), AesGcm.KEY_BYTES);
            try {
                return AesGcm.key(derived);
            } finally {
                Arrays.fill(derived, (byte) 0);
            }
        });
    }

    static byte[] aad(int bankId, int version) {
        return (INFO_PREFIX + bankId + ":v" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireBank(int bankId) {
        if (bankId <= 0) {
            throw new IllegalArgumentException("A connection secret belongs to a bank.");
        }
    }

    private static void requirePositive(int version, String variable) {
        if (version <= 0) {
            throw new IllegalStateException(variable + ": key versions are positive integers.");
        }
    }

    private static byte[] decodeKey(String value, String variable) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(variable + " is not valid base64. Generate a key "
                    + "with `openssl rand -base64 32`.");
        }
        if (key.length != AesGcm.KEY_BYTES) {
            throw new IllegalStateException(variable + " must be base64 of exactly "
                    + AesGcm.KEY_BYTES + " bytes (`openssl rand -base64 32`); it decodes to "
                    + key.length + ".");
        }
        return key;
    }
}
