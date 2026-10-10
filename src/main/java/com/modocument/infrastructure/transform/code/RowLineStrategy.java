package com.modocument.infrastructure.transform.code;

import com.modocument.infrastructure.file.RowLineTableMaterializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The workbook serialization ({@code Row N: a<TAB>b<TAB>c}) through
 * {@link RowLineTableMaterializer}, without the parse stage's strict gates:
 * the real header is found below title rows instead of assuming row 1.
 */
final class RowLineStrategy implements ExtractionStrategy {

    private static final Pattern ROW_LINE = Pattern.compile("^Row\\s+\\d+:\\s*(.*)$");

    private final RowLineTableMaterializer materializer;

    RowLineStrategy(RowLineTableMaterializer materializer) {
        this.materializer = materializer;
    }

    @Override
    public CodeStrategy name() {
        return CodeStrategy.ROW_LINE;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        String text = context.text();
        if (!materializer.isRowLineText(text)) {
            return List.of();
        }
        int width = 0;
        for (String line : text.lines().toList()) {
            Matcher matcher = ROW_LINE.matcher(line.trim());
            if (matcher.matches()) {
                width = Math.max(width, matcher.group(1).split("\t", -1).length);
            }
        }
        if (width < 2) {
            return List.of();
        }
        List<String> positions = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            positions.add("c" + i);
        }
        // The materializer treats the first Row line as the header and skips
        // it; header detection happens in TextTables, so a placeholder first
        // line keeps every real row.
        List<List<String>> records = new ArrayList<>();
        for (Map<String, String> row : materializer.parseRows("Row 0:\n" + text, positions)) {
            List<String> cells = new ArrayList<>(width);
            for (String position : positions) {
                cells.add(row.getOrDefault(position, ""));
            }
            records.add(cells);
        }
        List<String> notes = new ArrayList<>();
        notes.add("workbook row lines");
        return TextTables.build(CodeStrategy.ROW_LINE, records, context, notes)
                .map(List::of).orElse(List.of());
    }
}
