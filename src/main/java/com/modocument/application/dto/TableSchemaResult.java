package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.List;

/** LLM schema-pass output: column names only. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = TableSchemaResultDeserializer.class)
public class TableSchemaResult {

    private List<String> columns = new ArrayList<>();

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }
}
