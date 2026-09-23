package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.UpdateKeywordsRequest;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
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
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Document-creation blueprints: upload the company's source .docx, see the
 * sections the engine broke it into, tune each section's auto-assignment
 * keywords, download the original, and delete when nothing builds on it.
 */
@RestController
public class DocBlueprintController {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocBlueprintUseCase useCase;

    public DocBlueprintController(DocBlueprintUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(value = "/api/doccreation/blueprints", consumes = "multipart/form-data")
    public ResponseEntity<?> create(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "name", required = false) String name,
                                    @RequestParam(value = "companyId", required = false) String companyId) {
        BlueprintDto created = useCase.create(file, name, companyId);
        return ResponseEntity
                .created(URI.create("/api/doccreation/blueprints/" + created.blueprintId()))
                .body(created);
    }

    @GetMapping("/api/doccreation/blueprints")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(useCase.list());
    }

    @GetMapping("/api/doccreation/blueprints/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    @GetMapping("/api/doccreation/blueprints/{id}/file")
    public ResponseEntity<?> downloadOriginal(@PathVariable int id) {
        DocBlueprintUseCase.StoredFile stored = useCase.getFile(id);
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + stored.downloadName().replace("\"", "") + "\"")
                    .contentType(MediaType.parseMediaType(DOCX_CONTENT_TYPE))
                    .body(Files.readAllBytes(stored.path()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the blueprint file.", e);
        }
    }

    @PutMapping("/api/doccreation/blueprints/{id}/sections/{sectionId}/keywords")
    public ResponseEntity<?> updateKeywords(@PathVariable int id,
                                            @PathVariable int sectionId,
                                            @RequestBody UpdateKeywordsRequest request) {
        return ResponseEntity.ok(useCase.updateKeywords(id, sectionId, request.getKeywords()));
    }

    @DeleteMapping("/api/doccreation/blueprints/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
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
