package com.modocument.application.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/ingestion/jobs/{id}/export/file (ws18): the same file
 * export as the GET, for callers that state a reason above their role's row
 * cap (gate D2) — in a body, so the free-text reason never sits in a URL
 * (proxy/access logs, browser history), the same idiom as the ws14 DSR
 * export.
 */
public class FileExportRequest {

    @Size(max = 10)
    private String format = "csv";

    /** Why the caller needs more rows than the cap; recorded verbatim in the audit trail. */
    @Size(max = 500)
    private String reason;

    public FileExportRequest() {
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
