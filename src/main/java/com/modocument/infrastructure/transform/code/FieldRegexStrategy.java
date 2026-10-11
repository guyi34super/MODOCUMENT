package com.modocument.infrastructure.transform.code;

import com.modocument.application.validation.SafeRegex;

import java.util.ArrayList;
import java.util.List;

/**
 * Each field's author-supplied value regex (vetted by {@link SafeRegex}, run
 * under the document's regex budget) searched over the whole text; group 1
 * is the value when the pattern has a group. The n-th match of every field
 * forms record n; a field matched exactly once while others repeat is a
 * document-level value copied into every record.
 */
final class FieldRegexStrategy implements ExtractionStrategy {

    static final int MAX_MATCHES = 10_000;

    @Override
    public CodeStrategy name() {
        return CodeStrategy.FIELD_REGEX;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        FieldMatcher matcher = context.matcher();
        String text = context.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<FieldSpec> fields = matcher.fields();
        List<List<String>> matches = new ArrayList<>(fields.size());
        boolean any = false;
        List<String> notes = new ArrayList<>();
        for (FieldSpec field : fields) {
            List<String> values = new ArrayList<>();
            if (matcher.valuePattern(field) != null) {
                try {
                    for (SafeRegex.Match match : context.budget().findAll(
                            matcher.valuePattern(field), text, MAX_MATCHES)) {
                        values.add(TextCells.clean(match.firstGroupOrValue()));
                    }
                } catch (SafeRegex.RegexTimeoutException e) {
                    notes.add("value pattern of field '" + field.name() + "' stopped: " + e.getMessage());
                    values.clear();
                }
            }
            any |= !values.isEmpty();
            matches.add(values);
        }
        if (!any) {
            return List.of();
        }
        int records = 1;
        for (List<String> values : matches) {
            if (values.size() > 1) {
                records = Math.max(records, values.size());
            }
        }
        int aligned = 0;
        int patterned = 0;
        List<List<String>> rows = new ArrayList<>(records);
        for (int r = 0; r < records; r++) {
            rows.add(new ArrayList<>());
        }
        for (List<String> values : matches) {
            if (!values.isEmpty()) {
                patterned++;
                if (values.size() == records || values.size() == 1) {
                    aligned++;
                }
            }
            for (int r = 0; r < records; r++) {
                String value;
                if (values.size() == 1) {
                    value = values.get(0);
                } else {
                    value = r < values.size() ? values.get(r) : "";
                }
                rows.get(r).add(value);
            }
        }
        notes.add(records + " record" + (records == 1 ? "" : "s") + " from value patterns of "
                + patterned + " field" + (patterned == 1 ? "" : "s"));
        return List.of(new Candidate(CodeStrategy.FIELD_REGEX, TextCells.fieldHeaders(fields), rows, true,
                patterned == 0 ? 0 : (double) aligned / patterned, notes));
    }
}
