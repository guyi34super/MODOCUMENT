package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.integration.ConnectionRequest;
import com.modocument.application.dto.integration.ConnectionView;
import com.modocument.application.usecase.IntegrationConnectionUseCase;
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
import java.util.Map;

/**
 * Integration connections (ws25, contracts §3 SE4 / §7.3), INTEGRATION_ADMIN:
 * <ul>
 *   <li>{@code GET /api/admin/integrations/connections} → {@code [ConnectionView]};</li>
 *   <li>{@code POST} → 202 PendingChange ({@code INTEGRATION_CONNECTION_CHANGE});</li>
 *   <li>{@code GET|PUT|DELETE /{id}} — PUT: rename-only 200 ConnectionView,
 *       anything else 202 PendingChange; DELETE 204 (direct);</li>
 *   <li>{@code POST /{id}/test} → {@code {ok, message, latencyMillis}}; an AGENT
 *       connection answers 202 {@code {runId}}.</li>
 * </ul>
 * Secrets are write-only: no response ever carries one.
 */
@RestController
@Tag(name = "Integration connections", description = "Bank integration hub: where the bank's systems are")
public class IntegrationConnectionController {

    private static final String BASE = "/api/admin/integrations/connections";

    private final IntegrationConnectionUseCase connections;

    public IntegrationConnectionController(IntegrationConnectionUseCase connections) {
        this.connections = connections;
    }

    @Operation(summary = "The bank's integration connections (secrets never returned)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @GetMapping(BASE)
    public List<ConnectionView> list() {
        return connections.list();
    }

    @Operation(summary = "Request a new connection (maker/checker, 202)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE)
    public ResponseEntity<?> create(@RequestBody ConnectionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(connections.create(request));
    }

    @Operation(summary = "One connection")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @GetMapping(BASE + "/{id}")
    public ConnectionView get(@PathVariable long id) {
        return connections.get(id);
    }

    @Operation(summary = "Rename (200) or change host/settings/credentials (maker/checker, 202)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PutMapping(BASE + "/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody ConnectionRequest request) {
        IntegrationConnectionUseCase.Outcome outcome = connections.update(id, request);
        if (outcome.pendingChange() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.pendingChange());
        }
        return ResponseEntity.ok(outcome.view());
    }

    @Operation(summary = "Delete a connection no flow uses (direct)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @DeleteMapping(BASE + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        connections.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Test the connection (an AGENT connection is tested by its agent: 202 {runId})")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping(BASE + "/{id}/test")
    public ResponseEntity<?> test(@PathVariable long id) {
        IntegrationConnectionUseCase.Outcome outcome = connections.test(id);
        if (outcome.runId() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", outcome.runId()));
        }
        return ResponseEntity.ok(outcome.testResult());
    }
}
