package com.modocument.application.usecase;

import com.modocument.application.dto.InferredSchema;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.FallbackReason;
import com.modocument.infrastructure.transform.code.DateNormalizer;
import com.modocument.infrastructure.transform.code.NumberNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Code-only stand-ins for the two extraction AI helpers (plan §4, heuristic
 * fallbacks), used when the AI is switched off, not configured, behind an
 * open circuit, or fails:
 * <ul>
 *   <li>{@link #inferSchema} — field names from the record keys and types
 *       from the sample values, reusing the code extractor's
 *       {@link NumberNormalizer} and {@link DateNormalizer};</li>
 *   <li>{@link #summarize} — a summary from the run's counters, fill rates
 *       and distinct counts. It never quotes a record value, so it is safe
 *       to show without masking.</li>
 * </ul>
 * Types use the same vocabulary as the SchemaInference prompt: string, int,
 * decimal, date, datetime, boolean.
 */
final class ExtractionHeuristics {

    static final int MAX_EXAMPLE_LENGTH = 200;

    private static final Pattern NAME_JUNK = Pattern.compile("[^A-Za-z0-9_]+");
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern DIGITS = Pattern.compile("[+-]?\\d+");
    private static final Set<String> BOOLEANS = Set.of("true", "false", "yes", "no");
    /** Longer digit runs are identifiers (accounts, cards, IBAN parts), not quantities. */
    private static final int MAX_NUMBER_DIGITS = 15;

    private ExtractionHeuristics() {
    }

    /** One field per distinct key, in first-seen order, typed from its non-blank values. */
    static List<InferredSchema.InferredField> inferSchema(List<Map<String, String>> records) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (Map<String, String> record : records) {
            if (record == null) {
                continue;
            }
            record.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    return;
                }
                List<String> values = columns.computeIfAbsent(key, k -> new ArrayList<>());
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            });
        }
        List<InferredSchema.InferredField> fields = new ArrayList<>(columns.size());
        Set<String> used = new HashSet<>();
        int index = 0;
        for (Map.Entry<String, List<String>> column : columns.entrySet()) {
            index++;
            List<String> values = column.getValue();
            String example = values.isEmpty() ? null : truncate(values.get(0));
            fields.add(new InferredSchema.InferredField(
                    uniqueName(fieldName(column.getKey(), index), used), dataType(values), example));
        }
        return fields;
    }

    /** {@code string} unless every non-blank value fits one narrower type. */
    static String dataType(List<String> values) {
        if (values.isEmpty()) {
            return "string";
        }
        if (values.stream().allMatch(v -> BOOLEANS.contains(v.toLowerCase(Locale.ROOT)))) {
            return "boolean";
        }
        if (values.stream().allMatch(v -> ISO_DATE_TIME.matcher(v).matches())) {
            return "datetime";
        }
        boolean anyNonDigit = values.stream().anyMatch(v -> !DIGITS.matcher(v).matches());
        if (anyNonDigit && DateNormalizer.normalizeColumn(values, List.of()).stream()
                .allMatch(DateNormalizer::isIsoDate)) {
            return "date";
        }
        NumberNormalizer.Style style = NumberNormalizer.detect(values);
        boolean allNumbers = true;
        boolean anyFraction = false;
        for (String value : values) {
            String normalized = NumberNormalizer.normalize(value, style);
            if (!NumberNormalizer.isPlainNumber(normalized) || isIdentifierLike(normalized)) {
                allNumbers = false;
                break;
            }
            anyFraction |= normalized.indexOf('.') >= 0;
        }
        if (allNumbers) {
            return anyFraction ? "decimal" : "int";
        }
        return "string";
    }

    /** Leading zeros ({@code 00123}) or very long digit runs: a code, kept as text. */
    private static boolean isIdentifierLike(String normalized) {
        String digits = normalized.startsWith("-") ? normalized.substring(1) : normalized;
        int point = digits.indexOf('.');
        String whole = point >= 0 ? digits.substring(0, point) : digits;
        return (whole.length() > 1 && whole.charAt(0) == '0')
                || digits.replace(".", "").length() > MAX_NUMBER_DIGITS;
    }

    /** Letters, digits and underscores only (what the AI path is asked for). */
    static String fieldName(String key, int index) {
        String name = NAME_JUNK.matcher(key.trim()).replaceAll("_").replaceAll("^_+|_+$", "");
        if (name.isEmpty()) {
            return "Field" + index;
        }
        return Character.isDigit(name.charAt(0)) ? "F_" + name : name;
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = name + "_" + suffix++;
        }
        return candidate;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_EXAMPLE_LENGTH ? value : value.substring(0, MAX_EXAMPLE_LENGTH);
    }

    /** Fill rate and distinct values of one key over the records read. */
    record FieldStats(String name, long filled, long distinct) {
    }

    /** Accumulates per-key fill and distinct counts over a bounded record scan. */
    static final class StatsCollector {

        /** Distinct values tracked per key; beyond it the count is reported as "N+". */
        static final int DISTINCT_CAP = 10_000;

        private final Map<String, long[]> filled = new LinkedHashMap<>();
        private final Map<String, Set<String>> distinct = new LinkedHashMap<>();
        private final Set<String> distinctCapped = new HashSet<>();
        private long records;

        void add(Map<String, String> record) {
            records++;
            if (record == null) {
                return;
            }
            record.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    return;
                }
                long[] count = filled.computeIfAbsent(key, k -> new long[1]);
                Set<String> seen = distinct.computeIfAbsent(key, k -> new HashSet<>());
                if (value != null && !value.isBlank()) {
                    count[0]++;
                    if (seen.size() < DISTINCT_CAP) {
                        seen.add(value.trim());
                    } else if (!seen.contains(value.trim())) {
                        distinctCapped.add(key);
                    }
                }
            });
        }

        long records() {
            return records;
        }

        List<FieldStats> fields() {
            List<FieldStats> out = new ArrayList<>(filled.size());
            filled.forEach((key, count) -> out.add(
                    new FieldStats(key, count[0], distinct.get(key).size())));
            return out;
        }

        boolean distinctCapped(String key) {
            return distinctCapped.contains(key);
        }
    }

    /**
     * Plain-text summary from counters only: status, record and task counts,
     * then one line per field with its fill rate and distinct count. Field
     * names are the template's/keys' names; no value is quoted.
     */
    static String summarize(String status, Map<String, Long> counters, Map<?, Long> tasks,
                            long processedRecords, StatsCollector stats, boolean partialScan,
                            FallbackReason reason) {
        StringBuilder out = new StringBuilder();
        out.append("Run status: ").append(status == null ? "UNKNOWN" : status).append(". ");
        out.append(processedRecords).append(" processed record(s)");
        long raw = counter(counters, ExtractionJob.RECORDS_RAW);
        long deduped = counter(counters, ExtractionJob.RECORDS_DEDUPED);
        if (raw > 0 || deduped > 0) {
            out.append(" from ").append(raw).append(" raw record(s), ")
                    .append(deduped).append(" deduplicated");
        }
        out.append(". ");
        long totalTasks = tasks == null ? 0 : tasks.values().stream().mapToLong(Long::longValue).sum();
        out.append(totalTasks).append(" task(s)");
        if (tasks != null && !tasks.isEmpty()) {
            List<String> parts = new ArrayList<>();
            tasks.forEach((taskStatus, count) -> {
                if (count != null && count > 0) {
                    parts.add(count + " " + String.valueOf(taskStatus).toLowerCase(Locale.ROOT));
                }
            });
            if (!parts.isEmpty()) {
                out.append(" (").append(String.join(", ", parts)).append(")");
            }
        }
        out.append(".");

        List<FieldStats> fields = stats.fields();
        if (stats.records() > 0 && !fields.isEmpty()) {
            out.append("\n").append(fields.size()).append(" field(s)");
            if (partialScan) {
                out.append(" over the first ").append(stats.records()).append(" record(s)");
            }
            out.append(":");
            for (FieldStats field : fields) {
                long pct = Math.round(100.0 * field.filled() / stats.records());
                out.append("\n- ").append(field.name()).append(": ").append(pct).append("% filled, ")
                        .append(field.distinct()).append(stats.distinctCapped(field.name()) ? "+" : "")
                        .append(" distinct value(s)");
            }
        }
        out.append("\nSummary built from run counters")
                .append(reason == null ? "" : " (AI unavailable: " + reason.name() + ")")
                .append(".");
        return out.toString();
    }

    private static long counter(Map<String, Long> counters, String key) {
        if (counters == null) {
            return 0;
        }
        Long value = counters.get(key);
        return value == null ? 0 : value;
    }
}
