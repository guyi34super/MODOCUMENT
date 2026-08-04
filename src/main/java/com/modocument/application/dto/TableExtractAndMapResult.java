package com.modocument.application.dto;

import java.util.List;
import java.util.Map;

/** LLM response for combined column extraction, row data, and mapping suggestions. */
public class TableExtractAndMapResult {

    private List<String> columns;
    private Map<String, String> mapping;
    private List<Map<String, String>> rows;

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public Map<String, String> mapping() {
        return mapping;
    }

    public Map<String, String> getMapping() {
        return mapping;
    }

    public void setMapping(Map<String, String> mapping) {
        this.mapping = mapping;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }
}
