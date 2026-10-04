package com.modocument.application.privacy;

import com.modocument.domain.model.PiiKind;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The masking engine (ws12, gate C2, PRIV-005): HOW each {@link PiiKind}
 * masks. WHETHER a value masks on a given read is {@link MaskingPolicy}'s
 * decision (channel × role); this class only transforms values.
 *
 * <p>Rules (mirrored in docs/security/masking-matrix.md):
 * <ul>
 *   <li>{@link PiiKind#ACCOUNT_NUMBER} — last four characters kept:
 *       {@code ****1234}.</li>
 *   <li>{@link PiiKind#NIC} — fully masked: {@code ********} (fixed width,
 *       so the mask does not even leak the id's length or format).</li>
 *   <li>{@link PiiKind#EMAIL} — local part down to its first character,
 *       domain kept: {@code j***@example.com}.</li>
 *   <li>{@link PiiKind#PHONE} — last two characters kept: {@code ****99}.</li>
 *   <li>{@link PiiKind#NAME} — first letter kept: {@code A***}.</li>
 *   <li>{@link PiiKind#NONE} / unclassified — untouched.</li>
 * </ul>
 *
 * <p>Every rule is null-safe and idempotent: masking an already-masked value
 * is a no-op, so a value that travels through two masked channels (an export
 * of a masked screen copy, a re-render) never degrades further and never
 * double-masks.
 */
public final class Masker {

    /** The full mask for values that keep nothing (NIC). */
    public static final String FULL_MASK = "********";

    /** An already-masked prefix-keep value: asterisks + up to 4 kept chars. */
    private static final Pattern ALREADY_MASKED = Pattern.compile("\\*+[^*]{0,4}");

    /** Already-masked email local part: one kept char + {@code ***}. */
    private static final Pattern MASKED_EMAIL = Pattern.compile("[^@*]\\*{3}@.+");

    private Masker() {
    }

    /** Masks one value per its kind; null/blank values and NONE pass through. */
    public static String mask(PiiKind kind, String value) {
        if (kind == null || kind == PiiKind.NONE || value == null || value.isBlank()) {
            return value;
        }
        return switch (kind) {
            case NIC -> maskAll(value);
            case ACCOUNT_NUMBER -> keepLast(value, 4);
            case EMAIL -> maskEmail(value);
            case PHONE -> keepLast(value, 2);
            case NAME -> maskName(value);
            case NONE -> value;
        };
    }

    /**
     * One-way masking of every PII value SHAPE embedded in free text — the
     * REPORT channel's form, where there are no field names to classify by
     * (a generated document's paragraphs and captions). NIC shapes mask
     * fully, bare 9+ digit account runs keep their last four, emails keep
     * their first character and domain ({@link PiiPatterns} supplies the
     * shapes). Null-safe and idempotent: the masked forms no longer match
     * any shape.
     */
    public static String maskShapes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = PiiPatterns.EMAIL.matcher(text).replaceAll("$1***$2");
        result = PiiPatterns.NIC.matcher(result).replaceAll(FULL_MASK);
        Matcher accounts = PiiPatterns.ACCOUNT.matcher(result);
        StringBuilder out = new StringBuilder();
        while (accounts.find()) {
            String run = accounts.group();
            accounts.appendReplacement(out,
                    Matcher.quoteReplacement("****" + run.substring(run.length() - 4)));
        }
        accounts.appendTail(out);
        return out.toString();
    }

    private static String maskAll(String value) {
        return allAsterisks(value) ? value : FULL_MASK;
    }

    private static String keepLast(String value, int kept) {
        if (allAsterisks(value) || ALREADY_MASKED.matcher(value).matches()) {
            return value;
        }
        if (value.length() <= kept) {
            return "****";
        }
        return "****" + value.substring(value.length() - kept);
    }

    private static String maskEmail(String value) {
        if (MASKED_EMAIL.matcher(value).matches()) {
            return value;
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            // No usable local part — mask like a name so nothing leaks.
            return maskName(value);
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    private static boolean allAsterisks(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c == '*');
    }

    private static String maskName(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        char first = trimmed.charAt(0);
        if (first == '*') {
            return value; // already masked
        }
        return trimmed.length() == 4 && trimmed.endsWith("***")
                ? value
                : first + "***";
    }
}
