package com.modocument.infrastructure.transform.code;

import com.modocument.infrastructure.file.MarkdownTableMaterializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipe tables through {@link MarkdownTableMaterializer}, without the parse
 * stage's strict gates (header count equal to the pre-scan, rows ±1): the
 * header row is detected here and the scorer judges the result.
 */
final class MarkdownTableStrategy implements ExtractionStrategy {

    private final MarkdownTableMaterializer materializer;

    MarkdownTableStrategy(MarkdownTableMaterializer materializer) {
        this.materializer = materializer;
    }

    @Override
    public CodeStrategy name() {
        return CodeStrategy.MARKDOWN_TABLE;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        String text = context.text();
        if (!materializer.isMarkdownTableText(text)) {
            return List.of();
        }
        int width = 0;
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("|") && trimmed.endsWith("|")) {
                width = Math.max(width, trimmed.substring(1, trimmed.length() - 1).split("\\|", -1).length);
            }
        }
        if (width < 2) {
            return List.of();
        }
        // Positional placeholder columns: every pipe row comes back as cells,
        // the header among them is found by TextTables.
        List<String> positions = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            positions.add("c" + i);
        }
        List<List<String>> records = new ArrayList<>();
        for (Map<String, String> row : materializer.parseRows(text, positions)) {
            List<String> cells = new ArrayList<>(width);
            for (String position : positions) {
                cells.add(row.getOrDefault(position, ""));
            }
            records.add(cells);
        }
        List<String> notes = new ArrayList<>();
        notes.add("pipe table");
        return TextTables.build(CodeStrategy.MARKDOWN_TABLE, records, context, notes)
                .map(List::of).orElse(List.of());
    }
}
