package com.modocument.connector.core.support;

import java.util.regex.Pattern;

/**
 * The credential shapes a connector error message must never carry (driver
 * and HTTP client messages can quote what they were given): a Bearer
 * credential, {@code password=...}-style key/value pairs and JWTs. The same
 * patterns as the server's {@code PiiPatterns} for these three shapes; the
 * server applies its full PII scrub on top ({@code ConnectorErrors} there).
 */
public final class Redaction {

    public static final String MASK = "«redacted»";

    private static final Pattern BEARER =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}");
    private static final Pattern CREDENTIAL_KV = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|pwd|passphrase|secret|token|api[_-]?key"
                    + "|client[_-]?secret|authorization|credential)[\"']?"
                    + "\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)");
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}(?:\\.[A-Za-z0-9_-]+){0,2}");

    private Redaction() {
    }

    /** Masks credential shapes; null-safe and idempotent. */
    public static String credentials(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = BEARER.matcher(text).replaceAll("Bearer " + MASK);
        result = CREDENTIAL_KV.matcher(result).replaceAll("$1" + MASK);
        return JWT.matcher(result).replaceAll(MASK);
    }
}
