package com.modocument.application.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tabular form every ingested document is normalized into: ordered column
 * names plus one string map per row. Also the structured-output shape the LLM
 * returns for unstructured documents and chat edits.
 */
public class TableData {

    private List<String> columns = new ArrayList<>();
    private List<Map<String, String>> rows = new ArrayList<>();

    public TableData() {
    }

    public TableData(List<String> columns, List<Map<String, String>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }

    public boolean isEmpty() {
        return columns == null || columns.isEmpty() || rows == null || rows.isEmpty();
    }
}
