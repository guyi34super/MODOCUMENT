package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.ExtractionSourceDto;
import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.application.usecase.ExtractionSourceUseCase;
import com.modocument.domain.model.Permission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Extraction sources: the authorized places data may be collected from.
 * Creation requires acknowledging the source's terms of use; website sources
 * must allow-list their domains; no source configuration ever stores secrets.
 */
@RestController
public class ExtractionSourceController {

    private final ExtractionSourceUseCase useCase;

    public ExtractionSourceController(ExtractionSourceUseCase useCase) {
        this.useCase = useCase;
    }

    @RequiresPermission(Permission.EXTRACTION_WRITE)
    @PostMapping("/api/extraction/sources")
    public ResponseEntity<?> create(@RequestBody SaveExtractionSourceRequest request) {
        ExtractionSourceDto dto = ExtractionSourceDto.from(useCase.create(request));
        return ResponseEntity.created(URI.create("/api/extraction/sources/" + dto.id())).body(dto);
    }

    @RequiresPermission(Permission.EXTRACTION_READ)
    @GetMapping("/api/extraction/sources")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(useCase.list().stream().map(ExtractionSourceDto::from).toList());
    }

    @RequiresPermission(Permission.EXTRACTION_READ)
    @GetMapping("/api/extraction/sources/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return ResponseEntity.ok(ExtractionSourceDto.from(useCase.get(id)));
    }

    @RequiresPermission(Permission.EXTRACTION_WRITE)
    @PutMapping("/api/extraction/sources/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody SaveExtractionSourceRequest request) {
        return ResponseEntity.ok(ExtractionSourceDto.from(useCase.update(id, request)));
    }

    @RequiresPermission(Permission.EXTRACTION_WRITE)
    @DeleteMapping("/api/extraction/sources/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
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
