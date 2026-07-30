package com.modocument.api.controller;

import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.domain.entity.TemplateFields;
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
import java.util.Optional;

@RestController
public class TemplateFieldsController {

    private final TemplateFieldsRepository repo;

    public TemplateFieldsController(TemplateFieldsRepository repo) {
        this.repo = repo;
    }

    /**
     * With {@code templateId} the answer is scoped to that template — which is
     * all any caller actually needs, and avoids shipping every template's
     * fields to a client that then filters them away.
     */
    @GetMapping("/TemplateFields/GetAll")
    public ResponseEntity<?> getAll(
            @RequestParam(value = "templateId", required = false) Integer templateId) {
        return ResponseEntity.ok(templateId == null
                ? repo.list()
                : repo.listByTemplateId(templateId));
    }

    @GetMapping("/TemplateFields/GetById/{id}")
    public ResponseEntity<?> getTemplateFieldsById(@PathVariable int id) {
        Optional<TemplateFields> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TemplateFields item = itemOpt.get();
        TemplateFields dto = new TemplateFields();
        dto.setTemplateFieldsId(item.getTemplateFieldsId());
        dto.setTemplateId(item.getTemplateId());
        dto.setFieldName(item.getFieldName());
        dto.setTemplate(item.getTemplate());
        dto.setTemplateDataTypeId(item.getTemplateDataTypeId());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/TemplateFields/Create")
    public ResponseEntity<?> create(@RequestBody TemplateFields dto) {
        TemplateFields entity = new TemplateFields();
        entity.setTemplateId(dto.getTemplateId());
        entity.setFieldName(dto.getFieldName());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/TemplateFields/GetById/" + entity.getTemplateFieldsId()))
                .body(entity);
    }

    @PutMapping("/TemplateFields/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody TemplateFields dto) {
        Optional<TemplateFields> existingOpt = repo.getById(dto.getTemplateFieldsId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
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

    @DeleteMapping("/TemplateFields/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<TemplateFields> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
