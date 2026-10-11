package com.modocument.infrastructure.transform.code;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Locale-aware number clean-up for NUMBER fields: thousands separators are
 * stripped according to the column's detected style ({@code 1,234.56} vs
 * {@code 1.234,56}), sign and decimals are kept, a currency symbol or code
 * next to the amount and accounting parentheses are understood. Anything that
 * is not a number after that stays as it was (trimmed), so validation flags it.
 */
public final class NumberNormalizer {

    /** Which character is the decimal mark in a column. */
    public enum Style { POINT_DECIMAL, COMMA_DECIMAL }

    private static final Pattern PLAIN = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern CURRENCY = Pattern.compile(
            "^(?:[$€£¥₹₨]|[A-Z]{3}|Rs\\.?)\\s*|\\s*(?:[$€£¥₹₨]|[A-Z]{3}|Rs\\.?)$");
    private static final Pattern MULTI_COMMA_GROUPS = Pattern.compile("\\d{1,3}(,\\d{3}){2,}");
    private static final Pattern MULTI_POINT_GROUPS = Pattern.compile("\\d{1,3}(\\.\\d{3}){2,}");
    private static final Pattern POINT_FRACTION = Pattern.compile("\\d*\\.(\\d{1,2}|\\d{4,})");
    private static final Pattern COMMA_FRACTION = Pattern.compile("\\d*,(\\d{1,2}|\\d{4,})");
    private static final Pattern POINT_GROUPED = Pattern.compile(
            "\\d{1,3}(,\\d{3})*(\\.\\d+)?|\\d{1,2}(,\\d{2})+,\\d{3}(\\.\\d+)?|\\d+(\\.\\d+)?|\\.\\d+");
    private static final Pattern COMMA_GROUPED = Pattern.compile(
            "\\d{1,3}(\\.\\d{3})*(,\\d+)?|\\d+(,\\d+)?");

    private NumberNormalizer() {
    }

    /** Normalises a whole column with one detected style. */
    public static List<String> normalizeColumn(List<String> values) {
        Style style = detect(values);
        return values.stream().map(v -> normalize(v, style)).toList();
    }

    /**
     * Votes over the column. Only unambiguous values vote: both marks in one
     * value (the last one is the decimal mark), a fraction that is not three
     * digits ({@code 12.5}, {@code 3,75}), a leading zero ({@code 0,125}) or
     * two or more three-digit groups ({@code 1.234.567}). A lone three-digit
     * group ({@code 1.234}) is ambiguous and never switches the column to the
     * decimal comma: the decimal point is the default.
     */
    public static Style detect(Collection<String> values) {
        int point = 0;
        int comma = 0;
        for (String raw : values) {
            String v = core(raw);
            if (v == null) {
                continue;
            }
            int lastPoint = v.lastIndexOf('.');
            int lastComma = v.lastIndexOf(',');
            if (lastPoint >= 0 && lastComma >= 0) {
                if (lastPoint > lastComma) {
                    point++;
                } else {
                    comma++;
                }
            } else if (POINT_FRACTION.matcher(v).matches() || MULTI_COMMA_GROUPS.matcher(v).matches()
                    || v.matches("0\\.\\d+")) {
                point++;
            } else if (COMMA_FRACTION.matcher(v).matches() || MULTI_POINT_GROUPS.matcher(v).matches()
                    || v.matches("0,\\d+")) {
                comma++;
            }
        }
        return comma > point ? Style.COMMA_DECIMAL : Style.POINT_DECIMAL;
    }

    /** {@code -1234.56} style plain number, or the trimmed input when it is not a number. */
    public static String normalize(String raw, Style style) {
        if (raw == null) {
            return "";
        }
        String value = TextCells.clean(raw);
        if (value.isEmpty()) {
            return value;
        }
        boolean negative = false;
        String v = value;
        if (v.startsWith("(") && v.endsWith(")")) {
            negative = true;
            v = v.substring(1, v.length() - 1).trim();
        }
        v = CURRENCY.matcher(v).replaceAll("").trim();
        if (v.startsWith("-") || v.startsWith("−")) {
            negative = !negative;
            v = v.substring(1).trim();
        } else if (v.startsWith("+")) {
            v = v.substring(1).trim();
        } else if (v.endsWith("-")) {
            negative = !negative;
            v = v.substring(0, v.length() - 1).trim();
        }
        v = CURRENCY.matcher(v).replaceAll("").trim();
        // Spaces and apostrophes group thousands in fr/ch styles.
        v = v.replaceAll("(?<=\\d)[ '’](?=\\d{3}\\b)", "");
        if (style == Style.COMMA_DECIMAL) {
            if (!COMMA_GROUPED.matcher(v).matches()) {
                return value;
            }
            v = v.replace(".", "").replace(',', '.');
        } else {
            if (!POINT_GROUPED.matcher(v).matches()) {
                return value;
            }
            v = v.replace(",", "");
            if (v.startsWith(".")) {
                v = "0" + v;
            }
        }
        if (!PLAIN.matcher(v).matches()) {
            return value;
        }
        boolean zero = v.matches("0+(\\.0+)?");
        return negative && !zero ? "-" + v : v;
    }

    /** True for the normalised form: optional minus, digits, optional decimals. */
    public static boolean isPlainNumber(String value) {
        return value != null && PLAIN.matcher(value).matches();
    }

    /** The digits-and-marks core used for voting, or null when the value has no number shape. */
    private static String core(String raw) {
        if (raw == null) {
            return null;
        }
        String v = TextCells.clean(raw);
        v = v.replaceAll("^[(+\\-−]+|[)\\-]+$", "");
        v = CURRENCY.matcher(v).replaceAll("").trim();
        v = v.replaceAll("(?<=\\d)[ '’](?=\\d{3}\\b)", "");
        return v.matches("[\\d.,]*\\d[\\d.,]*") ? v : null;
    }
}
