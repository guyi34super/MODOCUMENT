package com.modocument.doccreation.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.AssignAssetRequest;
import com.modocument.doccreation.dto.CreateDocInstanceRequest;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.ReorderBlocksRequest;
import com.modocument.doccreation.dto.UpdateBlockRequest;
import com.modocument.doccreation.dto.UpdateDocInstanceRequest;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Document instances built on a blueprint: the section-fill builder (paragraph
 * and image blocks), bulk image upload with auto-assignment, authenticated
 * image previews, and generation/download of the reproduced document.
 */
@RestController
@Tag(name = "Doc Creation: Instances", description = "Document authoring, assets and generation")
public class DocInstanceController {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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

    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances")
    public ResponseEntity<?> create(@Valid @RequestBody CreateDocInstanceRequest request) {
        InstanceSummaryDto created = useCase.create(request.getBlueprintId(), request.getTitle());
        return ResponseEntity
                .created(URI.create("/api/doccreation/instances/" + created.instanceId()))
                .body(created);
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances")
    public ResponseEntity<?> list(@RequestParam(value = "blueprintId", required = false) Integer blueprintId) {
        return ResponseEntity.ok(useCase.list(blueprintId));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.getBuilder(id));
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

    // ---------------------------------------------------------------- blocks

    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances/{id}/sections/{sectionId}/blocks")
    public ResponseEntity<?> addBlock(@PathVariable int id,
                                      @PathVariable int sectionId,
                                      @Valid @RequestBody AddBlockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.addBlock(id, sectionId, request));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blocks/{blockId}")
    public ResponseEntity<?> updateBlock(@PathVariable int blockId, @Valid @RequestBody UpdateBlockRequest request) {
        return ResponseEntity.ok(useCase.updateBlock(blockId, request.getText(), request.getCaption()));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/instances/{id}/sections/{sectionId}/blocks/order")
    public ResponseEntity<?> reorderBlocks(@PathVariable int id,
                                           @PathVariable int sectionId,
                                           @Valid @RequestBody ReorderBlocksRequest request) {
        useCase.reorderBlocks(id, sectionId, request.getBlockIds());
        return ResponseEntity.ok().build();
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/blocks/{blockId}")
    public ResponseEntity<?> deleteBlock(@PathVariable int blockId) {
        useCase.deleteBlock(blockId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- assets

    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping(value = "/api/doccreation/instances/{id}/assets", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadAssets(@PathVariable int id,
                                          @RequestParam("files") List<MultipartFile> files,
                                          @RequestParam(value = "autoAssign", defaultValue = "true")
                                          boolean autoAssign) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files uploaded."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.uploadAssets(id, files, autoAssign));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/assets/{assetId}/content")
    public ResponseEntity<?> assetContent(@PathVariable int assetId) {
        DocInstanceUseCase.StoredAsset asset = useCase.getAssetContent(assetId);
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .contentType(MediaType.parseMediaType(asset.contentType()))
                    .body(Files.readAllBytes(asset.path()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the image file.", e);
        }
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/assets/{assetId}/assignment")
    public ResponseEntity<?> assignAsset(@PathVariable int assetId, @Valid @RequestBody AssignAssetRequest request) {
        useCase.assignAsset(assetId, request.getSectionId());
        return ResponseEntity.ok().build();
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/assets/{assetId}")
    public ResponseEntity<?> deleteAsset(@PathVariable int assetId) {
        useCase.deleteAsset(assetId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ generation

    @RequiresPermission(Permission.DOC_GENERATE)
    @PostMapping("/api/doccreation/instances/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable int id) {
        GenerationResultDto result = useCase.generate(id);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("docxFileName", result.docxFileName());
        values.put("pdfReady", result.pdfReady());
        values.put("composedBy", result.composedBy());
        audit.success(AuditActions.DOC_GENERATED, "DocInstance", String.valueOf(id), null, values);
        return ResponseEntity.ok(result);
    }

    @RequiresPermission(Permission.DOC_GENERATE)
    @GetMapping("/api/doccreation/instances/{id}/download")
    public ResponseEntity<?> download(@PathVariable int id,
                                      @RequestParam(value = "format", defaultValue = "docx") String format) {
        DocInstanceUseCase.DownloadFile file = useCase.download(id, format);
        String contentType = file.downloadName().endsWith(".pdf") ? "application/pdf" : DOCX_CONTENT_TYPE;
        try {
            byte[] bytes = Files.readAllBytes(file.path());
            audit.success(AuditActions.DOC_DOWNLOADED, "DocInstance", String.valueOf(id), null,
                    Map.of("format", contentType.equals("application/pdf") ? "pdf" : "docx",
                            "bytes", bytes.length));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + file.downloadName().replace("\"", "") + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the generated file.", e);
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
