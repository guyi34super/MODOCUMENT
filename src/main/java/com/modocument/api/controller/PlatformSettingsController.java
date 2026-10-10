package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.PlatformSettingView;
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
 * The platform owner's settings layer (ws20): the defaults every bank
 * inherits, the platform-only settings, and the range ({@code bankMin}/
 * {@code bankMax}) each bank may choose from. Operational values apply at
 * once (200); security values and every bank-bound change are one
 * maker/checker request (202) a second platform admin approves. Only
 * platform-bank users get past {@code SettingsUseCase} (403 otherwise).
 */
@RestController
@Tag(name = "Platform settings", description = "Global defaults and bank bounds (platform owner)")
public class PlatformSettingsController {

    private final SettingsUseCase settings;

    public PlatformSettingsController(SettingsUseCase settings) {
        this.settings = settings;
    }

    @RequiresPermission(Permission.PLATFORM_READ)
    @Operation(summary = "Platform settings: values, hard limits and bank bounds")
    @GetMapping("/api/platform/settings")
    public SettingsPage<PlatformSettingView> get() {
        return settings.platformSettings();
    }

    @RequiresPermission(Permission.PLATFORM_SETTINGS)
    @Operation(summary = "Change platform settings or bank bounds (security: 202 pending)")
    @PutMapping("/api/platform/settings")
    public ResponseEntity<SettingsUpdateResult<PlatformSettingView>> update(
            @Valid @RequestBody SettingsUpdateRequest request) {
        SettingsUpdateResult<PlatformSettingView> result =
                settings.updatePlatformSettings(request);
        return ResponseEntity.status(result.pendingChange() == null
                ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }
}
