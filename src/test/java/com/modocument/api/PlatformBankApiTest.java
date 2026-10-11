package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.infrastructure.persistence.UserMfaJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws21 {@code GET /api/platform/banks[/{id}]}: the platform console's view
 * of a bank is its identifying columns plus COUNTS — never an email, a file
 * name, an error message or any other row content of the bank.
 *
 * <p>Fixture: one customer bank with an MFA-enrolled ADMIN (signed in), a
 * READ_WRITE without MFA and a disabled READ_ONLY; two ingestion jobs this
 * week (one FAILED) and a FAILED one from ten days ago. Committed (the use
 * case reads in its own transaction) and removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformBankApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_FILE = "payroll-secret-ws21.csv";
    private static final String SECRET_ERROR = "row 7 holds IBAN DE00 ws21";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private UserMfaJpaRepository userMfa;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private IntegrationConnectionJpaRepository connections;
    @Autowired
    private IntegrationFlowJpaRepository flows;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private OutboundDeliveryJpaRepository deliveries;
    @Autowired
    private IntegrationAgentJpaRepository agents;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<AppUser> createdUsers = new ArrayList<>();
    private final List<IngestionJob> createdJobs = new ArrayList<>();
    private Bank customer;
    private AppUser enrolledAdmin;
    private String platformToken;

    @BeforeEach
    void seed() {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        AppUser platformAdmin = keep(PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "ws21-api"));
        platformToken = "Bearer " + jwtService.issueToken(platformAdmin);

        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        customer = banks.save(new Bank("PB" + tag, "Counted Bank " + tag));
        int bankId = customer.getBankId();

        enrolledAdmin = PlatformBankFixture.user(users, bankId, Role.ADMIN, "ws21-api");
        enrolledAdmin.setLastLoginAt(LocalDateTime.now().minusHours(1));
        keep(users.save(enrolledAdmin));
        UserMfa mfa = new UserMfa();
        mfa.setUserId(enrolledAdmin.getAppUserId());
        mfa.setSecretCiphertext("ciphertext-not-a-secret");
        mfa.setEnabledAt(Instant.now());
        mfa.setCreatedAt(Instant.now());
        userMfa.save(mfa);
        AppUser member = keep(PlatformBankFixture.user(users, bankId, Role.READ_WRITE, "ws21-api"));
        AppUser leaver = PlatformBankFixture.user(users, bankId, Role.READ_ONLY, "ws21-api");
        leaver.setDisabled(true);
        keep(users.save(leaver));

        job(bankId, member, JobStatus.COMPLETED, LocalDateTime.now().minusDays(1));
        job(bankId, member, JobStatus.FAILED, LocalDateTime.now().minusDays(2));
        job(bankId, member, JobStatus.FAILED, LocalDateTime.now().minusDays(10));
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("OutboundDelivery", "IntegrationRun", "IntegrationFlow",
                "IntegrationConnection", "IntegrationAgent")) {
            jdbc.update("DELETE FROM " + table + " WHERE BankId = ?", customer.getBankId());
        }
        jobs.deleteAll(createdJobs);
        userMfa.deleteById(enrolledAdmin.getAppUserId());
        users.deleteAll(createdUsers);
        banks.delete(customer);
        createdJobs.clear();
        createdUsers.clear();
    }

    @Test
    void theListCarriesCountsOnlyForEveryBank() throws Exception {
        String body = mvc.perform(get("/api/platform/banks").header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = MAPPER.readTree(body);
        assertThat(list.isArray()).isTrue();
        JsonNode summary = null;
        for (JsonNode node : list) {
            if (node.get("bankId").asInt() == customer.getBankId()) {
                summary = node;
            }
        }
        assertThat(summary).as("the customer bank is listed").isNotNull();
        assertCounts(summary);
        // The platform bank itself is listed too, as kind PLATFORM.
        assertThat(list.findValuesAsText("kind")).contains("PLATFORM", "CUSTOMER");
        assertNoRowContent(body);
    }

    @Test
    void oneBankCarriesTheSameCountsAndAnUnknownIdIs404() throws Exception {
        String body = mvc.perform(get("/api/platform/banks/" + customer.getBankId())
                        .header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode summary = MAPPER.readTree(body);
        assertCounts(summary, "integrations");
        assertNoRowContent(body);
        // No integration activity yet: all zeros, not absent.
        assertThat(summary.at("/integrations/connections").asLong()).isZero();
        assertThat(summary.at("/integrations/runs24h/ok").asLong()).isZero();

        mvc.perform(get("/api/platform/banks/987654321").header("Authorization", platformToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Wave 4: the one-bank read adds {@code integrations} — counts of
     * connections (and FAILED ones), enabled flows, runs finished in the last
     * 24 h by outcome, DEAD deliveries and offline agents — and nothing of
     * their configuration or content.
     */
    @Test
    void oneBankCarriesItsIntegrationHealthAsCountsOnly() throws Exception {
        int bankId = customer.getBankId();
        int owner = enrolledAdmin.getAppUserId();
        IntegrationConnection ok = connection(bankId, owner, "core-ok", IntegrationConnection.STATUS_OK);
        connection(bankId, owner, "core-failing", IntegrationConnection.STATUS_FAILED);
        IntegrationFlow enabled = flow(bankId, owner, ok, true);
        flow(bankId, owner, ok, false);
        Instant now = Instant.now();
        run(bankId, ok, RunStatus.SUCCEEDED, now.minus(Duration.ofHours(1)));
        run(bankId, ok, RunStatus.SUCCEEDED, now.minus(Duration.ofHours(3)));
        run(bankId, ok, RunStatus.FAILED, now.minus(Duration.ofHours(2)));
        run(bankId, ok, RunStatus.FAILED, now.minus(Duration.ofHours(30)));   // outside 24 h
        run(bankId, ok, RunStatus.RUNNING, null);                             // not finished
        delivery(bankId, enabled, DeliveryStatus.DEAD);
        delivery(bankId, enabled, DeliveryStatus.PENDING);
        agent(bankId, owner, AgentStatus.ONLINE, now);                         // fresh heartbeat
        agent(bankId, owner, AgentStatus.ONLINE, null);                        // never heard from
        agent(bankId, owner, AgentStatus.ONLINE, now.minus(Duration.ofDays(2)));
        agent(bankId, owner, AgentStatus.REVOKED, now.minus(Duration.ofDays(2)));

        String body = mvc.perform(get("/api/platform/banks/" + bankId)
                        .header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode integrations = MAPPER.readTree(body).get("integrations");
        assertThat(integrations.get("connections").asLong()).isEqualTo(2);
        assertThat(integrations.get("connectionsFailing").asLong()).isEqualTo(1);
        assertThat(integrations.get("flowsEnabled").asLong()).isEqualTo(1);
        assertThat(integrations.at("/runs24h/ok").asLong()).isEqualTo(2);
        assertThat(integrations.at("/runs24h/failed").asLong()).isEqualTo(1);
        assertThat(integrations.get("deadLetters").asLong()).isEqualTo(1);
        assertThat(integrations.get("agentsOffline").asLong()).isEqualTo(2);
        assertThat(body).doesNotContain("core-ok").doesNotContain("core-failing")
                .doesNotContain("bank.example.invalid").doesNotContain("agent-ws21");
        assertNoRowContent(body);

        // The list stays the plain BankSummary.
        String list = mvc.perform(get("/api/platform/banks").header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("\"integrations\"");
    }

    private void assertCounts(JsonNode summary, String... extraFields) {
        Set<String> fields = new TreeSet<>();
        for (Iterator<String> names = summary.fieldNames(); names.hasNext(); ) {
            fields.add(names.next());
        }
        // Exactly the contract's BankSummary (docs/phase2/contracts.md §3).
        List<String> expected = new ArrayList<>(List.of("bankId", "code", "name", "kind",
                "status", "users", "mfaEnrolledPct", "jobs7d", "failures7d", "aiShare7d",
                "codeShare7d", "lastActivityAt"));
        expected.addAll(List.of(extraFields));
        assertThat(fields).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(summary.get("code").asText()).isEqualTo(customer.getCode());
        assertThat(summary.get("kind").asText()).isEqualTo("CUSTOMER");
        assertThat(summary.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(summary.get("users").asLong()).isEqualTo(2);          // the leaver is out
        assertThat(summary.get("mfaEnrolledPct").asInt()).isEqualTo(50);  // 1 of 2
        assertThat(summary.get("jobs7d").asLong()).isEqualTo(2);         // 10-day-old one is out
        assertThat(summary.get("failures7d").asLong()).isEqualTo(1);
        assertThat(summary.get("aiShare7d").isNull()).isTrue();          // until ws23
        assertThat(summary.get("codeShare7d").isNull()).isTrue();
        assertThat(summary.get("lastActivityAt").isNull()).isFalse();
    }

    /** No email (of anyone), no job file name, no error text — counts only. */
    private static void assertNoRowContent(String body) {
        assertThat(body).doesNotContain("@").doesNotContain(SECRET_FILE)
                .doesNotContain(SECRET_ERROR).doesNotContain("ciphertext");
    }

    private IntegrationConnection connection(int bankId, int owner, String name, String status) {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bankId);
        connection.setName(name);
        connection.setType(ConnectionType.REST_API);
        connection.setMode(ConnectionMode.DIRECT);
        connection.setConfigJson("{\"baseUrl\":\"https://bank.example.invalid\"}");
        connection.setAuthType("NONE");
        connection.setStatus(status);
        connection.setCreatedBy(owner);
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    private IntegrationFlow flow(int bankId, int owner, IntegrationConnection connection,
                                 boolean enabled) {
        IntegrationFlow flow = new IntegrationFlow();
        flow.setBankId(bankId);
        flow.setName("flow-" + UUID.randomUUID().toString().substring(0, 6));
        flow.setConnectionId(connection.getId());
        flow.setTemplateId(1);
        flow.setDirection(FlowDirection.OUTBOUND);
        flow.setTriggerType(FlowTrigger.MANUAL);
        flow.setEnabled(enabled);
        flow.setCreatedBy(owner);
        flow.setCreatedAt(Instant.now());
        return flows.save(flow);
    }

    private void run(int bankId, IntegrationConnection connection, RunStatus status,
                     Instant finishedAt) {
        IntegrationRun run = new IntegrationRun();
        run.setBankId(bankId);
        run.setConnectionId(connection.getId());
        run.setOperation("READ");
        run.setTriggerType(FlowTrigger.MANUAL);
        run.setScheduledFor(Instant.now());
        run.setStatus(status);
        run.setCreatedAt(Instant.now());
        run.setFinishedAt(finishedAt);
        runs.save(run);
    }

    private void delivery(int bankId, IntegrationFlow flow, DeliveryStatus status) {
        OutboundDelivery delivery = new OutboundDelivery();
        delivery.setBankId(bankId);
        delivery.setFlowId(flow.getId());
        delivery.setPayloadKind(PayloadKind.JOB_STATUS);
        delivery.setPayloadRef("job:1");
        delivery.setStatus(status);
        delivery.setCreatedAt(Instant.now());
        deliveries.save(delivery);
    }

    private void agent(int bankId, int owner, AgentStatus status, Instant lastHeartbeat) {
        IntegrationAgent agent = new IntegrationAgent();
        agent.setBankId(bankId);
        agent.setName("agent-ws21-" + UUID.randomUUID().toString().substring(0, 6));
        agent.setStatus(status);
        agent.setLastHeartbeatAt(lastHeartbeat);
        agent.setCreatedByUserId(owner);
        agent.setCreatedAt(Instant.now());
        agents.save(agent);
    }

    private AppUser keep(AppUser user) {
        createdUsers.add(user);
        return user;
    }

    private void job(int bankId, AppUser owner, JobStatus status, LocalDateTime createdDate) {
        IngestionJob job = new IngestionJob();
        job.setBankId(bankId);
        job.setOwnerUserId(owner.getAppUserId());
        job.setTemplateId(1);
        job.setFileName(SECRET_FILE);
        job.setStatus(status);
        job.setErrorMessage(status == JobStatus.FAILED ? SECRET_ERROR : null);
        job.setCreatedDate(createdDate);
        job.setUpdatedDate(createdDate);
        createdJobs.add(jobs.save(job));
    }
}
