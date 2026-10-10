package com.modocument.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.usecase.AgentCallerResolver;
import com.modocument.application.usecase.AgentEnrolmentService;
import com.modocument.application.usecase.AgentProtocolUseCase;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.infrastructure.integration.agent.AgentTaskLongPoll;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.release.SystemVersionService;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The agent protocol {@code /api/agent/v1/**} (ws26, contracts §7.4) at the
 * controller: shapes, the long poll (no thread parked, wait capped at 30 s,
 * claims as the agent's bank), size limits, and the caller pinning — only an
 * enrolled, non-revoked AGENT client bound to that agent gets through.
 *
 * <p>Standalone MockMvc against mocks of SE2's {@link CurrentClientProvider}
 * and SE4's {@link AgentRunPort} (both parallel Wave 3 ports). The route-level
 * {@code @RequiresPermission(AGENT_CONNECT)} is method security, proven over
 * the real chain by IntegrationAgentApiTest (a bank ADMIN gets 403).
 */
class AgentProtocolControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BANK = 41;
    private static final int AGENT = 7;
    private static final int CLIENT = 900;

    private final CurrentClientProvider clients = mock(CurrentClientProvider.class);
    private final IntegrationAgentJpaRepository agents = mock(IntegrationAgentJpaRepository.class);
    private final AgentRunPort runs = mock(AgentRunPort.class);
    private final AgentEnrolmentService enrolment = mock(AgentEnrolmentService.class);
    private final SystemVersionService versions = mock(SystemVersionService.class);
    private AgentTaskLongPoll longPoll;
    private IntegrationAgent agent;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // A long scheduler interval: the test drives tick() itself.
        longPoll = new AgentTaskLongPoll(runs, 3_600_000, 2, 10);
        AgentProtocolUseCase useCase = new AgentProtocolUseCase(
                new AgentCallerResolver(clients, agents), runs, longPoll, agents, versions,
                MAPPER, 3);
        AgentProtocolController controller =
                new AgentProtocolController(enrolment, useCase, "https://modocument.test/");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(mock(PasswordChangeRequiredGate.class)))
                .build();

        agent = new IntegrationAgent();
        agent.setIntegrationAgentId(AGENT);
        agent.setBankId(BANK);
        agent.setName("core");
        agent.setStatus(AgentStatus.ONLINE);
        agent.setApiClientId(CLIENT);
        when(agents.findByIntegrationAgentIdAndBankId(AGENT, BANK)).thenReturn(Optional.of(agent));
        when(clients.current()).thenReturn(Optional.of(principal(ApiClientKind.AGENT, AGENT, CLIENT,
                Set.of(Permission.AGENT_CONNECT))));
        when(versions.version()).thenReturn("1.2.0");
    }

    @AfterEach
    void tearDown() {
        longPoll.destroy();
    }

    @Test
    void tasksAnswerAtOnceWhenTheAgentHasWork() throws Exception {
        when(runs.claimTasks(BANK, AGENT, 10)).thenReturn(List.of(task(31)));

        MvcResult started = mvc.perform(get("/api/agent/v1/tasks").param("wait", "30"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].runId").value(31))
                .andExpect(jsonPath("$.tasks[0].type").value("DATABASE"))
                .andExpect(jsonPath("$.tasks[0].operation").value("READ"))
                .andExpect(jsonPath("$.tasks[0].spec.table").value("v_customers"));
    }

    @Test
    void anIdleAgentWaitsWithoutAThreadAndGetsTasksOnTheNextTick() throws Exception {
        List<Integer> claimBanks = new ArrayList<>();
        when(runs.claimTasks(BANK, AGENT, 10)).thenAnswer(call -> {
            claimBanks.add(TenantContext.currentBankId().orElse(-1));
            return claimBanks.size() < 2 ? List.of() : List.of(task(32));
        });

        MvcResult started = mvc.perform(get("/api/agent/v1/tasks").param("wait", "30"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // The servlet thread is released; the poll is parked as a waiter.
        assertThat(longPoll.waiting()).isEqualTo(1);
        assertThatThrownBy(() -> started.getAsyncResult(0)).isInstanceOf(IllegalStateException.class);

        longPoll.tick();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].runId").value(32));
        assertThat(longPoll.waiting()).isZero();
        // Every claim ran as the agent's bank (request thread and scheduler).
        assertThat(claimBanks).containsExactly(BANK, BANK);
    }

    @Test
    void waitIsCappedAtThirtySecondsAndZeroAnswersImmediately() throws Exception {
        when(runs.claimTasks(anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        MvcResult capped = mvc.perform(get("/api/agent/v1/tasks").param("wait", "3600"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // DeferredResult timeout = wait (capped to 30 s) + 5 s backstop.
        assertThat(capped.getRequest().getAsyncContext().getTimeout()).isEqualTo(35_000L);

        MvcResult immediate = mvc.perform(get("/api/agent/v1/tasks").param("wait", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(immediate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void recordsAreDelegatedAndOversizedPagesRefused() throws Exception {
        String page = MAPPER.writeValueAsString(Map.of(
                "records", List.of(Map.of("id", 1), Map.of("id", 2)),
                "nextCursor", "2026-10-10T00:00:00Z", "last", true));
        mvc.perform(post("/api/agent/v1/runs/55/records").contentType(MediaType.APPLICATION_JSON)
                        .content(page))
                .andExpect(status().isNoContent());
        verify(runs).acceptRecords(eq(BANK), eq(AGENT), eq(55L), eq(List.of(Map.of("id", 1),
                Map.of("id", 2))), eq("2026-10-10T00:00:00Z"), eq(true));

        String tooMany = MAPPER.writeValueAsString(Map.of("records",
                List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3), Map.of("id", 4)),
                "last", false));
        mvc.perform(post("/api/agent/v1/runs/56/records").contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany))
                .andExpect(status().isBadRequest());
        verify(runs, never()).acceptRecords(anyInt(), anyInt(), eq(56L), anyList(), any(), anyBoolean());
    }

    @Test
    void payloadIsTheOutboundBatchWithBase64FileContentAndUnknownRunsAre404() throws Exception {
        byte[] file = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        when(runs.payload(BANK, AGENT, 60L)).thenReturn(new OutboundBatch("run-60-batch-1",
                PayloadKind.VALIDATED_RECORDS, PayloadFormat.CSV, List.of(), "out.csv", "text/csv", file));
        when(runs.payload(BANK, AGENT, 61L)).thenThrow(new NoSuchElementException("run 61"));

        mvc.perform(get("/api/agent/v1/runs/60/payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value("run-60-batch-1"))
                .andExpect(jsonPath("$.fileName").value("out.csv"))
                .andExpect(jsonPath("$.fileContent").value(Base64.getEncoder().encodeToString(file)));
        mvc.perform(get("/api/agent/v1/runs/61/payload")).andExpect(status().isNotFound());
    }

    @Test
    void resultIsDelegatedWithTheErrorTruncated() throws Exception {
        String error = "x".repeat(5000);
        mvc.perform(post("/api/agent/v1/runs/70/result").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("success", false, "recordsIn", 3,
                                "recordsOut", 0, "error", error))))
                .andExpect(status().isNoContent());
        ArgumentCaptor<AgentRunPort.AgentRunResult> captor =
                ArgumentCaptor.forClass(AgentRunPort.AgentRunResult.class);
        verify(runs).complete(eq(BANK), eq(AGENT), eq(70L), captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().recordsIn()).isEqualTo(3);
        assertThat(captor.getValue().error()).hasSize(2000);
    }

    @Test
    void heartbeatStoresVersionAndConnectionsAndAnswersTheLatestRelease() throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/v1/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.1.0\",\"status\":\"OK\",\"connections\":"
                                + "[{\"connectionId\":5,\"ok\":true,\"message\":\"fine\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("latestVersion").asText()).isEqualTo("1.2.0");
        assertThat(body.get("pollSeconds").asInt()).isEqualTo(30);
        assertThat(agent.getVersion()).isEqualTo("1.1.0");
        assertThat(agent.getLastHeartbeatAt()).isNotNull();
        assertThat(agent.getLastHeartbeatJson()).contains("\"connectionId\":5");
        verify(agents).save(agent);

        mvc.perform(post("/api/agent/v1/heartbeat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0; rm -rf /\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAnEnrolledAgentClientBoundToThatAgentGetsThrough() {
        // No machine principal (e.g. a bank user's token that passed the route check).
        when(clients.current()).thenReturn(Optional.empty());
        assertDenied();

        when(clients.current()).thenReturn(Optional.of(principal(ApiClientKind.SYSTEM, null, CLIENT,
                Set.of(Permission.INTEGRATION_INGEST))));
        assertDenied();

        when(clients.current()).thenReturn(Optional.of(principal(ApiClientKind.AGENT, AGENT, CLIENT,
                Set.of(Permission.INTEGRATION_INGEST))));
        assertDenied();

        // Another client presenting this agent's id.
        when(clients.current()).thenReturn(Optional.of(principal(ApiClientKind.AGENT, AGENT, CLIENT + 1,
                Set.of(Permission.AGENT_CONNECT))));
        assertDenied();

        when(clients.current()).thenReturn(Optional.of(principal(ApiClientKind.AGENT, AGENT, CLIENT,
                Set.of(Permission.AGENT_CONNECT))));
        agent.setStatus(AgentStatus.REVOKED);
        assertDenied();
        verify(runs, never()).claimTasks(anyInt(), anyInt(), anyInt());
        verify(runs, never()).payload(anyInt(), anyInt(), anyLong());
    }

    @Test
    void enrolIsPublicAndAnswersTheContractShapeOr401() throws Exception {
        when(enrolment.enroll(any(), any(), eq("https://modocument.test/api/integration/oauth/token")))
                .thenAnswer(call -> {
                    AgentProtocol.EnrollRequest request = call.getArgument(0);
                    if (!"mda1.7.good".equals(request.token())) {
                        throw new AgentEnrolmentService.InvalidEnrolmentTokenException();
                    }
                    return new AgentProtocol.EnrollResponse(AGENT, "agt_x", "secret-once",
                            call.getArgument(2));
                });

        mvc.perform(post("/api/agent/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"mda1.7.good\",\"name\":\"host\",\"version\":\"1.2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(AGENT))
                .andExpect(jsonPath("$.clientId").value("agt_x"))
                .andExpect(jsonPath("$.secret").value("secret-once"))
                .andExpect(jsonPath("$.tokenUrl").value("https://modocument.test/api/integration/oauth/token"));
        mvc.perform(post("/api/agent/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"mda1.7.bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_ENROLMENT_INVALID"));
    }

    private void assertDenied() {
        assertThatThrownBy(() -> mvc.perform(get("/api/agent/v1/tasks").param("wait", "0")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mvc.perform(get("/api/agent/v1/runs/1/payload")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    private static ClientPrincipal principal(ApiClientKind kind, Integer agentId, int clientId,
                                             Set<Permission> scopes) {
        return new ClientPrincipal(clientId, BANK, "agt_x", kind,
                ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, scopes, 3, agentId);
    }

    private static AgentRunPort.AgentTask task(long runId) {
        return new AgentRunPort.AgentTask(runId, 12, ConnectionType.DATABASE, "READ",
                Map.of("vendor", "POSTGRESQL", "host", "db.bank.local"), "core-db",
                Map.of("table", "v_customers", "columns", List.of("id", "name")), null, 500);
    }
}
