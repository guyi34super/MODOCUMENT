package com.modocument.domain.model.settings;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text ⇄ value conversions for settings (ws20). One canonical text form per
 * type is what BankSetting stores and what the API shows for durations:
 * <ul>
 *   <li>INT — {@code "12"}; DECIMAL — {@code "0.75"} (no trailing zeros);</li>
 *   <li>BOOLEAN — {@code "true"}/{@code "false"}; ENUM — the constant;</li>
 *   <li>DURATION — the largest whole unit: {@code "12h"}, {@code "15m"},
 *       {@code "90s"}, {@code "250ms"}, {@code "1d"}.</li>
 * </ul>
 * Parsing accepts the canonical forms plus ISO-8601 durations
 * ({@code "PT15M"}); a bare number is a duration only where the caller names
 * its unit (a yml property such as {@code window-millis}).
 */
public final class SettingValues {

    private static final Pattern SIMPLE_DURATION =
            Pattern.compile("^(\\d{1,12})\\s*(ms|s|m|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d{1,15}$");

    private SettingValues() {
    }

    /**
     * Parses text as a value of {@code type}. ENUM text is returned trimmed
     * and unchecked — membership is the definition's business
     * ({@link SettingDefinition#parse}).
     *
     * @throws IllegalArgumentException when the text is not a value of the type
     */
    public static Object parse(SettingType type, String text) {
        return parse(type, text, null);
    }

    /**
     * {@link #parse(SettingType, String)}, where a bare number is a DURATION
     * in {@code bareNumberUnit} (null: bare numbers are refused).
     */
    public static Object parse(SettingType type, String text, ChronoUnit bareNumberUnit) {
        Objects.requireNonNull(type, "type");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A value is required.");
        }
        String trimmed = text.trim();
        switch (type) {
            case INT -> {
                try {
                    return Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + abbreviate(trimmed)
                            + "' is not a whole number.");
                }
            }
            case DECIMAL -> {
                try {
                    return normalize(new BigDecimal(trimmed));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + abbreviate(trimmed)
                            + "' is not a decimal number.");
                }
            }
            case BOOLEAN -> {
                if (trimmed.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                }
                if (trimmed.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("'" + abbreviate(trimmed)
                        + "' is not true or false.");
            }
            case ENUM -> {
                return trimmed;
            }
            case DURATION -> {
                return parseDuration(trimmed, bareNumberUnit);
            }
            default -> throw new IllegalStateException("Unhandled type " + type);
        }
    }

    /**
     * A duration from {@code "15m"}-style or ISO-8601 text; a bare number
     * only when {@code bareNumberUnit} names its unit. Negative durations are
     * refused.
     */
    public static Duration parseDuration(String text, ChronoUnit bareNumberUnit) {
        String trimmed = text == null ? "" : text.trim();
        Duration duration;
        Matcher simple = SIMPLE_DURATION.matcher(trimmed);
        if (simple.matches()) {
            long amount = Long.parseLong(simple.group(1));
            duration = switch (simple.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable");
            };
        } else if (BARE_NUMBER.matcher(trimmed).matches()) {
            if (bareNumberUnit == null) {
                throw new IllegalArgumentException("'" + abbreviate(trimmed)
                        + "' needs a unit, e.g. 30s, 15m or 12h.");
            }
            duration = Duration.of(Long.parseLong(trimmed), bareNumberUnit);
        } else {
            try {
                duration = Duration.parse(trimmed.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("'" + abbreviate(trimmed)
                        + "' is not a duration; use e.g. 30s, 15m or 12h.");
            }
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("A duration cannot be negative.");
        }
        return duration;
    }

    /** The canonical text of a value of {@code type}. */
    public static String format(SettingType type, Object value) {
        Objects.requireNonNull(value, "value");
        return switch (type) {
            case INT -> String.valueOf((Integer) value);
            case DECIMAL -> normalize((BigDecimal) value).toPlainString();
            case BOOLEAN -> String.valueOf((Boolean) value);
            case ENUM -> (String) value;
            case DURATION -> formatDuration((Duration) value);
        };
    }

    /** Orders two values of a ranged type (INT, DECIMAL, DURATION). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(SettingType type, Object a, Object b) {
        if (!type.isRanged()) {
            throw new IllegalArgumentException(type + " values are not ordered.");
        }
        return ((Comparable) a).compareTo(b);
    }

    /** Value equality per type (DECIMAL ignores scale: 0.7 equals 0.70). */
    public static boolean same(SettingType type, Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return type.isRanged() ? compare(type, a, b) == 0 : a.equals(b);
    }

    /** The larger of two optional bounds (null = unbounded). */
    public static Object maxOf(SettingType type, Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return compare(type, a, b) >= 0 ? a : b;
    }

    /** The smaller of two optional bounds (null = unbounded). */
    public static Object minOf(SettingType type, Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return compare(type, a, b) <= 0 ? a : b;
    }

    /** {@code value} moved into [lo, hi]; either bound may be null (unbounded). */
    public static Object clamp(SettingType type, Object value, Object lo, Object hi) {
        if (!type.isRanged()) {
            return value;
        }
        Object result = value;
        if (lo != null && compare(type, result, lo) < 0) {
            result = lo;
        }
        if (hi != null && compare(type, result, hi) > 0) {
            result = hi;
        }
        return result;
    }

    static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // 0.00 strips to 0E-2 on some JDKs; keep a plain zero.
        return stripped.signum() == 0 ? BigDecimal.ZERO : stripped;
    }

    static String formatDuration(Duration duration) {
        if (duration.getNano() % 1_000_000 != 0) {
            return duration.toString();
        }
        long millis = duration.toMillis();
        if (millis == 0) {
            return "0s";
        }
        if (millis % 86_400_000L == 0) {
            return (millis / 86_400_000L) + "d";
        }
        if (millis % 3_600_000L == 0) {
            return (millis / 3_600_000L) + "h";
        }
        if (millis % 60_000L == 0) {
            return (millis / 60_000L) + "m";
        }
        if (millis % 1_000L == 0) {
            return (millis / 1_000L) + "s";
        }
        return millis + "ms";
    }

    /** Echo at most 40 characters of a rejected value back into a message. */
    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
