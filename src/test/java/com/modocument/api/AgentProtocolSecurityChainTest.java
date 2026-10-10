package com.modocument.api;

import com.modocument.api.security.PermissionChecker;
import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The agent protocol through the REAL filter chain and method security
 * (H2): the long-poll's DeferredResult completes and its async dispatch is
 * still authorized; the heartbeat lands on the agent row.
 *
 * <p>Agent client tokens are SE2's (parallel Wave 3 branch), so the caller
 * here is a bank user's JWT with the {@code @perm} checker answering yes
 * and a mocked {@link CurrentClientProvider} naming the agent's client — the
 * pieces SE2's client authentication supplies after the merge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentProtocolSecurityChainTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private IntegrationAgentJpaRepository agents;
    @Autowired
    private JwtService jwtService;

    @MockitoBean(name = "perm")
    private PermissionChecker perm;
    @MockitoBean
    private CurrentClientProvider clients;
    @MockitoBean
    private AgentRunPort runs;

    private Bank bank;
    private AppUser user;
    private IntegrationAgent agent;

    @BeforeEach
    void seed() {
        PlatformBankFixture.ensurePlatformBank(banks);
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bank = banks.save(new Bank("APC" + tag, "Agent chain " + tag));
        user = PlatformBankFixture.user(users, bank.getBankId(), Role.ADMIN, "ws26-chain");
        agent = new IntegrationAgent();
        agent.setBankId(bank.getBankId());
        agent.setName("chain " + tag);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setApiClientId(812);
        agent.setCreatedByUserId(user.getAppUserId());
        agent.setCreatedAt(Instant.now());
        agent = agents.save(agent);

        when(perm.has(anyString())).thenAnswer(call -> Permission.AGENT_CONNECT.name().equals(call.getArgument(0)));
        when(clients.current()).thenReturn(Optional.of(new ClientPrincipal(812, bank.getBankId(), "agt_chain",
                ApiClientKind.AGENT, ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, Set.of(Permission.AGENT_CONNECT),
                user.getAppUserId(), agent.getIntegrationAgentId())));
    }

    @AfterEach
    void cleanUp() {
        agents.delete(agent);
        users.delete(user);
        banks.delete(bank);
    }

    @Test
    void theLongPollCompletesThroughTheSecurityChain() throws Exception {
        when(runs.claimTasks(bank.getBankId(), agent.getIntegrationAgentId(), 10))
                .thenReturn(List.of(new AgentRunPort.AgentTask(77, 5, ConnectionType.DATABASE, "READ",
                        Map.of("host", "db"), "core-db", Map.of("table", "v"), null, 100)));

        MvcResult started = mvc.perform(get("/api/agent/v1/tasks").param("wait", "0")
                        .header("Authorization", bearer()))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].runId").value(77));
    }

    @Test
    void theHeartbeatIsStoredAndAnswersTheLatestVersion() throws Exception {
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"status\":\"OK\",\"connections\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pollSeconds").value(30));
        org.assertj.core.api.Assertions.assertThat(
                agents.findById(agent.getIntegrationAgentId()).orElseThrow().getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void aRevokedAgentIsRefusedEvenWithAValidClient() throws Exception {
        agent.setStatus(AgentStatus.REVOKED);
        agents.save(agent);
        mvc.perform(get("/api/agent/v1/tasks").param("wait", "0").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    private String bearer() {
        return "Bearer " + jwtService.issueToken(user);
    }
}
