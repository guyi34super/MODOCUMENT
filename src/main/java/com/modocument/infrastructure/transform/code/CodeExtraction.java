package com.modocument.infrastructure.transform.code;

import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A table produced by code: the same shape as the LLM parse
 * ({@link TableData} columns + string rows, plus a column→field suggested
 * mapping) with the strategy that produced it and how much to trust it.
 *
 * @param headers          ordered column names
 * @param rows             one map per row, keyed by header, values normalised
 * @param suggestedMapping column → template field for the columns the extractor matched
 * @param strategy         the winning strategy
 * @param confidence       0..1, see {@link Confidence}
 * @param lowConfidence    set by {@link CodeTableExtractor#accept} when a CODE_ONLY
 *                         extraction passed below the normal threshold
 * @param breakdown        the confidence components
 * @param notes            decisions taken (delimiter, encoding, dropped rows …); never cell values
 */
public record CodeExtraction(List<String> headers,
                             List<Map<String, String>> rows,
                             Map<String, String> suggestedMapping,
                             CodeStrategy strategy,
                             double confidence,
                             boolean lowConfidence,
                             Confidence breakdown,
                             List<String> notes) {

    public CodeExtraction {
        headers = List.copyOf(headers);
        List<Map<String, String>> copied = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            copied.add(new LinkedHashMap<>(row));
        }
        rows = copied;
        suggestedMapping = suggestedMapping == null ? Map.of() : new LinkedHashMap<>(suggestedMapping);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The parse-stage table. */
    public TableData table() {
        List<Map<String, String>> copy = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        return new TableData(new ArrayList<>(headers), copy);
    }

    /** Drop-in replacement for the LLM's {@link ParseResult}. */
    public ParseResult toParseResult() {
        return new ParseResult(table(), suggestedMapping);
    }

    CodeExtraction withLowConfidence(boolean value) {
        return new CodeExtraction(headers, rows, suggestedMapping, strategy, confidence,
                value, breakdown, notes);
    }

    /**
     * Confidence = 0.40 × field coverage + 0.30 × value shape + 0.20 ×
     * structural consistency + 0.10 × row-count agreement with the pre-scan
     * hints. Without a usable row-count hint that term is neutral (0.5), so a
     * flawless table scores 0.95 until a pre-scan confirms its length.
     *
     * @param coverage     share of template fields matched by header or found by label
     * @param shape        share of sampled values that fit their field (rules, pattern or type)
     * @param structure    row-width uniformity and few empty cells
     * @param rowAgreement row count vs the hint (±10% is full marks); null (neutral) when there is no hint
     */
    public record Confidence(double coverage, double shape, double structure, Double rowAgreement) {

        public static final double COVERAGE_WEIGHT = 0.40;
        public static final double SHAPE_WEIGHT = 0.30;
        public static final double STRUCTURE_WEIGHT = 0.20;
        public static final double ROWS_WEIGHT = 0.10;

        /** Row-count agreement assumed when there is no hint to compare with. */
        public static final double NEUTRAL_ROWS = 0.5;

        public double total() {
            double rows = rowAgreement == null ? NEUTRAL_ROWS : rowAgreement;
            double score = COVERAGE_WEIGHT * coverage + SHAPE_WEIGHT * shape
                    + STRUCTURE_WEIGHT * structure + ROWS_WEIGHT * rows;
            return Math.max(0, Math.min(1, score));
        }
    }
}
