package com.modocument.api.controller;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.FileExportRequest;
import com.modocument.application.dto.TableData;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.infrastructure.export.DatabaseExportService;
import com.modocument.infrastructure.export.ExportCapExceededException;
import com.modocument.infrastructure.export.ExportGuard;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.file.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Export of reviewed/validated ingestion data: download as csv/json/xlsx (a
 * copy is archived under wwwroot/exports) or push directly into a
 * user-supplied database.
 *
 * <p>ws18 (gate D2, PRIV-002): BOTH paths count the rows first and pass the
 * {@link ExportGuard} before anything is exported — above the caller's role
 * cap an export needs a stated {@code reason} (GET query parameter, or the
 * POST bodies), otherwise it is refused with 403
 * {@code {"code":"EXPORT_CAP_EXCEEDED","cap":..,"rows":..}}, audited
 * EXPORT_BLOCKED and alerted at once. Every completed export is logged as
 * EXPORT_RUN in the audit trail.
 */
@RestController
@RequiresPermission(Permission.EXPORT_RUN)
public class IngestionExportController {

    private static final Logger log = LoggerFactory.getLogger(IngestionExportController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IngestionJobUseCase jobs;
    private final FileExportService fileExport;
    private final DatabaseExportService databaseExport;
    private final PiiMaskingService masking;
    private final ExportGuard exportGuard;
    private final Path exportsDir;

    public IngestionExportController(IngestionJobUseCase jobs,
                                     FileExportService fileExport,
                                     DatabaseExportService databaseExport,
                                     PiiMaskingService masking,
                                     ExportGuard exportGuard,
                                     StorageProperties storage) {
        this.jobs = jobs;
        this.fileExport = fileExport;
        this.databaseExport = databaseExport;
        this.masking = masking;
        this.exportGuard = exportGuard;
        this.exportsDir = storage.exportsDir();
    }

    @GetMapping("/api/ingestion/jobs/{id}/export/file")
    public ResponseEntity<?> exportFile(@PathVariable int id,
                                        @RequestParam(defaultValue = "csv") String format,
                                        @RequestParam(required = false) String reason) {
        return fileExport(id, format, reason);
    }

    /**
     * Same export as the GET, with the over-cap reason in the body so the
     * free text never sits in a URL (the SPA's retry after a 403
     * EXPORT_CAP_EXCEEDED uses this).
     */
    @PostMapping("/api/ingestion/jobs/{id}/export/file")
    public ResponseEntity<?> exportFileWithReason(@PathVariable int id,
                                                  @Valid @RequestBody FileExportRequest request) {
        return fileExport(id, request.getFormat() == null ? "csv" : request.getFormat(),
                request.getReason());
    }

    private ResponseEntity<?> fileExport(int id, String format, String reason) {
        // jobs.get is owner-scoped (getByIdAndOwner): only the job owner can
        // export or re-download — anyone else gets a 404 here.
        IngestionJob job = jobs.get(id);
        TableData rawTable = exportableRows(job);
        FileExportService.Format exportFormat = FileExportService.Format.parse(format);

        // Count BEFORE exporting: the cap decision (and its audit/alert)
        // happens before a single byte is produced.
        ExportGuard.Attempt attempt = new ExportGuard.Attempt(job.getIngestionJobId(), "file",
                exportFormat.name(), rawTable.getRows().size(), reason);
        ExportGuard.Decision decision = exportGuard.authorize(attempt);

        TableData table = masking.maskForExport(job.getTemplateId(), rawTable);
        byte[] bytes = fileExport.export(table, exportFormat);
        // Downloading is a GET, so browsers prefetch it and users double-click
        // it: a timestamped name piled up a new archive copy every time. One
        // copy per job and format, overwritten, is the useful artifact.
        String exportName = "job" + job.getIngestionJobId() + exportFormat.extension();
        archiveCopy(job.getOwnerUserId(), exportName, bytes);

        Map<String, Object> exportInfo = new LinkedHashMap<>();
        exportInfo.put("type", "file");
        exportInfo.put("format", exportFormat.name());
        exportInfo.put("fileName", exportName);
        exportInfo.put("rowCount", table.getRows().size());
        jobs.recordExport(job, exportInfo);
        exportGuard.recordExport(attempt, decision, table.getRows().size());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportName + "\"")
                .contentType(MediaType.parseMediaType(exportFormat.contentType()))
                .body(bytes);
    }

