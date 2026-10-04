package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.usecase.DataSubjectUseCase;
import com.modocument.domain.model.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Data-subject request endpoints (ws14, gate C12, PRIV-016). USER_ADMIN on
 * purpose: handling a data-subject request is an administrative function of
 * the bank, exactly like user administration — no new permission is minted
 * (documented in docs/security/retention-and-dsr.md). Everything is scoped
 * to the CALLER'S bank (explicit BankId filters in the use case, backed by
 * RLS on PostgreSQL).
 *
 * <p>All three are POST — the identifier is personal data and must never
 * sit in a URL (access logs, browser history).
 */
@RestController
@RequiresPermission(Permission.USER_ADMIN)
public class DataSubjectController {

    private final DataSubjectUseCase dsr;

    public DataSubjectController(DataSubjectUseCase dsr) {
        this.dsr = dsr;
    }

    public record DsrRequest(
            @NotBlank @Size(max = 320) String identifier,
            Boolean confirm) {
    }

    @PostMapping("/api/admin/dsr/search")
    public ResponseEntity<?> search(@Valid @RequestBody DsrRequest request) {
        return ResponseEntity.ok(dsr.search(request.identifier()));
    }

    @PostMapping("/api/admin/dsr/export")
    public ResponseEntity<?> export(@Valid @RequestBody DsrRequest request) {
        DataSubjectUseCase.ExportBundle bundle = dsr.export(request.identifier());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + bundle.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bundle.zipBytes());
    }

    /** Destructive: refuses without the explicit {@code confirm: true}. */
    @PostMapping("/api/admin/dsr/erase")
    public ResponseEntity<?> erase(@Valid @RequestBody DsrRequest request) {
        if (!Boolean.TRUE.equals(request.confirm())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Erasure is irreversible; repeat the request with confirm: true."));
        }
        return ResponseEntity.ok(dsr.erase(request.identifier()));
    }
}
