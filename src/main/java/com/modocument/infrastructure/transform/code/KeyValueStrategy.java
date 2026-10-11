package com.modocument.infrastructure.transform.code;

import com.modocument.application.validation.SafeRegex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Label-anchored values for forms and statements. Each field's labels (name,
 * spaced name, per-field aliases, global alias tokens — all
 * {@link Pattern#quote quoted}, case-insensitive — and the author's label
 * regex) are searched at a label boundary (line start, a tab, two spaces or
 * a pipe) followed by a separator: {@code Label: value}, {@code Label - value},
 * {@code Label = value}, {@code Label<TAB>value}, {@code Label   value}, or
 * the label alone with the value on the next line. Several labels may share a
 * line; a value ends where the next label starts. Free-text {@code Label:}
 * lines that name a field closely enough (ColumnNameSimilarity ≥ 0.85) count too.
 *
 * <p>Repeated blocks become records (statement style): fields seen before the
 * first repeating label are a preamble copied into every record; a new record
 * starts when the first repeating label comes back, when a field repeats
 * within the current record, or at a page break.
 */
final class KeyValueStrategy implements ExtractionStrategy {

    // Possessive runs and a non-space label start keep matching linear on
    // long whitespace (every space of a run is a potential start position).
    private static final String PREFIX =
            "(?:^[ \\t]*+|(?<=\\t)|(?<=[ \\t]{2})|(?<=\\|)[ \\t]*+)(?:[-•*][ \\t]++|\\d{1,2}[.)][ \\t]++)?";
    private static final String SEPARATOR =
            "(?:[ \\t]*+[:=]|[ \\t]*+[-\\u2013\\u2014](?=[ \\t])|\\t|[ ]{2,}|[ \\t]*+$)";
    private static final String LABEL_END = "(?![\\p{L}\\p{N}])";
    private static final Pattern GENERIC_LABEL = Pattern.compile(
            "^[ \\t]*(?:[-•*][ \\t]+|\\d{1,2}[.)][ \\t]+)?"
                    + "(\\p{L}[\\p{L}\\p{N} .'/#&()-]{0,48}?)[ \\t]*[:=][ \\t]*(.*)$");
    private static final Pattern UNKNOWN_LABEL_AFTER_GAP = Pattern.compile(
            "\\s{2,}\\p{L}[\\p{L}\\p{N} .'/#&()-]{0,40}:.*$");
    private static final Pattern WIDE_GAP = Pattern.compile("\\t|\\s{2,}");
    private static final int MAX_LINE = 2_000;
    private static final int MAX_USER_HITS_PER_LINE = 20;

