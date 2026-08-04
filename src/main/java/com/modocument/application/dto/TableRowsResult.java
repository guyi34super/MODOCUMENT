package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** LLM row-batch output: data rows only (no columns key). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = TableRowsResultDeserializer.class)
public class TableRowsResult {

    private List<Map<String, String>> rows = new ArrayList<>();

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }
}
