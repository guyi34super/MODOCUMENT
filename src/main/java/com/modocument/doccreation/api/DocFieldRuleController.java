package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.ImportRulesRequest;
import com.modocument.doccreation.dto.SaveRulesRequest;
import com.modocument.doccreation.dto.TestRulesRequest;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.doccreation.usecase.DocFieldRuleUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * The field rules of a PDF template (docs/doccreation/field-validation-contract.md
 * §3): read and replace them, test sample values, and import rules from
 * flow-template field rules. The import routes additionally need
 * {@code validation:read}, checked by the use case (403).
 */
@RestController
@Tag(name = "Doc Creation: Field rules", description = "Validation rules of a PDF template's fields")
public class DocFieldRuleController {

    private final DocFieldRuleUseCase useCase;

    public DocFieldRuleController(DocFieldRuleUseCase useCase) {
        this.useCase = useCase;
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/api/doccreation/blueprints/{id}/rules")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    /** Replaces the whole set; 409 when {@code baseRevision} is stale. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/api/doccreation/blueprints/{id}/rules")
    public ResponseEntity<?> save(@PathVariable int id, @RequestBody SaveRulesRequest request) {
        return ResponseEntity.ok(useCase.save(id, request));
    }

    /** Checks sample values against the given or the saved rules; nothing is stored. */
    @RequiresPermission(Permission.DOC_READ)
    @PostMapping("/api/doccreation/blueprints/{id}/rules/test")
    public ResponseEntity<?> test(@PathVariable int id, @RequestBody(required = false) TestRulesRequest request) {
        return ResponseEntity.ok(useCase.test(id, request));
    }

    /** What an import would copy (also needs validation:read). */
    @RequiresPermission(Permission.DOC_WRITE)
    @GetMapping("/api/doccreation/blueprints/{id}/rules/import-preview")
    public ResponseEntity<?> importPreview(@PathVariable int id,
                                           @RequestParam(required = false) Integer templateId) {
        return ResponseEntity.ok(useCase.importPreview(id, templateId));
    }

    /** Copies flow-template field rules (also needs validation:read). */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/api/doccreation/blueprints/{id}/rules/import")
    public ResponseEntity<?> importRules(@PathVariable int id, @RequestBody ImportRulesRequest request) {
        return ResponseEntity.ok(useCase.importRules(id, request));
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
