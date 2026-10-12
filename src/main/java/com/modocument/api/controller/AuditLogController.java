package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.dto.AuditBankCounts;
import com.modocument.application.dto.AuditPage;
import com.modocument.application.usecase.AuditLogUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The audit viewer API (ws28, docs/optimization/contracts.md §3). AUDIT_READ
 * (ADMIN, APPROVER, PLATFORM_ADMIN); every read is the CALLER's bank — a
 * platform admin's is the platform bank — and the platform routes
 * additionally require the platform bank (403 otherwise). See
 * {@link AuditLogUseCase} for paging, masking and self-audit.
 */
@RestController
@Tag(name = "Audit Log", description = "Browse and export the bank's audit trail")
@RequiresPermission(Permission.AUDIT_READ)
public class AuditLogController {

    private final AuditLogUseCase auditLog;

    public AuditLogController(AuditLogUseCase auditLog) {
        this.auditLog = auditLog;
    }

    @Operation(summary = "The caller's bank's audit events, newest first (keyset-paged)")
    @GetMapping("/api/admin/audit")
    public AuditPage list(@RequestParam(required = false) String actor,
                          @RequestParam(required = false) List<String> action,
                          @RequestParam(required = false) String entityType,
                          @RequestParam(required = false) String entityId,
                          @RequestParam(required = false) String outcome,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(required = false) Integer limit) {
        return auditLog.list(new AuditLogUseCase.Filters(actor, action, entityType, entityId,
                outcome, from, to), cursor, limit);
    }

    /**
     * Streams CSV (same filters, no cursor): the newest
     * {@value AuditLogUseCase#EXPORT_MAX_ROWS} matches at most;
     * {@code X-Export-Total} says how many matched and
     * {@code X-Export-Truncated} whether the file was cut. Values are masked
     * (EXPORT channel). Audited AUDIT_LOG_EXPORTED.
     */
    @Operation(summary = "Export the filtered audit events as CSV (at most 50 000 rows)")
    @GetMapping(value = "/api/admin/audit/export", produces = "text/csv")
    public void export(@RequestParam(required = false) String actor,
                       @RequestParam(required = false) List<String> action,
                       @RequestParam(required = false) String entityType,
                       @RequestParam(required = false) String entityId,
                       @RequestParam(required = false) String outcome,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       HttpServletResponse response) throws IOException {
        PrintWriter[] writer = new PrintWriter[1];
        AuditLogUseCase.Export export = auditLog.export(new AuditLogUseCase.Filters(actor, action,
                entityType, entityId, outcome, from, to), new AuditLogUseCase.CsvSink() {
            @Override
            public void write(String line) {
                writer[0].write(line);
                writer[0].write("\r\n");
            }

            @Override
            public void flush() {
                writer[0].flush();
                if (writer[0].checkError()) {
                    throw new UncheckedIOException(new IOException("The client went away."));
                }
            }
        });
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Export-Total", String.valueOf(export.matched()));
        response.setHeader("X-Export-Truncated", String.valueOf(export.truncated()));
        writer[0] = response.getWriter();
        export.stream().run();
    }

    @Operation(summary = "The audit action catalogue (filter picker)")
    @GetMapping("/api/admin/audit/actions")
    public Map<String, List<AuditActions.Action>> actions() {
        return Map.of("actions", auditLog.actions());
    }

    @Operation(summary = "The platform bank's own audit events (platform bank only)")
    @GetMapping("/api/platform/audit")
    public AuditPage platformList(@RequestParam(required = false) String actor,
                                  @RequestParam(required = false) List<String> action,
                                  @RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) String entityId,
                                  @RequestParam(required = false) String outcome,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return auditLog.listPlatform(new AuditLogUseCase.Filters(actor, action, entityType,
                entityId, outcome, from, to), cursor, limit);
    }

    @Operation(summary = "Audit event counts per bank (counts only; platform bank only)")
    @GetMapping("/api/platform/audit/counts")
    public AuditBankCounts platformCounts(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return auditLog.platformCounts(from, to);
    }
}
