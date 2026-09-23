package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.AssignAssetRequest;
import com.modocument.doccreation.dto.CreateDocInstanceRequest;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.dto.ReorderBlocksRequest;
import com.modocument.doccreation.dto.UpdateBlockRequest;
import com.modocument.doccreation.dto.UpdateDocInstanceRequest;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Document instances built on a blueprint: the section-fill builder (paragraph
 * and image blocks), bulk image upload with auto-assignment, authenticated
 * image previews, and generation/download of the reproduced document.
 */
@RestController
public class DocInstanceController {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocInstanceUseCase useCase;

    public DocInstanceController(DocInstanceUseCase useCase) {
        this.useCase = useCase;
    }

    // ------------------------------------------------------------- instances

    @PostMapping("/api/doccreation/instances")
    public ResponseEntity<?> create(@RequestBody CreateDocInstanceRequest request) {
        InstanceSummaryDto created = useCase.create(request.getBlueprintId(), request.getTitle());
        return ResponseEntity
                .created(URI.create("/api/doccreation/instances/" + created.instanceId()))
                .body(created);
    }

    @GetMapping("/api/doccreation/instances")
    public ResponseEntity<?> list(@RequestParam(value = "blueprintId", required = false) Integer blueprintId) {
        return ResponseEntity.ok(useCase.list(blueprintId));
    }

    @GetMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.getBuilder(id));
    }

    @PutMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> rename(@PathVariable int id, @RequestBody UpdateDocInstanceRequest request) {
        return ResponseEntity.ok(useCase.rename(id, request.getTitle()));
    }

    @DeleteMapping("/api/doccreation/instances/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- blocks

    @PostMapping("/api/doccreation/instances/{id}/sections/{sectionId}/blocks")
    public ResponseEntity<?> addBlock(@PathVariable int id,
                                      @PathVariable int sectionId,
                                      @RequestBody AddBlockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.addBlock(id, sectionId, request));
    }

    @PutMapping("/api/doccreation/blocks/{blockId}")
    public ResponseEntity<?> updateBlock(@PathVariable int blockId, @RequestBody UpdateBlockRequest request) {
        return ResponseEntity.ok(useCase.updateBlock(blockId, request.getText(), request.getCaption()));
    }

    @PutMapping("/api/doccreation/instances/{id}/sections/{sectionId}/blocks/order")
    public ResponseEntity<?> reorderBlocks(@PathVariable int id,
                                           @PathVariable int sectionId,
                                           @RequestBody ReorderBlocksRequest request) {
        useCase.reorderBlocks(id, sectionId, request.getBlockIds());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/doccreation/blocks/{blockId}")
    public ResponseEntity<?> deleteBlock(@PathVariable int blockId) {
        useCase.deleteBlock(blockId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- assets

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

    @PutMapping("/api/doccreation/assets/{assetId}/assignment")
    public ResponseEntity<?> assignAsset(@PathVariable int assetId, @RequestBody AssignAssetRequest request) {
        useCase.assignAsset(assetId, request.getSectionId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/doccreation/assets/{assetId}")
    public ResponseEntity<?> deleteAsset(@PathVariable int assetId) {
        useCase.deleteAsset(assetId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ generation

    @PostMapping("/api/doccreation/instances/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable int id) {
        return ResponseEntity.ok(useCase.generate(id));
    }

    @GetMapping("/api/doccreation/instances/{id}/download")
    public ResponseEntity<?> download(@PathVariable int id,
                                      @RequestParam(value = "format", defaultValue = "docx") String format) {
        DocInstanceUseCase.DownloadFile file = useCase.download(id, format);
        String contentType = file.downloadName().endsWith(".pdf") ? "application/pdf" : DOCX_CONTENT_TYPE;
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + file.downloadName().replace("\"", "") + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(Files.readAllBytes(file.path()));
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
