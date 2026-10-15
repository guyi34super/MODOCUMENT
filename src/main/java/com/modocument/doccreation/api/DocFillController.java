package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.FillFromRecordRequest;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.doccreation.usecase.DocFillUseCase;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Filling a document from flow records and moving it to the template's
 * current version (docs/doccreation/flow-records-contract.md §4). Reading the
 * records also needs {@code ingestion:read}, re-checked by the use case.
 */
@RestController
@Tag(name = "Doc Creation: Fill from data", description = "Fill a document from validated records; upgrade")
public class DocFillController {

    private final DocFillUseCase fill;
    private final DocInstanceUseCase instances;

    public DocFillController(DocFillUseCase fill, DocInstanceUseCase instances) {
        this.fill = fill;
        this.instances = instances;
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @GetMapping("/api/doccreation/instances/{id}/fill-preview")
    public ResponseEntity<?> preview(@PathVariable int id, @RequestParam("recordId") long recordId) {
        return ResponseEntity.ok(fill.preview(id, recordId));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances/{id}/fill-from-record")
    public ResponseEntity<?> fillFromRecord(@PathVariable int id, @Valid @RequestBody FillFromRecordRequest request) {
        return ResponseEntity.ok(fill.fill(id, request));
    }

    /** The document on the template's current version, plus {@code droppedKeys}. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/instances/{id}/upgrade")
    public ResponseEntity<?> upgrade(@PathVariable int id) {
        return ResponseEntity.ok(instances.upgrade(id));
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
