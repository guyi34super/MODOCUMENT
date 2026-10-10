package com.modocument.infrastructure.integration.connector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Outbound webhook signatures (ws25, docs/integration): every POST carries
 * <pre>
 * X-MoDocument-Timestamp: &lt;unix seconds&gt;
 * X-MoDocument-Signature: sha256=&lt;hex HMAC-SHA256(secret, timestamp + "." + body)&gt;
 * </pre>
 * The receiver recomputes the HMAC over the raw body and refuses a
 * timestamp outside a 5-minute window (replay). {@link #verify} is the
 * reference verifier the developer guide documents.
 */
public final class WebhookSigner {

    public static final String TIMESTAMP_HEADER = "X-MoDocument-Timestamp";
    public static final String SIGNATURE_HEADER = "X-MoDocument-Signature";
    public static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);

    private WebhookSigner() {
    }

    /** {@code sha256=<hex>} for the given timestamp and raw body. */
    public static String signature(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable.", e);
        }
    }

    /** The receiver side: constant-time compare plus the replay window. */
    public static boolean verify(String secret, String timestampHeader, String signatureHeader,
                                 byte[] body, Instant now) {
        if (timestampHeader == null || signatureHeader == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long age = Math.abs(now.getEpochSecond() - timestamp);
        if (age > REPLAY_WINDOW.toSeconds()) {
            return false;
        }
        byte[] expected = signature(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signatureHeader.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
