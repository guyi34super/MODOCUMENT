package com.modocument.api.controller;

import com.modocument.application.dto.BridgeRequest;
import com.modocument.application.dto.CreateExtractionJobRequest;
import com.modocument.application.dto.ExtractionJobDto;
import com.modocument.application.usecase.BridgeToTemplateUseCase;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.InferSchemaUseCase;
import com.modocument.application.usecase.SummarizeExtractionUseCase;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.RawRecordStore;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.document.RawRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Distributed extraction jobs: create against a source, run through the
 * worker fleet, peek at raw/processed records and the audit trail, then
 * bridge the results into the document pipeline (template mapping,
 * validation, chat editing, export).
 */
@RestController
public class ExtractionJobController {

    private static final int MAX_PAGE_SIZE = 500;

    private final ExtractionJobUseCase jobs;
    private final BridgeToTemplateUseCase bridge;
    private final InferSchemaUseCase inferSchema;
    private final SummarizeExtractionUseCase summarize;
    private final RawRecordStore rawRecords;
    private final ProcessedRecordStore processedRecords;
    private final ExtractionAuditLog audit;

    public ExtractionJobController(ExtractionJobUseCase jobs,
                                   BridgeToTemplateUseCase bridge,
                                   InferSchemaUseCase inferSchema,
                                   SummarizeExtractionUseCase summarize,
                                   RawRecordStore rawRecords,
                                   ProcessedRecordStore processedRecords,
                                   ExtractionAuditLog audit) {
        this.jobs = jobs;
        this.bridge = bridge;
        this.inferSchema = inferSchema;
        this.summarize = summarize;
        this.rawRecords = rawRecords;
        this.processedRecords = processedRecords;
        this.audit = audit;
    }

    /** Creates a job and (by default) starts it immediately. */
    @PostMapping("/api/extraction/jobs")
    public ResponseEntity<?> create(@RequestBody CreateExtractionJobRequest request) {
        if (request.getSourceId() == null || request.getSourceId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceId is required."));
        }
        ExtractionJob job = jobs.create(request.getSourceId(), request.getTemplateId(),
                request.getDataSpec(), request.isIncremental(), request.getCredentials());
        if (request.getAutoStart() == null || request.getAutoStart()) {
            job = jobs.start(job.getId());
        }
        return ResponseEntity.created(URI.create("/api/extraction/jobs/" + job.getId()))
                .body(dto(job));
    }

    @GetMapping("/api/extraction/jobs")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(jobs.list().stream()
                .map(job -> ExtractionJobDto.from(job, null))
                .toList());
    }

    @GetMapping("/api/extraction/jobs/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return ResponseEntity.ok(dto(jobs.get(id)));
    }

    @PostMapping("/api/extraction/jobs/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        return ResponseEntity.ok(dto(jobs.start(id)));
    }

    @PostMapping("/api/extraction/jobs/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        return ResponseEntity.ok(dto(jobs.pause(id)));
    }

    @PostMapping("/api/extraction/jobs/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        return ResponseEntity.ok(dto(jobs.resume(id)));
    }

    @PostMapping("/api/extraction/jobs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        return ResponseEntity.ok(dto(jobs.cancel(id)));
    }

    /** Cursor-paged peek at the job's records (kind=raw|processed). */
    @GetMapping("/api/extraction/jobs/{id}/records")
    public ResponseEntity<?> records(@PathVariable String id,
                                     @RequestParam(defaultValue = "processed") String kind,
                                     @RequestParam(required = false) String afterId,
                                     @RequestParam(defaultValue = "50") int limit) {
        jobs.get(id);
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        Map<String, Object> page = new LinkedHashMap<>();
        if ("raw".equalsIgnoreCase(kind)) {
            List<RawRecord> batch = rawRecords.findBatch(id, afterId, pageSize);
            page.put("records", batch);
            page.put("nextAfterId", batch.isEmpty() ? null : batch.get(batch.size() - 1).getId());
            page.put("total", rawRecords.countByJob(id));
        } else {
            List<ProcessedRecord> batch = processedRecords.findBatch(id, afterId, pageSize);
            page.put("records", batch);
            page.put("nextAfterId", batch.isEmpty() ? null : batch.get(batch.size() - 1).getId());
            page.put("total", processedRecords.countByJob(id));
        }
        return ResponseEntity.ok(page);
    }

    /** Cursor-paged audit trail (every fetch, block, retry, LLM call). */
    @GetMapping("/api/extraction/jobs/{id}/audit")
    public ResponseEntity<?> auditTrail(@PathVariable String id,
                                        @RequestParam(required = false) String afterId,
                                        @RequestParam(defaultValue = "100") int limit) {
        jobs.get(id);
        var events = audit.findByJob(id, afterId, Math.min(Math.max(limit, 1), MAX_PAGE_SIZE));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("events", events);
        page.put("nextAfterId", events.isEmpty() ? null : events.get(events.size() - 1).getId());
        return ResponseEntity.ok(page);
    }

    /** Hands the extracted records to the document pipeline. */
    @PostMapping("/api/extraction/jobs/{id}/bridge")
    public ResponseEntity<?> bridge(@PathVariable String id, @RequestBody BridgeRequest request) {
        jobs.get(id); // owner gate: 404 before the pipeline reads another user's job
        return ResponseEntity.ok(bridge.bridge(id, request.getTemplateId(),
                request.getMaxRows(), request.isLlmClean()));
    }

    /** LLM: suggest a template schema from sample records. */
    @PostMapping("/api/extraction/jobs/{id}/infer-schema")
    public ResponseEntity<?> inferSchema(@PathVariable String id) {
        jobs.get(id); // owner gate: 404 before the LLM reads another user's records
        return ResponseEntity.ok(inferSchema.infer(id));
    }

    /** LLM: short human summary of the run. */
    @GetMapping("/api/extraction/jobs/{id}/summary")
    public ResponseEntity<?> summary(@PathVariable String id) {
        jobs.get(id); // owner gate: 404 before summarizing another user's job
        return ResponseEntity.ok(Map.of("summary", summarize.summarize(id)));
    }

    private ExtractionJobDto dto(ExtractionJob job) {
        return ExtractionJobDto.from(job, jobs.taskCounts(job.getId()));
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
