package com.modocument.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.TableData;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.export.DatabaseExportService;
import com.modocument.infrastructure.export.FileExportService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Export of reviewed/validated ingestion data: download as csv/json/xlsx (a
 * copy is archived under wwwroot/exports) or push directly into a
 * user-supplied database.
 */
@RestController
public class IngestionExportController {

    /** Exported file copies live under ./wwwroot/exports. */
    public static final Path EXPORTS_DIR = Path.of("wwwroot", "exports");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IngestionJobUseCase jobs;
    private final FileExportService fileExport;
    private final DatabaseExportService databaseExport;

    public IngestionExportController(IngestionJobUseCase jobs,
                                     FileExportService fileExport,
                                     DatabaseExportService databaseExport) {
        this.jobs = jobs;
        this.fileExport = fileExport;
        this.databaseExport = databaseExport;
    }

    @GetMapping("/api/ingestion/jobs/{id}/export/file")
    public ResponseEntity<?> exportFile(@PathVariable int id,
                                        @RequestParam(defaultValue = "csv") String format) {
        IngestionJob job = jobs.get(id);
        TableData table = exportableTable(job);
        FileExportService.Format exportFormat = FileExportService.Format.parse(format);

        byte[] bytes = fileExport.export(table, exportFormat);
        String exportName = "job" + job.getIngestionJobId() + "_"
                + LocalDateTime.now().format(TIMESTAMP) + exportFormat.extension();
        archiveCopy(exportName, bytes);

        Map<String, Object> exportInfo = new LinkedHashMap<>();
        exportInfo.put("type", "file");
        exportInfo.put("format", exportFormat.name());
        exportInfo.put("fileName", exportName);
        exportInfo.put("rowCount", table.getRows().size());
        jobs.recordExport(job, exportInfo);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportName + "\"")
                .contentType(MediaType.parseMediaType(exportFormat.contentType()))
                .body(bytes);
    }

    @PostMapping("/api/ingestion/jobs/{id}/export/database")
    public ResponseEntity<?> exportDatabase(@PathVariable int id,
                                            @RequestBody DatabaseExportRequest request) {
        IngestionJob job = jobs.get(id);
        TableData table = exportableTable(job);

        int rowCount = databaseExport.export(table, request);

        // Persist only what is safe: never the credentials.
        Map<String, Object> exportInfo = new LinkedHashMap<>();
        exportInfo.put("type", "database");
        exportInfo.put("target", DatabaseExportService.describeTarget(request.getJdbcUrl()));
        exportInfo.put("tableName", request.getTableName());
        exportInfo.put("rowCount", rowCount);
        IngestionJob updated = jobs.recordExport(job, exportInfo);

        return ResponseEntity.ok(IngestionJobDto.from(updated));
    }

    /** Exports operate on the mapped (post-review) rows of a validated job. */
    private static TableData exportableTable(IngestionJob job) {
        if (job.getCurrentStep() != JobStep.VALIDATED && job.getCurrentStep() != JobStep.EXPORTED) {
            throw new IllegalStateException(
                    "Job " + job.getIngestionJobId() + " has not been validated yet "
                            + "(current step: " + job.getCurrentStep() + ").");
        }
        String json = job.getMappedRowsJson();
        if (json == null) {
            throw new IllegalStateException("Job has no mapped rows to export.");
        }
        try {
            return MAPPER.readValue(json, TableData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the job's mapped rows.", e);
        }
    }

    private static void archiveCopy(String exportName, byte[] bytes) {
        try {
            Files.createDirectories(EXPORTS_DIR);
            Files.write(EXPORTS_DIR.resolve(exportName), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not archive the export file.", e);
        }
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
