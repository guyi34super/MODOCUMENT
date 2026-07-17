package com.modocument.api.controller;

import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.domain.entity.ImportDetails;
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
public class ImportDetailsController {

    private final ImportDetailsRepository repo;

    public ImportDetailsController(ImportDetailsRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/ImportDetails/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.list());
    }

    @GetMapping("/ImportDetails/GetById/{id}")
    public ResponseEntity<?> getImportDetailsById(@PathVariable int id) {
        Optional<ImportDetails> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportDetails item = itemOpt.get();
        ImportDetails dto = new ImportDetails();
        dto.setImportDetailsId(item.getImportDetailsId());
        dto.setImportHistoryId(item.getImportHistoryId());
        dto.setProcessStepId(item.getProcessStepId());
        dto.setRawJson(item.getRawJson());
        dto.setImportHistory(item.getImportHistory());
        dto.setProcessStep(item.getProcessStep());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/ImportDetails/Create")
    public ResponseEntity<?> create(@RequestBody ImportDetails dto) {
        ImportDetails entity = new ImportDetails();
        entity.setImportHistoryId(dto.getImportHistoryId());
        entity.setProcessStepId(dto.getProcessStepId());
        entity.setRawJson(dto.getRawJson());
        entity.setImportHistory(dto.getImportHistory());
        entity.setProcessStep(dto.getProcessStep());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/ImportDetails/GetById/" + entity.getImportDetailsId()))
                .body(entity);
    }

    @PutMapping("/ImportDetails/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody ImportDetails dto) {
        Optional<ImportDetails> existingOpt = repo.getById(dto.getImportDetailsId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportDetails existing = existingOpt.get();
        existing.setImportHistoryId(dto.getImportHistoryId());
        existing.setProcessStepId(dto.getProcessStepId());
        existing.setRawJson(dto.getRawJson());
        existing.setImportHistory(dto.getImportHistory());
        existing.setProcessStep(dto.getProcessStep());

        ImportDetails response = repo.update(existing);
        ImportDetails updated = new ImportDetails();
        updated.setImportDetailsId(response.getImportDetailsId());
        updated.setImportHistoryId(response.getImportHistoryId());
        updated.setProcessStepId(response.getProcessStepId());
        updated.setRawJson(response.getRawJson());
        updated.setImportHistory(response.getImportHistory());
        updated.setProcessStep(response.getProcessStep());

        return ResponseEntity
                .created(URI.create("/ImportDetails/GetById/" + updated.getImportDetailsId()))
                .body(updated);
    }

    @DeleteMapping("/ImportDetails/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<ImportDetails> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
