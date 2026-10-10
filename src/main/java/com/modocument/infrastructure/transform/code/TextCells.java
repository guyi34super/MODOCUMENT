package com.modocument.infrastructure.transform.code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Small cell-level helpers shared by the strategies and the scorer. */
final class TextCells {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF]");
    private static final Pattern NUMBER_LIKE = Pattern.compile(
            "[(+\\-\\u2212]?\\s*(?:[$€£¥₹₨]|[A-Z]{3}|Rs\\.?)?\\s*\\d[\\d,.' ]*\\)?\\s*(?:[$€£¥₹₨]|[A-Z]{3})?-?");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}(?:[T ].*)?"
                    + "|\\d{1,2}[ \\-/.]?[A-Za-z]{3,9}[ \\-/.,]+\\d{2,4}"
                    + "|[A-Za-z]{3,9}\\.? \\d{1,2},? \\d{4}");
    private static final Pattern GENERATED_HEADER = Pattern.compile("Column\\d+");

    private TextCells() {
    }

    /** Trims, removes zero-width characters and collapses any whitespace run to one space. */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String noInvisible = INVISIBLE.matcher(value).replaceAll("");
        return WHITESPACE.matcher(noInvisible).replaceAll(" ").trim();
    }

    static boolean isBlank(String value) {
        return value == null || clean(value).isEmpty();
    }

    static boolean looksNumeric(String value) {
        if (value == null) {
            return false;
        }
        String v = clean(value);
        return !v.isEmpty() && NUMBER_LIKE.matcher(v).matches() && v.chars().anyMatch(Character::isDigit);
    }

    static boolean looksDate(String value) {
        return value != null && DATE_LIKE.matcher(clean(value)).matches();
    }

    static boolean isGeneratedHeader(String header) {
        return header != null && GENERATED_HEADER.matcher(header).matches();
    }

    /** Header names made unique and non-blank, the way {@code TabularNormalizer} does it. */
    static List<String> uniqueHeaders(List<String> headers) {
        List<String> columns = new ArrayList<>(headers.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = clean(headers.get(i));
            if (header.isEmpty()) {
                header = "Column" + (i + 1);
            }
            int count = seen.merge(header.toLowerCase(Locale.ROOT), 1, Integer::sum);
            columns.add(count == 1 ? header : header + " (" + count + ")");
        }
        return columns;
    }

    /** Field names as column names, verbatim when unique (they are, per template). */
    static List<String> fieldHeaders(List<FieldSpec> fields) {
        List<String> names = fields.stream().map(FieldSpec::name).toList();
        return new HashSet<>(names).size() == names.size() ? names : uniqueHeaders(names);
    }

    static int nonBlankCount(List<String> cells) {
        int count = 0;
        for (String cell : cells) {
            if (!isBlank(cell)) {
                count++;
            }
        }
        return count;
    }

    /** {@code cells} padded with blanks (or cut) to {@code width}. */
    static List<String> fit(List<String> cells, int width) {
        List<String> out = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            out.add(i < cells.size() ? clean(cells.get(i)) : "");
        }
        return out;
    }

    /** Normalised comparison key: lower-case letters and digits only. */
    static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
