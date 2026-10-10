package com.modocument.application.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Body of PUT /api/ingestion/jobs/{id}/rows: the full edited row set, keyed by template field. */
public class UpdateRowsRequest {

    @NotEmpty
    @Size(max = 50_000)
    private List<Map<String, String>> rows;

    public UpdateRowsRequest() {
    }

    public UpdateRowsRequest(List<Map<String, String>> rows) {
        this.rows = rows;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }
}
