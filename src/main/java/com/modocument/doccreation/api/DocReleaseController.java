package com.modocument.doccreation.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.dto.ReleaseDecisionRequest;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.doccreation.usecase.DocReleaseUseCase;
import com.modocument.domain.model.Permission;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The ws11 release workflow routes (gate B8, SEC-012/SEC-024; C10).
 *
 * <p>Permissions: submit is the AUTHOR's verb and carries
 * {@code DOC_GENERATE} (the owner-scoped use case then pins it to the
 * instance's owner); the review verbs — queue, release, reject, review
 * download — carry {@code DOC_RELEASE} (APPROVER + ADMIN). The releaser
 * being the document's author or submitter is a hard 409 in
 * {@link DocReleaseUseCase} regardless of role (B8), audited
 * DOC_SELF_RELEASE_BLOCKED (DENIED).
 *
 * <p>Error mapping matches DocInstanceController: unknown/foreign id → 404,
 * wrong state (unsubmitted, already released, self-release) → 409, bad
 * input → 400.
 */
@RestController
@Tag(name = "Doc Creation: Release", description = "Review / release workflow (SoD enforced)")
public class DocReleaseController {

    private final DocReleaseUseCase useCase;

    public DocReleaseController(DocReleaseUseCase useCase) {
        this.useCase = useCase;
    }

    /** Author: sends the generated document for release review. */
    @RequiresPermission(Permission.DOC_GENERATE)
    @PostMapping("/api/doccreation/instances/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable int id) {
        return ResponseEntity.ok(useCase.submit(id));
    }

    /** Approver: the bank's release queue, values and photos included (C10). */
    @RequiresPermission(Permission.DOC_RELEASE)
    @GetMapping("/api/doccreation/instances/pending-release")
    public ResponseEntity<?> pendingReleases() {
        return ResponseEntity.ok(useCase.pendingReleases());
    }

    /** Approver: releases. Author/submitter releasing their own is a hard 409 (gate B8). */
    @RequiresPermission(Permission.DOC_RELEASE)
    @PostMapping("/api/doccreation/instances/{id}/release")
    public ResponseEntity<?> release(@PathVariable int id,
                                     @Valid @RequestBody(required = false)
                                     ReleaseDecisionRequest request) {
        return ResponseEntity.ok(useCase.release(id, commentOf(request)));
    }

    /** Approver: refuses — the document returns to GENERATED for rework. */
    @RequiresPermission(Permission.DOC_RELEASE)
    @PostMapping("/api/doccreation/instances/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable int id,
                                    @Valid @RequestBody(required = false)
                                    ReleaseDecisionRequest request) {
        return ResponseEntity.ok(useCase.reject(id, commentOf(request)));
    }

    /**
     * Approver: downloads a SUBMITTED document of their bank exactly as last
     * generated — never regenerating someone else's instance. Output is PDF
     * only; {@code format} is accepted for older clients and ignored.
     */
    @RequiresPermission(Permission.DOC_RELEASE)
    @GetMapping("/api/doccreation/instances/{id}/review-file")
    public ResponseEntity<byte[]> reviewFile(@PathVariable int id,
                                             @RequestParam(value = "format", required = false) String format) {
        DocInstanceUseCase.PdfDownload file = useCase.downloadForReview(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, DocCreationErrors.attachment(file.downloadName()))
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.bytes());
    }

    private static String commentOf(ReleaseDecisionRequest request) {
        return request == null ? null : request.getComment();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
