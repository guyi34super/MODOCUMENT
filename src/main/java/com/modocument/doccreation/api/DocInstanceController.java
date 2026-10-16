package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.doccreation.dto.CreateDocInstanceRequest;
import com.modocument.doccreation.dto.CreatedInstanceDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.dto.InstanceDto;
import com.modocument.doccreation.dto.PlaceImageRequest;
import com.modocument.doccreation.dto.UpdateDocInstanceRequest;
import com.modocument.doccreation.dto.UpdateValuesRequest;
import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Documents built on a PDF template (docs/doccreation/pdf-templates-contract.md
 * §5): values (merged), photos (tray, auto-fill, drag and drop with swap),
 * live page previews, validation (ws33, docs/doccreation/field-validation-contract.md
 * §4) and generation/download of the PDF.
 */
@RestController
@Tag(name = "Doc Creation: Instances", description = "Document values, photos, previews and PDF generation")
public class DocInstanceController {

    private final DocInstanceUseCase useCase;
    private final AuditRecorder audit;

    public DocInstanceController(DocInstanceUseCase useCase) {
        this(useCase, spec -> { });
    }

    @Autowired
    public DocInstanceController(DocInstanceUseCase useCase, AuditRecorder audit) {
        this.useCase = useCase;
        this.audit = audit;
    }

    // ------------------------------------------------------------- instances

    /** 201 {@code {id, instanceId}}; a DRAFT template is a 409. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances")
    public ResponseEntity<?> create(@Valid @RequestBody CreateDocInstanceRequest request) {
        CreatedInstanceDto created = useCase.create(request.getBlueprintId(), request.getTitle());
        return ResponseEntity.created(URI.create("/api/doccreation/instances/" + created.id())).body(created);
    }

    /** The caller's documents; {@code status} (optional) keeps one status, an unknown one is a 400. */
    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances")
    public ResponseEntity<?> list(@RequestParam(value = "blueprintId", required = false) Integer blueprintId,
                                  @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(useCase.list(blueprintId, status));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> rename(@PathVariable int id, @Valid @RequestBody UpdateDocInstanceRequest request) {
        return ResponseEntity.ok(useCase.rename(id, request.getTitle()));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- values

    /** Merges the values (empty string clears a key; unknown key 400). */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/instances/{id}/values")
    public ResponseEntity<?> updateValues(@PathVariable int id, @Valid @RequestBody UpdateValuesRequest request) {
        return ResponseEntity.ok(useCase.updateValues(id, request.values()));
    }

    // ------------------------------------------------------------ validation

    /**
     * ws33: checks the document against its fields and the template's rules;
     * VALIDATED without errors, DRAFT with them (both 200, the result in
     * {@code validation}); SUBMITTED/RELEASED 409. Audited DOC_VALIDATED
     * (SUCCESS / FAILED) with counts, the keys with errors and the rules
     * revision, never values.
     */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances/{id}/validate")
    public ResponseEntity<?> validate(@PathVariable int id) {
        InstanceDto result = useCase.validate(id);
        ValidationResultDto validation = result.validation();
        boolean passed = validation == null || validation.passed();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", result.status());
        values.put("errors", validation == null ? 0 : validation.errors().size());
        values.put("warnings", validation == null ? 0 : validation.warnings().size());
        values.put("setupWarnings", validation == null ? 0 : validation.setupWarnings().size());
        values.put("errorKeys", validation == null ? List.of() : new ArrayList<>(
                validation.errors().stream().map(ValidationResultDto.Issue::regionKey)
                        .collect(Collectors.toCollection(LinkedHashSet::new))));
        values.put("rulesRevision", validation == null ? null : validation.rulesRevision());
        audit.record(AuditEventSpec.builder(AuditActions.DOC_VALIDATED,
                        passed ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
                .entity("DocInstance", String.valueOf(id))
                .newValues(values)
                .build());
        return ResponseEntity.ok(result);
    }

    // ---------------------------------------------------------------- photos

    /** Multipart {@code files} (repeated) and {@code autoFill} (default true). */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping(value = "/api/doccreation/instances/{id}/images", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadImages(@PathVariable int id,
                                          @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                          @RequestParam(value = "autoFill", defaultValue = "true") boolean autoFill) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files uploaded."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.uploadImages(id, files, autoFill));
    }

    /** Drag and drop: {@code {regionKey}} places the photo (swapping), null moves it back to the tray. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/instances/{id}/images/{imageId}")
    public ResponseEntity<?> placeImage(@PathVariable int id, @PathVariable int imageId,
                                        @Valid @RequestBody(required = false) PlaceImageRequest request) {
        return ResponseEntity.ok(useCase.placeImage(id, imageId, request == null ? null : request.regionKey()));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/instances/{id}/images/{imageId}")
    public ResponseEntity<?> deleteImage(@PathVariable int id, @PathVariable int imageId) {
        useCase.deleteImage(id, imageId);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances/{id}/images/{imageId}/content")
    public ResponseEntity<byte[]> imageContent(@PathVariable int id, @PathVariable int imageId) {
        DocInstanceUseCase.StoredImage image = useCase.imageContent(id, imageId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.bytes());
    }

    // ------------------------------------------------------------- previews

    /** A page as PNG with the values drawn in (not stored). */
    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances/{id}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable int id, @PathVariable int page,
                                       @RequestParam(value = "dpi", required = false) Integer dpi) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_PNG)
                .body(useCase.pagePng(id, page, dpi));
    }

    // ------------------------------------------------------------ generation

    @RequiresPermission(Permission.DOC_GENERATE)
    @PostMapping("/api/doccreation/instances/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable int id) {
        GenerationResultDto result = useCase.generate(id);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "pdf");
        values.put("exact", result.exact());
        values.put("addedPages", result.addedPages());
        values.put("continuedKeys", result.continuedKeys().size());
        audit.success(AuditActions.DOC_GENERATED, "DocInstance", String.valueOf(id), null, values);
        return ResponseEntity.ok(result);
    }

    /**
     * The generated PDF, regenerated first when stale. {@code format} is
     * accepted for older clients; the output is always PDF.
     */
    @RequiresPermission(Permission.DOC_GENERATE)
    @GetMapping("/api/doccreation/instances/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable int id,
                                           @RequestParam(value = "format", required = false) String format) {
        DocInstanceUseCase.PdfDownload file = useCase.download(id);
        audit.success(AuditActions.DOC_DOWNLOADED, "DocInstance", String.valueOf(id), null,
                Map.of("format", "pdf", "bytes", file.bytes().length));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, DocCreationErrors.attachment(file.downloadName()))
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.bytes());
    }

    @ExceptionHandler(DocCreationException.class)
    public ResponseEntity<?> docCreation(DocCreationException e) {
        return DocCreationErrors.of(e);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return DocCreationErrors.notFound(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return DocCreationErrors.conflict(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return DocCreationErrors.badRequest(e);
    }
}
