package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AgentEnrolmentView;
import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.dto.AgentView;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.release.SystemVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Bank-side management of on-prem Connector Agents (ws26,
 * docs/phase2/contracts.md §3 SE5 / §7.3, docs/integration/agent.md).
 * INTEGRATION_ADMIN on every route; registration goes through maker/checker
 * ({@code AGENT_REGISTER}, {@link AgentRegisterChangeApplier}).
 *
 * <ul>
 *   <li>{@link #applyRegistration} (the checker's approval) creates the agent
 *       PENDING_ENROLMENT with a one-time enrolment token valid
 *       {@code modocument.agent.enrolment-ttl} (15 min). Only the SHA-256 of
 *       the token's secret is kept for the enrolment; the token itself is
 *       SEALED for the maker with the connector {@link SecretVault}.</li>
 *   <li>{@link #readEnrolment}: the MAKER reads the token once, with a
 *       ready-to-paste {@code docker run} command; the sealed copy is deleted
 *       in the same transaction. A second read, a read after the expiry or
 *       after the enrolment answers 410 Gone (the controller maps
 *       {@link EnrolmentUnavailableException}).</li>
 *   <li>{@link #revoke}: direct (the lower-risk direction) and terminal;
 *       every later {@code /api/agent/v1} call of the agent is refused.</li>
 * </ul>
 * Status is computed per view: an enrolled agent silent for longer than the
 * bank's {@code integration.agentHeartbeatTimeout} reads OFFLINE.
 */
@Service
public class IntegrationAgentUseCase {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAgentUseCase.class);

    /** Key of the token inside the sealed SecretMaterial. */
    static final String SEALED_TOKEN_KEY = "enrolmentToken";

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._()@-]{1,100}");
    private static final Pattern RELEASE =
            Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(-[0-9A-Za-z.]+)?$");
    private static final Pattern BASE_URL =
            Pattern.compile("https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]{0,200})?");
    private static final Pattern IMAGE =Pattern.compile("[a-z0-9][a-z0-9./_:-]{0,199}");
    private static final TypeReference<List<AgentProtocol.ConnectionHealth>> HEALTH_LIST =
            new TypeReference<>() { };

    private final IntegrationAgentJpaRepository agents;
    private final CurrentUserProvider currentUser;
    private final SecretVault vault;
    private final AuditRecorder audit;
    private final SettingsReader settings;
    private final SystemVersionService versions;
    private final ObjectMapper objectMapper;
    private final Duration enrolmentTtl;
    private final String image;
    private Clock clock = Clock.systemUTC();

    public IntegrationAgentUseCase(IntegrationAgentJpaRepository agents,
                                   CurrentUserProvider currentUser, SecretVault vault,
                                   AuditRecorder audit, SettingsReader settings,
                                   SystemVersionService versions, ObjectMapper objectMapper,
                                   @Value("${modocument.agent.enrolment-ttl:15m}") Duration enrolmentTtl,
                                   @Value("${modocument.agent.image:ghcr.io/guyi34super/modocument-agent}")
                                   String image) {
        this.agents = agents;
        this.currentUser = currentUser;
        this.vault = vault;
        this.audit = audit;
        this.settings = settings;
        this.versions = versions;
        this.objectMapper = objectMapper;
        if (enrolmentTtl.isNegative() || enrolmentTtl.isZero() || enrolmentTtl.toMinutes() > 60) {
            throw new IllegalStateException("modocument.agent.enrolment-ttl must be 1s-60m");
        }
        this.enrolmentTtl = enrolmentTtl;
        if (!IMAGE.matcher(image).matches()) {
            throw new IllegalStateException("modocument.agent.image is not an image reference");
        }
        this.image = image;
    }

    /** Test seam. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The caller's bank's agents, by name. */
    @Transactional(readOnly = true)
    public List<AgentView> list() {
        int bankId = currentUser.requireBankId();
        int userId = currentUser.requireUserId();
        Duration timeout = heartbeatTimeout(bankId);
        Instant now = clock.instant();
        List<AgentView> views = new ArrayList<>();
        for (IntegrationAgent agent : agents.findByBank(bankId)) {
            views.add(view(agent, now, timeout, userId));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public AgentView get(int agentId) {
        int bankId = currentUser.requireBankId();
        IntegrationAgent agent = agents.findByIntegrationAgentIdAndBankId(agentId, bankId)
                .orElseThrow(() -> notFound(agentId));
        return view(agent, clock.instant(), heartbeatTimeout(bankId), currentUser.requireUserId());
    }

    /**
     * AGENT_REGISTER submit (as the maker) and apply (as the checker): the
     * normalized name, refused when blank, too long, odd characters, or
     * already used by a non-revoked agent of the bank.
     */
    @Transactional(readOnly = true)
    public String requireRegistrableName(String requested) {
        String name = requested == null ? "" : requested.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("An agent name is required.");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("An agent name has 1-" + IntegrationAgent.NAME_MAX
                    + " letters, digits, spaces or . _ - ( ) @.");
        }
        int bankId = currentUser.requireBankId();
        boolean taken = agents.findByBank(bankId).stream()
                .anyMatch(a -> a.getStatus() != AgentStatus.REVOKED
                        && a.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new IllegalStateException("An agent named '" + name + "' already exists.");
        }
        return name;
    }

    /**
     * The approved AGENT_REGISTER (inside the approval's transaction, as the
     * checker): the agent row, its one-time token (hash kept, plaintext
     * sealed for the maker), and the AGENT_REGISTERED audit row.
     */
    @Transactional
    public IntegrationAgent applyRegistration(String requestedName, int makerUserId) {
        String name = requireRegistrableName(requestedName);
        int bankId = currentUser.requireBankId();
        Instant now = clock.instant();

        IntegrationAgent agent = new IntegrationAgent();
        agent.setBankId(bankId);
        agent.setName(name);
        agent.setStatus(AgentStatus.PENDING_ENROLMENT);
        agent.setCreatedByUserId(makerUserId);
        agent.setCreatedAt(now);
        agent = agents.saveAndFlush(agent);

        AgentEnrolmentToken token = AgentEnrolmentToken.generate(agent.getIntegrationAgentId());
        SecretVault.Sealed sealed = vault.seal(bankId,
                new SecretMaterial(Map.of(SEALED_TOKEN_KEY, token.value())));
        agent.setEnrolmentTokenHash(AgentEnrolmentToken.hash(token.secret()));
        agent.setEnrolmentTokenCiphertext(sealed.ciphertext());
        agent.setEnrolmentTokenKeyVersion(sealed.keyVersion());
        agent.setEnrolmentExpiresAt(now.plus(enrolmentTtl));
        agent = agents.save(agent);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("agentId", agent.getIntegrationAgentId());
        values.put("name", name);
        values.put("makerUserId", makerUserId);
        values.put("enrolmentExpiresAt", agent.getEnrolmentExpiresAt().toString());
        audit.success("AGENT_REGISTERED", "IntegrationAgent",
                String.valueOf(agent.getIntegrationAgentId()), null, values);
        return agent;
    }

    /**
     * The maker's single read of the enrolment token. 404 for another bank's
     * or an unknown agent, 403 for anyone but the maker, 410 (and the sealed
     * copy deleted) once read, enrolled, revoked or expired.
     *
     * @param baseUrl the MoDocument URL the agent must call (the configured
     *                public URL, else this request's)
     */
    @Transactional(noRollbackFor = EnrolmentUnavailableException.class)
    public AgentEnrolmentView readEnrolment(int agentId, String baseUrl) {
        int bankId = currentUser.requireBankId();
        IntegrationAgent agent = agents.findForUpdate(agentId, bankId)
                .orElseThrow(() -> notFound(agentId));
        if (agent.getCreatedByUserId() != currentUser.requireUserId()) {
            throw new AccessDeniedException(
                    "Only the admin who registered this agent can read its enrolment token.");
        }
        Instant now = clock.instant();
        if (agent.getStatus() != AgentStatus.PENDING_ENROLMENT
                || agent.getEnrolmentTokenCiphertext() == null
                || agent.getEnrolmentReadAt() != null) {
            throw new EnrolmentUnavailableException("The enrolment token of this agent was "
                    + "already shown, used or revoked. Revoke the agent and register it again.");
        }
        if (agent.getEnrolmentExpiresAt() == null || !now.isBefore(agent.getEnrolmentExpiresAt())) {
            agent.clearEnrolmentSecret();
            agents.save(agent);
            throw new EnrolmentUnavailableException("The enrolment token of this agent expired. "
                    + "Revoke the agent and register it again.");
        }
        SecretMaterial opened = vault.open(bankId, new SecretVault.Sealed(
                agent.getEnrolmentTokenCiphertext(), agent.getEnrolmentTokenKeyVersion()));
        String token = opened.get(SEALED_TOKEN_KEY);
        if (token == null) {
            throw new IllegalStateException("The sealed enrolment token is unreadable.");
        }
        // Readable once: the sealed copy goes now; the hash stays for the
        // agent's enrolment until it is used or expires.
        agent.setEnrolmentReadAt(now);
        agent.setEnrolmentTokenCiphertext(null);
        agent.setEnrolmentTokenKeyVersion(null);
        agents.save(agent);
        return new AgentEnrolmentView(token, agent.getEnrolmentExpiresAt(),
                dockerCommand(baseUrl, token));
    }

    /** Direct revocation (idempotent): terminal, token forgotten, audited AGENT_REVOKED. */
    @Transactional
    public AgentView revoke(int agentId) {
        int bankId = currentUser.requireBankId();
        int userId = currentUser.requireUserId();
        IntegrationAgent agent = agents.findForUpdate(agentId, bankId)
                .orElseThrow(() -> notFound(agentId));
        Instant now = clock.instant();
        if (agent.getStatus() != AgentStatus.REVOKED) {
            AgentStatus before = agent.getStatus();
            agent.setStatus(AgentStatus.REVOKED);
            agent.setRevokedAt(now);
            agent.setRevokedByUserId(userId);
            agent.clearEnrolmentSecret();
            agent = agents.save(agent);
            audit.success("AGENT_REVOKED", "IntegrationAgent", String.valueOf(agentId),
                    Map.of("status", before.name()),
                    Map.of("status", AgentStatus.REVOKED.name(), "name", agent.getName()));
        }
        return view(agent, now, heartbeatTimeout(bankId), userId);
    }

    /** {@code docker run} for the bank's host: read-only rootfs, state volume, allow-list mount. */
    String dockerCommand(String baseUrl, String token) {
        // Pasted into a shell by the bank's operator: nothing but a plain URL.
        if (baseUrl == null || !BASE_URL.matcher(baseUrl).matches()) {
            throw new IllegalStateException("The MoDocument URL for agents is not a plain "
                    + "http(s) URL; set modocument.agent.public-url.");
        }
        String tag = versions.version() == null ? "latest" : versions.version();
        return "docker run -d --name modocument-agent --restart unless-stopped"
                + " --read-only --tmpfs /tmp"
                + " -v modocument-agent-state:/var/lib/modocument-agent"
                + " -v /etc/modocument-agent:/etc/modocument-agent:ro"
                + " -e MODOCUMENT_URL=" + baseUrl
                + " -e MODOCUMENT_ENROLMENT_TOKEN=" + token
                + " " + image + ":" + tag;
    }

    private AgentView view(IntegrationAgent agent, Instant now, Duration timeout, int callerId) {
        String latest = versions.version();
        String status = agent.getStatus().name();
        if (agent.getStatus() == AgentStatus.ONLINE
                && (agent.getLastHeartbeatAt() == null
                || agent.getLastHeartbeatAt().isBefore(now.minus(timeout)))) {
            status = AgentStatus.OFFLINE.name();
        }
        boolean pending = agent.getStatus() == AgentStatus.PENDING_ENROLMENT;
        boolean enrolmentAvailable = pending && agent.getCreatedByUserId() == callerId
                && agent.getEnrolmentTokenCiphertext() != null
                && agent.getEnrolmentExpiresAt() != null
                && now.isBefore(agent.getEnrolmentExpiresAt());
        return new AgentView(agent.getIntegrationAgentId(), agent.getName(), status,
                agent.getVersion(), latest, isOlder(agent.getVersion(), latest),
                agent.getLastHeartbeatAt(), agent.getApiClientId(), connectionIds(agent),
                agent.getCreatedByUserId(), agent.getCreatedAt(),
                pending ? agent.getEnrolmentExpiresAt() : null, enrolmentAvailable);
    }

    private List<Long> connectionIds(IntegrationAgent agent) {
        String json = agent.getLastHeartbeatJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, HEALTH_LIST).stream()
                    .map(AgentProtocol.ConnectionHealth::connectionId)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("Agent {}: unreadable heartbeat snapshot ({})", agent.getIntegrationAgentId(),
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Duration heartbeatTimeout(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, bankId);
    }

    /**
     * Whether release {@code agentVersion} is older than {@code latest}
     * (X.Y.Z, a pre-release X.Y.Z-rc.N sorts before X.Y.Z). Anything
     * unparseable is never "older" — no false upgrade prompts.
     */
    static boolean isOlder(String agentVersion, String latest) {
        if (agentVersion == null || latest == null) {
            return false;
        }
        var a = RELEASE.matcher(agentVersion.strip());
        var b = RELEASE.matcher(latest.strip());
        if (!a.matches() || !b.matches()) {
            return false;
        }
        for (int group = 1; group <= 3; group++) {
            int cmp = Integer.compare(Integer.parseInt(a.group(group)), Integer.parseInt(b.group(group)));
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return a.group(4) != null && b.group(4) == null;
    }

    private static NoSuchElementException notFound(int agentId) {
        return new NoSuchElementException("Agent " + agentId + " not found.");
    }

    /** 410 Gone (mapped by the admin controller): the enrolment token can no longer be shown (the sealed copy is gone). */
    public static class EnrolmentUnavailableException extends IllegalStateException {
        public EnrolmentUnavailableException(String message) {
            super(message);
        }
    }
}
