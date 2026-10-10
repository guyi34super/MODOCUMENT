package com.modocument.application.usecase;

import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is calling {@code /api/agent/v1/**} (ws26). {@code @RequiresPermission
 * (AGENT_CONNECT)} already holds at the route (the API_CLIENT role grant ∩
 * the token's scopes); this pins the caller to ONE live agent:
 * <ul>
 *   <li>a machine principal ({@link CurrentClientProvider}) of Kind AGENT,
 *       bound to an agent id, with the AGENT_CONNECT scope;</li>
 *   <li>that agent exists in the principal's bank, is enrolled (not
 *       PENDING_ENROLMENT) and not REVOKED;</li>
 *   <li>the agent's ApiClientId is the principal's client — an agent's
 *       credentials never act for another agent.</li>
 * </ul>
 * Anything else is a 403 (AccessDeniedException). Checked on every call, so
 * revoking an agent cuts it off at its next request.
 */
@Component
public class AgentCallerResolver {

    private final CurrentClientProvider clients;
    private final IntegrationAgentJpaRepository agents;

    public AgentCallerResolver(CurrentClientProvider clients, IntegrationAgentJpaRepository agents) {
        this.clients = clients;
        this.agents = agents;
    }

    /** The calling agent; 403 unless every check above holds. */
    @Transactional(readOnly = true)
    public AgentCaller require() {
        ClientPrincipal principal = clients.current()
                .orElseThrow(() -> denied("Agent endpoints need an agent client token."));
        if (principal.kind() != ApiClientKind.AGENT || principal.agentId() == null
                || !principal.scopes().contains(Permission.AGENT_CONNECT)) {
            throw denied("This client is not a Connector Agent.");
        }
        IntegrationAgent agent = agents
                .findByIntegrationAgentIdAndBankId(principal.agentId(), principal.bankId())
                .orElseThrow(() -> denied("This agent is not registered."));
        if (agent.getStatus() == AgentStatus.REVOKED
                || agent.getStatus() == AgentStatus.PENDING_ENROLMENT) {
            throw denied("This agent is revoked or not enrolled.");
        }
        if (agent.getApiClientId() == null || agent.getApiClientId() != principal.apiClientId()) {
            throw denied("This client is not the agent's credential.");
        }
        return new AgentCaller(principal.bankId(), agent.getIntegrationAgentId(),
                principal.apiClientId());
    }

    private static AccessDeniedException denied(String message) {
        return new AccessDeniedException(message);
    }

    /** The resolved caller: bank, agent, client. */
    public record AgentCaller(int bankId, int agentId, int apiClientId) {
    }
}
