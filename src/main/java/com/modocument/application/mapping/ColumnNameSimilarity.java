package com.modocument.application.mapping;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Header-name similarity for the alias/token tier: headers are split into
 * normalized tokens, common abbreviations are expanded through an alias
 * table, and candidates are scored with token-set overlap plus an
 * edit-distance ratio. Hand-rolled on purpose — the whole surface is ~60
 * lines and the build has no network access for new dependencies.
 */
public final class ColumnNameSimilarity {

    /** Built-in abbreviation table (normalized token -> canonical token). */
    private static final Map<String, String> BUILTIN_ALIASES = Map.ofEntries(
            Map.entry("surname", "lastname"),
            Map.entry("famname", "lastname"),
            Map.entry("familyname", "lastname"),
            Map.entry("lname", "lastname"),
            Map.entry("fname", "firstname"),
            Map.entry("givenname", "firstname"),
            Map.entry("forename", "firstname"),
            Map.entry("dob", "dateofbirth"),
            Map.entry("birthdate", "dateofbirth"),
            Map.entry("mail", "email"),
            Map.entry("emailaddress", "email"),
            Map.entry("tel", "phone"),
            Map.entry("telephone", "phone"),
            Map.entry("mobile", "phone"),
            Map.entry("cell", "phone"),
            Map.entry("no", "number"),
            Map.entry("num", "number"),
            Map.entry("nbr", "number"),
            Map.entry("addr", "address"));

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])");

    private ColumnNameSimilarity() {
    }

    /** Similarity in [0,1]; 0.95 when the alias-expanded normalized forms are equal. */
    public static double score(String column, String field, Map<String, String> extraAliases) {
        Set<String> columnTokens = expandedTokens(column, extraAliases);
        Set<String> fieldTokens = expandedTokens(field, extraAliases);
        if (columnTokens.isEmpty() || fieldTokens.isEmpty()) {
            return 0;
        }
        String columnJoined = String.join("", columnTokens);
        String fieldJoined = String.join("", fieldTokens);
        if (columnJoined.equals(fieldJoined)) {
            return 0.95;
        }
        return 0.6 * jaccard(columnTokens, fieldTokens)
                + 0.4 * levenshteinRatio(columnJoined, fieldJoined);
    }

    static Set<String> expandedTokens(String header, Map<String, String> extraAliases) {
        Set<String> tokens = new LinkedHashSet<>();
        if (header == null) {
            return tokens;
        }
        for (String raw : TOKEN_SPLIT.split(header.trim())) {
            String token = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (token.isEmpty()) {
                continue;
            }
            String expanded = extraAliases == null ? null : extraAliases.get(token);
            if (expanded == null) {
                expanded = BUILTIN_ALIASES.getOrDefault(token, token);
            }
            tokens.add(expanded);
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    static double levenshteinRatio(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 0 : 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
