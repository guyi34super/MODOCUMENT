package com.modocument.infrastructure.transform.code;

import com.modocument.application.mapping.ColumnNameSimilarity;
import com.modocument.application.validation.SafeRegex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches headers and labels to template fields: the field name, its
 * per-field aliases and the global {@code mapping.aliases} through
 * {@link ColumnNameSimilarity} (the same scorer the auto-mapper uses), plus
 * the field's label regex when the author gave one. Author regexes are
 * vetted by {@link SafeRegex} once per document and run under its budget.
 */
final class FieldMatcher {

    /** Same default as {@code mapping.confidence-threshold}. */
    static final double HEADER_THRESHOLD = 0.7;

    /** Free-text labels ({@code Something: value}) need a near-exact match. */
    static final double FUZZY_LABEL_THRESHOLD = 0.85;

    private final List<FieldSpec> fields;
    private final Map<String, String> globalAliases;
    private final SafeRegex.Budget budget;
    private final Map<String, Pattern> labelPatterns = new HashMap<>();
    private final Map<String, Pattern> valuePatterns = new HashMap<>();
    private final Map<String, List<String>> labels = new HashMap<>();
    private final Set<String> labelKeys = new HashSet<>();

    FieldMatcher(List<FieldSpec> fields, Map<String, String> globalAliases,
                 SafeRegex.Budget budget, List<String> notes) {
        this.fields = fields;
        this.globalAliases = globalAliases;
        this.budget = budget;
        for (FieldSpec field : fields) {
            if (field.labelPattern() != null) {
                compileHint(field, field.labelPattern(), "label", notes)
                        .ifPresent(p -> labelPatterns.put(field.name(), p));
            }
            if (field.valuePattern() != null) {
                compileHint(field, field.valuePattern(), "value", notes)
                        .ifPresent(p -> valuePatterns.put(field.name(), p));
            }
            List<String> literal = literalLabels(field);
            labels.put(field.name(), literal);
            for (String label : literal) {
                labelKeys.add(TextCells.key(label));
            }
        }
    }

    List<FieldSpec> fields() {
        return fields;
    }

    /** Header/label similarity to one field in [0,1]; 1.0 for an exact name, alias or label-regex hit. */
    double score(String header, FieldSpec field) {
        String h = TextCells.clean(header);
        if (h.isEmpty()) {
            return 0;
        }
        String key = TextCells.key(h);
        for (String label : labels.getOrDefault(field.name(), List.of())) {
            if (!key.isEmpty() && key.equals(TextCells.key(label))) {
                return 1.0;
            }
        }
        double best = ColumnNameSimilarity.score(h, field.name(), globalAliases);
        for (String alias : field.aliases()) {
            best = Math.max(best, ColumnNameSimilarity.score(h, alias, globalAliases));
        }
        Pattern labelPattern = labelPatterns.get(field.name());
        if (labelPattern != null && best < 1.0 && matchesWhole(labelPattern, h)) {
            return 1.0;
        }
        return best;
    }

    /** Best score of {@code header} against any field. */
    double bestScore(String header) {
        double best = 0;
        for (FieldSpec field : fields) {
            best = Math.max(best, score(header, field));
        }
        return best;
    }

    /** The field {@code label} names with at least {@code threshold}, or null. */
    FieldSpec bestField(String label, double threshold) {
        FieldSpec best = null;
        double bestScore = threshold;
        for (FieldSpec field : fields) {
            double s = score(label, field);
            if (s >= bestScore) {
                best = field;
                bestScore = s;
            }
        }
        return best;
    }

    /** Column index → field, each field used once, highest similarity first, at or above the threshold. */
    Map<Integer, FieldSpec> match(List<String> headers) {
        record Pair(int column, FieldSpec field, double score) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int c = 0; c < headers.size(); c++) {
            for (FieldSpec field : fields) {
                double s = score(headers.get(c), field);
                if (s >= HEADER_THRESHOLD) {
                    pairs.add(new Pair(c, field, s));
                }
            }
        }
        pairs.sort((a, b) -> Double.compare(b.score(), a.score()));
        Map<Integer, FieldSpec> mapping = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Pair pair : pairs) {
            if (mapping.containsKey(pair.column()) || used.contains(pair.field().name())) {
                continue;
            }
            mapping.put(pair.column(), pair.field());
            used.add(pair.field().name());
        }
        return mapping;
    }

    /** Literal label texts for a field: name, its spaced form, per-field aliases, matching global alias keys. */
    List<String> labels(FieldSpec field) {
        return labels.getOrDefault(field.name(), List.of());
    }

    /** True when {@code value} is literally one of the fields' labels (a header leaking into data). */
    boolean isLabel(String value) {
        String key = TextCells.key(value);
        return !key.isEmpty() && labelKeys.contains(key);
    }

    /** The vetted label regex of the field, or null. */
    Pattern labelPattern(FieldSpec field) {
        return labelPatterns.get(field.name());
    }

    /** The vetted value regex of the field, or null. */
    Pattern valuePattern(FieldSpec field) {
        return valuePatterns.get(field.name());
    }

    SafeRegex.Budget budget() {
        return budget;
    }

    private boolean matchesWhole(Pattern pattern, String value) {
        try {
            return budget.find(pattern, value)
                    .map(m -> m.start() == 0 && m.end() == value.length())
                    .orElse(false);
        } catch (SafeRegex.RegexTimeoutException e) {
            return false;
        }
    }

    private List<String> literalLabels(FieldSpec field) {
        Set<String> out = new LinkedHashSet<>();
        out.add(field.name().trim());
        String spaced = field.name()
                .replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ")
                .replaceAll("[_]+", " ")
                .trim();
        out.add(spaced);
        out.addAll(field.aliases());
        for (String aliasToken : globalAliases.keySet()) {
            if (ColumnNameSimilarity.score(aliasToken, field.name(), globalAliases) >= 0.95) {
                out.add(aliasToken);
            }
        }
        out.removeIf(String::isBlank);
        return List.copyOf(out);
    }

    private static Optional<Pattern> compileHint(FieldSpec field, String pattern,
                                                           String kind, List<String> notes) {
        try {
            return Optional.of(SafeRegex.compile(pattern,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        } catch (SafeRegex.UnsafePatternException e) {
            notes.add("ignored the " + kind + " pattern of field '" + field.name() + "': " + e.getMessage());
            return Optional.empty();
        }
    }
}
