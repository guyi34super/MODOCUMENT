package com.modocument.api.controller;

import com.modocument.application.dto.CreateIngestionJobRequest;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.UpdateMappingRequest;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.infrastructure.file.UploadStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Resumable ingestion jobs: upload a document against a template, walk it
 * through parse -> map -> validate with persisted checkpoints, review/adjust
 * the mapping, and resume after failures.
 */
@RestController
public class IngestionJobController {

    private final IngestionJobUseCase useCase;
    private final UploadStorage uploadStorage;

    public IngestionJobController(IngestionJobUseCase useCase, UploadStorage uploadStorage) {
        this.useCase = useCase;
        this.uploadStorage = uploadStorage;
    }

    /** Multipart creation: uploads the file and (by default) runs the pipeline. */
    @PostMapping(value = "/api/ingestion/jobs", consumes = "multipart/form-data")
    public ResponseEntity<?> createFromUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("templateId") int templateId,
                                              @RequestParam(value = "autoRun", defaultValue = "true")
                                              boolean autoRun) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
        }
        String storedName = uploadStorage.store(file);
        return created(create(storedName, templateId, autoRun));
    }

    /** JSON creation for a file already stored via /api/fileupload/upload. */
    @PostMapping(value = "/api/ingestion/jobs", consumes = "application/json")
    public ResponseEntity<?> createFromExisting(@RequestBody CreateIngestionJobRequest request) {
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName is required."));
        }
        boolean autoRun = request.getAutoRun() == null || request.getAutoRun();
        return created(create(request.getFileName(), request.getTemplateId(), autoRun));
    }

    @GetMapping("/api/ingestion/jobs")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(useCase.list().stream().map(IngestionJobDto::from).toList());
    }

    @GetMapping("/api/ingestion/jobs/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.get(id)));
    }

    /** Continues the pipeline from the last completed step (also after FAILED). */
    @PostMapping("/api/ingestion/jobs/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable int id) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.resume(id)));
    }

    @PostMapping("/api/ingestion/jobs/{id}/remap")
    public ResponseEntity<?> remap(@PathVariable int id) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.remap(id)));
    }

    @PostMapping("/api/ingestion/jobs/{id}/revalidate")
    public ResponseEntity<?> revalidate(@PathVariable int id) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.revalidate(id)));
    }

    @PostMapping("/api/ingestion/jobs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable int id) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.cancel(id)));
    }

    /** User-reviewed mapping override; clears validation so it can be re-run. */
    @PutMapping("/api/ingestion/jobs/{id}/mapping")
    public ResponseEntity<?> updateMapping(@PathVariable int id,
                                           @RequestBody UpdateMappingRequest request) {
        if (request.getMapping() == null || request.getMapping().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "mapping is required."));
        }
        return ResponseEntity.ok(IngestionJobDto.from(useCase.updateMapping(id, request.getMapping())));
    }

    private IngestionJob create(String fileName, int templateId, boolean autoRun) {
        IngestionJob job = useCase.create(fileName, templateId);
        return autoRun ? useCase.resume(job.getIngestionJobId()) : job;
    }

    private static ResponseEntity<?> created(IngestionJob job) {
        return ResponseEntity
                .created(URI.create("/api/ingestion/jobs/" + job.getIngestionJobId()))
                .body(IngestionJobDto.from(job));
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
