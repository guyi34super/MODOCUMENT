package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.usecase.AgentEnrolmentService;
import com.modocument.application.usecase.AgentProtocolUseCase;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.security.ClientIps;
import com.modocument.infrastructure.security.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Connector Agent protocol {@code /api/agent/v1} (ws26,
 * docs/phase2/contracts.md §7.4, docs/integration/agent.md). The agent only
 * ever calls OUT to these routes; it has no inbound port.
 * <ul>
 *   <li>{@code POST /enroll} — PUBLIC (no @RequiresPermission; listed in
 *       RoutePermissionCoverageTest.PUBLIC_ROUTES and permitted in
 *       SecurityConfig): the one-time enrolment token in the body is the
 *       credential, see {@link AgentEnrolmentService};</li>
 *   <li>everything else needs AGENT_CONNECT, i.e. an agent client token,
 *       and the caller must be an enrolled, non-revoked agent whose
 *       ApiClient is the token's client
 *       ({@code AgentCallerResolver}).</li>
 * </ul>
 *
 * <p>{@link MachineApi}: agent calls are metered per client, stamp LastUsedAt
 * and are audited with the agent client as actor; the public enroll call has
 * no client yet, so the interceptor leaves it alone.
 */
@MachineApi
@RestController
@Tag(name = "Connector agent protocol", description = "Outbound-only protocol of the on-prem agent")
public class AgentProtocolController {

    private final AgentEnrolmentService enrolment;
    private final AgentProtocolUseCase protocol;
    private final String publicUrl;

    public AgentProtocolController(AgentEnrolmentService enrolment, AgentProtocolUseCase protocol,
                                   @Value("${modocument.agent.public-url:}") String publicUrl) {
        this.enrolment = enrolment;
        this.protocol = protocol;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip();
    }

    @Operation(summary = "Enrol an agent with its one-time token; returns its own client credentials "
            + "(public route: the token is the credential; rate-limited per IP)")
    @PostMapping("/api/agent/v1/enroll")
    public ResponseEntity<AgentProtocol.EnrollResponse> enroll(
            @RequestBody AgentProtocol.EnrollRequest request, HttpServletRequest httpRequest) {
        AgentProtocol.EnrollResponse response = enrolment.enroll(request,
                ClientIps.clientIp(httpRequest), baseUrl() + "/api/integration/oauth/token");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @Operation(summary = "Long-poll for run tasks (wait 0-30 s)")
    @RequiresPermission(Permission.AGENT_CONNECT)
    @GetMapping("/api/agent/v1/tasks")
    public DeferredResult<AgentProtocol.TasksResponse> tasks(
            @RequestParam(defaultValue = "30") int wait) {
        return protocol.tasks(wait);
    }

    @Operation(summary = "Upload one page of records pulled for an inbound run")
    @RequiresPermission(Permission.AGENT_CONNECT)
    @PostMapping("/api/agent/v1/runs/{id}/records")
    public ResponseEntity<Void> records(@PathVariable long id,
                                        @RequestBody AgentProtocol.RecordsRequest request) {
        protocol.records(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "The outbound batch an outbound run must write locally (fileContent base64)")
    @RequiresPermission(Permission.AGENT_CONNECT)
    @GetMapping("/api/agent/v1/runs/{id}/payload")
    public ResponseEntity<OutboundBatch> payload(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(protocol.payload(id));
    }

    @Operation(summary = "Report a run's outcome")
    @RequiresPermission(Permission.AGENT_CONNECT)
    @PostMapping("/api/agent/v1/runs/{id}/result")
    public ResponseEntity<Void> result(@PathVariable long id,
                                       @RequestBody AgentProtocol.ResultRequest request) {
        protocol.result(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Heartbeat: version, health and connections; answers the latest version")
    @RequiresPermission(Permission.AGENT_CONNECT)
    @PostMapping("/api/agent/v1/heartbeat")
    public AgentProtocol.HeartbeatResponse heartbeat(
            @RequestBody AgentProtocol.HeartbeatRequest request) {
        return protocol.heartbeat(request);
    }

    /** 401 with the contract's error shape; the same answer for every token refusal. */
    @ExceptionHandler(AgentEnrolmentService.InvalidEnrolmentTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken(
            AgentEnrolmentService.InvalidEnrolmentTokenException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", AgentEnrolmentService.InvalidEnrolmentTokenException.CODE);
        body.put("message", e.getMessage());
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private String baseUrl() {
        if (!publicUrl.isEmpty()) {
            return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
