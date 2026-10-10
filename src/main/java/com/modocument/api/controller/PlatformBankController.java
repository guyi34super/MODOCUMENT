package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.BankSummary;
import com.modocument.application.usecase.PlatformBankUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The platform console's bank registry (ws21, docs/phase2/contracts.md §3).
 * PLATFORM_READ is held only by PLATFORM_ADMIN, and the permission checker
 * additionally requires the caller to sit in the platform bank. Responses
 * are counts only ({@link BankSummary}). Onboarding, suspension and
 * break-glass admin routes (PLATFORM_BANKS, maker/checker) join this
 * controller in wave 2, hence the per-method permissions.
 */
@RestController
@Tag(name = "Platform", description = "Platform owner console: banks (counts only)")
public class PlatformBankController {

    private final PlatformBankUseCase platformBanks;

    public PlatformBankController(PlatformBankUseCase platformBanks) {
        this.platformBanks = platformBanks;
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
}
