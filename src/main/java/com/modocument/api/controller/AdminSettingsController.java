package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.SettingView;
import com.modocument.application.dto.SettingsPage;
import com.modocument.application.dto.SettingsUpdateRequest;
import com.modocument.application.dto.SettingsUpdateResult;
import com.modocument.application.usecase.SettingsUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A bank's own settings (ws20, docs/phase2/contracts.md §3): GET shows every
 * bank-scope setting with its effective value, source, limits and pending
 * state; PUT changes some of them — operational ones at once (200),
 * security ones as one maker/checker request (202 + the pending change).
 * Errors carry the contract codes (see SettingsExceptionHandler). A caller
 * of the platform bank gets 409 USE_PLATFORM_SETTINGS.
 */
@RestController
@Tag(name = "Settings", description = "Bank settings within the platform's guardrails (admin)")
public class AdminSettingsController {

    private final SettingsUseCase settings;

    public AdminSettingsController(SettingsUseCase settings) {
        this.settings = settings;
    }

    @RequiresPermission(Permission.SETTINGS_READ)
    @Operation(summary = "The bank's settings: values, sources, limits, pending changes")
    @GetMapping("/api/admin/settings")
    public SettingsPage<SettingView> get() {
        return settings.bankSettings();
    }

    @RequiresPermission(Permission.SETTINGS_WRITE)
    @Operation(summary = "Change bank settings (security changes need a second approver: 202)")
    @PutMapping("/api/admin/settings")
    public ResponseEntity<SettingsUpdateResult<SettingView>> update(
            @Valid @RequestBody SettingsUpdateRequest request) {
        SettingsUpdateResult<SettingView> result = settings.updateBankSettings(request);
        return ResponseEntity.status(result.pendingChange() == null
                ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }
}
