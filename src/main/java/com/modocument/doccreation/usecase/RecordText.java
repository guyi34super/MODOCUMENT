package com.modocument.doccreation.usecase;

import com.modocument.doccreation.entity.TemplateRecordKey;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The text rules of the record index (docs/doccreation/flow-records-contract.md
 * §1): {@link #fold} for the search text (lower-cased, accent-folded),
 * {@link #key} for join keys (trimmed, lower-cased, whitespace and
 * punctuation removed) and {@link #sameName} for the "same name" rule of
 * auto-mapping.
 */
public final class RecordText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern KEY_STRIP = Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}]+");
    private static final Pattern NAME_STRIP = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WORDS = Pattern.compile("\\s+");

    private RecordText() {
    }

    /** Lower-cased and accent-folded ("Zoë" → "zoe"); null stays null. */
    public static String fold(String value) {
        if (value == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** The search text of a record: its non-blank values folded and joined with " ". */
    public static String searchText(Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        for (String value : values.values()) {
            if (value != null && !value.isBlank()) {
                parts.add(fold(value.strip()));
            }
        }
        return String.join(" ", parts);
    }

    /** The folded words of a query (blank → none). */
    public static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String word : WORDS.split(fold(query.strip()))) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * The join key of a value: trimmed, lower-cased, whitespace and
     * punctuation (and symbols) removed, cut to
     * {@link TemplateRecordKey#MAX_KEY_LENGTH}; empty for a blank value.
     */
    public static String key(String value) {
        if (value == null) {
            return "";
        }
        String key = KEY_STRIP.matcher(value.strip().toLowerCase(Locale.ROOT)).replaceAll("");
        return key.length() > TemplateRecordKey.MAX_KEY_LENGTH
                ? key.substring(0, TemplateRecordKey.MAX_KEY_LENGTH) : key;
    }

    /** A name reduced to [a-z0-9] for the same-name rule ("Full name" = "full_name"). */
    public static String name(String value) {
        return value == null ? "" : NAME_STRIP.matcher(fold(value)).replaceAll("");
    }

    /** True when the field's normalised name equals the region's key or label. */
    public static boolean sameName(String fieldName, String regionKey, String regionLabel) {
        String field = name(fieldName);
        return !field.isEmpty() && (field.equals(name(regionKey)) || field.equals(name(regionLabel)));
    }
}
