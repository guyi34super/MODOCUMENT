package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.SaveOverlayRequest;
import com.modocument.doccreation.dto.ValidationSettingRequest;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Exact PDF templates (docs/doccreation/pdf-templates-contract.md §5): upload
 * a PDF (blank, or filled with data to remove), review and edit the region
 * overlay as versions, page previews, the original and template PDFs.
 * Templates of the retired Word path answer 410.
 */
@RestController
@Tag(name = "Doc Creation: Blueprints", description = "Exact PDF templates: upload, overlay editor, versions")
public class DocBlueprintController {

    private final DocBlueprintUseCase useCase;

    public DocBlueprintController(DocBlueprintUseCase useCase) {
        this.useCase = useCase;
    }

    /** Multipart {@code file} (PDF only, checked by its bytes), {@code name}, {@code companyId}, {@code flow}. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping(value = "/api/doccreation/blueprints", consumes = "multipart/form-data")
    public ResponseEntity<?> create(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "name", required = false) String name,
                                    @RequestParam(value = "companyId", required = false) String companyId,
                                    @RequestParam(value = "flow", required = false) String flow) {
        BlueprintDto created = useCase.create(file, name, companyId, flow);
        return ResponseEntity.created(URI.create("/api/doccreation/blueprints/" + created.id())).body(created);
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(useCase.list());
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}")
    public ResponseEntity<?> get(@PathVariable int id,
                                 @RequestParam(value = "version", required = false) Integer version) {
        return ResponseEntity.ok(useCase.get(id, version));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable int id) {
        return ResponseEntity.ok(useCase.versions(id));
    }

    /** A page as PNG: {@code variant=original|template} (default template), {@code version}, {@code dpi} (36–200). */
    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable int id,
                                       @PathVariable int page,
                                       @RequestParam(value = "variant", required = false) String variant,
                                       @RequestParam(value = "version", required = false) Integer version,
                                       @RequestParam(value = "dpi", required = false) Integer dpi) {
        byte[] png = useCase.pagePng(id, page, variant, version, dpi);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /** Saves the reviewed or edited overlay (409 when {@code baseVersion} is stale). */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/overlay")
    public ResponseEntity<?> saveOverlay(@PathVariable int id, @Valid @RequestBody SaveOverlayRequest request) {
        return ResponseEntity.ok(useCase.saveOverlay(id, request));
    }

    /**
     * ws34 (data-templates-contract §8): {@code {enabled}} — whether new
     * documents from this template are validated (their default only).
     */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/validation-setting")
    public ResponseEntity<?> validationSetting(@PathVariable int id,
                                               @Valid @RequestBody ValidationSettingRequest request) {
        return ResponseEntity.ok(useCase.setValidationEnabled(id, request.enabled()));
    }

    /** Multipart {@code file}: a PNG/JPEG for an ADD_IMAGE edit; returns its {@code imageKey}. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping(value = "/api/doccreation/blueprints/{id}/edit-images", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadEditImage(@PathVariable int id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(Map.of("imageKey", useCase.uploadEditImage(id, file)));
    }

    /** The uploaded PDF ({@code variant=original}) or a version's template PDF (default). */
    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable int id,
                                       @RequestParam(value = "variant", required = false) String variant,
                                       @RequestParam(value = "version", required = false) Integer version) {
        DocBlueprintUseCase.PdfFile file = useCase.file(id, variant, version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, DocCreationErrors.attachment(file.downloadName()))
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.bytes());
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/blueprints/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
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
