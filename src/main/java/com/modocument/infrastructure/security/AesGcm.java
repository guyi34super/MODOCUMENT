package com.modocument.infrastructure.security;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The one AES-256-GCM primitive of the application, parameterised by key and
 * associated data: a fresh random 96-bit nonce per encryption and a 128-bit
 * tag. Used by {@link MfaSecretCipher} (TOTP secrets, no associated data) and
 * the connector {@code SecretVault} (bank-bound associated data). Callers
 * choose the storage format; this class only moves bytes.
 *
 * <p>Also hosts HKDF-SHA256 (RFC 5869) for deriving sub-keys from a master
 * key with a domain-separating {@code info}.
 */
public final class AesGcm {

    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    /** A nonce and the ciphertext with the tag appended (as the JCE returns it). */
    public record Box(byte[] nonce, byte[] ciphertextWithTag) {

        /** {@code nonce || ciphertext || tag}. */
        public byte[] concatenated() {
            byte[] out = new byte[nonce.length + ciphertextWithTag.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertextWithTag, 0, out, nonce.length, ciphertextWithTag.length);
            return out;
        }

        /** Splits {@code nonce || ciphertext || tag}; too short to hold a tag → IllegalArgumentException. */
        public static Box split(byte[] concatenated) {
            if (concatenated == null || concatenated.length < NONCE_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException("Ciphertext too short.");
            }
            return new Box(Arrays.copyOfRange(concatenated, 0, NONCE_BYTES),
                    Arrays.copyOfRange(concatenated, NONCE_BYTES, concatenated.length));
        }
    }

    /** An AES key from exactly {@value #KEY_BYTES} bytes. */
    public static SecretKey key(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("An AES-256 key is exactly " + KEY_BYTES + " bytes.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts with a fresh random nonce.
     *
     * @param aad associated data bound to the ciphertext (null for none)
     * @throws GeneralSecurityException only on a broken JCE / bad key
     */
    public static Box encrypt(SecretKey key, byte[] plaintext, byte[] aad)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return new Box(nonce, cipher.doFinal(plaintext));
    }

    /**
     * Decrypts and authenticates.
     *
     * @throws AEADBadTagException on a wrong key, wrong associated data or a
     *     tampered ciphertext — never returns garbage
     */
    public static byte[] decrypt(SecretKey key, Box box, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, box.nonce()));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(box.ciphertextWithTag());
    }

    /**
     * HKDF-SHA256 (RFC 5869) with an all-zero salt: extract, then expand to
     * {@code length} bytes under {@code info}.
     */
    public static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] info, int length) {
        try {
            Mac extract = Mac.getInstance("HmacSHA256");
            extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = extract.doFinal(inputKeyMaterial);
            Mac expand = Mac.getInstance("HmacSHA256");
            expand.init(new SecretKeySpec(prk, "HmacSHA256"));
            ByteArrayOutputStream out = new ByteArrayOutputStream(length);
            byte[] previous = new byte[0];
            int counter = 1;
            while (out.size() < length) {
                expand.update(previous);
                expand.update(info);
                expand.update((byte) counter++);
                previous = expand.doFinal();
                out.write(previous, 0, Math.min(previous.length, length - out.size()));
            }
            Arrays.fill(prk, (byte) 0);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 unavailable.", e);
        }
    }
}
