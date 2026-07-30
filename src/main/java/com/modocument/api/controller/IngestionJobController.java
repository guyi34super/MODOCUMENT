package com.modocument.api.controller;

import com.modocument.application.dto.ChatEditRequest;
import com.modocument.application.dto.CreateIngestionJobRequest;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.UpdateMappingRequest;
import com.modocument.application.dto.UpdateRowsRequest;
import com.modocument.application.dto.UpdateWizardStepRequest;
import com.modocument.application.usecase.ChatEditTableUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
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
    private final ChatEditTableUseCase chatEdit;
    private final UploadStorage uploadStorage;
    private final SupportedDocumentTypes supportedTypes;

    public IngestionJobController(IngestionJobUseCase useCase,
                                  ChatEditTableUseCase chatEdit,
                                  UploadStorage uploadStorage,
                                  SupportedDocumentTypes supportedTypes) {
        this.useCase = useCase;
        this.chatEdit = chatEdit;
        this.uploadStorage = uploadStorage;
        this.supportedTypes = supportedTypes;
    }

    /** Multipart creation: uploads the file and (by default) runs the pipeline. */
    @PostMapping(value = "/api/ingestion/jobs", consumes = "multipart/form-data")
    public ResponseEntity<?> createFromUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("templateId") int templateId,
                                              @RequestParam(value = "autoRun", defaultValue = "true")
                                              boolean autoRun,
                                              @RequestParam(value = "runUntil", defaultValue = "MAPPED")
                                              String runUntil) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
        }
        if (!supportedTypes.isSupported(file.getOriginalFilename())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported types: " + supportedTypes.describeSupported() + "."));
        }
        String storedName = uploadStorage.store(file);
        return created(create(storedName, templateId, autoRun, parseUntil(runUntil)));
    }

    /** JSON creation for a file already stored via /api/fileupload/upload. */
    @PostMapping(value = "/api/ingestion/jobs", consumes = "application/json")
    public ResponseEntity<?> createFromExisting(@RequestBody CreateIngestionJobRequest request) {
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName is required."));
        }
        if (!supportedTypes.isSupported(request.getFileName())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported types: " + supportedTypes.describeSupported() + "."));
        }
        boolean autoRun = request.getAutoRun() == null || request.getAutoRun();
        JobStep until = request.getRunUntil() == null || request.getRunUntil().isBlank()
                ? JobStep.MAPPED
                : parseUntil(request.getRunUntil());
        return created(create(request.getFileName(), request.getTemplateId(), autoRun, until));
    }

    /**
     * What this build can actually parse. The file picker reads it so it never
     * offers a format the upload would reject (docling formats appear only
     * when a docling endpoint is configured).
     */
    @GetMapping("/api/ingestion/supported-types")
    public ResponseEntity<?> supportedTypes() {
        return ResponseEntity.ok(Map.of(
                "extensions", supportedTypes.supportedExtensions(),
                "accept", String.join(",", supportedTypes.supportedExtensions()),
                "description", supportedTypes.describeSupported()));
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
    public ResponseEntity<?> resume(@PathVariable int id,
                                    @RequestParam(value = "until", defaultValue = "VALIDATED")
                                    String until) {
        return ResponseEntity.ok(IngestionJobDto.from(useCase.resume(id, parseUntil(until))));
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

    /** Modifies the job's current table via a natural-language instruction. */
    @PostMapping("/api/ingestion/jobs/{id}/chat-edit")
    public ResponseEntity<?> chatEdit(@PathVariable int id, @RequestBody ChatEditRequest request) {
        return ResponseEntity.ok(IngestionJobDto.from(
                chatEdit.handle(id, request == null ? null : request.getInstruction())));
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

    /** Persists user-edited row values; clears validation so it can be re-run. */
    @PutMapping("/api/ingestion/jobs/{id}/rows")
    public ResponseEntity<?> updateRows(@PathVariable int id,
                                        @RequestBody UpdateRowsRequest request) {
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rows is required."));
        }
        return ResponseEntity.ok(IngestionJobDto.from(useCase.updateRows(id, request.getRows())));
    }

    /** Checkpoints the user's wizard position so reopening the job resumes there. */
    @PutMapping("/api/ingestion/jobs/{id}/wizard-step")
    public ResponseEntity<?> updateWizardStep(@PathVariable int id,
                                              @RequestBody UpdateWizardStepRequest request) {
        if (request == null || request.getWizardStep() == null || request.getWizardStep().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "wizardStep is required."));
        }
        return ResponseEntity.ok(IngestionJobDto.from(
                useCase.setWizardStep(id, parseWizardStep(request.getWizardStep()))));
    }

    private IngestionJob create(String fileName, int templateId, boolean autoRun, JobStep runUntil) {
        IngestionJob job = useCase.create(fileName, templateId);
        return autoRun ? useCase.resume(job.getIngestionJobId(), runUntil) : job;
    }

    private static JobStep parseUntil(String value) {
        try {
            JobStep step = JobStep.valueOf(value.trim().toUpperCase());
            if (step == JobStep.UPLOADED || step == JobStep.EXPORTED) {
                throw new IllegalArgumentException(
                        "until must be one of PARSED, MAPPED, or VALIDATED.");
            }
            return step;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown pipeline step '" + value + "'; use PARSED, MAPPED, or VALIDATED.");
        }
    }

    private static WizardStep parseWizardStep(String value) {
        try {
            return WizardStep.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown wizard step '" + value + "'; use MAPPING, VALIDATION, or EXPORT.");
        }
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
