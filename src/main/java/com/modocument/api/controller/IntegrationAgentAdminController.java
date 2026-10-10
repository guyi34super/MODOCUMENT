package com.modocument.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AgentEnrolmentView;
import com.modocument.application.dto.AgentView;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.usecase.IntegrationAgentUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.modocument.infrastructure.security.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bank's on-prem Connector Agents (ws26, docs/phase2/contracts.md §3
 * SE5 / §7.3), INTEGRATION_ADMIN throughout:
 * <ul>
 *   <li>{@code GET /api/admin/integrations/agents} → {@code [AgentView]};
 *       {@code GET …/{id}} → AgentView;</li>
 *   <li>{@code POST …/agents {name}} → 202 PendingChange
 *       ({@code AGENT_REGISTER}); approving it creates the agent with its
 *       one-time enrolment token;</li>
 *   <li>{@code GET …/{id}/enrolment} → {@code {token, expiresAt,
 *       dockerCommand}} — the MAKER only, once, within 15 minutes;</li>
 *   <li>{@code POST …/{id}/revoke} → AgentView (direct, terminal).</li>
 * </ul>
 * The URL the agent is told to call is {@code modocument.agent.public-url}
 * when set, else this request's own base URL (built from the forwarded
 * host nginx pins to MODOCUMENT_PUBLIC_HOST).
 */
@RestController
@Tag(name = "Integration agents", description = "On-prem Connector Agents of the bank")
@RequiresPermission(Permission.INTEGRATION_ADMIN)
public class IntegrationAgentAdminController {

    private final IntegrationAgentUseCase agents;
    private final PendingChangeUseCase pendingChanges;
    private final ObjectMapper objectMapper;
    private final String publicUrl;

    public IntegrationAgentAdminController(IntegrationAgentUseCase agents,
                                           PendingChangeUseCase pendingChanges,
                                           ObjectMapper objectMapper,
                                           @Value("${modocument.agent.public-url:}") String publicUrl) {
        this.agents = agents;
        this.pendingChanges = pendingChanges;
        this.objectMapper = objectMapper;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip();
    }

    @Operation(summary = "The bank's Connector Agents with computed status, version and update notice")
    @GetMapping("/api/admin/integrations/agents")
    public List<AgentView> list() {
        return agents.list();
    }

    @Operation(summary = "One Connector Agent")
    @GetMapping("/api/admin/integrations/agents/{id}")
    public AgentView get(@PathVariable int id) {
        return agents.get(id);
    }

    @Operation(summary = "Register an agent (maker/checker AGENT_REGISTER, answers 202)")
    @PostMapping("/api/admin/integrations/agents")
    public ResponseEntity<PendingChangeDto> register(@RequestBody RegisterAgentRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", request == null ? null : request.name());
        return ResponseEntity.accepted()
                .body(pendingChanges.submit(ChangeType.AGENT_REGISTER, payload, null));
    }

    @Operation(summary = "The one-time enrolment token and docker command (maker only, readable once)")
    @GetMapping("/api/admin/integrations/agents/{id}/enrolment")
    public ResponseEntity<AgentEnrolmentView> enrolment(@PathVariable int id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(agents.readEnrolment(id, baseUrl()));
    }

    @Operation(summary = "Revoke an agent: every later agent call is refused")
    @PostMapping("/api/admin/integrations/agents/{id}/revoke")
    public AgentView revoke(@PathVariable int id) {
        return agents.revoke(id);
    }

    /**
     * 410 Gone: the one-time token was already shown, used, revoked or has
     * expired (the UI tells the admin to revoke and register again).
     */
    @ExceptionHandler(IntegrationAgentUseCase.EnrolmentUnavailableException.class)
    public ResponseEntity<Map<String, String>> enrolmentGone(
            IntegrationAgentUseCase.EnrolmentUnavailableException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", "AGENT_ENROLMENT_GONE");
        body.put("message", e.getMessage());
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    private String baseUrl() {
        if (!publicUrl.isEmpty()) {
            return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /** Body of {@code POST /api/admin/integrations/agents}. */
    public record RegisterAgentRequest(String name) {
    }
}
