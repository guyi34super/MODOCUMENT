package com.modocument.application.privacy;

import java.util.regex.Pattern;

/**
 * Shared PII/credential value-shape patterns and the one-way scrub used by
 * everything that writes text a human might later read in bulk: the logback
 * redaction converters (human pattern and structured JSON output, ws13) and
 * the persisted exception-text columns ({@code IngestionJob.ErrorMessage},
 * {@code ExtractionTask.LastError}, {@code ExtractionAuditEvent.Detail}) via
 * {@link LogSafe#scrub(String)}.
 *
 * <p>The value shapes are aligned with {@link PiiTokenizer}'s detection
 * knowledge (email shape, long digit runs covering its CARD/NINE_DIGIT_ID
 * families) and extend it with the identifiers and credentials that matter
 * for logs: Mauritius NIC formats, JWTs, Bearer headers, key=value credential
 * shapes and the refresh-cookie value. {@code PiiTokenizer} (ws12) can adopt
 * these constants instead of its private copies; this class deliberately only
 * ADDS a file and leaves the tokenizer untouched.
 *
 * <p>Deliberately NOT matched, to keep log utility (gate E9): UUIDs/request
 * ids (digit runs adjacent to a hyphen or letters never match), small
 * integers such as user/bank/job ids (digit runs shorter than 9), timestamps
 * (fraction-of-second runs follow a dot) and ordinary log prose.
 */
public final class PiiPatterns {

    /** Greppable replacement marker: {@code grep -F '«redacted»' app.log}. */
    public static final String MASK = "«redacted»";

    /**
     * {@code Authorization: Bearer <token>} — the whole credential after the
     * scheme. Applied FIRST, before {@link #CREDENTIAL_KV}, which would
     * otherwise stop at the first space and leave the token itself behind.
     */
    public static final Pattern BEARER =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}");

    /**
     * {@code password=...}, {@code token: ...}, {@code "client_secret":"..."}
     * and friends, including our refresh cookie ({@code modoc_refresh=...},
     * see AuthController.REFRESH_COOKIE). Group 1 is the key and separator,
     * kept so the line stays diagnosable; the value is replaced.
     */
    public static final Pattern CREDENTIAL_KV = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|pwd|passphrase|secret|token|api[_-]?key"
                    + "|client[_-]?secret|authorization|credential|modoc_refresh)[\"']?"
                    + "\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)");

    /** JWT-looking strings: base64url {@code eyJ…} header, 1–3 dot-joined parts. */
    public static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}(?:\\.[A-Za-z0-9_-]+){0,2}");

    /**
     * Email addresses: the local part is masked down to its first character,
     * the domain survives ({@code j***@example.com}) so support can still see
     * which tenant/provider a line is about. Character classes match
     * {@link PiiTokenizer}'s EMAIL pattern.
     */
    public static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /**
     * Mauritius NIC shapes and letter-prefixed account-like ids: one letter,
     * nine or more digits, optional check letter. Covers the modern NIC
     * (letter + 12 digits + check character, e.g. {@code M090976290148E}),
     * the letter+13-digit form and shorter letter-prefixed 10–12 character
     * ids. The boundaries refuse alphanumeric neighbours so hex hashes and
     * UUID segments never match.
     */
    public static final Pattern NIC =
            Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]\\d{9,}[A-Za-z]?(?![A-Za-z0-9])");

    /**
     * Bare account-number runs: 9+ consecutive digits (unifies
     * {@link PiiTokenizer}'s CARD and NINE_DIGIT_ID families for log text).
     * A run adjacent to a letter, hyphen, dot or underscore is left alone —
     * that is how UUIDs, request ids, timestamps' fractional seconds and
     * machine identifiers stay readable.
     */
    public static final Pattern ACCOUNT =
            Pattern.compile("(?<![0-9A-Za-z._-])\\d{9,}(?![0-9A-Za-z-])");

    private PiiPatterns() {
    }

    /**
     * One-way masking of every known PII/credential shape. Null-safe and
     * idempotent; order matters (Bearer before the key=value shape, emails
     * before the bare digit runs).
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = BEARER.matcher(result).replaceAll("Bearer " + MASK);
        result = CREDENTIAL_KV.matcher(result).replaceAll("$1" + MASK);
        result = JWT.matcher(result).replaceAll(MASK);
        result = EMAIL.matcher(result).replaceAll("$1***$2");
        result = NIC.matcher(result).replaceAll(MASK);
        result = ACCOUNT.matcher(result).replaceAll(MASK);
        return result;
    }
}
