package com.modocument.api.controller;

import com.modocument.api.config.DeprecatedEndpoint;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.domain.entity.TemplateDataType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
@Tag(name = "Template Data Types")
public class TemplateDataTypesController {

    private final TemplateDataTypeRepository repo;

    public TemplateDataTypesController(TemplateDataTypeRepository repo) {
        this.repo = repo;
    }

    @RequiresPermission(Permission.LOOKUP_READ)
    @GetMapping("/TemplateDataTypes/GetAll")
    public ResponseEntity<?> getAllTemplateDataTypes() {
        return ResponseEntity.ok(repo.list());
    }

    @RequiresPermission(Permission.LOOKUP_READ)
    @DeprecatedEndpoint(replacement = "GET /TemplateDataTypes/GetAll")
    @GetMapping("/TemplateDataTypes/GetById/{id}")
    public ResponseEntity<?> getTemplateDataTypeById(@PathVariable int id) {
        Optional<TemplateDataType> item = repo.getById(id);
        if (item.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TemplateDataType dto =
                new TemplateDataType(item.get().getTemplateDataTypeId(), item.get().getDataType());
        return ResponseEntity.ok(dto);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @DeprecatedEndpoint
    @PostMapping("/TemplateDataTypes/Create")
    public ResponseEntity<?> createTemplateDataType(@Valid @RequestBody TemplateDataType dto) {
        TemplateDataType entity = new TemplateDataType(0, dto.getDataType());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/TemplateDataTypes/GetById/" + entity.getTemplateDataTypeId()))
                .body(entity);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @DeprecatedEndpoint
    @PutMapping("/TemplateDataTypes/Update")
    public ResponseEntity<?> updateTemplateDataType(@Valid @RequestBody TemplateDataType dto) {
        Optional<TemplateDataType> existingOpt = repo.getById(dto.getTemplateDataTypeId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TemplateDataType existing = existingOpt.get();
        existing.setDataType(dto.getDataType());

        TemplateDataType response = repo.update(existing);
        TemplateDataType updated =
                new TemplateDataType(response.getTemplateDataTypeId(), response.getDataType());

        return ResponseEntity
                .created(URI.create("/TemplateDataTypes/GetById/" + updated.getTemplateDataTypeId()))
                .body(updated);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @DeleteMapping("/TemplateDataTypes/{id}")
    public ResponseEntity<?> deleteTemplateDataType(@PathVariable int id) {
        Optional<TemplateDataType> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
