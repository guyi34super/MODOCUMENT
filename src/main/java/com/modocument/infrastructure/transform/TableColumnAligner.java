package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Re-keys LLM output to pre-scanned header names when the column count matches. */
final class TableColumnAligner {

    private TableColumnAligner() {
    }

    static TableData alignToHints(TableData table, TableExtractionHints hints) {
        if (table == null || hints == null) {
            return table;
        }
        List<String> authoritative = sanitizeHeaders(hints.candidateHeaderCells());
        List<String> columns = table.getColumns();
        if (authoritative.isEmpty() || columns == null || columns.isEmpty()
                || authoritative.size() != columns.size()) {
            return table;
        }
        if (authoritative.equals(columns)) {
            return table;
        }
        List<Map<String, String>> rows = table.getRows() == null ? List.of() : table.getRows();
        List<Map<String, String>> alignedRows = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> aligned = new LinkedHashMap<>();
            for (int i = 0; i < authoritative.size(); i++) {
                String sourceKey = columns.get(i);
                aligned.put(authoritative.get(i), row.getOrDefault(sourceKey, ""));
            }
            alignedRows.add(aligned);
        }
        return new TableData(authoritative, alignedRows);
    }

    static Map<String, String> alignMappingKeys(Map<String, String> mapping,
                                                List<String> llmColumns,
                                                List<String> authoritative) {
        if (mapping == null || mapping.isEmpty() || authoritative == null
                || llmColumns == null || authoritative.size() != llmColumns.size()) {
            return mapping;
        }
        Map<String, String> aligned = new LinkedHashMap<>();
        for (int i = 0; i < authoritative.size(); i++) {
            String llmColumn = llmColumns.get(i);
            String header = authoritative.get(i);
            if (mapping.containsKey(llmColumn)) {
                aligned.put(header, mapping.get(llmColumn));
            } else if (mapping.containsKey(header)) {
                aligned.put(header, mapping.get(header));
            } else {
                aligned.put(header, null);
            }
        }
        return aligned;
    }

    private static List<String> sanitizeHeaders(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String header : headers) {
            cleaned.add(header == null ? "" : header.trim());
        }
        return cleaned;
    }
}
