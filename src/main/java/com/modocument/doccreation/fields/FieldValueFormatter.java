package com.modocument.doccreation.fields;

import com.modocument.doccreation.pdftemplate.RegionKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How field values are stored and drawn (docs/doccreation/field-validation-contract.md §2).
 *
 * <ul>
 *   <li>{@link #format}: a stored value as it is drawn in the PDF. DATE values
 *       are stored ISO {@code yyyy-MM-dd} and drawn with {@code props.dateFormat}
 *       (English); NUMBER values with {@code props.numberFormat} (HALF_UP,
 *       {@code ,} grouping, prefix and suffix). A value that does not parse is
 *       drawn as typed.</li>
 *   <li>{@link #normalize}: a value that comes from a record, turned into the
 *       stored shape when it parses (common date layouts to ISO, grouped or
 *       currency numbers to a plain decimal); otherwise kept as it is.</li>
 * </ul>
 */
public final class FieldValueFormatter {

    /** The layouts a record's date may come in (day first), tried in order. */
    private static final List<DateTimeFormatter> RECORD_DATE_FORMATS = List.of(
            strict("uuuu-MM-dd"), strict("dd/MM/uuuu"), strict("d/M/uuuu"), strict("dd-MM-uuuu"),
            strict("d-M-uuuu"), strict("dd.MM.uuuu"), strict("d.M.uuuu"), strict("d MMM uuuu"),
            strict("d MMMM uuuu"), strict("MMM d, uuuu"), strict("MMMM d, uuuu"));

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** An ISO date-time, as a record may hold: the date part is used. */
    private static final Pattern ISO_DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[T ]\\d{2}:\\d{2}.*");
    /** A plain decimal, optionally signed. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");
    /** Digits grouped by threes with one separator (",", "'", space), or ungrouped. */
    private static final Pattern GROUPED_NUMBER =
            Pattern.compile("([+-]?)(\\d{1,3}(?:([,' \\u00A0\\u202F])\\d{3})(?:\\3\\d{3})*|\\d+)(\\.\\d+)?");
    /** A currency code or symbol before or after the number ("Rs 1,000", "1,000 MUR", "$5"). */
    private static final Pattern CURRENCY_PREFIX = Pattern.compile("^(?:\\p{Sc}|[A-Z]{3}|[Rr]s\\.?)\\s*");
    private static final Pattern CURRENCY_SUFFIX = Pattern.compile("\\s*(?:\\p{Sc}|[A-Z]{3})$");

    private FieldValueFormatter() {
    }

    // ------------------------------------------------------------------ drawing

    /** {@code value} as it is drawn in the PDF for a field of {@code kind} with {@code props}. */
    public static String format(RegionKind kind, FieldProps props, String value) {
        if (value == null || kind == null || props == null) {
            return value;
        }
        return switch (kind) {
            case DATE -> formatDate(props.dateFormat(), value);
            case NUMBER -> formatNumber(props.numberFormat(), value);
            default -> value;
        };
    }

    /** An ISO date drawn with {@code pattern} (English); anything else as typed. */
    public static String formatDate(String pattern, String value) {
        if (pattern == null || pattern.isBlank() || value == null) {
            return value;
        }
        LocalDate date = parseIsoDate(value);
        if (date == null) {
            return value;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(date);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return value;
        }
    }

    /** A number drawn with {@code spec}; anything that does not parse as typed. */
    public static String formatNumber(FieldProps.NumberFormatSpec spec, String value) {
        if (spec == null || value == null) {
            return value;
        }
        BigDecimal number = parseNumber(value);
        if (number == null) {
            return value;
        }
        if (spec.decimals() != null) {
            number = number.setScale(Math.max(0, Math.min(6, spec.decimals())), RoundingMode.HALF_UP);
        }
        String plain = number.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        if (Boolean.TRUE.equals(spec.thousands())) {
            whole = group(whole);
        }
        String sign = number.signum() < 0 ? "-" : "";
        return sign + nullToEmpty(spec.prefix()) + whole + fraction + nullToEmpty(spec.suffix());
    }

    private static String group(String digits) {
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        int lead = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    // ------------------------------------------------------------ normalising

    /**
     * A record's value in the stored shape of a field of {@code kind}: DATE
     * values in a common layout become ISO, NUMBER values a plain decimal,
     * CHECKBOX values {@code true} or empty. A value that does not parse is
     * kept as it is.
     */
    public static String normalize(RegionKind kind, String value) {
        if (value == null || kind == null || value.isBlank()) {
            return value;
        }
        return switch (kind) {
            case DATE -> {
                LocalDate date = parseRecordDate(value);
                yield date == null ? value : date.toString();
            }
            case NUMBER -> {
                BigDecimal number = parseRecordNumber(value);
                yield number == null ? value : number.toPlainString();
            }
            case CHECKBOX -> normalizeCheckbox(value);
            default -> value;
        };
    }

    private static String normalizeCheckbox(String value) {
        String t = value.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "true", "x", "yes", "y", "1", "on", "checked", "✓", "✔" -> "true";
            case "false", "no", "n", "0", "off", "unchecked" -> "";
            default -> value;
        };
    }

    // ---------------------------------------------------------------- parsing

    /** A strict ISO {@code yyyy-MM-dd} date (trimmed), or null. */
    public static LocalDate parseIsoDate(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip();
        if (!ISO_DATE.matcher(t).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(t, RECORD_DATE_FORMATS.get(0));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A date in ISO or one of the common record layouts (day first), or null. */
    public static LocalDate parseRecordDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.strip().replaceAll("\\s+", " ");
        Matcher dateTime = ISO_DATE_TIME.matcher(t);
        if (dateTime.matches()) {
            t = dateTime.group(1);
        }
        for (DateTimeFormatter format : RECORD_DATE_FORMATS) {
            try {
                return LocalDate.parse(t, format);
            } catch (DateTimeParseException e) {
                // next layout
            }
        }
        return null;
    }

    /**
     * A number as the checks read it: trimmed, {@code ,} removed, then a
     * plain BigDecimal (no exponent); null when it does not parse.
     */
    public static BigDecimal parseNumber(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip().replace(",", "");
        if (!PLAIN_NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A number as a record may hold it: a currency code or symbol before or
     * after it, digits grouped by threes ({@code 1,234,567.50},
     * {@code 1 234}); null when it is anything else (for example a decimal
     * comma), so the value is kept as typed rather than misread.
     */
    public static BigDecimal parseRecordNumber(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip();
        t = CURRENCY_PREFIX.matcher(t).replaceFirst("");
        t = CURRENCY_SUFFIX.matcher(t).replaceFirst("").strip();
        Matcher m = GROUPED_NUMBER.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String digits = m.group(2).replaceAll("[,' \\u00A0\\u202F]", "");
        String fraction = m.group(4) == null ? "" : m.group(4);
        try {
            return new BigDecimal(m.group(1) + digits + fraction);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A strict formatter for a {@code DateTimeFormatter} pattern (English,
     * case-insensitive), with year-of-era {@code y} read as proleptic year
     * {@code u} so STRICT resolving needs no era.
     */
    public static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern(yearOfEraToYear(pattern))
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    private static String yearOfEraToYear(String pattern) {
        if (pattern.indexOf('G') >= 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            out.append(!quoted && c == 'y' ? 'u' : c);
        }
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
