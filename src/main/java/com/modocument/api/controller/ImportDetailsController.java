package com.modocument.api.controller;

import com.modocument.api.config.DeprecatedEndpoint;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.model.Permission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * ImportDetails are children of an ImportHistory, so access is scoped through
 * the parent's owner — there is no per-detail owner column.
 */
@DeprecatedEndpoint(replacement = "the ingestion job API (/api/ingestion/jobs)")
@RestController
@Tag(name = "Import Details")
public class ImportDetailsController {

    private final ImportDetailsRepository repo;
    private final ImportHistoryRepository imports;
    private final CurrentUserProvider currentUser;

    public ImportDetailsController(ImportDetailsRepository repo,
                                   ImportHistoryRepository imports,
                                   CurrentUserProvider currentUser) {
        this.repo = repo;
        this.imports = imports;
        this.currentUser = currentUser;
    }

    /** Verifies the caller owns the parent import; NoSuchElement → 404. */
    private void requireOwnedImport(int importHistoryId) {
        imports.getByIdAndOwner(importHistoryId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Import " + importHistoryId + " not found."));
    }

    @RequiresPermission(Permission.IMPORT_READ)
    @GetMapping("/ImportDetails/GetAll")
    public ResponseEntity<?> getAll() {
        // Only details whose parent import the caller owns — one bank- and
        // owner-filtered query (ws28), not the whole bank filtered in memory.
        return ResponseEntity.ok(repo.listByBankAndImportOwner(
                currentUser.requireBankId(), currentUser.requireUserId()));
    }

    @RequiresPermission(Permission.IMPORT_READ)
    @GetMapping("/ImportDetails/GetById/{id}")
    public ResponseEntity<?> getImportDetailsById(@PathVariable int id) {
        Optional<ImportDetails> itemOpt = repo.getById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireOwnedImport(itemOpt.get().getImportHistoryId());
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

    @RequiresPermission(Permission.IMPORT_WRITE)
    @PostMapping("/ImportDetails/Create")
    public ResponseEntity<?> create(@RequestBody ImportDetails dto) {
        requireOwnedImport(dto.getImportHistoryId());
        ImportDetails entity = new ImportDetails();
        entity.setImportHistoryId(dto.getImportHistoryId());
        entity.setBankId(currentUser.requireBankId());
        entity.setProcessStepId(dto.getProcessStepId());
        entity.setRawJson(dto.getRawJson());
        entity.setImportHistory(dto.getImportHistory());
        entity.setProcessStep(dto.getProcessStep());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/ImportDetails/GetById/" + entity.getImportDetailsId()))
                .body(entity);
    }

    @RequiresPermission(Permission.IMPORT_WRITE)
    @PutMapping("/ImportDetails/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody ImportDetails dto) {
        Optional<ImportDetails> existingOpt = repo.getById(dto.getImportDetailsId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireOwnedImport(existingOpt.get().getImportHistoryId());
        requireOwnedImport(dto.getImportHistoryId());
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

    @RequiresPermission(Permission.IMPORT_WRITE)
    @DeleteMapping("/ImportDetails/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<ImportDetails> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireOwnedImport(existing.get().getImportHistoryId());
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