    @Override
    public CodeStrategy name() {
        return CodeStrategy.KEY_VALUE;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        if (!context.input().hasFields()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        extract(context.text(), context, "extracted text").ifPresent(candidates::add);
        String layout = context.pdfLayoutText();
        if (layout != null && !layout.equals(context.text())) {
            extract(layout, context, "PDF text re-laid out by position").ifPresent(candidates::add);
        }
        return candidates;
    }

    private record Hit(int field, int line, int start, int valueStart, int end) {
    }

    private record Value(int field, String value, boolean pageBreakBefore) {
    }

    private Optional<Candidate> extract(String text, ExtractionContext context, String source) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        FieldMatcher matcher = context.matcher();
        List<FieldSpec> fields = matcher.fields();
        LiteralLabels literal = LiteralLabels.of(matcher);
        List<Pattern> authored = new ArrayList<>(fields.size());
        for (FieldSpec field : fields) {
            authored.add(matcher.labelPattern(field) == null ? null
                    : Pattern.compile(PREFIX + "(?:" + field.labelPattern() + ")" + SEPARATOR,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }

        // Not \\R: it counts a form feed as a line break and the page break would be lost.
        String[] lines = text.split("\\r?\\n|\\r", -1);
        boolean[] pageBreakAt = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].indexOf('\f') >= 0) {
                pageBreakAt[i] = true;
                lines[i] = lines[i].replace('\f', ' ');
            }
        }
        List<List<Hit>> hitsByLine = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            hitsByLine.add(hits(lines[i], i, literal, authored, matcher));
        }

        List<Value> values = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        boolean pageBreak = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (pageBreakAt[i]) {
                pageBreak = true;
            }
            if (consumed.contains(i)) {
                continue;
            }
            List<Hit> hits = hitsByLine.get(i);
            for (int h = 0; h < hits.size(); h++) {
                Hit hit = hits.get(h);
                int valueEnd = h + 1 < hits.size() ? hits.get(h + 1).start() : line.length();
                String value = line.substring(Math.min(hit.valueStart(), valueEnd), valueEnd);
                if (TextCells.isBlank(value) && h == hits.size() - 1) {
                    int next = nextValueLine(lines, hitsByLine, consumed, i);
                    if (next >= 0) {
                        value = lines[next];
                        consumed.add(next);
                    }
                }
                FieldSpec field = fields.get(hit.field());
                values.add(new Value(hit.field(), refine(value, field, matcher), pageBreak));
                pageBreak = false;
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<String> notes = new ArrayList<>();
        notes.add("labels searched in the " + source);
        List<Map<Integer, String>> records = records(values, notes);
        List<List<String>> rows = new ArrayList<>(records.size());
        Map<Set<Integer>, Integer> shapes = new HashMap<>();
        for (Map<Integer, String> record : records) {
            List<String> row = new ArrayList<>(fields.size());
            Set<Integer> filled = new HashSet<>();
            for (int f = 0; f < fields.size(); f++) {
                String value = record.getOrDefault(f, "");
                row.add(value);
                if (!value.isEmpty()) {
                    filled.add(f);
                }
            }
            shapes.merge(filled, 1, Integer::sum);
            rows.add(row);
        }
        int modal = shapes.values().stream().max(Integer::compare).orElse(0);
        long found = new HashSet<>(values.stream().map(Value::field).toList()).size();
        notes.add("labels found for " + found + " of " + fields.size() + " fields");
        return Optional.of(new Candidate(CodeStrategy.KEY_VALUE, TextCells.fieldHeaders(fields),
                rows, true, rows.isEmpty() ? 0 : (double) modal / rows.size(), notes));
    }

    /** All label hits on one line, left to right, overlaps resolved in favour of the longer label. */
    private static List<Hit> hits(String line, int index, LiteralLabels literal, List<Pattern> authored,
                                  FieldMatcher matcher) {
        if (line.isBlank() || line.length() > MAX_LINE) {
            return List.of();
        }
        List<Hit> found = new ArrayList<>();
        if (literal != null) {
            Matcher m = literal.pattern().matcher(line);
            while (m.find()) {
                int field = literal.field(m);
                if (field >= 0) {
                    found.add(new Hit(field, index, labelStart(line, m.start()), m.end(), m.end()));
                }
            }
        }
        for (int f = 0; f < authored.size(); f++) {
            Pattern user = authored.get(f);
            if (user != null && !matcher.budget().exhausted()) {
                try {
                    for (SafeRegex.Match m : matcher.budget().findAll(user, line, MAX_USER_HITS_PER_LINE)) {
                        found.add(new Hit(f, index, labelStart(line, m.start()), m.end(), m.end()));
                    }
                } catch (SafeRegex.RegexTimeoutException e) {
                    // Budget spent: the literal labels still apply.
                }
            }
        }
        if (found.isEmpty()) {
            Matcher generic = GENERIC_LABEL.matcher(line);
            if (generic.matches()) {
                FieldSpec field = matcher.bestField(generic.group(1), FieldMatcher.FUZZY_LABEL_THRESHOLD);
                if (field != null) {
                    found.add(new Hit(matcher.fields().indexOf(field), index, generic.start(1),
                            generic.start(2), generic.start(2)));
                }
            }
            return found;
        }
        found.sort(Comparator.comparingInt(Hit::start).thenComparing(Comparator.comparingInt(Hit::end).reversed()));
        List<Hit> resolved = new ArrayList<>();
        for (Hit hit : found) {
            Hit last = resolved.isEmpty() ? null : resolved.get(resolved.size() - 1);
            if (last != null && hit.start() < last.valueStart()) {
                continue;
            }
            resolved.add(hit);
        }
        return resolved;
    }

    private static int labelStart(String line, int matchStart) {
        int start = matchStart;
        while (start < line.length() && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) {
            start++;
        }
        return start;
    }

    /** The next non-blank line within two lines that carries no label, or -1. */
    private static int nextValueLine(String[] lines, List<List<Hit>> hitsByLine, Set<Integer> consumed, int from) {
        for (int j = from + 1; j < lines.length && j <= from + 2; j++) {
            if (lines[j].isBlank()) {
                continue;
            }
            return hitsByLine.get(j).isEmpty() && !consumed.contains(j) ? j : -1;
        }
        return -1;
    }

    /** Trims the raw value to what belongs to the field; the author's value regex picks its group. */
    private static String refine(String raw, FieldSpec field, FieldMatcher matcher) {
        String value = raw == null ? "" : raw;
        if (field.type() == FieldSpec.Type.TEXT) {
            value = UNKNOWN_LABEL_AFTER_GAP.matcher(value).replaceFirst("");
        } else {
            String trimmed = value.strip();
            Matcher gap = WIDE_GAP.matcher(trimmed);
            if (gap.find()) {
                value = trimmed.substring(0, gap.start());
            }
        }
        value = TextCells.clean(value);
        while (value.endsWith("|")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        Pattern valuePattern = matcher.valuePattern(field);
        if (valuePattern != null && !value.isEmpty() && !matcher.budget().exhausted()) {
            try {
                String current = value;
                value = matcher.budget().find(valuePattern, current)
                        .map(m -> TextCells.clean(m.firstGroupOrValue()))
                        .orElse(current);
            } catch (SafeRegex.RegexTimeoutException e) {
                // Keep the label-delimited value.
            }
        }
        return value;
    }

    /**
     * Groups label values into records: one record unless a field repeats.
     * Fields before the first repeating one form a preamble copied into
     * every record.
     */
    static List<Map<Integer, String>> records(List<Value> values, List<String> notes) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Value value : values) {
            counts.merge(value.field(), 1, Integer::sum);
        }
        int firstRepeat = -1;
        for (int i = 0; i < values.size(); i++) {
            if (counts.get(values.get(i).field()) > 1) {
                firstRepeat = i;
                break;
            }
        }
        if (firstRepeat < 0) {
            Map<Integer, String> single = new LinkedHashMap<>();
            for (Value value : values) {
                single.putIfAbsent(value.field(), value.value());
            }
            notes.add("1 record");
            return List.of(single);
        }
        Map<Integer, String> preamble = new LinkedHashMap<>();
        for (int i = 0; i < firstRepeat; i++) {
            preamble.putIfAbsent(values.get(i).field(), values.get(i).value());
        }
        int key = values.get(firstRepeat).field();
        List<Map<Integer, String>> records = new ArrayList<>();
        Map<Integer, String> current = new LinkedHashMap<>();
        for (int i = firstRepeat; i < values.size(); i++) {
            Value value = values.get(i);
            boolean startNew = !current.isEmpty()
                    && (value.field() == key || current.containsKey(value.field()) || value.pageBreakBefore());
            if (startNew) {
                records.add(current);
                current = new LinkedHashMap<>();
            }
            current.put(value.field(), value.value());
        }
        if (!current.isEmpty()) {
            records.add(current);
        }
        for (Map<Integer, String> record : records) {
            for (Map.Entry<Integer, String> entry : preamble.entrySet()) {
                String existing = record.get(entry.getKey());
                if (existing == null || existing.isEmpty()) {
                    record.put(entry.getKey(), entry.getValue());
                }
            }
        }
        notes.add(records.size() + " records from repeated label blocks");
        if (!preamble.isEmpty()) {
            notes.add(preamble.size() + " preamble field" + (preamble.size() == 1 ? "" : "s")
                    + " copied into every record");
        }
        return records;
    }

    /**
     * Every field's literal labels in one case-insensitive pattern, one
     * capturing group per label and the longest label first (so "Account
     * Number" wins over "Account" at the same spot): one scan per line
     * whatever the number of fields. Label words are {@link Pattern#quote quoted}.
     */
    record LiteralLabels(Pattern pattern, int[] fieldOfGroup) {

        static LiteralLabels of(FieldMatcher matcher) {
            record Label(int field, String regex, int length) {
            }
            List<Label> labels = new ArrayList<>();
            List<FieldSpec> fields = matcher.fields();
            for (int f = 0; f < fields.size(); f++) {
                for (String label : matcher.labels(fields.get(f))) {
                    List<String> quoted = new ArrayList<>();
                    for (String word : label.trim().split("\\s+")) {
                        if (!word.isEmpty()) {
                            quoted.add(Pattern.quote(word));
                        }
                    }
                    if (!quoted.isEmpty()) {
                        labels.add(new Label(f, String.join("[ \\t]+", quoted), label.length()));
                    }
                }
            }
            if (labels.isEmpty()) {
                return null;
            }
            labels.sort(Comparator.comparingInt(Label::length).reversed());
            StringBuilder alternatives = new StringBuilder();
            int[] fieldOfGroup = new int[labels.size() + 1];
            for (int i = 0; i < labels.size(); i++) {
                alternatives.append(i == 0 ? "" : "|").append('(').append(labels.get(i).regex()).append(')');
                fieldOfGroup[i + 1] = labels.get(i).field();
            }
            Pattern pattern = Pattern.compile(PREFIX + "(?:" + alternatives + ")" + LABEL_END + SEPARATOR,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            return new LiteralLabels(pattern, fieldOfGroup);
        }

        /** The field whose label group took part in the match, or -1. */
        int field(Matcher match) {
            for (int g = 1; g < fieldOfGroup.length; g++) {
                if (match.start(g) >= 0) {
                    return fieldOfGroup[g];
                }
            }
            return -1;
        }
    }
}
