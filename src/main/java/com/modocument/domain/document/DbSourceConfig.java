package com.modocument.domain.document;

import java.util.ArrayList;
import java.util.List;

/**
 * External-database connector settings. Mirrors DatabaseExportService's
 * safety rules: identifiers are allow-list validated and quoted, values only
 * travel as bound parameters, and credentials are supplied per-run in the
 * start-job request — never persisted with the source.
 */
public class DbSourceConfig {

    /** JDBC URL without credentials (jdbc:sqlserver://... or jdbc:h2:...). */
    private String jdbcUrl;
    private String table;
    /** Columns to read; empty = all columns. Identifier-validated. */
    private List<String> columns = new ArrayList<>();
    /** Monotonic column used for batching and incremental pulls (optional). */
    private String watermarkColumn;
    /** Rows per batch task and JDBC fetch size. */
    private int batchSize = 1000;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public String getWatermarkColumn() {
        return watermarkColumn;
    }

    public void setWatermarkColumn(String watermarkColumn) {
        this.watermarkColumn = watermarkColumn;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
