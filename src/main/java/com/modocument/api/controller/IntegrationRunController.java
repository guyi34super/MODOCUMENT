package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.integration.RunView;
import com.modocument.application.usecase.IntegrationRunUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Integration runs (ws25, contracts §3 SE4 / §7.3), INTEGRATION_RUN (ADMIN,
 * READ_WRITE): {@code POST /api/admin/integrations/flows/{id}/run} → 202
 * RunView; {@code GET …/runs?status=&flowId=&limit=}; {@code GET …/runs/{id}}
 * (with {@code events} and {@code deliveries}); {@code POST …/runs/{id}/retry}.
 */
@RestController
@Tag(name = "Integration runs", description = "Bank integration hub: run now, history, retry")
public class IntegrationRunController {

    private final IntegrationRunUseCase runs;

    public IntegrationRunController(IntegrationRunUseCase runs) {
        this.runs = runs;
    }

    @Operation(summary = "Run a flow now (an outbound flow must be enabled)")
    @RequiresPermission(Permission.INTEGRATION_RUN)
    @PostMapping("/api/admin/integrations/flows/{id}/run")
    public ResponseEntity<RunView> run(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runs.run(id));
    }

    @Operation(summary = "Run history, newest first")
    @RequiresPermission(Permission.INTEGRATION_RUN)
    @GetMapping("/api/admin/integrations/runs")
    public List<RunView> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long flowId,
                              @RequestParam(required = false) Integer limit) {
        return runs.list(status, flowId, limit);
    }

    @Operation(summary = "One run with its log and deliveries")
    @RequiresPermission(Permission.INTEGRATION_RUN)
    @GetMapping("/api/admin/integrations/runs/{id}")
    public RunView get(@PathVariable long id) {
        return runs.get(id);
    }

    @Operation(summary = "Replay dead deliveries or re-queue a failed run")
    @RequiresPermission(Permission.INTEGRATION_RUN)
    @PostMapping("/api/admin/integrations/runs/{id}/retry")
    public RunView retry(@PathVariable long id) {
        return runs.retry(id);
    }
}
