package com.modocument.api.controller;

import com.modocument.api.config.DeprecatedEndpoint;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.model.Permission;
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

@RestController
@Tag(name = "Import History")
public class ImportHistoryController {

    private final ImportHistoryRepository repo;
    private final CurrentUserProvider currentUser;
    private final TemplateOwnership templateOwnership;

    public ImportHistoryController(ImportHistoryRepository repo, CurrentUserProvider currentUser,
                                   TemplateOwnership templateOwnership) {
        this.repo = repo;
        this.currentUser = currentUser;
        this.templateOwnership = templateOwnership;
    }

    @RequiresPermission(Permission.IMPORT_READ)
    @GetMapping("/ImportHistory/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.listByOwner(currentUser.requireUserId()));
    }

    @RequiresPermission(Permission.IMPORT_READ)
    @DeprecatedEndpoint(replacement = "GET /ImportHistory/GetAll")
    @GetMapping("/ImportHistory/GetById/{id}")
    public ResponseEntity<?> getImportHistoryById(@PathVariable int id) {
        Optional<ImportHistory> itemOpt = repo.getByIdAndOwner(id, currentUser.requireUserId());
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

    @RequiresPermission(Permission.IMPORT_WRITE)
    @DeprecatedEndpoint(replacement = "POST /api/ingestion/jobs")
    @PostMapping("/ImportHistory/Create")
    public ResponseEntity<?> create(@Valid @RequestBody ImportHistory dto) {
        // ws22: an import names the template it used — a USE of the template,
        // so it must be readable (own, or shared with the bank). Unchecked,
        // the response's embedded template leaked another user's template.
        if (!templateUsable(dto.getTemplateId())) {
            return templateNotFound(dto.getTemplateId());
        }
        ImportHistory entity = new ImportHistory();
        entity.setOwnerUserId(currentUser.requireUserId());
        entity.setBankId(currentUser.requireBankId());
        entity.setImportDate(dto.getImportDate());
        entity.setFileName(dto.getFileName());
        entity.setTemplateId(dto.getTemplateId());
        entity.setTemplate(dto.getTemplate());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/ImportHistory/GetById/" + entity.getImportHistoryId()))
                .body(entity);
    }

    @RequiresPermission(Permission.IMPORT_WRITE)
    @DeprecatedEndpoint
    @PutMapping("/ImportHistory/Update/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @Valid @RequestBody ImportHistory dto) {
        Optional<ImportHistory> existingOpt =
                repo.getByIdAndOwner(dto.getImportHistoryId(), currentUser.requireUserId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!templateUsable(dto.getTemplateId())) {
            return templateNotFound(dto.getTemplateId());
        }
        ImportHistory existing = existingOpt.get();
        existing.setImportDate(dto.getImportDate());
        existing.setFileName(dto.getFileName());
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

    @RequiresPermission(Permission.IMPORT_WRITE)
    @DeleteMapping("/ImportHistory/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<ImportHistory> existing = repo.getByIdAndOwner(id, currentUser.requireUserId());
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }

    private boolean templateUsable(int templateId) {
        try {
            templateOwnership.requireReadable(templateId);
            return true;
        } catch (NoSuchElementException notReadable) {
            return false;
        }
    }

    private static ResponseEntity<?> templateNotFound(int templateId) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Template " + templateId + " not found."));
    }
}