    @PostMapping("/api/ingestion/jobs/{id}/export/database")
    public ResponseEntity<?> exportDatabase(@PathVariable int id,
                                            @Valid @RequestBody DatabaseExportRequest request) {
        IngestionJob job = jobs.get(id);
        TableData rawTable = exportableRows(job);
        String target = DatabaseExportService.describeTarget(request.getJdbcUrl());

        // Same cap as the file path — counted before the target is touched.
        ExportGuard.Attempt attempt = new ExportGuard.Attempt(job.getIngestionJobId(), "database",
                target, rawTable.getRows().size(), request.getReason());
        ExportGuard.Decision decision = exportGuard.authorize(attempt);

        TableData table = masking.maskForExport(job.getTemplateId(), rawTable);
        int rowCount = databaseExport.export(table, request);

        // Persist only what is safe: never the credentials.
        Map<String, Object> exportInfo = new LinkedHashMap<>();
        exportInfo.put("type", "database");
        exportInfo.put("target", target);
        exportInfo.put("tableName", request.getTableName());
        exportInfo.put("rowCount", rowCount);
        IngestionJob updated = jobs.recordExport(job, exportInfo);
        exportGuard.recordExport(attempt, decision, rowCount);

        return ResponseEntity.ok(jobs.view(updated));
    }

    /**
     * Exports operate on finished work only: an active job whose every template
     * field is mapped and whose rows all pass validation. The use case owns that
     * rule, so a caller bypassing the UI hits the same wall.
     *
     * <p>Returns the UNMASKED rows so they can be counted against the cap;
     * the callers mask them EXPORT-channel (gate C2, PRIV-005) before
     * anything leaves: PII-classified columns are masked per their kind for
     * every role, so the file download, its per-owner archive copy and the
     * database export all carry only masked values.
     */
    private TableData exportableRows(IngestionJob job) {
        jobs.requireExportable(job);
        String json = job.getMappedRowsJson();
        if (json == null) {
            throw new IllegalStateException("Job has no mapped rows to export.");
        }
        TableData table = readTable(json);
        if (table.isEmpty()) {
            throw new IllegalStateException(
                    "Job " + job.getIngestionJobId() + " has no mapped columns to export; "
                            + "map at least one column to a template field first.");
        }
        return table;
    }

    private static TableData readTable(String json) {
        try {
            return MAPPER.readValue(json, TableData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the job's mapped rows.", e);
        }
    }

    /**
     * Archive copies are scoped per owner (exports/u{ownerId}/...): the
     * exports folder is shared infrastructure, so nothing that later serves
     * or syncs it can hand one user's export to another. Nothing in the API
     * reads these back today — the download endpoint above regenerates from
     * the job (owner-scoped) instead of serving the archive.
     */
    private void archiveCopy(int ownerUserId, String exportName, byte[] bytes) {
        try {
            Path ownerDir = exportsDir.resolve("u" + ownerUserId);
            Files.createDirectories(ownerDir);
            Files.write(ownerDir.resolve(exportName), bytes);
        } catch (IOException e) {
            // The download itself succeeded; a missing archive copy must not
            // fail the user's export.
            log.warn("Could not archive export {}: {}", exportName, e.getMessage());
        }
    }

    /** gate D2: a stable machine code the SPA answers by prompting for a reason. */
    @ExceptionHandler(ExportCapExceededException.class)
    public ResponseEntity<?> capExceeded(ExportCapExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", ExportCapExceededException.CODE);
        body.put("cap", e.cap());
        body.put("rows", e.rows());
        body.put("reasonMaxLength", e.reasonMaxLength());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
