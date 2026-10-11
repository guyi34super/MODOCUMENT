package com.modocument.infrastructure.transform.code;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.validation.SafeRegex;
import com.modocument.domain.entity.ValidationRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps a candidate's columns to template fields, normalises typed values
 * (dates to ISO, numbers locale-aware) and scores it — see
 * {@link CodeExtraction.Confidence} for the formula.
 */
final class CandidateScorer {

    /** Values per field examined for the shape score (as {@code mapping.sample-size}). */
    static final int SAMPLE_SIZE = 20;

    /** Longest plausible single-cell text value. */
    private static final int MAX_TEXT_LENGTH = 160;

    private final ValueShapeScorer shapeScorer;

    CandidateScorer(ValueShapeScorer shapeScorer) {
        this.shapeScorer = shapeScorer;
    }

    CodeExtraction score(Candidate candidate, ExtractionContext context) {
        ExtractionInput input = context.input();
        FieldMatcher matcher = context.matcher();
        List<FieldSpec> fields = input.fields();
        List<String> headers = candidate.headers();

        Map<Integer, FieldSpec> mapping = new LinkedHashMap<>();
        if (candidate.fieldKeyed()) {
            for (int c = 0; c < headers.size() && c < fields.size(); c++) {
                mapping.put(c, fields.get(c));
            }
        } else {
            mapping.putAll(matcher.match(headers));
        }

        // Column-wise normalisation: typed fields get ISO dates / plain numbers.
        int width = headers.size();
        List<List<String>> columns = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            List<String> column = new ArrayList<>(candidate.rows().size());
            for (List<String> row : candidate.rows()) {
                column.add(TextCells.clean(row.get(c)));
            }
            FieldSpec field = mapping.get(c);
            if (field != null && field.type() == FieldSpec.Type.DATE) {
                column = DateNormalizer.normalizeColumn(column, field.dateFormats());
            } else if (field != null && field.type() == FieldSpec.Type.NUMBER) {
                column = NumberNormalizer.normalizeColumn(column);
            }
            columns.add(column);
        }

