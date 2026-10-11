package com.modocument.application.usecase;

import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.ratelimit.AgentEnrolmentRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code POST /api/agent/v1/enroll} (ws26, docs/phase2/contracts.md §7.4):
 * an agent trades its one-time enrolment token for its own ApiClient
 * (Kind AGENT, scope AGENT_CONNECT) through SE2's {@link ApiClientIssuer}.
 *
 * <p>The route is public — the token is the credential:
 * <ol>
 *   <li>rate-limited per client IP ({@link AgentEnrolmentRateLimiter});</li>
 *   <li>the token's agent id is resolved to its bank by
 *       {@code TenantContext.runAsSystem} in its own read-only transaction —
 *       a REGISTERED call site: the bank is unknown until the token is
 *       resolved, and the read returns the agent's bank id and status only;</li>
 *   <li>everything else runs as THAT bank ({@code runAs}), in one write
 *       transaction with the agent row locked: still PENDING_ENROLMENT, not
 *       expired, SHA-256 of the secret equal to the stored hash (constant-time
 *       compare), bank not suspended. The hash is cleared in the same
 *       transaction — single use, a replay finds nothing to match;</li>
 *   <li>audited AGENT_ENROLLED (the bank explicit; no secret in the values).</li>
 * </ol>
 * Every refusal of the token itself is the same 401 ("invalid or expired"),
 * so the endpoint tells a caller nothing about which check failed.
 */
