package com.modocument.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts TOTP secrets at rest (SEC-014): AES-256-GCM with a fresh random
 * 96-bit IV per encryption, stored alongside the ciphertext as
 * {@code base64(iv) ':' base64(ciphertext)}. The 256-bit key is derived
 * (SHA-256, domain-separated) from {@code MODOCUMENT_MFA_KEY}, which must be
 * at least 32 bytes in production — the app refuses to start otherwise.
 * Under {@code modocument.security.permit-dev-paths} (local/test profiles)
 * an unset key falls back to a derivation from the JWT signing secret, with
 * a loud warning: convenient for dev databases, never acceptable for real
 * ones because a JWT-secret leak would then also unlock every MFA secret.
 */
@Component
public class MfaSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(MfaSecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public MfaSecretCipher(
            @Value("${modocument.security.mfa.key:}") String mfaKey,
            @Value("${modocument.security.jwt.secret}") String jwtSecret,
            @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths) {
        String material;
        if (mfaKey != null && !mfaKey.isBlank()) {
            if (mfaKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
                throw new IllegalStateException(
                        "MODOCUMENT_MFA_KEY is too short: at least " + MIN_KEY_BYTES
                                + " bytes are required (e.g. `openssl rand -base64 48`).");
            }
            material = mfaKey;
        } else if (permitDevPaths) {
            log.warn("MODOCUMENT_MFA_KEY is not set; deriving the MFA secret-encryption key "
                    + "from the JWT secret (dev/test profile ONLY — set a dedicated key in "
                    + "any real deployment).");
            material = jwtSecret;
        } else {
            throw new IllegalStateException(
                    "MODOCUMENT_MFA_KEY is not set. Generate a key of at least " + MIN_KEY_BYTES
                            + " bytes (e.g. `openssl rand -base64 48`) and set it in the "
                            + "environment before starting the app.");
        }
        this.key = new SecretKeySpec(
                sha256("modocument-mfa-secret-encryption:" + material), "AES");
    }

    /** @return {@code base64(iv) ':' base64(ciphertext+tag)} */
    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA secret encryption failed.", e);
        }
    }

    /**
     * @throws IllegalStateException on a malformed or tampered ciphertext
     *     (GCM authentication failure) — never returns garbage
     */
    public String decrypt(String stored) {
        int separator = stored == null ? -1 : stored.indexOf(':');
        if (separator <= 0) {
            throw new IllegalStateException("Malformed MFA secret ciphertext.");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(stored.substring(0, separator));
            byte[] ciphertext = Base64.getDecoder().decode(stored.substring(separator + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("MFA secret decryption failed.", e);
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }
}
