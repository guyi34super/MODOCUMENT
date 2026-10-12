package com.modocument.infrastructure.integration.agent;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The agent offline alert (ws26, Wave 4): an enrolled agent silent for
 * longer than its bank's {@code integration.agentHeartbeatTimeout} (default
 * 2 min) is stored OFFLINE and audited AGENT_OFFLINE exactly once per
 * transition — in its own bank, across banks in one sweep — and the
 * IMMEDIATE rule {@code agent-offline} records a MEDIUM alert.
 */
@SpringBootTest
@AutoConfigureMockMvc // same context configuration as the API tests (one cached context)
@ActiveProfiles("test")
class AgentOfflineSweepTest {

    @Autowired
    private AgentOfflineSweep sweep;
    @Autowired
    private IntegrationAgentJpaRepository agents;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AlertLogJpaRepository alertLogs;
    @Autowired
    private JdbcTemplate jdbc;

    private Bank bankA;
    private Bank bankB;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void seed() {
        PlatformBankFixture.ensurePlatformBank(banks);
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = banks.save(new Bank("AOA" + tag, "Agent offline A " + tag));
        bankB = banks.save(new Bank("AOB" + tag, "Agent offline B " + tag));
        sweep.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        sweep.setClock(Clock.systemUTC());
        jdbc.update("DELETE FROM IntegrationAgent WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
    }

    @Test
    void aSilentAgentGoesOfflineOncePerTransitionInItsOwnBankAndRaisesTheAlert() {
        IntegrationAgent silent = agent(bankA, "core-a", AgentStatus.ONLINE, now.minus(Duration.ofMinutes(10)));
        IntegrationAgent fresh = agent(bankA, "fresh-a", AgentStatus.ONLINE, now.minusSeconds(20));
        IntegrationAgent pending = agent(bankA, "pending-a", AgentStatus.PENDING_ENROLMENT, null);
        IntegrationAgent revoked = agent(bankA, "revoked-a", AgentStatus.REVOKED, now.minus(Duration.ofDays(3)));
        IntegrationAgent neverHeard = agent(bankB, "core-b", AgentStatus.ONLINE, null);

        List<Integer> marked = sweep.sweep();
        assertThat(marked).contains(silent.getIntegrationAgentId(), neverHeard.getIntegrationAgentId())
                .doesNotContain(fresh.getIntegrationAgentId(), pending.getIntegrationAgentId(),
                        revoked.getIntegrationAgentId());
        assertThat(status(silent)).isEqualTo(AgentStatus.OFFLINE);
        assertThat(status(neverHeard)).isEqualTo(AgentStatus.OFFLINE);
        assertThat(status(fresh)).isEqualTo(AgentStatus.ONLINE);
        assertThat(status(pending)).isEqualTo(AgentStatus.PENDING_ENROLMENT);
        assertThat(status(revoked)).isEqualTo(AgentStatus.REVOKED);

        // Each audit row lands in the agent's own bank.
        assertThat(offlineAudits(silent, bankA)).isEqualTo(1);
        assertThat(offlineAudits(neverHeard, bankB)).isEqualTo(1);
        assertThat(offlineAudits(silent, bankB)).isZero();

        // Still silent on the next sweep: no second row (once per transition).
        assertThat(sweep.sweep()).doesNotContain(silent.getIntegrationAgentId(),
                neverHeard.getIntegrationAgentId());
        assertThat(offlineAudits(silent, bankA)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(alerts(bankA)).anySatisfy(alert -> {
                    assertThat(alert.getRuleName()).isEqualTo("agent-offline");
                    assertThat(alert.getSeverity().name()).isEqualTo("MEDIUM");
                }));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(alerts(bankB)).extracting(AlertLog::getRuleName).contains("agent-offline"));
    }

    @Test
    void theBanksHeartbeatTimeoutDecides() {
        // 90 s of silence is within the default 2 minutes...
        IntegrationAgent agent = agent(bankA, "edge-a", AgentStatus.ONLINE, now.minusSeconds(90));
        assertThat(sweep.sweep()).doesNotContain(agent.getIntegrationAgentId());
        assertThat(status(agent)).isEqualTo(AgentStatus.ONLINE);

        // ...a minute later it is not.
        sweep.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        assertThat(sweep.sweep()).contains(agent.getIntegrationAgentId());
        assertThat(status(agent)).isEqualTo(AgentStatus.OFFLINE);
    }

    @Test
    void anAgentBackOnlineCanGoOfflineAgain() {
        IntegrationAgent agent = agent(bankA, "flappy-a", AgentStatus.ONLINE, now.minus(Duration.ofMinutes(5)));
        assertThat(sweep.sweep()).contains(agent.getIntegrationAgentId());

        // What the heartbeat does (AgentProtocolUseCase): ONLINE again, fresh timestamp.
        IntegrationAgent stored = agents.findById(agent.getIntegrationAgentId()).orElseThrow();
        stored.setStatus(AgentStatus.ONLINE);
        stored.setLastHeartbeatAt(now);
        agents.save(stored);
        assertThat(sweep.sweep()).doesNotContain(agent.getIntegrationAgentId());

        sweep.setClock(Clock.fixed(now.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        assertThat(sweep.sweep()).contains(agent.getIntegrationAgentId());
        assertThat(offlineAudits(agent, bankA)).isEqualTo(2);
    }

    private IntegrationAgent agent(Bank bank, String name, AgentStatus status, Instant lastHeartbeat) {
        IntegrationAgent agent = new IntegrationAgent();
        agent.setBankId(bank.getBankId());
        agent.setName(name);
        agent.setStatus(status);
        agent.setLastHeartbeatAt(lastHeartbeat);
        agent.setVersion("1.1.0");
        agent.setCreatedByUserId(1);
        agent.setCreatedAt(now.minus(Duration.ofDays(7)));
        return agents.save(agent);
    }

    private AgentStatus status(IntegrationAgent agent) {
        return agents.findById(agent.getIntegrationAgentId()).orElseThrow().getStatus();
    }

    private int offlineAudits(IntegrationAgent agent, Bank bank) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ? "
                        + "AND EntityType = 'IntegrationAgent' AND EntityId = ? AND BankId = ?",
                Integer.class, AgentOfflineSweep.AGENT_OFFLINE,
                String.valueOf(agent.getIntegrationAgentId()), bank.getBankId());
        return count == null ? 0 : count;
    }

    private List<AlertLog> alerts(Bank bank) {
        return alertLogs.findByBankIdOrderByCreatedAtDescAlertLogIdDesc(bank.getBankId(), PageRequest.of(0, 20));
    }
}
