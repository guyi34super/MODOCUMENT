package com.modocument.infrastructure.file;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically parses Docling pipe-table row text into {@code TableData} rows. */
@Component
public class MarkdownTableMaterializer {

    public boolean isMarkdownTableText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && !isSeparatorRow(trimmed)) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, String>> parseRows(String text, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || !trimmed.endsWith("|") || isSeparatorRow(trimmed)) {
                continue;
            }
            List<String> cells = parseMarkdownRow(trimmed);
            rows.add(toRowMap(columns, cells));
        }
        return rows;
    }

    private static Map<String, String> toRowMap(List<String> columns, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String value = i < cells.size() ? cells.get(i) : "";
            row.put(columns.get(i), value == null ? "" : value);
        }
        return row;
    }

    private static List<String> parseMarkdownRow(String line) {
        String trimmed = line.substring(1, line.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private static boolean isSeparatorRow(String line) {
        return line.matches("\\|\\s*[-: ]+\\s*(\\|\\s*[-: ]+\\s*)+\\|");
    }
}
