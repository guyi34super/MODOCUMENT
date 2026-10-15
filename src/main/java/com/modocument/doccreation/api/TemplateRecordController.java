package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.usecase.TemplateRecordUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * The validated records of the data flow (docs/doccreation/flow-records-contract.md
 * §4): search a template's visible records and read one, masked per role.
 */
@RestController
@Tag(name = "Doc Creation: Records", description = "Search validated flow records to fill documents with")
public class TemplateRecordController {

    private final TemplateRecordUseCase useCase;

    public TemplateRecordController(TemplateRecordUseCase useCase) {
        this.useCase = useCase;
    }

    /** {@code {items, page, size, total}}; {@code size} at most 100, newest first. */
    @RequiresPermission(Permission.INGESTION_READ)
    @GetMapping("/api/doccreation/records")
    public ResponseEntity<?> search(@RequestParam(value = "templateId", required = false) Integer templateId,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "field", required = false) String field,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(useCase.search(templateId, q, field, page, size));
    }

    @RequiresPermission(Permission.INGESTION_READ)
    @GetMapping("/api/doccreation/records/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return DocCreationErrors.notFound(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return DocCreationErrors.badRequest(e);
    }
}
