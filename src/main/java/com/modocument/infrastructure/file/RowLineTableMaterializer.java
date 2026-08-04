package com.modocument.infrastructure.file;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically parses {@code Row N: col\\tcol\\t...} text into table rows. */
@Component
public class RowLineTableMaterializer {

    private static final Pattern ROW_LINE = Pattern.compile("^Row\\s+(\\d+):\\s*(.*)$");

    public boolean isRowLineText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int matches = 0;
        for (String line : text.lines().toList()) {
            if (ROW_LINE.matcher(line.trim()).matches()) {
                matches++;
                if (matches >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Parses data rows using the fixed column schema. The first {@code Row N:} line
     * is treated as the header and skipped.
     */
    public List<Map<String, String>> parseRows(String text, List<String> columns) {
        if (columns == null || columns.isEmpty() || text == null || text.isBlank()) {
            return List.of();
        }
        List<String> rowLines = new ArrayList<>();
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (ROW_LINE.matcher(trimmed).matches()) {
                rowLines.add(trimmed);
            }
        }
        if (rowLines.size() < 2) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < rowLines.size(); i++) {
            Matcher matcher = ROW_LINE.matcher(rowLines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            rows.add(toRowMap(columns, matcher.group(2)));
        }
        return rows;
    }

    private static Map<String, String> toRowMap(List<String> columns, String cellText) {
        String[] parts = cellText == null ? new String[0] : cellText.split("\t", -1);
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), i < parts.length ? parts[i].trim() : "");
        }
        return row;
    }
}
