package com.modocument.api.controller;

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
public class TemplateFieldsController {

    private final TemplateFieldsRepository repo;
    private final TemplateOwnership ownership;

    public TemplateFieldsController(TemplateFieldsRepository repo, TemplateOwnership ownership) {
        this.repo = repo;
        this.ownership = ownership;
    }

    /**
     * Always scoped to one caller-owned template — a global list would leak
     * every user's field definitions, so {@code templateId} is required and
     * ownership is enforced.
     */
    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/TemplateFields/GetAll")
    public ResponseEntity<?> getAll(
            @RequestParam(value = "templateId", required = false) Integer templateId) {
        if (templateId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateId is required."));
        }
        ownership.require(templateId);
        return ResponseEntity.ok(repo.listByTemplateId(templateId));
    }

    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/TemplateFields/GetById/{id}")
    public ResponseEntity<?> getTemplateFieldsById(@PathVariable int id) {
        Optional<TemplateFields> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // A field belongs to whoever owns its template.
        ownership.require(itemOpt.get().getTemplateId());
        TemplateFields item = itemOpt.get();
        TemplateFields dto = new TemplateFields();
        dto.setTemplateFieldsId(item.getTemplateFieldsId());
        dto.setTemplateId(item.getTemplateId());
        dto.setFieldName(item.getFieldName());
        dto.setTemplate(item.getTemplate());
        dto.setTemplateDataTypeId(item.getTemplateDataTypeId());
        return ResponseEntity.ok(dto);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PostMapping("/TemplateFields/Create")
    public ResponseEntity<?> create(@RequestBody TemplateFields dto) {
        ownership.require(dto.getTemplateId());
        TemplateFields entity = new TemplateFields();
        entity.setTemplateId(dto.getTemplateId());
        entity.setBankId(ownership.requireBankId());
        entity.setFieldName(dto.getFieldName());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/TemplateFields/GetById/" + entity.getTemplateFieldsId()))
                .body(entity);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PutMapping("/TemplateFields/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody TemplateFields dto) {
        Optional<TemplateFields> existingOpt = repo.getById(dto.getTemplateFieldsId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Must own both the current template and any template it's being moved to.
        ownership.require(existingOpt.get().getTemplateId());
        ownership.require(dto.getTemplateId());
        TemplateFields existing = existingOpt.get();
        existing.setFieldName(dto.getFieldName());
        existing.setTemplateId(dto.getTemplateId());

        TemplateFields response = repo.update(existing);
        TemplateFields updated = new TemplateFields();
        updated.setTemplateFieldsId(response.getTemplateFieldsId());
        updated.setTemplateId(response.getTemplateId());
        updated.setFieldName(response.getFieldName());
        updated.setTemplate(response.getTemplate());
        updated.setTemplateDataTypeId(response.getTemplateDataTypeId());

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
        ownership.require(existing.get().getTemplateId());
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<?> notFound(java.util.NoSuchElementException e) {
        // Cross-user template access is reported as not-found, never forbidden.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
