package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.CreateUserRequest;
import com.modocument.application.dto.ResetUserMfaRequest;
import com.modocument.application.dto.ResetUserPasswordRequest;
import com.modocument.application.dto.UpdateUserDisabledRequest;
import com.modocument.application.dto.UpdateUserRequest;
import com.modocument.application.dto.UpdateUserRoleRequest;
import com.modocument.application.usecase.AccessReviewUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.application.usecase.UserAdminUseCase;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bank-scoped user administration (ws09, gates B1/B10, PRIV-017). Every
 * route requires USER_ADMIN (ADMIN only — class-level annotation), and every
 * operation is scoped to the CALLER'S bank inside the use cases: a user of
 * another bank 404s like a non-existent id. Error mapping rides the global
 * ApiExceptionHandler: unknown/foreign user → 404, bad role or weak
 * temporary password → 400, self-targeting and the last-admin guard → 409.
 *
 * <p>Dual control (ws10, gate B7/SEC-023): user creation, role changes and
 * the DISABLE switch no longer apply here — they SUBMIT a PendingChange
 * (202 Accepted + the pending row) that a DIFFERENT user must approve via
 * POST /api/admin/changes/{id}/approve. Only re-enabling an account stays
 * direct: it restores access that was already dual-approved.
 *
 * <p>Phase 2 (ws22): unlock and display-name edits apply directly (200);
 * password and MFA resets are maker/checker (202, USER_PASSWORD_RESET /
 * USER_MFA_RESET) and share the {@code admin-security} rate-limit bucket.
 * Every one of them refuses the caller's own account (409).
 */
@RestController
@Tag(name = "User Admin", description = "User lifecycle (maker-checker gated)")
@RequiresPermission(Permission.USER_ADMIN)
public class UserAdminController {

    private final UserAdminUseCase userAdmin;
    private final AccessReviewUseCase accessReview;
    private final PendingChangeUseCase pendingChanges;
    private final ObjectMapper objectMapper;

    public UserAdminController(UserAdminUseCase userAdmin, AccessReviewUseCase accessReview,
                               PendingChangeUseCase pendingChanges, ObjectMapper objectMapper) {
        this.userAdmin = userAdmin;
        this.accessReview = accessReview;
        this.pendingChanges = pendingChanges;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(userAdmin.listUsers());
    }

    /** Maker side of USER_CREATE: 202 + the pending change awaiting a second admin. */
    @PostMapping("/api/admin/users")
    public ResponseEntity<?> create(@Valid @RequestBody CreateUserRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("email", request.getEmail());
        if (request.getDisplayName() != null) {
            payload.put("displayName", request.getDisplayName());
        }
        payload.put("role", request.getRole());
        // Plaintext travels no further than submit(): the applier validates
        // it against the password policy and persists only its hash.
        payload.put("temporaryPassword", request.getTemporaryPassword());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.USER_CREATE, payload, null));
    }

    /** Maker side of USER_ROLE_CHANGE: 202 + the pending change. */
    @PutMapping("/api/admin/users/{id}/role")
    public ResponseEntity<?> changeRole(@PathVariable int id,
                                        @Valid @RequestBody UpdateUserRoleRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetUserId", id);
        payload.put("role", request.getRole());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.USER_ROLE_CHANGE, payload, null));
    }

    /**
     * Disabling (the leaver switch) is maker/checker-gated: 202 + the
     * pending change. Re-enabling applies directly (200) — the lower-risk
     * direction, see the class comment.
     */
    @PutMapping("/api/admin/users/{id}/disabled")
    public ResponseEntity<?> setDisabled(@PathVariable int id,
                                         @Valid @RequestBody UpdateUserDisabledRequest request) {
        if (!request.getDisabled()) {
            return ResponseEntity.ok(userAdmin.setDisabled(id, false));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetUserId", id);
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.USER_DISABLE, payload, null));
    }

    // ------------------------------------------------ phase 2 (ws22) actions

    /** Lifts a login lockout early (direct: it restores access). */
    @Operation(summary = "Unlock a user's account after a login lockout")
    @PostMapping("/api/admin/users/{id}/unlock")
    public ResponseEntity<?> unlock(@PathVariable int id) {
        return ResponseEntity.ok(userAdmin.unlock(id));
    }

    /**
     * Maker side of USER_PASSWORD_RESET: 202 + the pending change. The
     * temporary password goes no further than submit(): the applier checks
     * it against the password policy and persists only its hash.
     */
    @Operation(summary = "Request a temporary password for a user (maker/checker)")
    @PostMapping("/api/admin/users/{id}/password-reset")
    public ResponseEntity<?> resetPassword(@PathVariable int id,
                                           @Valid @RequestBody ResetUserPasswordRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", id);
        payload.put("temporaryPassword", request.getTemporaryPassword());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.USER_PASSWORD_RESET, payload, null));
    }

    /** Maker side of USER_MFA_RESET: 202 + the pending change. */
    @Operation(summary = "Request removal of a user's MFA enrolment (maker/checker)")
    @PostMapping("/api/admin/users/{id}/mfa-reset")
    public ResponseEntity<?> resetMfa(@PathVariable int id,
                                      @Valid @RequestBody ResetUserMfaRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", id);
        payload.put("reason", request.getReason());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.USER_MFA_RESET, payload, null));
    }

    /** Edits another user's display name (direct). The email is not editable. */
    @Operation(summary = "Change a user's display name")
    @PatchMapping("/api/admin/users/{id}")
    public ResponseEntity<?> update(@PathVariable int id,
                                    @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userAdmin.updateDisplayName(id, request.getDisplayName()));
    }

    /** Gate B10: the caller's bank's users with last-login dates, as CSV. */
    @Operation(summary = "Export the bank's users with last-login dates (CSV)")
    @GetMapping("/api/admin/users/export")
    public ResponseEntity<byte[]> export() {
        UserAdminUseCase.CsvExport export = userAdmin.exportUsers();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName())
                        .build()
                        .toString())
                .body(export.content());
    }

    /** The SEC-013 quarterly-recertification worksheet for the caller's bank. */
    @GetMapping("/api/admin/users/access-review")
    public ResponseEntity<?> accessReviewWorksheet() {
        return ResponseEntity.ok(accessReview.generate());
    }
}
