package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/ingestion/jobs/{id}/export/database: connection details
 * for the user's target database. Credentials are used for the one export and
 * never persisted or logged.
 */
public class DatabaseExportRequest {

    @NotBlank
    @Size(max = 2000)
    private String jdbcUrl;

    @Size(max = 255)
    private String username;

    @Size(max = 512)
    private String password;

    /** DatabaseExportService#requireValidIdentifier owns the character rules. */
    @NotBlank
    @Size(max = 128)
    private String tableName;
    private Boolean createTableIfMissing;

    /**
     * ws18 (gate D2): the caller's stated reason for exporting more rows than
     * the role cap; recorded verbatim in the audit trail
     * (EXPORT_OVER_CAP_WITH_REASON). Optional below the cap.
     */
    @Size(max = 500)
    private String reason;

    public DatabaseExportRequest() {
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Boolean getCreateTableIfMissing() {
        return createTableIfMissing;
    }

    public void setCreateTableIfMissing(Boolean createTableIfMissing) {
        this.createTableIfMissing = createTableIfMissing;
    }
}
