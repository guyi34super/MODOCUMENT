package com.modocument.api.controller;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import org.springframework.beans.factory.annotation.Autowired;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.ChatEditRequest;
import com.modocument.application.dto.CreateIngestionJobRequest;
import com.modocument.application.dto.UpdateMappingRequest;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.UpdateRowsRequest;
import com.modocument.application.dto.UpdateWizardStepRequest;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.application.usecase.ChatEditTableUseCase;
import com.modocument.application.usecase.IngestionJobDeletionUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.application.usecase.UploadOwnership;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadContentChecker;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.ingestion.IngestionJobRunner;
import com.modocument.infrastructure.job.JobWaitService;
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
@Tag(name = "Ingestion Jobs", description = "Parse / map / validate pipeline")
public class IngestionJobController {

    private final IngestionJobUseCase useCase;
    private final ChatEditTableUseCase chatEdit;
    private final UploadStorage uploadStorage;
    private final SupportedDocumentTypes supportedTypes;
    private final IngestionJobRunner jobRunner;
    private final JobWaitService jobWaitService;
    private final UploadOwnership uploadOwnership;
    private final UploadContentChecker contentChecker;
    private final IngestionJobDeletionUseCase deletion;
    private final PiiMaskingService masking;

    /** ws28 (setter-injected; a no-op without Spring): the read audit. */
    private AuditRecorder recorder = spec -> { };

    public IngestionJobController(IngestionJobUseCase useCase,
                                  ChatEditTableUseCase chatEdit,
                                  UploadStorage uploadStorage,
                                  SupportedDocumentTypes supportedTypes,
                                  IngestionJobRunner jobRunner,
                                  JobWaitService jobWaitService,
                                  UploadOwnership uploadOwnership,
                                  UploadContentChecker contentChecker,
                                  IngestionJobDeletionUseCase deletion,
                                  PiiMaskingService masking) {
        this.useCase = useCase;
        this.chatEdit = chatEdit;
        this.uploadStorage = uploadStorage;
        this.supportedTypes = supportedTypes;
        this.jobRunner = jobRunner;
        this.jobWaitService = jobWaitService;
        this.uploadOwnership = uploadOwnership;
        this.contentChecker = contentChecker;
        this.deletion = deletion;
        this.masking = masking;
    }

    @Autowired
    void setAuditRecorder(AuditRecorder audit) {
        this.recorder = audit;
    }

