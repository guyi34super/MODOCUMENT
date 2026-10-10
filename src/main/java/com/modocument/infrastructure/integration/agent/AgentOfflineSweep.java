package com.modocument.infrastructure.integration.agent;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks Connector Agents OFFLINE (ws26, Wave 4): an enrolled agent whose
 * last heartbeat is older than its bank's {@code integration.agentHeartbeatTimeout}
 * is flipped ONLINE → OFFLINE and audited {@code AGENT_OFFLINE} exactly ONCE
 * per transition; the IMMEDIATE alert rule {@code agent-offline} (MEDIUM)
 * raises the alert from that audit row. The agent's next heartbeat flips it
 * back (AgentProtocolUseCase, audit {@code AGENT_ONLINE}).
 *
 * <p><b>Once per transition, with several ECS tasks:</b> each flip runs in
 * its own write transaction on the row-locked agent and re-checks status
 * and heartbeat under the lock, so a second task (or a heartbeat that won
 * the lock) finds nothing to do.
 *
 * <p><b>Tenancy</b> (call-site registry in {@link TenantContext}): ONE
 * {@code runAsSystem} read-only transaction lists the ids of the banks that
 * have ONLINE agents — bank ids only, no row content; each bank's check, the
 * status flip and its audit row run strictly inside {@code runAs(bank)}.
 *
 * <p>{@code modocument.agent.offline-sweep.enabled} gates only the
 * schedule (tests call {@link #sweep()} directly).
 */
@Component
public class AgentOfflineSweep {

    public static final String AGENT_OFFLINE = "AGENT_OFFLINE";

    private static final Logger log = LoggerFactory.getLogger(AgentOfflineSweep.class);

    private final IntegrationAgentJpaRepository agents;
    private final SettingsReader settings;
    private final AuditRecorder audit;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;
    private final boolean enabled;
    private Clock clock = Clock.systemUTC();

    public AgentOfflineSweep(IntegrationAgentJpaRepository agents, SettingsReader settings,
                             AuditRecorder audit, PlatformTransactionManager transactionManager,
                             @Value("${modocument.agent.offline-sweep.enabled:true}") boolean enabled) {
        this.agents = agents;
        this.settings = settings;
        this.audit = audit;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    /** Test seam. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${modocument.agent.offline-sweep.interval-ms:60000}",
            initialDelayString = "${modocument.agent.offline-sweep.interval-ms:60000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Agent offline sweep failed", e);
        }
    }

    /** One sweep over every bank with ONLINE agents; returns the agents marked OFFLINE now. */
    public List<Integer> sweep() {
        List<Integer> banks;
        try {
            // runAsSystem: the cross-bank scan — bank ids only (see class javadoc).
            banks = TenantContext.runAsSystem(() -> readOnly.execute(status ->
                    agents.findBankIdsWithStatus(AgentStatus.ONLINE)));
        } catch (Exception e) {
            throw new IllegalStateException("The agent offline sweep could not list banks.", e);
        }
        List<Integer> marked = new ArrayList<>();
        for (Integer bankId : banks == null ? List.<Integer>of() : banks) {
            try {
                marked.addAll(TenantContext.runAs(bankId, () -> sweepBank(bankId)));
            } catch (Exception e) {
                log.error("Agent offline sweep failed for bank {} (continuing)", bankId, e);
            }
        }
        return marked;
    }

    /** One bank; must run inside {@code runAs(bankId)}. */
    List<Integer> sweepBank(int bankId) {
        Duration timeout = settings.getDuration(SettingKeys.INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, bankId);
        Instant now = clock.instant();
        Instant cutoff = now.minus(timeout);
        List<Integer> candidates = readOnly.execute(status ->
                agents.findSilentSince(bankId, AgentStatus.ONLINE, cutoff));
        List<Integer> marked = new ArrayList<>();
        for (Integer agentId : candidates == null ? List.<Integer>of() : candidates) {
            Boolean flipped = write.execute(status -> markOffline(bankId, agentId, cutoff, timeout));
            if (Boolean.TRUE.equals(flipped)) {
                marked.add(agentId);
            }
        }
        return marked;
    }

    private boolean markOffline(int bankId, int agentId, Instant cutoff, Duration timeout) {
        IntegrationAgent agent = agents.findForUpdate(agentId, bankId).orElse(null);
        // Re-checked under the row lock: another task, or a heartbeat, may have won.
        if (agent == null || agent.getStatus() != AgentStatus.ONLINE
                || (agent.getLastHeartbeatAt() != null && !agent.getLastHeartbeatAt().isBefore(cutoff))) {
            return false;
        }
        agent.setStatus(AgentStatus.OFFLINE);
        agents.save(agent);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("agentId", agentId);
        values.put("name", agent.getName());
        values.put("status", AgentStatus.OFFLINE.name());
        values.put("lastHeartbeatAt", agent.getLastHeartbeatAt() == null
                ? null : agent.getLastHeartbeatAt().toString());
        values.put("heartbeatTimeoutSeconds", timeout.toSeconds());
        values.put("version", agent.getVersion());
        audit.record(AuditEventSpec.builder(AGENT_OFFLINE, AuditOutcome.SUCCESS)
                .entity("IntegrationAgent", String.valueOf(agentId))
                .bankId(bankId)
                .actorRole("SYSTEM")
                .oldValues(Map.of("status", AgentStatus.ONLINE.name()))
                .newValues(values)
                .build());
        log.warn("Connector Agent {} of bank {} is OFFLINE (last heartbeat {}, timeout {})",
                agentId, bankId, agent.getLastHeartbeatAt(), timeout);
        return true;
    }
}
