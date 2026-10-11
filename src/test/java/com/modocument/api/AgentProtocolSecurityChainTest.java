package com.modocument.api;

import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The agent protocol through the REAL filter chain and method security
 * (H2): an agent ApiClient issued through {@link ApiClientIssuer} gets its
 * token from {@code POST /api/integration/oauth/token}; the long-poll's
 * DeferredResult completes and its async dispatch is still authorized; the
 * heartbeat lands on the agent row; a revoked agent is refused, and
 * revoking it through the admin API kills its client token at once (401).
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
    private ApiClientIssuer issuer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AgentRunPort runs;

    private Bank bank;
    private AppUser user;
    private IntegrationAgent agent;
    private String token;

    @BeforeEach
    void seed() throws Exception {
        PlatformBankFixture.ensurePlatformBank(banks);
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bank = banks.save(new Bank("APC" + tag, "Agent chain " + tag));
        user = PlatformBankFixture.user(users, bank.getBankId(), Role.ADMIN, "ws26-chain");
        agent = new IntegrationAgent();
        agent.setBankId(bank.getBankId());
        agent.setName("chain " + tag);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setCreatedByUserId(user.getAppUserId());
        agent.setCreatedAt(Instant.now());
        agent = agents.save(agent);

        ApiClientIssuer.Issued client = issuer.issueAgentClient(bank.getBankId(),
                agent.getIntegrationAgentId(), agent.getName(),
                ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, null);
        agent.setApiClientId(client.apiClientId());
        agent = agents.save(agent);
        token = clientToken(client);
    }

    @AfterEach
    void cleanUp() {
        agents.delete(agent);
        for (String table : List.of("ApiClientInitialSecret", "ApiClientSecret", "ApiClient")) {
            jdbc.update("DELETE FROM " + table + " WHERE BankId = ?", bank.getBankId());
        }
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

    @Test
    void revokingTheAgentRevokesItsClientAndKillsItsTokenAtOnce() throws Exception {
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"status\":\"OK\",\"connections\":[]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/integrations/agents/" + agent.getIntegrationAgentId()
                                + "/revoke")
                        .header("Authorization", "Bearer " + jwtService.issueToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 401 at authentication (TokenVersion), not only 403 at the agent routes.
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"status\":\"OK\",\"connections\":[]}"))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT Status FROM ApiClient WHERE ApiClientId = ?", String.class,
                agent.getApiClientId())).isEqualTo("REVOKED");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT TokenVersion FROM ApiClient WHERE ApiClientId = ?", Integer.class,
                agent.getApiClientId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'API_CLIENT_REVOKED' AND EntityId = ?",
                Integer.class, String.valueOf(agent.getApiClientId()))).isEqualTo(1);
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private String clientToken(ApiClientIssuer.Issued client) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (client.clientId() + ":" + client.secret()).getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(post("/api/integration/oauth/token")
                        .header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).path("access_token").asText();
    }
}
