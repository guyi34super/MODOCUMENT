package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AdminDashboardDto;
import com.modocument.application.usecase.AdminDashboardUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bank admin's overview (phase 2, ws22): user, approval, ingestion,
 * extraction, alert, retention and template counters for the CALLER'S bank
 * — counts only, cached 30 s per bank. METRICS_READ, like the extraction
 * metrics (ADMIN only).
 */
@RestController
@Tag(name = "Admin Dashboard", description = "Bank-wide counters for the admin overview")
@RequiresPermission(Permission.METRICS_READ)
public class AdminDashboardController {

    private final AdminDashboardUseCase dashboard;

    public AdminDashboardController(AdminDashboardUseCase dashboard) {
        this.dashboard = dashboard;
    }

    @Operation(summary = "Counters of the caller's bank for the admin dashboard")
    @GetMapping("/api/admin/dashboard")
    public ResponseEntity<AdminDashboardDto> get() {
        return ResponseEntity.ok(dashboard.dashboard());
    }
}
