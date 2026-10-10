package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.integration.FlowPreview;
import com.modocument.application.dto.integration.FlowRequest;
import com.modocument.application.dto.integration.FlowView;
import com.modocument.application.usecase.IntegrationFlowUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Integration flows (ws25, contracts §3 SE4 / §7.3), INTEGRATION_ADMIN:
 * CRUD ({@code POST} 201 FlowView, created disabled; {@code PUT} 200 FlowView,
 * or 202 PendingChange when it widened an enabled outbound flow), {@code enable}
 * (INBOUND 200 FlowView; OUTBOUND 202 PendingChange {@code INTEGRATION_FLOW_ENABLE}),
 * {@code disable} (200, direct), {@code preview} (no body; ≤ 20 rows, nothing written).
 */
@RestController
@Tag(name = "Integration flows", description = "Bank integration hub: what to receive or send, and when")
public class IntegrationFlowController {

    private static final String BASE = "/api/admin/integrations/flows";

    private final IntegrationFlowUseCase flows;

    public IntegrationFlowController(IntegrationFlowUseCase flows) {
        this.flows = flows;
    }

    @Operation(summary = "The bank's integration flows")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @GetMapping(BASE)
    public List<FlowView> list() {
        return flows.list();
    }

    @Operation(summary = "Create a flow (disabled)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE)
    public ResponseEntity<FlowView> create(@RequestBody FlowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(flows.create(request));
    }

    @Operation(summary = "One flow")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @GetMapping(BASE + "/{id}")
    public FlowView get(@PathVariable long id) {
        return flows.get(id);
    }

    @Operation(summary = "Update a flow (widening an enabled outbound flow disables it and asks approval: 202)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PutMapping(BASE + "/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody FlowRequest request) {
        IntegrationFlowUseCase.Outcome outcome = flows.update(id, request);
        return outcome.pendingChange() != null
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.pendingChange())
                : ResponseEntity.ok(outcome.view());
    }

    @Operation(summary = "Delete a flow with its run history")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @DeleteMapping(BASE + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        flows.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Enable: inbound at once (200), outbound by a second admin (202)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE + "/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable long id) {
        IntegrationFlowUseCase.Outcome outcome = flows.enable(id);
        return outcome.pendingChange() != null
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.pendingChange())
                : ResponseEntity.ok(outcome.view());
    }

    @Operation(summary = "Disable (direct)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE + "/{id}/disable")
    public FlowView disable(@PathVariable long id) {
        return flows.disable(id);
    }

    @Operation(summary = "Dry run: the first 20 rows and a suggested mapping; nothing is written")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE + "/{id}/preview")
    public FlowPreview preview(@PathVariable long id) throws Exception {
        return flows.preview(id);
    }
}
