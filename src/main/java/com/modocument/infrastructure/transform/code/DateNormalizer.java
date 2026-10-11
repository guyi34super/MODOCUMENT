package com.modocument.infrastructure.transform.code;

import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date clean-up for DATE fields: values become ISO {@code uuuu-MM-dd} (what
 * the DATE_FORMAT rule accepts) when they can be read without guessing.
 *
 * <ol>
 *   <li>the field's own {@code dateFormats}, in order;</li>
 *   <li>unambiguous built-ins: ISO date or date-time, {@code uuuu/MM/dd},
 *       {@code uuuuMMdd}, month names ({@code 5 Jan 2024}, {@code January 5, 2024});</li>
 *   <li>numeric day/month ({@code 03/04/2024}): only when a part above 12
 *       decides the order — in the value itself, or (consistently, with no
 *       contrary value) elsewhere in the same column — or both parts are equal.</li>
 * </ol>
 * Anything else, two-digit years included, stays raw so validation flags it.
 */
public final class DateNormalizer {

    /** Order of a numeric day/month date, as decided for a column. */
    enum Order { DAY_FIRST, MONTH_FIRST, UNKNOWN }

    private static final DateTimeFormatter ISO = strict("uuuu-MM-dd");
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern NUMERIC_DMY = Pattern.compile("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})");
    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})");
    private static final Pattern COMPACT = Pattern.compile("(19|20)\\d{6}");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final List<DateTimeFormatter> MONTH_NAME_FORMATS = List.of(
            strict("d MMM uuuu"), strict("d MMMM uuuu"), strict("d-MMM-uuuu"),
            strict("d/MMM/uuuu"), strict("d.MMM.uuuu"), strict("MMM d, uuuu"),
            strict("MMMM d, uuuu"), strict("MMM d uuuu"), strict("MMMM d uuuu"),
            strict("EEE, d MMM uuuu"), strict("EEEE, d MMMM uuuu"), strict("EEEE, MMMM d, uuuu"));

    private DateNormalizer() {
    }

    /** Normalises a whole column so that a disambiguating value can settle the day/month order. */
    public static List<String> normalizeColumn(List<String> values, List<String> fieldFormats) {
        List<DateTimeFormatter> formats = compile(fieldFormats);
        Order order = columnOrder(values);
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(normalize(value, formats, order));
        }
        return out;
    }

    /** Normalises one value on its own (no column evidence). */
    public static String normalize(String raw, List<String> fieldFormats) {
        return normalize(raw, compile(fieldFormats), Order.UNKNOWN);
    }

    /** True for a valid ISO {@code uuuu-MM-dd} date. */
    public static boolean isIsoDate(String value) {
        return value != null && parse(value.trim(), ISO).isPresent();
    }

    static String normalize(String raw, List<DateTimeFormatter> formats, Order order) {
        if (raw == null) {
            return "";
        }
        String value = TextCells.clean(raw);
        if (value.isEmpty()) {
            return value;
        }
        for (DateTimeFormatter format : formats) {
            Optional<LocalDate> parsed = parse(value, format);
            if (parsed.isPresent()) {
                return parsed.get().toString();
            }
        }
        Matcher iso = ISO_DATE_TIME.matcher(value);
        if (iso.matches()) {
            return parse(iso.group(1), ISO).map(LocalDate::toString).orElse(value);
        }
        Matcher yearFirst = YEAR_FIRST.matcher(value);
        if (yearFirst.matches()) {
            return of(yearFirst.group(1), yearFirst.group(2), yearFirst.group(3)).orElse(value);
        }
        if (COMPACT.matcher(value).matches()) {
            return of(value.substring(0, 4), value.substring(4, 6), value.substring(6, 8)).orElse(value);
        }
        Matcher numeric = NUMERIC_DMY.matcher(value);
        if (numeric.matches()) {
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(2));
            String year = numeric.group(3);
            Order use = order;
            if (first > 12 && second <= 12) {
                use = Order.DAY_FIRST;
            } else if (second > 12 && first <= 12) {
                use = Order.MONTH_FIRST;
            } else if (first == second) {
                use = Order.DAY_FIRST;
            }
            return switch (use) {
                case DAY_FIRST -> of(year, numeric.group(2), numeric.group(1)).orElse(value);
                case MONTH_FIRST -> of(year, numeric.group(1), numeric.group(2)).orElse(value);
                case UNKNOWN -> value;
            };
        }
        if (!HAS_LETTER.matcher(value).find()) {
            return value;
        }
        for (DateTimeFormatter format : MONTH_NAME_FORMATS) {
            Optional<LocalDate> parsed = parse(value, format);
            if (parsed.isPresent()) {
                return parsed.get().toString();
            }
        }
        return value;
    }

    /** DAY_FIRST/MONTH_FIRST when the column's values point one way only, otherwise UNKNOWN. */
    static Order columnOrder(List<String> values) {
        boolean dayFirst = false;
        boolean monthFirst = false;
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            Matcher numeric = NUMERIC_DMY.matcher(TextCells.clean(raw));
            if (!numeric.matches()) {
                continue;
            }
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(2));
            if (first > 12 && second <= 12) {
                dayFirst = true;
            } else if (second > 12 && first <= 12) {
                monthFirst = true;
            }
        }
        if (dayFirst == monthFirst) {
            return Order.UNKNOWN;
        }
        return dayFirst ? Order.DAY_FIRST : Order.MONTH_FIRST;
    }

    private static Optional<String> of(String year, String month, String day) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month),
                    Integer.parseInt(day)).toString());
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Parses without throwing for the common miss (a whole column tries many formats). */
    private static Optional<LocalDate> parse(String value, DateTimeFormatter format) {
        ParsePosition position = new ParsePosition(0);
        if (format.parseUnresolved(value, position) == null || position.getErrorIndex() >= 0
                || position.getIndex() != value.length()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value, format));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Compiles the field's patterns. {@code y} (year-of-era) is read as
     * {@code u} so that strict resolution works without an era; patterns
     * that do not compile are skipped.
     */
    static List<DateTimeFormatter> compile(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<DateTimeFormatter> formats = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank() || pattern.length() > 64) {
                continue;
            }
            try {
                formats.add(strict(pattern.trim().replace('y', 'u')));
            } catch (IllegalArgumentException e) {
                // An unusable author-supplied format is no format.
            }
        }
        return formats;
    }

    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
