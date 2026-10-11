package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.ChangeDecisionRequest;
import com.modocument.application.dto.SubmitChangeRequest;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.domain.model.Permission;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maker/checker approval queue (ws10, gate B7, SEC-023).
 *
 * <p>Permissions: approve/reject are the checker verbs and carry
 * {@code CHANGE_APPROVE} (APPROVER, ADMIN, PLATFORM_ADMIN) on the route;
 * the use case further narrows each type to its checker audience (a second
 * platform admin for platform types, see {@code ChangeAudience}). The maker-side
 * routes (submit, list, cancel) cannot be expressed as ONE route-level
 * permission — the required permission depends on the change TYPE (user
 * ops need USER_ADMIN, template ops TEMPLATE_WRITE) or on being the row's
 * maker — so they carry PROFILE_SELF (any fully authenticated account) to
 * satisfy the deny-by-default coverage test, and
 * {@link PendingChangeUseCase} enforces the real per-type/per-row rule
 * through the same PermissionChecker (which also keeps the ws09
 * forced-password-change gate closed here). Going through this queue is
 * never a privilege shortcut.
 *
 * <p>Error mapping rides the global ApiExceptionHandler: unknown/foreign
 * change id → 404, bad payload/type → 400, self-approval and
 * already-decided → 409, missing per-type permission → 403.
 */
@RestController
@Tag(name = "Change Approvals", description = "Maker-checker pending changes (admin)")
public class PendingChangeController {

    private final PendingChangeUseCase pendingChanges;

    public PendingChangeController(PendingChangeUseCase pendingChanges) {
        this.pendingChanges = pendingChanges;
    }

    /** Maker: submits a change for a different user's approval (202 + the pending row). */
    @RequiresPermission(Permission.PROFILE_SELF)
    @Operation(summary = "Maker: submit a change for another user's approval")
    @PostMapping("/api/admin/changes")
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitChangeRequest request) {
        return ResponseEntity.accepted().body(pendingChanges.submit(
                request.getChangeType(), request.getPayload(), request.getComment()));
    }

    /** CHANGE_APPROVE holders see the bank's queue; a plain maker only their own rows. */
    @RequiresPermission(Permission.PROFILE_SELF)
    @GetMapping("/api/admin/changes")
    public ResponseEntity<?> list(@RequestParam(name = "status", required = false) String status,
                                  @RequestParam(name = "page", required = false) Integer page,
                                  @RequestParam(name = "size", required = false) Integer size) {
        // ws28: with a page parameter, the paged envelope {items, page, size,
        // total}; without one, the old plain list (capped at the newest 200).
        if (page != null) {
            return ResponseEntity.ok(pendingChanges.page(status, page, size));
        }
        return ResponseEntity.ok(pendingChanges.list(status));
    }

    /** Checker: approves and APPLIES. Self-approval is a hard 409 (gate B7). */
    @RequiresPermission(Permission.CHANGE_APPROVE)
    @PostMapping("/api/admin/changes/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable int id,
                                     @Valid @RequestBody(required = false)
                                     ChangeDecisionRequest request) {
        return ResponseEntity.ok(pendingChanges.approve(id, commentOf(request)));
    }

    /** Checker: refuses the change; nothing is applied. */
    @RequiresPermission(Permission.CHANGE_APPROVE)
    @PostMapping("/api/admin/changes/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable int id,
                                    @Valid @RequestBody(required = false)
                                    ChangeDecisionRequest request) {
        return ResponseEntity.ok(pendingChanges.reject(id, commentOf(request)));
    }

    /** Maker: withdraws their own undecided request. */
    @RequiresPermission(Permission.PROFILE_SELF)
    @PostMapping("/api/admin/changes/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable int id) {
        return ResponseEntity.ok(pendingChanges.cancel(id));
    }

    private static String commentOf(ChangeDecisionRequest request) {
        return request == null ? null : request.getComment();
    }
}
