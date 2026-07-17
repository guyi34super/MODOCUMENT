package com.modocument.api.controller;

import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.domain.entity.ImportHistory;
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
public class ImportHistoryController {

    private final ImportHistoryRepository repo;

    public ImportHistoryController(ImportHistoryRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/ImportHistory/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.list());
    }

    @GetMapping("/ImportHistory/GetById/{id}")
    public ResponseEntity<?> getImportHistoryById(@PathVariable int id) {
        Optional<ImportHistory> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportHistory item = itemOpt.get();
        ImportHistory dto = new ImportHistory();
        dto.setImportHistoryId(item.getImportHistoryId());
        dto.setImportDate(item.getImportDate());
        dto.setFileName(item.getFileName());
        dto.setCompanyId(item.getCompanyId());
        dto.setTemplateId(item.getTemplateId());
        dto.setTemplate(item.getTemplate());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/ImportHistory/Create")
    public ResponseEntity<?> create(@RequestBody ImportHistory dto) {
        ImportHistory entity = new ImportHistory();
        entity.setImportDate(dto.getImportDate());
        entity.setFileName(dto.getFileName());
        entity.setCompanyId(dto.getCompanyId());
        entity.setTemplateId(dto.getTemplateId());
        entity.setTemplate(dto.getTemplate());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/ImportHistory/GetById/" + entity.getImportHistoryId()))
                .body(entity);
    }

    @PutMapping("/ImportHistory/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody ImportHistory dto) {
        Optional<ImportHistory> existingOpt = repo.getById(dto.getImportHistoryId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportHistory existing = existingOpt.get();
        existing.setImportDate(dto.getImportDate());
        existing.setFileName(dto.getFileName());
        existing.setCompanyId(dto.getCompanyId());
        existing.setTemplateId(dto.getTemplateId());
        existing.setTemplate(dto.getTemplate());

        ImportHistory response = repo.update(existing);
        ImportHistory updated = new ImportHistory();
        updated.setImportHistoryId(response.getImportHistoryId());
        updated.setImportDate(response.getImportDate());
        updated.setFileName(response.getFileName());
        updated.setCompanyId(response.getCompanyId());
        updated.setTemplateId(response.getTemplateId());
        updated.setTemplate(response.getTemplate());

        return ResponseEntity
                .created(URI.create("/ImportHistory/GetById/" + updated.getImportHistoryId()))
                .body(updated);
    }

    @DeleteMapping("/ImportHistory/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<ImportHistory> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
