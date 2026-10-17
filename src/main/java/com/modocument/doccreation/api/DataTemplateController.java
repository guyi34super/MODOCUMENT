package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.AddFlowTemplatesRequest;
import com.modocument.doccreation.dto.CopyFlowTemplateRequest;
import com.modocument.doccreation.dto.CopyFlowTemplatesRequest;
import com.modocument.doccreation.dto.SaveDataFieldsRequest;
import com.modocument.doccreation.dto.SaveDataRulesRequest;
import com.modocument.doccreation.dto.SaveDataTemplateRequest;
import com.modocument.doccreation.dto.TestDataRulesRequest;
import com.modocument.doccreation.usecase.DataTemplateUseCase;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Data templates of document creation (docs/doccreation/data-templates-contract.md
 * §2): the reusable fields a document collects and their rules. Owner-scoped
 * (another user's or bank's answers 404); reading needs {@code doc:read},
 * changing {@code doc:write}. Copying from a flow template additionally
 * needs {@code template:read} and {@code validation:read}, checked by the
 * use case (403).
 */
@RestController
@RequestMapping("/api/doccreation/data-templates")
@Tag(name = "Doc Creation: Data templates", description = "Reusable fields and validation rules of documents")
public class DataTemplateController {

    private final DataTemplateUseCase useCase;

    public DataTemplateController(DataTemplateUseCase useCase) {
        this.useCase = useCase;
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(useCase.list());
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping
    public ResponseEntity<?> create(@RequestBody SaveDataTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.create(request));
    }

    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable int id) {
        return ResponseEntity.ok(useCase.get(id));
    }

    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody SaveDataTemplateRequest request) {
        return ResponseEntity.ok(useCase.update(id, request));
    }

    /** 409 while a PDF template uses it. */
    @RequiresPermission(Permission.DOC_WRITE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the field set; 409 when {@code baseRevision} is stale. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/{id}/fields")
    public ResponseEntity<?> saveFields(@PathVariable int id, @RequestBody SaveDataFieldsRequest request) {
        return ResponseEntity.ok(useCase.saveFields(id, request));
    }

    /** Replaces the rule set; 409 when {@code baseRevision} is stale. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PutMapping("/{id}/rules")
    public ResponseEntity<?> saveRules(@PathVariable int id, @RequestBody SaveDataRulesRequest request) {
        return ResponseEntity.ok(useCase.saveRules(id, request));
    }

    /** Checks sample values (by field name) against the given or the saved rules; nothing is stored. */
    @RequiresPermission(Permission.DOC_READ)
    @PostMapping("/{id}/rules/test")
    public ResponseEntity<?> test(@PathVariable int id,
                                  @RequestBody(required = false) TestDataRulesRequest request) {
        return ResponseEntity.ok(useCase.test(id, request));
    }

    /** What a copy of a flow template would create (also needs template:read and validation:read). */
    @RequiresPermission(Permission.DOC_READ)
    @GetMapping("/copy-preview")
    public ResponseEntity<?> copyPreview(@RequestParam(required = false) Integer templateId) {
        return ResponseEntity.ok(useCase.copyPreview(templateId));
    }

    /** A new data template from a flow template's fields and rules (also needs template:read and validation:read). */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/from-flow-template")
    public ResponseEntity<?> copyFromFlowTemplate(@RequestBody CopyFlowTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.copyFromFlowTemplate(request));
    }

    /**
     * A new data template from the chosen fields of several flow templates
     * (each also needs template:read and validation:read and must be readable).
     */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/from-flow-templates")
    public ResponseEntity<?> copyFromFlowTemplates(@RequestBody CopyFlowTemplatesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.copyFromFlowTemplates(request));
    }

    /** Adds flow fields and their rules to a data template; 409 when {@code baseRevision} is stale. */
    @RequiresPermission(Permission.DOC_WRITE)
    @PostMapping("/{id}/add-from-flow-templates")
    public ResponseEntity<?> addFromFlowTemplates(@PathVariable int id, @RequestBody AddFlowTemplatesRequest request) {
        return ResponseEntity.ok(useCase.addFromFlowTemplates(id, request));
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
