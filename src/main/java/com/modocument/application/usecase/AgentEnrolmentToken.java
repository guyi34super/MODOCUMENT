package com.modocument.application.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-time enrolment token of a Connector Agent (ws26):
 * {@code mda1.<agentId>.<secret>}, the secret being 32 random bytes in
 * base64url (43 characters). The agent id lets the enrolment find the row
 * (and its bank) without a hash index; the secret is the credential —
 * only {@link #hash(String) its SHA-256} is stored and compared in constant
 * time ({@link #matches}). SHA-256 without a salt or a slow KDF is enough
 * for 256 bits of randomness that live 15 minutes.
 *
 * <p>{@link #toString()} never prints the secret.
 */
public record AgentEnrolmentToken(int agentId, String secret) {

    static final String PREFIX = "mda1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT =
            Pattern.compile("^mda1\\.([1-9][0-9]{0,9})\\.([A-Za-z0-9_-]{43})$");

    /** A fresh token for the agent. */
    public static AgentEnrolmentToken generate(int agentId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new AgentEnrolmentToken(agentId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Parses a presented token; empty for anything that is not exactly the format. */
    public static Optional<AgentEnrolmentToken> parse(String presented) {
        if (presented == null || presented.length() > 64) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(presented.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        long id = Long.parseLong(m.group(1));
        if (id > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new AgentEnrolmentToken((int) id, m.group(2)));
    }

    /** SHA-256 of a secret, lower-case hex (the stored EnrolmentTokenHash). */
    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of this token's secret with a stored hash. */
    public boolean matches(String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** The token as the maker copies it. */
    public String value() {
        return PREFIX + "." + agentId + "." + secret;
    }

    @Override
    public String toString() {
        return "AgentEnrolmentToken[agentId=" + agentId + "]";
    }
}
