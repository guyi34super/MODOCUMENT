package com.modocument.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.EgressRuleRequest;
import com.modocument.application.dto.EgressRuleView;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.usecase.EgressRuleUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bank egress rules (ws25, docs/phase2/contracts.md §3 SE1, §7.3
 * {@code EgressRuleView}): which private-network destinations a bank's
 * DIRECT connections may reach.
 *
 * <ul>
 *   <li>Bank side, INTEGRATION_ADMIN: list, request (200, PENDING — opens
 *       nothing yet), revoke (direct, 200; DELETE or POST …/{id}/revoke).</li>
 *   <li>Platform side: the cross-bank listing (PLATFORM_READ) and the
 *       approval (PLATFORM_BANKS) — 202 with an EGRESS_RULE_APPROVE pending
 *       change that a SECOND platform admin approves through
 *       {@code POST /api/admin/changes/{id}/approve}.</li>
 * </ul>
 */
@RestController
@Tag(name = "Integrations", description = "Bank Integration Hub: egress rules")
public class EgressRuleController {

    private final EgressRuleUseCase egressRules;
    private final PendingChangeUseCase pendingChanges;
    private final ObjectMapper objectMapper;

    public EgressRuleController(EgressRuleUseCase egressRules,
                                PendingChangeUseCase pendingChanges,
                                ObjectMapper objectMapper) {
        this.egressRules = egressRules;
        this.pendingChanges = pendingChanges;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ bank

    @Operation(summary = "The bank's egress rules (every status), newest first")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @GetMapping("/api/admin/integrations/egress-rules")
    public List<EgressRuleView> list() {
        return egressRules.listForBank();
    }

    @Operation(summary = "Request a private-network destination (PENDING until the platform "
            + "approves it)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping("/api/admin/integrations/egress-rules")
    public EgressRuleView request(@Valid @RequestBody EgressRuleRequest request) {
        return egressRules.request(request);
    }

    @Operation(summary = "Revoke an egress rule (direct)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @DeleteMapping("/api/admin/integrations/egress-rules/{id}")
    public EgressRuleView revoke(@PathVariable int id) {
        return egressRules.revoke(id);
    }

    /** The same revocation as {@code DELETE …/{id}}, as the Integrations UI calls it. */
    @Operation(summary = "Revoke an egress rule (direct; same as DELETE)")
    @RequiresPermission(Permission.INTEGRATION_ADMIN)
    @PostMapping("/api/admin/integrations/egress-rules/{id}/revoke")
    public EgressRuleView revokeViaPost(@PathVariable int id) {
        return egressRules.revoke(id);
    }

    // -------------------------------------------------------------- platform

    @Operation(summary = "Every bank's egress rules (default: pending and approved)")
    @RequiresPermission(Permission.PLATFORM_READ)
    @GetMapping("/api/platform/egress-rules")
    public List<EgressRuleView> listForPlatform(
            @RequestParam(name = "status", required = false) String status) {
        return egressRules.listForPlatform(status);
    }

    /** Maker side of EGRESS_RULE_APPROVE: 202 + the pending change. */
    @Operation(summary = "Request approval of a bank's egress rule (maker/checker)")
    @RequiresPermission(Permission.PLATFORM_BANKS)
    @PostMapping("/api/platform/egress-rules/{id}/approve")
    public ResponseEntity<PendingChangeDto> approve(@PathVariable int id) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ruleId", id);
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.EGRESS_RULE_APPROVE, payload, null));
    }
}