@Service
public class AgentEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentEnrolmentService.class);

    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
    private static final int MAX_CERTIFICATE_PEM = 16_384;
    private static final int MAX_REPORTED_NAME = 200;

    private final IntegrationAgentJpaRepository agents;
    private final ApiClientIssuer issuer;
    private final BankDirectory banks;
    private final AuditRecorder audit;
    private final AgentEnrolmentRateLimiter rateLimiter;
    private final TransactionTemplate readOnlyNewTx;
    private final TransactionTemplate writeTx;
    private Clock clock = Clock.systemUTC();

    public AgentEnrolmentService(IntegrationAgentJpaRepository agents, ApiClientIssuer issuer,
                                 BankDirectory banks, AuditRecorder audit,
                                 AgentEnrolmentRateLimiter rateLimiter,
                                 PlatformTransactionManager transactionManager) {
        this.agents = agents;
        this.issuer = issuer;
        this.banks = banks;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly.setReadOnly(true);
        this.readOnlyNewTx = readOnly;
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTx = write;
    }

    /** Test seam. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param clientIp the caller's address (rate limit, audit)
     * @param tokenUrl absolute URL of the client-credentials token endpoint
     * @throws InvalidEnrolmentTokenException (401) for any token refusal
     */
    public AgentProtocol.EnrollResponse enroll(AgentProtocol.EnrollRequest request, String clientIp,
                                               String tokenUrl) {
        rateLimiter.check(clientIp);
        if (request == null) {
            throw new IllegalArgumentException("An enrolment request body is required.");
        }
        AgentEnrolmentToken token = AgentEnrolmentToken.parse(request.token())
                .orElseThrow(InvalidEnrolmentTokenException::new);
        ApiAuthMethod method = authMethod(request.authMethod());
        String certificatePem = request.certificatePem();
        if (method == ApiAuthMethod.MTLS) {
            if (certificatePem == null || certificatePem.isBlank()
                    || certificatePem.length() > MAX_CERTIFICATE_PEM
                    || !certificatePem.contains("-----BEGIN CERTIFICATE-----")) {
                throw new IllegalArgumentException(
                        "MTLS enrolment needs the agent's client certificate (PEM).");
            }
        } else {
            certificatePem = null;
        }
        String version = request.version() == null ? null : request.version().strip();
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("The agent version is not a release version.");
        }

        Integer bankId = pendingAgentBank(token.agentId());
        if (bankId == null || banks.isSuspended(bankId)) {
            log.warn("Agent enrolment refused for agent id {} from {}", token.agentId(), clientIp);
            throw new InvalidEnrolmentTokenException();
        }
        String reportedName = truncate(request.name(), MAX_REPORTED_NAME);
        String pem = certificatePem;
        try {
            return TenantContext.runAs(bankId, () -> writeTx.execute(status ->
                    enrolAs(bankId, token, method, pem, version, reportedName, clientIp, tokenUrl)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Enrolment failed.", e);
        }
    }

    /**
     * REGISTERED runAsSystem CALL SITE (see TenantContext): the bank of a
     * PENDING_ENROLMENT agent, or null. Reads one row by primary key and
     * returns its bank id only.
     */
    private Integer pendingAgentBank(int agentId) {
        try {
            return TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status -> {
                Optional<IntegrationAgent> agent = agents.findById(agentId);
                return agent.filter(a -> a.getStatus() == AgentStatus.PENDING_ENROLMENT)
                        .map(IntegrationAgent::getBankId)
                        .orElse(null);
            }));
        } catch (Exception e) {
            throw new IllegalStateException("Could not resolve the enrolling agent.", e);
        }
    }

    private AgentProtocol.EnrollResponse enrolAs(int bankId, AgentEnrolmentToken token,
                                                 ApiAuthMethod method, String certificatePem,
                                                 String version, String reportedName,
                                                 String clientIp, String tokenUrl) {
        IntegrationAgent agent = agents.findForUpdate(token.agentId(), bankId)
                .orElseThrow(InvalidEnrolmentTokenException::new);
        Instant now = clock.instant();
        boolean valid = agent.getStatus() == AgentStatus.PENDING_ENROLMENT
                && agent.getEnrolmentExpiresAt() != null
                && now.isBefore(agent.getEnrolmentExpiresAt());
        // Constant-time compare, evaluated even when the state check already
        // failed, so the timing does not tell which part was wrong.
        boolean matches = token.matches(agent.getEnrolmentTokenHash());
        if (!valid || !matches) {
            log.warn("Agent enrolment refused for agent {} of bank {} from {}",
                    agent.getIntegrationAgentId(), bankId, clientIp);
            throw new InvalidEnrolmentTokenException();
        }

        ApiClientIssuer.Issued issued = issuer.issueAgentClient(bankId,
                agent.getIntegrationAgentId(), agent.getName(), method, certificatePem);
        agent.setApiClientId(issued.apiClientId());
        agent.setStatus(AgentStatus.ONLINE);
        agent.setEnrolledAt(now);
        agent.setLastHeartbeatAt(now);
        agent.setVersion(version);
        agent.clearEnrolmentSecret();
        agents.save(agent);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("agentId", agent.getIntegrationAgentId());
        values.put("name", agent.getName());
        values.put("apiClientId", issued.apiClientId());
        values.put("clientId", issued.clientId());
        values.put("authMethod", method.name());
        values.put("version", version);
        values.put("reportedName", reportedName);
        audit.record(AuditEventSpec.builder("AGENT_ENROLLED", AuditOutcome.SUCCESS)
                .entity("IntegrationAgent", String.valueOf(agent.getIntegrationAgentId()))
                .bankId(bankId)
                .actorRole("AGENT")
                .newValues(values)
                .build());
        return new AgentProtocol.EnrollResponse(agent.getIntegrationAgentId(), issued.clientId(),
                issued.secret(), tokenUrl);
    }

    private static ApiAuthMethod authMethod(String requested) {
        if (requested == null || requested.isBlank()) {
            return ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS;
        }
        try {
            ApiAuthMethod method = ApiAuthMethod.valueOf(requested.strip().toUpperCase(Locale.ROOT));
            if (method == ApiAuthMethod.API_KEY) {
                throw new IllegalArgumentException(
                        "Agents authenticate with OAUTH2_CLIENT_CREDENTIALS or MTLS.");
            }
            return method;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Agents authenticate with OAUTH2_CLIENT_CREDENTIALS or MTLS.");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    /** 401: the token is unknown, malformed, used, expired or of a revoked agent. */
    public static class InvalidEnrolmentTokenException extends RuntimeException {

        public static final String CODE = "AGENT_ENROLMENT_INVALID";

        public InvalidEnrolmentTokenException() {
            super("The enrolment token is invalid or expired. Ask your administrator for a new one.");
        }
    }
}
