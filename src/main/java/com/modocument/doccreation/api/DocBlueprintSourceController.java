package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.BlueprintSourceDto;
import com.modocument.doccreation.dto.CreateBlueprintSourceRequest;
import com.modocument.doccreation.dto.UpdateBlueprintSourceRequest;
import com.modocument.doccreation.usecase.BlueprintSourceUseCase;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.NoSuchElementException;

/**
 * The saved data sources of a PDF template (docs/doccreation/flow-records-contract.md
 * §4): flow templates mapped to the template's fields, one PRIMARY and any
 * number of LINKED sources joined to it through a shared key.
 */
@RestController
@Tag(name = "Doc Creation: Data sources", description = "Flow templates mapped to a PDF template's fields")
public class DocBlueprintSourceController {

    private final BlueprintSourceUseCase useCase;

    public DocBlueprintSourceController(BlueprintSourceUseCase useCase) {
        this.useCase = useCase;
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/sources")
    public ResponseEntity<?> list(@PathVariable int id) {
        return ResponseEntity.ok(useCase.list(id));
    }

    /** 201 with the source, its mapping pre-filled by auto-mapping. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/blueprints/{id}/sources")
    public ResponseEntity<?> create(@PathVariable int id, @Valid @RequestBody CreateBlueprintSourceRequest request) {
        BlueprintSourceDto created = useCase.create(id, request);
        return ResponseEntity.created(URI.create("/api/doccreation/blueprints/" + id + "/sources/" + created.id()))
                .body(created);
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/sources/{sourceId}")
    public ResponseEntity<?> update(@PathVariable int id, @PathVariable long sourceId,
                                    @Valid @RequestBody UpdateBlueprintSourceRequest request) {
        return ResponseEntity.ok(useCase.update(id, sourceId, request));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/api/doccreation/blueprints/{id}/sources/{sourceId}")
    public ResponseEntity<?> delete(@PathVariable int id, @PathVariable long sourceId) {
        useCase.delete(id, sourceId);
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
