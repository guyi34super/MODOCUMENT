package com.modocument.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AddBankAdminRequest;
import com.modocument.application.dto.BankSummary;
import com.modocument.application.dto.OnboardBankRequest;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.dto.PlatformOverview;
import com.modocument.application.dto.SuspendBankRequest;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.application.usecase.PlatformBankLifecycleUseCase;
import com.modocument.application.usecase.PlatformBankUseCase;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The platform console's bank registry (ws21, docs/phase2/contracts.md §3).
 * PLATFORM_READ / PLATFORM_BANKS are held only by PLATFORM_ADMIN, and the
 * permission checker additionally requires the caller to sit in the
 * platform bank. Reads are counts only ({@link BankSummary}).
 *
 * <p>Onboarding, suspension and the break-glass admin are maker/checker:
 * they answer 202 with the pending change (BANK_CREATE / BANK_SUSPEND /
 * BANK_ADMIN_ADD) that a SECOND platform admin approves through
 * {@code POST /api/admin/changes/{id}/approve}. Reactivation is direct (200).
 * Temporary passwords go no further than submit: the appliers check them
 * against the password policy and persist only their hashes.
 */
@RestController
@Tag(name = "Platform", description = "Platform owner console: banks (counts only)")
public class PlatformBankController {

    private final PlatformBankUseCase platformBanks;
    private final PlatformBankLifecycleUseCase lifecycle;
    private final PendingChangeUseCase pendingChanges;
    private final ObjectMapper objectMapper;

    public PlatformBankController(PlatformBankUseCase platformBanks,
                                  PlatformBankLifecycleUseCase lifecycle,
                                  PendingChangeUseCase pendingChanges,
                                  ObjectMapper objectMapper) {
        this.platformBanks = platformBanks;
        this.lifecycle = lifecycle;
        this.pendingChanges = pendingChanges;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Customer banks with counts-only figures, plus totals")
    @RequiresPermission(Permission.PLATFORM_READ)
    @GetMapping("/api/platform/overview")
    public PlatformOverview overview() {
        return platformBanks.overview();
    }

    @Operation(summary = "Every bank with counts-only activity figures")
    @RequiresPermission(Permission.PLATFORM_READ)
    @GetMapping("/api/platform/banks")
    public List<BankSummary> list() {
        return platformBanks.listBanks();
    }

    @Operation(summary = "One bank with counts-only activity figures")
    @RequiresPermission(Permission.PLATFORM_READ)
    @GetMapping("/api/platform/banks/{id}")
    public BankSummary get(@PathVariable int id) {
        return platformBanks.getBank(id);
    }

    /** Maker side of BANK_CREATE: 202 + the pending change awaiting a second platform admin. */
    @Operation(summary = "Request onboarding a bank with its first users (maker/checker)")
    @RequiresPermission(Permission.PLATFORM_BANKS)
    @PostMapping("/api/platform/banks")
    public ResponseEntity<PendingChangeDto> onboard(
            @Valid @RequestBody OnboardBankRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("code", request.code());
        payload.put("name", request.name());
        ArrayNode users = payload.putArray("users");
        if (request.users() != null) {
            for (OnboardBankRequest.User user : request.users()) {
                ObjectNode entry = users.addObject();
                entry.put("email", user.email());
                if (user.displayName() != null) {
                    entry.put("displayName", user.displayName());
                }
                entry.put("role", user.role());
                entry.put("temporaryPassword", user.temporaryPassword());
            }
        }
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.BANK_CREATE, payload, null));
    }

    /** Maker side of BANK_SUSPEND: 202 + the pending change. */
    @Operation(summary = "Request suspending a bank: its users' sessions end (maker/checker)")
    @RequiresPermission(Permission.PLATFORM_BANKS)
    @PostMapping("/api/platform/banks/{id}/suspend")
    public ResponseEntity<PendingChangeDto> suspend(@PathVariable int id,
                                                    @Valid @RequestBody SuspendBankRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("bankId", id);
        payload.put("reason", request.reason());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.BANK_SUSPEND, payload, null));
    }

    /** Direct (the lower-risk direction): the bank's users can sign in again. */
    @Operation(summary = "Reactivate a suspended bank")
    @RequiresPermission(Permission.PLATFORM_BANKS)
    @PostMapping("/api/platform/banks/{id}/reactivate")
    public BankSummary reactivate(@PathVariable int id) {
        return lifecycle.reactivate(id);
    }

    /** Maker side of BANK_ADMIN_ADD (break-glass): 202 + the pending change. */
    @Operation(summary = "Request a break-glass administrator for a bank (maker/checker)")
    @RequiresPermission(Permission.PLATFORM_BANKS)
    @PostMapping("/api/platform/banks/{id}/admins")
    public ResponseEntity<PendingChangeDto> addAdmin(@PathVariable int id,
                                                     @Valid @RequestBody AddBankAdminRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("bankId", id);
        payload.put("email", request.email());
        if (request.displayName() != null) {
            payload.put("displayName", request.displayName());
        }
        payload.put("temporaryPassword", request.temporaryPassword());
        payload.put("reason", request.reason());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.BANK_ADMIN_ADD, payload, null));
    }
}
