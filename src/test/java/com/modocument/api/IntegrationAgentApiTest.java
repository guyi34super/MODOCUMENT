package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.application.usecase.AgentEnrolmentService;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationAgent;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
import com.modocument.infrastructure.release.SystemVersionService;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws26 Connector Agent management over the real HTTP stack (H2): register
 * under maker/checker (AGENT_REGISTER), the maker's single read of the
 * one-time enrolment token, the agent's enrolment with it (single use,
 * expiry), computed ONLINE/OFFLINE status and revocation — scoped to the
 * caller's bank and to INTEGRATION_ADMIN.
 *
 * <p>The SecretVault (SE1) and ApiClientIssuer (SE2) are parallel Wave 3
 * ports whose placeholders refuse on this branch, so they are mocked here:
 * the vault with a reversible fake, the issuer with a fixed client. The
 * enrolment itself is driven through {@link AgentEnrolmentService}: its
 * public route is permitted by SE2's SecurityConfig change, not on this
 * branch. Not {@code @Transactional}: the enrolment runs in its own
 * transactions, so fixtures are committed and removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationAgentApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private IntegrationAgentJpaRepository agents;
    @Autowired
    private AgentEnrolmentService enrolment;
    @Autowired
    private SystemVersionService versions;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private SecretVault vault;
    @MockitoBean
    private ApiClientIssuer issuer;

    private final List<AppUser> createdUsers = new ArrayList<>();
    private Bank bankA;
    private Bank bankB;
    private String makerA;
    private String checkerA;
    private String writerA;
    private String adminB;
    private String tag;

    @BeforeEach
    void seed() {
        PlatformBankFixture.ensurePlatformBank(banks);
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = banks.save(new Bank("AGA" + tag, "Agent bank A " + tag));
        bankB = banks.save(new Bank("AGB" + tag, "Agent bank B " + tag));
        makerA = token(bankA, Role.ADMIN);
        checkerA = token(bankA, Role.ADMIN);
        writerA = token(bankA, Role.READ_WRITE);
        adminB = token(bankB, Role.ADMIN);

        // Reversible fake of SE1's vault; bound to the bank like the real one.
        when(vault.seal(anyInt(), any())).thenAnswer(call -> {
            int bank = call.getArgument(0);
            SecretMaterial material = call.getArgument(1);
            String plain = bank + "|" + MAPPER.writeValueAsString(material.values());
            return new SecretVault.Sealed(
                    Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)), 3);
        });
        when(vault.open(anyInt(), any())).thenAnswer(call -> {
            int bank = call.getArgument(0);
            SecretVault.Sealed sealed = call.getArgument(1);
            String plain = new String(Base64.getDecoder().decode(sealed.ciphertext()),
                    StandardCharsets.UTF_8);
            if (!plain.startsWith(bank + "|")) {
                throw new IllegalStateException("not this bank's secret");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> values = MAPPER.readValue(plain.substring(plain.indexOf('|') + 1),
                    Map.class);
            return new SecretMaterial(values);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM IntegrationAgent WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
        users.deleteAll(createdUsers);
        createdUsers.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    @Test
    void registerApproveReadTokenOnceEnrolAndRevoke() throws Exception {
        int changeId = submit(makerA, "Core banking agent " + tag);
        assertThat(list(makerA)).isEmpty();
        approve(checkerA, changeId);

        JsonNode agent = list(makerA).get(0);
        int agentId = agent.get("id").asInt();
        assertThat(agent.get("name").asText()).isEqualTo("Core banking agent " + tag);
        assertThat(agent.get("status").asText()).isEqualTo("PENDING_ENROLMENT");
        assertThat(agent.get("enrolmentAvailable").asBoolean()).isTrue();
        assertThat(agent.get("enrolmentExpiresAt").isNull()).isFalse();
        // Only the maker may read the token.
        assertThat(list(checkerA).get(0).get("enrolmentAvailable").asBoolean()).isFalse();
        mvc.perform(get(enrolmentPath(agentId)).header("Authorization", checkerA))
                .andExpect(status().isForbidden());

        MvcResult read = mvc.perform(get(enrolmentPath(agentId)).header("Authorization", makerA))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        JsonNode enrol = json(read);
        String token = enrol.get("token").asText();
        assertThat(token).startsWith("mda1." + agentId + ".");
        assertThat(enrol.get("dockerCommand").asText())
                .contains("MODOCUMENT_ENROLMENT_TOKEN=" + token)
                .contains("MODOCUMENT_URL=http://localhost")
                .contains("--read-only");
        Instant expiresAt = Instant.parse(enrol.get("expiresAt").asText());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(
                Duration.ofMinutes(14), Duration.ofMinutes(15));

        // Readable once: the sealed copy is gone, the hash stays for the enrolment.
        mvc.perform(get(enrolmentPath(agentId)).header("Authorization", makerA))
                .andExpect(status().isGone());
        IntegrationAgent stored = agents.findById(agentId).orElseThrow();
        assertThat(stored.getEnrolmentTokenCiphertext()).isNull();
        assertThat(stored.getEnrolmentTokenHash()).hasSize(64).doesNotContain(token);
        assertThat(stored.getEnrolmentReadAt()).isNotNull();

        // A wrong secret for the right agent: the same 401 as anything else.
        String forged = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> enrol(forged))
                .isInstanceOf(AgentEnrolmentService.InvalidEnrolmentTokenException.class);
        verify(issuer, never()).issueAgentClient(anyInt(), anyInt(), any(), any(), any());

        when(issuer.issueAgentClient(eq(bankA.getBankId()), eq(agentId), eq("Core banking agent " + tag),
                eq(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS), isNull()))
                .thenReturn(new ApiClientIssuer.Issued(4711, "agt_" + tag, "s3cret-once",
                        ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS));
        AgentProtocol.EnrollResponse enrolled = enrol(token);
        assertThat(enrolled.agentId()).isEqualTo(agentId);
        assertThat(enrolled.clientId()).isEqualTo("agt_" + tag);
        assertThat(enrolled.secret()).isEqualTo("s3cret-once");
        assertThat(enrolled.tokenUrl()).isEqualTo("https://modocument.test/api/integration/oauth/token");

        // Single use.
        assertThatThrownBy(() -> enrol(token))
                .isInstanceOf(AgentEnrolmentService.InvalidEnrolmentTokenException.class);

        JsonNode online = list(makerA).get(0);
        assertThat(online.get("status").asText()).isEqualTo("ONLINE");
        assertThat(online.get("apiClientId").asInt()).isEqualTo(4711);
        assertThat(online.get("version").asText()).isEqualTo("1.0.0");
        assertThat(online.get("enrolmentAvailable").asBoolean()).isFalse();
        assertThat(agents.findById(agentId).orElseThrow().getEnrolmentTokenHash()).isNull();

        mvc.perform(post("/api/admin/integrations/agents/" + agentId + "/revoke")
                        .header("Authorization", checkerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(auditActions(agentId))
                .containsExactly("AGENT_REGISTERED", "AGENT_ENROLLED", "AGENT_REVOKED");
        // The agent's ApiClient is revoked with it: its tokens die at once.
        verify(issuer).revokeAgentClient(bankA.getBankId(), 4711);
        AuditEvent enrolledAudit = audits(agentId).get(1);
        assertThat(enrolledAudit.getBankId()).isEqualTo(bankA.getBankId());
        assertThat(enrolledAudit.getNewValues()).doesNotContain("s3cret-once").doesNotContain(token);
    }

    @Test
    void anotherBankNeitherSeesNorTouchesTheAgent() throws Exception {
        int agentId = registered("Branch agent " + tag);

        assertThat(list(adminB)).isEmpty();
        mvc.perform(get("/api/admin/integrations/agents/" + agentId).header("Authorization", adminB))
                .andExpect(status().isNotFound());
        mvc.perform(get(enrolmentPath(agentId)).header("Authorization", adminB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/integrations/agents/" + agentId + "/revoke")
                        .header("Authorization", adminB))
                .andExpect(status().isNotFound());
        assertThat(agents.findById(agentId).orElseThrow().getStatus())
                .isEqualTo(AgentStatus.PENDING_ENROLMENT);
    }

    @Test
    void onlyIntegrationAdminsManageAgentsAndBankUsersCannotSpeakTheAgentProtocol() throws Exception {
        mvc.perform(get("/api/admin/integrations/agents").header("Authorization", writerA))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/integrations/agents").header("Authorization", writerA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/integrations/agents")).andExpect(status().isUnauthorized());

        // AGENT_CONNECT belongs to agent clients only: a bank ADMIN is refused.
        mvc.perform(get("/api/agent/v1/tasks").header("Authorization", makerA))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", makerA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/v1/tasks")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRefusesBlankOddAndDuplicateNames() throws Exception {
        registered("Duplicate " + tag);
        mvc.perform(post("/api/admin/integrations/agents").header("Authorization", makerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"duplicate " + tag + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/integrations/agents").header("Authorization", makerA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/integrations/agents").header("Authorization", makerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"$(curl evil)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExpiredTokenCanNeitherBeReadNorUsed() throws Exception {
        int agentId = registered("Expiring " + tag);
        jdbc.update("UPDATE IntegrationAgent SET EnrolmentExpiresAt = ? WHERE IntegrationAgentId = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), agentId);

        mvc.perform(get(enrolmentPath(agentId)).header("Authorization", makerA))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("expired")));
        // The refusal still forgot the token (committed despite the 410).
        IntegrationAgent stored = agents.findById(agentId).orElseThrow();
        assertThat(stored.getEnrolmentTokenCiphertext()).isNull();
        assertThat(stored.getEnrolmentTokenHash()).isNull();
    }

    @Test
    void statusIsComputedFromTheHeartbeatAgeAndTheRunningVersion() throws Exception {
        int agentId = registered("Heartbeat " + tag);
        jdbc.update("UPDATE IntegrationAgent SET Status = 'ONLINE', Version = '0.9.0', "
                        + "LastHeartbeatAt = ?, LastHeartbeatJson = ? WHERE IntegrationAgentId = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                "[{\"connectionId\":7,\"ok\":true,\"message\":\"ok\"},"
                        + "{\"connectionId\":9,\"ok\":false,\"message\":\"timeout\"}]",
                agentId);

        JsonNode offline = list(makerA).get(0);
        // Default integration.agentHeartbeatTimeout is 2 minutes.
        assertThat(offline.get("status").asText()).isEqualTo("OFFLINE");
        assertThat(offline.get("connections")).extracting(JsonNode::asLong).containsExactly(7L, 9L);
        String running = versions.version();
        assertThat(offline.get("latestVersion").isNull() ? null : offline.get("latestVersion").asText())
                .isEqualTo(running);
        assertThat(offline.get("updateAvailable").asBoolean())
                .isEqualTo(running != null && running.matches("\\d+\\.\\d+\\.\\d+.*")
                        && !running.startsWith("0."));

        jdbc.update("UPDATE IntegrationAgent SET LastHeartbeatAt = ? WHERE IntegrationAgentId = ?",
                java.sql.Timestamp.from(Instant.now()), agentId);
        assertThat(list(makerA).get(0).get("status").asText()).isEqualTo("ONLINE");
    }

    // ------------------------------------------------------------------ helpers

    private int registered(String name) throws Exception {
        approve(checkerA, submit(makerA, name));
        for (JsonNode agent : list(makerA)) {
            if (agent.get("name").asText().equals(name)) {
                return agent.get("id").asInt();
            }
        }
        throw new AssertionError("agent " + name + " not registered");
    }

    private int submit(String caller, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/integrations/agents")
                        .header("Authorization", caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("AGENT_REGISTER"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn();
        return json(result).get("pendingChangeId").asInt();
    }

    private void approve(String caller, int changeId) throws Exception {
        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    private JsonNode list(String caller) throws Exception {
        return json(mvc.perform(get("/api/admin/integrations/agents").header("Authorization", caller))
                .andExpect(status().isOk())
                .andReturn());
    }

    private AgentProtocol.EnrollResponse enrol(String token) {
        return enrolment.enroll(new AgentProtocol.EnrollRequest(token, "host-1", "1.0.0", null, null),
                "10.77." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250),
                "https://modocument.test/api/integration/oauth/token");
    }

    private List<AuditEvent> audits(int agentId) {
        return entityManager.createQuery("SELECT e FROM AuditEvent e WHERE e.entityType = "
                        + "'IntegrationAgent' AND e.entityId = :id ORDER BY e.occurredAt", AuditEvent.class)
                .setParameter("id", String.valueOf(agentId))
                .getResultList();
    }

    private List<String> auditActions(int agentId) {
        return audits(agentId).stream().map(AuditEvent::getAction).toList();
    }

    private static String enrolmentPath(int agentId) {
        return "/api/admin/integrations/agents/" + agentId + "/enrolment";
    }

    private String token(Bank bank, Role role) {
        AppUser user = PlatformBankFixture.user(users, bank.getBankId(), role, "ws26-agent");
        createdUsers.add(user);
        return "Bearer " + jwtService.issueToken(user);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
