package com.modocument.domain.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 content hashing for extraction dedup keys and upload duplicates. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    /**
     * Streaming hash for uploads: never materializes the whole file in memory
     * (uploads may be up to the multipart limit). The stream is read to its
     * end but deliberately not closed — the caller owns it.
     */
    public static String sha256(InputStream content) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = content.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read content to hash.", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }
}
