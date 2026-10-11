package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.DashboardSummary;
import com.modocument.application.usecase.DashboardSummaryUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user dashboard (ws28): counts and the newest rows of the caller's own
 * templates, ingestion jobs, extraction jobs and imports in one call. Gated
 * like the job list the dashboard was built on ({@code ingestion:read});
 * every role holding it also reads templates, extraction jobs and imports.
 */
@RestController
@Tag(name = "Dashboard", description = "User dashboard summary")
public class DashboardController {

    private final DashboardSummaryUseCase dashboard;

    public DashboardController(DashboardSummaryUseCase dashboard) {
        this.dashboard = dashboard;
    }

    @RequiresPermission(Permission.INGESTION_READ)
    @Operation(summary = "The caller's dashboard: counts and the newest jobs and imports")
    @GetMapping("/api/dashboard/summary")
    public ResponseEntity<DashboardSummary> summary() {
        return ResponseEntity.ok(dashboard.summary());
    }
}
