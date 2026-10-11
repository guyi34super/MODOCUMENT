package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/ingestion/jobs/{id}/export/database: the target database
 * as STRUCTURED fields (ws28). The JDBC URL is built server-side by the
 * integration hub's {@code DbDialect} with connect/socket timeouts and only
 * allow-listed options — a raw {@code jdbcUrl} is no longer accepted (400
 * naming the replacement fields; migration note in CHANGELOG 1.2.0).
 * Credentials are used for the one export and never persisted or logged.
 */
public class DatabaseExportRequest {

    /** POSTGRESQL, ORACLE, SQLSERVER or MARIADB (required; checked by the service). */
    @Size(max = 20)
    private String vendor;

    /** Host name or IPv4 address, no scheme, port or path (required). */
    @Size(max = 253)
    private String host;

    /** Optional; the vendor default (5432, 1521, 1433, 3306) when absent. */
    @Min(1)
    @Max(65535)
    private Integer port;

    /** Database name; Oracle: the service name (required). */
    @Size(max = 128)
    private String database;

    /** Optional schema the table lives in (same identifier rule as the table). */
    @Size(max = 128)
    private String schema;

    /**
     * Optional TLS mode, PostgreSQL vocabulary for every vendor: disable,
     * allow, prefer, require, verify-ca, verify-full (mapped per driver).
     */
    @Size(max = 20)
    private String sslmode;

    @Size(max = 128)
    private String username;

    @Size(max = 512)
    private String password;

    /**
     * The target table (required as {@code table} or {@code tableName}; the
     * UI sends both). DatabaseExportService#requireValidIdentifier owns the
     * character rules.
     */
    @Size(max = 128)
    private String tableName;
    /** Alias of {@link #tableName} (ws28 UI contract); either may be sent. */
    @Size(max = 128)
    private String table;
    private Boolean createTableIfMissing;

    /**
     * ws18 (gate D2): the caller's stated reason for exporting more rows than
     * the role cap; recorded verbatim in the audit trail
     * (EXPORT_OVER_CAP_WITH_REASON). Optional below the cap.
     */
    @Size(max = 500)
    private String reason;

    /**
     * Removed in 1.2.0: captured only so a pre-1.2 client gets a 400 that
     * names the replacement fields instead of a vague "vendor is required".
     * Never read, never logged (it may embed credentials).
     */
    @JsonIgnore
    private boolean legacyJdbcUrlSent;

    public DatabaseExportRequest() {
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getSslmode() {
        return sslmode;
    }

    public void setSslmode(String sslmode) {
        this.sslmode = sslmode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    /** {@code tableName}, else its alias {@code table}. */
    public String getTableName() {
        return tableName != null && !tableName.isBlank() ? tableName : table;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
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

    /** Write-only legacy field; see {@link #isLegacyJdbcUrlSent()}. */
    @JsonProperty(value = "jdbcUrl", access = JsonProperty.Access.WRITE_ONLY)
    @Schema(hidden = true)
    public void setJdbcUrl(String jdbcUrl) {
        this.legacyJdbcUrlSent = jdbcUrl != null && !jdbcUrl.isBlank();
    }

    /** Whether the body still carried the removed {@code jdbcUrl}. */
    @JsonIgnore
    public boolean isLegacyJdbcUrlSent() {
        return legacyJdbcUrlSent;
    }
}
