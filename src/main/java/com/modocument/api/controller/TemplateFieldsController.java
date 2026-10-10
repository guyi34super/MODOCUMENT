package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.domain.entity.TemplateFields;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@RestController
@Tag(name = "Template Fields")
public class TemplateFieldsController {

    private final TemplateFieldsRepository repo;
    private final TemplateOwnership ownership;

    public TemplateFieldsController(TemplateFieldsRepository repo, TemplateOwnership ownership) {
        this.repo = repo;
        this.ownership = ownership;
    }

    /**
     * Always scoped to one template the caller can read (own, or shared with
     * their bank — ws22) — a global list would leak every user's field
     * definitions, so {@code templateId} is required.
     */
    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/TemplateFields/GetAll")
    public ResponseEntity<?> getAll(
            @RequestParam(value = "templateId", required = false) Integer templateId) {
        if (templateId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateId is required."));
        }
        ownership.requireReadable(templateId);
        return ResponseEntity.ok(repo.listByTemplateId(templateId));
    }

    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/TemplateFields/GetById/{id}")
    public ResponseEntity<?> getTemplateFieldsById(@PathVariable int id) {
        Optional<TemplateFields> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // A field is readable wherever its template is (owner, or bank-shared).
        ownership.requireReadable(itemOpt.get().getTemplateId());
        TemplateFields item = itemOpt.get();
        TemplateFields dto = new TemplateFields();
        dto.setTemplateFieldsId(item.getTemplateFieldsId());
        dto.setTemplateId(item.getTemplateId());
        dto.setFieldName(item.getFieldName());
        dto.setTemplate(item.getTemplate());
        dto.setTemplateDataTypeId(item.getTemplateDataTypeId());
        dto.setPiiKind(item.getPiiKind());
        return ResponseEntity.ok(dto);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PostMapping("/TemplateFields/Create")
    public ResponseEntity<?> create(@Valid @RequestBody TemplateFields dto) {
        // Changing a template's definition: owner (PRIVATE) / template:publish (BANK).
        ownership.requireWritable(dto.getTemplateId());
        TemplateFields entity = new TemplateFields();
        entity.setTemplateId(dto.getTemplateId());
        entity.setBankId(ownership.requireBankId());
        entity.setFieldName(dto.getFieldName());
        // The data type the editor sends (it was dropped, so every field
        // created here hit the TemplateDataTypes foreign key with id 0).
        entity.setTemplateDataTypeId(dto.getTemplateDataTypeId());
        // Explicit PII tag (ws12, PRIV-004); left null, the classifier tags
        // it on first use and the result shows up here for correction.
        entity.setPiiKind(dto.getPiiKind());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/TemplateFields/GetById/" + entity.getTemplateFieldsId()))
                .body(entity);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PutMapping("/TemplateFields/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @Valid @RequestBody TemplateFields dto) {
        Optional<TemplateFields> existingOpt = repo.getById(dto.getTemplateFieldsId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Must be allowed to change both the current template and any
        // template the field is being moved to.
        ownership.requireWritable(existingOpt.get().getTemplateId());
        ownership.requireWritable(dto.getTemplateId());
        TemplateFields existing = existingOpt.get();
        existing.setFieldName(dto.getFieldName());
        if (dto.getTemplateDataTypeId() > 0) {
            existing.setTemplateDataTypeId(dto.getTemplateDataTypeId());
        }
        existing.setTemplateId(dto.getTemplateId());
        existing.setPiiKind(dto.getPiiKind());

        TemplateFields response = repo.update(existing);
        TemplateFields updated = new TemplateFields();
        updated.setTemplateFieldsId(response.getTemplateFieldsId());
        updated.setTemplateId(response.getTemplateId());
        updated.setFieldName(response.getFieldName());
        updated.setTemplate(response.getTemplate());
        updated.setTemplateDataTypeId(response.getTemplateDataTypeId());
        updated.setPiiKind(response.getPiiKind());

        return ResponseEntity
                .created(URI.create("/TemplateFields/GetById/" + updated.getTemplateFieldsId()))
                .body(updated);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @DeleteMapping("/TemplateFields/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<TemplateFields> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ownership.requireWritable(existing.get().getTemplateId());
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<?> notFound(java.util.NoSuchElementException e) {
        // Cross-user template access is reported as not-found, never forbidden.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