        double coverage;
        double shape;
        int matchedWithValues = 0;
        List<Double> shapes = new ArrayList<>();
        for (Map.Entry<Integer, FieldSpec> entry : mapping.entrySet()) {
            List<String> values = nonBlank(columns.get(entry.getKey()));
            if (values.isEmpty()) {
                continue;
            }
            matchedWithValues++;
            shapes.add(fieldShape(entry.getValue(), values, input.rulesByField(), matcher));
        }
        if (fields.isEmpty()) {
            coverage = headerQuality(headers);
            shape = genericShape(columns);
        } else {
            coverage = (double) matchedWithValues / fields.size();
            shape = shapes.isEmpty() ? 0 : shapes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double structure = structure(candidate, columns, mapping);
        Double rowAgreement = rowAgreement(candidate.rows().size(), input.hints(), headers);
        CodeExtraction.Confidence breakdown = new CodeExtraction.Confidence(coverage, shape, structure, rowAgreement);

        List<Map<String, String>> rows = new ArrayList<>(candidate.rows().size());
        for (int r = 0; r < candidate.rows().size(); r++) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < width; c++) {
                row.put(headers.get(c), columns.get(c).get(r));
            }
            rows.add(row);
        }
        Map<String, String> suggested = new LinkedHashMap<>();
        for (Map.Entry<Integer, FieldSpec> entry : mapping.entrySet()) {
            suggested.put(headers.get(entry.getKey()), entry.getValue().name());
        }

        List<String> notes = new ArrayList<>(candidate.notes());
        notes.addAll(context.notes());
        if (!fields.isEmpty()) {
            notes.add(matchedWithValues + " of " + fields.size() + " fields matched with values");
        }
        return new CodeExtraction(headers, rows, suggested, candidate.strategy(), round(breakdown.total()),
                false, breakdown, notes);
    }

    /**
     * Share of a field's sampled values that fit it: the author's value
     * regex when given (and it has no capture group); else the template's validation rules through
     * {@link ValueShapeScorer} (capped by the type check for typed fields);
     * else the type check — a plain number, an ISO date, or a text cell
     * that is neither overlong nor a label.
     */
    private double fieldShape(FieldSpec field, List<String> values, Map<String, List<ValidationRule>> rulesByField,
                              FieldMatcher matcher) {
        List<String> sample = values.size() > SAMPLE_SIZE ? values.subList(0, SAMPLE_SIZE) : values;
        Pattern valuePattern = matcher.valuePattern(field);
        // A pattern with a capture group describes the value in its context
        // ("REF-(\\d+)"), so only a group-less pattern can judge a bare value.
        if (valuePattern != null && valuePattern.matcher("").groupCount() == 0
                && !matcher.budget().exhausted()) {
            try {
                int fit = 0;
                for (String value : sample) {
                    if (matcher.budget().find(valuePattern, value).isPresent()) {
                        fit++;
                    }
                }
                return (double) fit / sample.size();
            } catch (SafeRegex.RegexTimeoutException e) {
                // Fall through to rules and type checks.
            }
        }
        double typeCheck = typeCheck(field, sample, matcher);
        List<ValidationRule> rules = rulesByField.get(field.name());
        if (shapeScorer != null && rules != null && !rules.isEmpty()) {
            OptionalDouble byRules = shapeScorer.score(field.toTemplateField(), rules, sample);
            if (byRules.isPresent()) {
                return field.type() == FieldSpec.Type.TEXT
                        ? byRules.getAsDouble()
                        : Math.min(byRules.getAsDouble(), typeCheck);
            }
        }
        return typeCheck;
    }

    private static double typeCheck(FieldSpec field, List<String> sample, FieldMatcher matcher) {
        int fit = 0;
        for (String value : sample) {
            boolean ok = switch (field.type()) {
                case NUMBER -> NumberNormalizer.isPlainNumber(value);
                case DATE -> DateNormalizer.isIsoDate(value);
                case TEXT -> value.length() <= MAX_TEXT_LENGTH && !matcher.isLabel(value)
                        && value.chars().anyMatch(Character::isLetterOrDigit);
            };
            if (ok) {
                fit++;
            }
        }
        return (double) fit / sample.size();
    }

    /** Uniform rows (half) and few empty cells in the columns that carry data (half). */
    private static double structure(Candidate candidate, List<List<String>> columns, Map<Integer, FieldSpec> mapping) {
        List<Integer> relevant = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            boolean anyValue = !nonBlank(columns.get(c)).isEmpty();
            if (anyValue && (mapping.isEmpty() || mapping.containsKey(c))) {
                relevant.add(c);
            }
        }
        if (relevant.isEmpty() || candidate.rows().isEmpty()) {
            return 0;
        }
        int cells = 0;
        int empty = 0;
        for (int c : relevant) {
            for (String value : columns.get(c)) {
                cells++;
                if (value.isEmpty()) {
                    empty++;
                }
            }
        }
        double filled = cells == 0 ? 0 : 1.0 - (double) empty / cells;
        return 0.5 * candidate.uniformity() + 0.5 * filled;
    }

    /**
     * 1 within ±10% (at least ±1 row) of the hinted row count, falling to 0
     * at 50% off. Null (neutral) without a hint, or when the hint's header
     * cells do not name at least half of this candidate's columns — then
     * the pre-scan described some other table and its count says nothing.
     */
    static Double rowAgreement(int rows, TableExtractionHints hints, List<String> headers) {
        if (hints == null || hints.dataRowCount() <= 0) {
            return null;
        }
        List<String> hinted = hints.candidateHeaderCells() == null ? List.of() : hints.candidateHeaderCells();
        if (!hinted.isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (String header : headers) {
                keys.add(TextCells.key(header));
            }
            long same = hinted.stream().map(TextCells::key).filter(k -> !k.isEmpty() && keys.contains(k)).count();
            if (same * 2 < Math.max(1, Math.min(hinted.size(), headers.size()))) {
                return null;
            }
        }
        int expected = hints.dataRowCount();
        double tolerance = Math.max(1, Math.ceil(expected * 0.10));
        double off = Math.abs(rows - expected);
        if (off <= tolerance) {
            return 1.0;
        }
        double ratio = off / expected;
        return Math.max(0, 1.0 - (ratio - 0.10) / 0.40);
    }

    /** Without template fields: named, short, non-numeric, distinct headers. */
    private static double headerQuality(List<String> headers) {
        if (headers.isEmpty()) {
            return 0;
        }
        int good = 0;
        Map<String, Integer> seen = new HashMap<>();
        for (String header : headers) {
            String key = TextCells.key(header);
            if (seen.merge(key, 1, Integer::sum) > 1) {
                continue;
            }
            if (!TextCells.isGeneratedHeader(header) && !key.isEmpty() && header.length() <= 40
                    && !TextCells.looksNumeric(header) && !TextCells.looksDate(header)) {
                good++;
            }
        }
        return (double) good / headers.size();
    }

    /** Without template fields: how consistently each column holds one kind of value. */
    private static double genericShape(List<List<String>> columns) {
        double total = 0;
        int counted = 0;
        for (List<String> column : columns) {
            List<String> values = nonBlank(column);
            if (values.isEmpty()) {
                continue;
            }
            int numbers = 0;
            int dates = 0;
            for (String value : values) {
                if (TextCells.looksDate(value)) {
                    dates++;
                } else if (TextCells.looksNumeric(value)) {
                    numbers++;
                }
            }
            int texts = values.size() - numbers - dates;
            total += (double) Math.max(texts, Math.max(numbers, dates)) / values.size();
            counted++;
        }
        return counted == 0 ? 0 : total / counted;
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
