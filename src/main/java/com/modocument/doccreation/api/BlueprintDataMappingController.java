package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.ChooseDataTemplateRequest;
import com.modocument.doccreation.dto.SaveDataMappingRequest;
import com.modocument.doccreation.usecase.BlueprintDataMappingUseCase;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * A PDF template's data template and field mapping
 * (docs/doccreation/data-templates-contract.md §3): choose or clear the
 * data template, read and save the mapping of the template's fields onto
 * the data fields. The template and the data template must be the caller's
 * (404 otherwise).
 */
@RestController
@Tag(name = "Doc Creation: Data mapping", description = "A PDF template's data template and field mapping")
public class BlueprintDataMappingController {

    private final BlueprintDataMappingUseCase useCase;

    public BlueprintDataMappingController(BlueprintDataMappingUseCase useCase) {
        this.useCase = useCase;
    }

    /** Chooses ({@code dataTemplateId}) or clears ({@code null}) the data template; pre-fills the mapping. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/data-template")
    public ResponseEntity<?> choose(@PathVariable int id,
                                    @RequestBody(required = false) ChooseDataTemplateRequest request) {
        return ResponseEntity.ok(useCase.choose(id, request));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/data-mapping")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    /** Replaces the mapping (region key to data field name); raises the rules revision. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/data-mapping")
    public ResponseEntity<?> save(@PathVariable int id,
                                  @RequestBody(required = false) SaveDataMappingRequest request) {
        return ResponseEntity.ok(useCase.saveMapping(id, request));
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