    /** Multipart creation: uploads the file and (by default) runs the pipeline. */
    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping(value = "/api/ingestion/jobs", consumes = "multipart/form-data")
    public ResponseEntity<?> createFromUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("templateId") int templateId,
                                              @RequestParam(value = "autoRun", defaultValue = "true")
                                              boolean autoRun,
                                              @RequestParam(value = "runUntil", defaultValue = "MAPPED")
                                              String runUntil,
                                              @RequestParam(value = "wait", defaultValue = "false")
                                              boolean wait) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
        }
        if (!supportedTypes.isSupported(file.getOriginalFilename())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported types: " + supportedTypes.describeSupported() + "."));
        }
        // The content must look like what the extension claims (SEC-030): a
        // renamed executable must not enter the pipeline as a ".csv"/".xlsx".
        contentChecker.requireContentMatchesExtension(file.getOriginalFilename(), file);
        // Duplicate gate (C6): same owner + same bytes + same original name is
        // a re-upload, refused with 409 before anything lands on disk.
        String sha256Hex = FileUploadController.sha256(file);
        uploadOwnership.rejectDuplicate(file.getOriginalFilename(), sha256Hex);

        String storedName = uploadStorage.store(file);
        // Ownership row (V20): the uploader owns the stored file, so the JSON
        // create path can later reference it by name.
        uploadOwnership.record(storedName, file.getOriginalFilename(), file.getSize(), sha256Hex);
        return created(storedName, templateId, autoRun, parseUntil(runUntil), wait);
    }

    /** JSON creation for a file already stored via /api/fileupload/upload. */
    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping(value = "/api/ingestion/jobs", consumes = "application/json")
    public ResponseEntity<?> createFromExisting(@Valid @RequestBody CreateIngestionJobRequest request) {
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName is required."));
        }
        if (!supportedTypes.isSupported(request.getFileName())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported types: " + supportedTypes.describeSupported() + "."));
        }
        // The referenced upload must belong to the caller — another user's (or
        // an unknown/pre-V20) stored name is a 404, so the pipeline never reads
        // a file the caller does not own.
        uploadOwnership.require(request.getFileName());
        boolean autoRun = request.getAutoRun() == null || request.getAutoRun();
        JobStep until = request.getRunUntil() == null || request.getRunUntil().isBlank()
                ? JobStep.MAPPED
                : parseUntil(request.getRunUntil());
        boolean wait = request.getWait() != null && request.getWait();
        return created(request.getFileName(), request.getTemplateId(), autoRun, until, wait);
    }

    @RequiresPermission(Permission.INGESTION_READ)
    @GetMapping("/api/ingestion/supported-types")
    public ResponseEntity<?> supportedTypes() {
        return ResponseEntity.ok(Map.of(
                "extensions", supportedTypes.supportedExtensions(),
                "accept", String.join(",", supportedTypes.supportedExtensions()),
                "description", supportedTypes.describeSupported()));
    }

    /**
     * The two read routes are the SCREEN channels (gate C2, PRIV-005): a
     * READ_ONLY caller (an owner whose role was downgraded — cross-user
     * reads 404 on ownership anyway) gets SCREEN_READONLY with every
     * PII-classified column masked; a writing role gets its SCREEN_WORKING
     * raw view, because cell editing needs the true values. The write
     * routes below all require {@code ingestion:write}, so a READ_ONLY
     * caller can never reach an unmasked echo through them.
     */
    @RequiresPermission(Permission.INGESTION_READ)
    @GetMapping("/api/ingestion/jobs")
    public ResponseEntity<?> list(@RequestParam(value = "view", required = false) String view,
                                  @RequestParam(value = "page", required = false) Integer page,
                                  @RequestParam(value = "size", required = false) Integer size) {
        // ws28 (docs/optimization/contracts.md §5): the paged summary carries
        // no cell values — ids, names, statuses and counts — so it needs no
        // masking and costs one projection query per page.
        if ("summary".equalsIgnoreCase(view)) {
            return ResponseEntity.ok(useCase.summaries(page, size));
        }
        if (view != null && !view.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown view '" + view + "'; use view=summary or omit it."));
        }
        // Compatibility shape (full job views), capped at the 200 newest jobs.
        return ResponseEntity.ok(masking.maskJobViews(useCase.views(useCase.listCapped())));
    }

    @RequiresPermission(Permission.INGESTION_READ)
    @GetMapping("/api/ingestion/jobs/{id}")
    public ResponseEntity<?> get(@PathVariable int id,
                                 @RequestParam(defaultValue = "false") boolean wait,
                                 @RequestParam(defaultValue = "MAPPED") String until) {
        IngestionJobDto view = wait
                ? jobWaitService.waitForIngestionView(id, parseUntil(until))
                : useCase.view(useCase.get(id));
        // ws28 read audit: the job's parsed/mapped rows were shown (once per
        // actor, job and hour; gated by audit.readAuditEnabled).
        recorder.success(AuditActions.SENSITIVE_DATA_VIEWED, "IngestionJob", String.valueOf(id),
                null, Map.of("view", "ingestion-job-detail"));
        return ResponseEntity.ok(masking.maskJobView(view));
    }

    /**
     * Deletes the job, its export archive copies and (when no other job of
     * the owner references it) its uploaded file — the user's own early
     * exercise of the ws14 retention policy. Owner-scoped: someone else's
     * job 404s like a non-existent id.
     */
    @RequiresPermission(Permission.INGESTION_WRITE)
    @org.springframework.web.bind.annotation.DeleteMapping("/api/ingestion/jobs/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        deletion.delete(id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping("/api/ingestion/jobs/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable int id,
                                    @RequestParam(value = "until", defaultValue = "VALIDATED")
                                    String until) {
        IngestionJob job = useCase.get(id);
        JobStep target = parseUntil(until);
        if (job.getStatus() != JobStatus.RUNNING) {
            jobRunner.enqueueResume(id, target);
        }
        return ResponseEntity.ok(jobWaitService.waitForIngestionView(id, target));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping("/api/ingestion/jobs/{id}/remap")
    public ResponseEntity<?> remap(@PathVariable int id) {
        return ResponseEntity.ok(useCase.view(useCase.remap(id)));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping("/api/ingestion/jobs/{id}/revalidate")
    public ResponseEntity<?> revalidate(@PathVariable int id) {
        return ResponseEntity.ok(useCase.view(useCase.revalidate(id)));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PostMapping("/api/ingestion/jobs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable int id) {
        return ResponseEntity.ok(useCase.view(useCase.cancel(id)));
    }

    @RequiresPermission(Permission.AI_USE)
    @PostMapping("/api/ingestion/jobs/{id}/chat-edit")
    public ResponseEntity<?> chatEdit(@PathVariable int id, @Valid @RequestBody ChatEditRequest request) {
        return ResponseEntity.ok(useCase.view(
                chatEdit.handle(
                        id,
                        request == null ? null : request.getInstruction(),
                        request == null ? null : request.getRow(),
                        request == null ? null : request.getColumn())));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PutMapping("/api/ingestion/jobs/{id}/mapping")
    public ResponseEntity<?> updateMapping(@PathVariable int id,
                                           @Valid @RequestBody UpdateMappingRequest request) {
        if (request.getMapping() == null || request.getMapping().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "mapping is required."));
        }
        return ResponseEntity.ok(useCase.view(useCase.updateMapping(id, request.getMapping())));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PutMapping("/api/ingestion/jobs/{id}/rows")
    public ResponseEntity<?> updateRows(@PathVariable int id,
                                        @Valid @RequestBody UpdateRowsRequest request) {
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rows is required."));
        }
        return ResponseEntity.ok(useCase.view(useCase.updateRows(id, request.getRows())));
    }

    @RequiresPermission(Permission.INGESTION_WRITE)
    @PutMapping("/api/ingestion/jobs/{id}/wizard-step")
    public ResponseEntity<?> updateWizardStep(@PathVariable int id,
                                              @Valid @RequestBody UpdateWizardStepRequest request) {
        if (request == null || request.getWizardStep() == null || request.getWizardStep().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "wizardStep is required."));
        }
        return ResponseEntity.ok(useCase.view(
                useCase.setWizardStep(id, parseWizardStep(request.getWizardStep()))));
    }

    private ResponseEntity<?> created(String fileName, int templateId,
                                      boolean autoRun, JobStep runUntil, boolean wait) {
        IngestionJob job = useCase.create(fileName, templateId);
        if (!autoRun) {
            return created(job);
        }
        jobRunner.enqueueResume(job.getIngestionJobId(), runUntil);
        if (wait) {
            return ResponseEntity.ok(
                    jobWaitService.waitForIngestionView(job.getIngestionJobId(), runUntil));
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/ingestion/jobs/" + job.getIngestionJobId()))
                .body(useCase.view(job));
    }

    private ResponseEntity<?> created(IngestionJob job) {
        return ResponseEntity
                .created(URI.create("/api/ingestion/jobs/" + job.getIngestionJobId()))
                .body(useCase.view(job));
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
