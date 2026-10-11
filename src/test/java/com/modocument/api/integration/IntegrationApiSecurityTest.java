package com.modocument.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Machine identity + Integration API v1 end to end on H2 (ws26): the
 * API_CLIENT_CREATE maker/checker flow and the one-time secret, all three
 * authentication methods, and the mandatory security properties — user and
 * client tokens are never interchangeable, and revoked, expired, wrong-network
 * and missing-scope callers are refused,
 * spoofed mTLS headers ignored, cross-bank ids 404, idempotent replay.
 *
 * <p>SE1's SecretVault and SE4's RecordsIngestion / IntegrationEventLog are
 * mocked (their placeholders refuse on this branch).
 */
@SpringBootTest(properties = {
        "modocument.integration.clients.mtls-trusted-proxy-cidrs=127.0.0.1/32",
        "modocument.integration.clients.verify-per-ip-max=100000",
        "modocument.integration.api.token-per-ip-max=100000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationApiSecurityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String V1 = "/api/integration/v1";
    private static final String CLIENTS = "/api/admin/integrations/api-clients";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SecretVault vault;
    @MockitoBean
    private RecordsIngestion recordsIngestion;
    @MockitoBean
    private IntegrationEventLog eventLog;

    private Bank bankA;
    private Bank bankB;
    private AppUser makerA;
    private AppUser checkerA;
    private AppUser makerB;
    private AppUser checkerB;
    private Template sharedA;
    private Template privateA;
    private Template sharedB;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = banks.save(new Bank("IA" + tag, "Integration Bank A"));
        bankB = banks.save(new Bank("IB" + tag, "Integration Bank B"));
        makerA = user("ia-maker-" + tag, bankA, Role.ADMIN);
        checkerA = user("ia-checker-" + tag, bankA, Role.ADMIN);
        makerB = user("ib-maker-" + tag, bankB, Role.ADMIN);
        checkerB = user("ib-checker-" + tag, bankB, Role.ADMIN);
        sharedA = template("Shared A " + tag, makerA, TemplateVisibility.BANK);
        privateA = template("Private A " + tag, makerA, TemplateVisibility.PRIVATE);
        sharedB = template("Shared B " + tag, makerB, TemplateVisibility.BANK);

        // A transparent stand-in for SE1's vault: base64 "sealing" per bank.
        when(vault.seal(anyInt(), any())).thenAnswer(inv -> new SecretVault.Sealed(
                inv.getArgument(0) + ":" + Base64.getEncoder().encodeToString(
                        ((SecretMaterial) inv.getArgument(1)).get("clientSecret")
                                .getBytes(StandardCharsets.UTF_8)), 1));
        when(vault.open(anyInt(), any())).thenAnswer(inv -> {
            String sealed = ((SecretVault.Sealed) inv.getArgument(1)).ciphertext();
            String[] parts = sealed.split(":", 2);
            assertThat(Integer.parseInt(parts[0])).isEqualTo((int) inv.getArgument(0));
            return new SecretMaterial(Map.of("clientSecret",
                    new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8)));
        });
    }

    @AfterEach
    void cleanUp() {
        for (Bank bank : List.of(bankA, bankB)) {
            int id = bank.getBankId();
            jdbc.update("DELETE FROM IdempotencyKey WHERE BankId = ?", id);
            jdbc.update("DELETE FROM ApiClientInitialSecret WHERE BankId = ?", id);
            jdbc.update("DELETE FROM ApiClientSecret WHERE BankId = ?", id);
            jdbc.update("DELETE FROM ApiClient WHERE BankId = ?", id);
            jdbc.update("DELETE FROM PendingChange WHERE BankId = ?", id);
            jdbc.update("DELETE FROM IngestionJob WHERE BankId = ?", id);
            jdbc.update("DELETE FROM ImportHistory WHERE BankId = ?", id);
            jdbc.update("DELETE FROM UploadedFile WHERE BankId = ?", id);
            jdbc.update("DELETE FROM Template WHERE BankId = ?", id);
        }
        for (Integer id : userIds) {
            users.deleteById(id);
        }
        userIds.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    // ============================================================ management

    @Test
    void makerCheckerCreatesTheClientAndOnlyTheMakerReadsTheSecretOnce() throws Exception {
        String pendingJson = mvc.perform(post(CLIENTS).header("Authorization", bearer(makerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "Core banking",
                                "authMethod", "API_KEY",
                                "scopes", List.of("INTEGRATION_INGEST"),
                                "expiresAt", "2027-12-31T23:59:59Z"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("API_CLIENT_CREATE"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        int changeId = JSON.readTree(pendingJson).path("pendingChangeId").asInt();

        // Listed as PENDING while waiting; nothing exists yet.
        mvc.perform(get(CLIENTS).header("Authorization", bearer(makerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].pendingChangeId").value(changeId));
        // The maker cannot approve their own client.
        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", bearer(makerA)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", bearer(checkerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        int clientId = Integer.parseInt(jdbc.queryForObject(
                "SELECT TargetEntityId FROM PendingChange WHERE PendingChangeId = ?",
                String.class, changeId));

        mvc.perform(get(CLIENTS).header("Authorization", bearer(makerA)))
                .andExpect(jsonPath("$[0].id").value(clientId))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].initialSecretAvailable").value(true))
                .andExpect(jsonPath("$[0].createdBy").value(makerA.getAppUserId()));
        // The checker (and anyone else) never sees it; neither does bank B.
        mvc.perform(get(CLIENTS + "/" + clientId + "/initial-secret")
                        .header("Authorization", bearer(checkerA)))
                .andExpect(status().isNotFound());
        mvc.perform(get(CLIENTS + "/" + clientId + "/initial-secret")
                        .header("Authorization", bearer(makerB)))
                .andExpect(status().isNotFound());
        String secretJson = mvc.perform(get(CLIENTS + "/" + clientId + "/initial-secret")
                        .header("Authorization", bearer(makerA)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        String apiKey = JSON.readTree(secretJson).path("secret").asText();
        assertThat(apiKey).startsWith("mdk_").contains(".");
        assertThat(JSON.readTree(secretJson).path("clientId").asText()).startsWith("mdc_");
        // Once only.
        mvc.perform(get(CLIENTS + "/" + clientId + "/initial-secret")
                        .header("Authorization", bearer(makerA)))
                .andExpect(status().isNotFound());
        // Stored as an Argon2id hash, never in clear.
        String hash = jdbc.queryForObject(
                "SELECT SecretHash FROM ApiClientSecret WHERE ApiClientId = ?", String.class,
                clientId);
        assertThat(hash).startsWith("{argon2}").doesNotContain(apiKey.substring(5));

        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(1))
                .andExpect(jsonPath("$.templates[0].templateId").value(sharedA.getTemplateId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                + "'API_CLIENT_CREATED' AND EntityId = ?", Integer.class, String.valueOf(clientId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                + "'INTEGRATION_API_CALL' AND EntityId = ? AND ActorRole = 'API_CLIENT'",
                Integer.class, String.valueOf(clientId))).isEqualTo(1);
    }

    @Test
    void requestsAreValidatedAtSubmit() throws Exception {
        createRequest(makerA, Map.of("name", "x", "authMethod", "API_KEY",
                "scopes", List.of("AGENT_CONNECT")), 400);
        createRequest(makerA, Map.of("name", "x", "authMethod", "API_KEY",
                "scopes", List.of("INTEGRATION_INGEST"), "allowedCidrs", List.of("evil.example")),
                400);
        createRequest(makerA, Map.of("name", "x", "authMethod", "MTLS",
                "scopes", List.of("INTEGRATION_INGEST")), 400);
        createRequest(makerA, Map.of("name", "x", "authMethod", "API_KEY",
                "scopes", List.of("INTEGRATION_INGEST"), "expiresAt", "2001-01-01T00:00:00Z"),
                400);
        AppUser writer = user("ia-writer-" + UUID.randomUUID().toString().substring(0, 6), bankA,
                Role.READ_WRITE);
        createRequest(writer, Map.of("name", "x", "authMethod", "API_KEY",
                "scopes", List.of("INTEGRATION_INGEST")), 403);
    }

    // ============================================================ crossover

    @Test
    void userTokensAndClientCredentialsNeverCrossOver() throws Exception {
        Created client = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        // A user token (even a bank ADMIN's) on the machine API.
        mvc.perform(get(V1 + "/templates").header("Authorization", bearer(makerA)))
                .andExpect(status().isForbidden());
        // A client credential on user routes.
        mvc.perform(get("/api/ingestion/jobs").header("Authorization", "ApiKey " + client.secret))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", "ApiKey " + client.secret))
                .andExpect(status().isForbidden());
        // Anonymous.
        mvc.perform(get(V1 + "/templates")).andExpect(status().isUnauthorized());

        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        String token = token(oauth, null);
        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ingestion/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMissingScopeIsA403() throws Exception {
        Created client = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        mvc.perform(get(V1 + "/events").header("Authorization", "ApiKey " + client.secret))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
        mvc.perform(get(V1 + "/jobs/1").header("Authorization", "ApiKey " + client.secret))
                .andExpect(status().isForbidden());

        // A token narrowed by `scope` carries only that scope.
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST", "INTEGRATION_EVENTS"), null, null);
        String narrow = token(oauth, "INTEGRATION_EVENTS");
        when(eventLog.page(eq(bankA.getBankId()), anyLong(), anyInt())).thenReturn(List.of());
        mvc.perform(get(V1 + "/events").header("Authorization", "Bearer " + narrow))
                .andExpect(status().isOk());
        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + narrow))
                .andExpect(status().isForbidden());
        // A scope beyond the client's is refused at the token endpoint.
        mvc.perform(post("/api/integration/oauth/token")
                        .header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&scope=INTEGRATION_DOCS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    // ============================================================ lifecycle

    @Test
    void revocationIsImmediateForTokensAndKeys() throws Exception {
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        String token = token(oauth, null);
        Created key = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isOk()); // cached verification

        revoke(oauth);
        revoke(key);

        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integration/oauth/token").header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        assertThat(jdbc.queryForObject("SELECT TokenVersion FROM ApiClient WHERE ApiClientId = ?",
                Integer.class, oauth.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                + "'API_CLIENT_AUTH_FAILED' AND EntityId = ?", Integer.class,
                String.valueOf(oauth.id))).isGreaterThanOrEqualTo(2);
    }

    @Test
    void anExpiredClientIsA401() throws Exception {
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        String token = token(oauth, null);
        Created key = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        jdbc.update("UPDATE ApiClient SET ExpiresAt = ? WHERE ApiClientId IN (?, ?)",
                java.sql.Timestamp.from(Instant.now().minusSeconds(5)), oauth.id, key.id);

        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CLIENTS).header("Authorization", bearer(makerA)))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
    }

    /**
     * Leaver check (wave 4): a client stops working when the user who
     * created it is disabled or removed — token issue, API key and an
     * already-issued token all answer 401, audited CREATOR_INACTIVE.
     */
    @Test
    void aClientDiesWithItsCreator() throws Exception {
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        String token = token(oauth, null);
        Created key = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isOk()); // the verification is now cached

        jdbc.update("UPDATE AppUsers SET Disabled = TRUE WHERE AppUserId = ?",
                makerA.getAppUserId());

        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integration/oauth/token").header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        for (int id : new int[] {oauth.id, key.id}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                            + "'API_CLIENT_AUTH_FAILED' AND EntityId = ? "
                            + "AND CAST(NewValues AS VARCHAR) LIKE '%CREATOR_INACTIVE%'",
                    Integer.class, String.valueOf(id))).isGreaterThanOrEqualTo(1);
        }

        // Re-enabled: the same credentials work again (the creator was the only reason).
        jdbc.update("UPDATE AppUsers SET Disabled = FALSE WHERE AppUserId = ?",
                makerA.getAppUserId());
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isOk());

        // A removed creator, or one that is not an account of the client's bank.
        jdbc.update("UPDATE ApiClient SET CreatedByUserId = ? WHERE ApiClientId = ?",
                987_654_321, key.id);
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE ApiClient SET CreatedByUserId = ? WHERE ApiClientId = ?",
                makerB.getAppUserId(), key.id);
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCallerAddressMustBeInsideAllowedCidrs() throws Exception {
        Created key = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"),
                List.of("203.0.113.0/24"), null);
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret)
                        .header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + key.secret)
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isOk());
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), List.of("203.0.113.0/24"), null);
        mvc.perform(post("/api/integration/oauth/token").header("Authorization", basic(oauth))
                        .header("X-Forwarded-For", "198.51.100.7")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rotationKeepsTheOldSecretForTheOverlap() throws Exception {
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        String rotated = JSON.readTree(mvc.perform(post(CLIENTS + "/" + oauth.id + "/rotate")
                        .header("Authorization", bearer(checkerA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("secret").asText();
        assertThat(rotated).isNotEqualTo(oauth.secret);
        token(oauth, null);
        token(new Created(oauth.id, oauth.clientId, rotated), null);
        // After the overlap only the new secret works.
        jdbc.update("UPDATE ApiClientSecret SET ExpiresAt = ? WHERE ApiClientId = ? "
                + "AND ExpiresAt IS NOT NULL", java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                oauth.id);
        mvc.perform(post("/api/integration/oauth/token").header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized());
        token(new Created(oauth.id, oauth.clientId, rotated), null);
    }

    // ============================================================ mTLS

    @Test
    void certificateClientsAuthenticateOnlyThroughTheTrustedProxy() throws Exception {
        String pem = TestCertificates.selfSignedPem("core-banking");
        Created mtls = create(makerA, checkerA, "MTLS", List.of("INTEGRATION_INGEST"), null, pem);
        assertThat(mtls.secret).isNull();
        String header = TestCertificates.headerValue(pem);

        // From the trusted proxy (the MockMvc socket peer is 127.0.0.1).
        mvc.perform(get(V1 + "/templates").header("X-Amzn-Mtls-Clientcert-Leaf", header))
                .andExpect(status().isOk());
        // The same headers planted by a direct, untrusted peer are ignored.
        mvc.perform(get(V1 + "/templates").header("X-Amzn-Mtls-Clientcert-Leaf", header)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.50");
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
        // A certificate nobody registered.
        mvc.perform(get(V1 + "/templates").header("X-Amzn-Mtls-Clientcert-Leaf",
                        TestCertificates.headerValue(TestCertificates.selfSignedPem("rogue"))))
                .andExpect(status().isUnauthorized());

        // mTLS-bound token: usable only together with the same certificate.
        String token = JSON.readTree(mvc.perform(post("/api/integration/oauth/token")
                        .header("X-Amzn-Mtls-Clientcert-Leaf", header)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("access_token").asText();
        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token)
                        .header("X-Amzn-Mtls-Clientcert-Leaf", header))
                .andExpect(status().isOk());
        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // The same certificate cannot be registered twice (any bank).
        createRequest(makerB, Map.of("name", "dup", "authMethod", "MTLS",
                "scopes", List.of("INTEGRATION_INGEST"), "certificatePem", pem), 409);
    }

    // ============================================================ data

    @Test
    void anotherBanksJobIsA404AndOwnJobsAreReadable() throws Exception {
        Created a = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_RESULTS"), null, null);
        Created b = create(makerB, checkerB, "API_KEY", List.of("INTEGRATION_RESULTS"), null, null);
        IngestionJob job = validatedJob(makerA, sharedA);

        mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId())
                        .header("Authorization", "ApiKey " + b.secret))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId() + "/results")
                        .header("Authorization", "ApiKey " + b.secret))
                .andExpect(status().isNotFound());

        mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId())
                        .header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.counts.rows").value(2))
                .andExpect(jsonPath("$.counts.valid").value(1))
                .andExpect(jsonPath("$.counts.rejected").value(1));
        mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId() + "/results")
                        .header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rows[0].Name").value("Alice"));
        MvcResult csv = mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId() + "/results")
                        .param("format", "csv").header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        mvc.perform(get(V1 + "/jobs/" + job.getIngestionJobId() + "/errors")
                        .header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].row").value(2))
                .andExpect(jsonPath("$.rejected[0].errors[0].field").value("Name"));

        // A job on a PRIVATE template is not a machine job.
        IngestionJob privateJob = validatedJob(makerA, privateA);
        mvc.perform(get(V1 + "/jobs/" + privateJob.getIngestionJobId())
                        .header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordsAreIdempotent() throws Exception {
        Created a = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null, null);
        when(recordsIngestion.createFromRecords(any())).thenReturn(4242, 4343);
        String body = JSON.writeValueAsString(Map.of("templateId", sharedA.getTemplateId(),
                "externalBatchId", "batch-1",
                "records", List.of(Map.of("Name", "Alice", "Amount", 12.5))));

        mvc.perform(post(V1 + "/records").header("Authorization", "ApiKey " + a.secret)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(withKey(post(V1 + "/records"), a, "key-1").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(4242));
        mvc.perform(withKey(post(V1 + "/records"), a, "key-1").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.jobId").value(4242));
        mvc.perform(withKey(post(V1 + "/records"), a, "key-1").content(body.replace("Alice", "Bob")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(recordsIngestion, times(1)).createFromRecords(any());
        verify(recordsIngestion).createFromRecords(org.mockito.ArgumentMatchers.argThat(command ->
                command.bankId() == bankA.getBankId()
                        && command.ownerUserId() == makerA.getAppUserId()
                        && command.origin().equals("api-client:" + a.id)
                        && command.externalBatchId().equals("batch-1")));

        // Templates the client may not use: private, or another bank's.
        String privateBody = body.replace(String.valueOf(sharedA.getTemplateId()),
                String.valueOf(privateA.getTemplateId()));
        mvc.perform(withKey(post(V1 + "/records"), a, "key-2").content(privateBody))
                .andExpect(status().isNotFound());
        String foreignBody = body.replace(String.valueOf(sharedA.getTemplateId()),
                String.valueOf(sharedB.getTemplateId()));
        mvc.perform(withKey(post(V1 + "/records"), a, "key-3").content(foreignBody))
                .andExpect(status().isNotFound());
        // The failed requests released their keys.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM IdempotencyKey WHERE ApiClientId = ?",
                Integer.class, a.id)).isEqualTo(1);
        mvc.perform(withKey(post(V1 + "/records"), a, "key-4").content("{\"templateId\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(withKey(post(V1 + "/records"), a, "key-5").content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    @Test
    void filesNeedASharedTemplateAndAnIdempotencyKey() throws Exception {
        Created a = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null, null);
        MockMultipartFile file = new MockMultipartFile("file", "rows.csv", "text/csv",
                "Name,Amount\nAlice,1\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(V1 + "/files").file(file)
                        .param("templateId", String.valueOf(privateA.getTemplateId()))
                        .header("Authorization", "ApiKey " + a.secret)
                        .header("Idempotency-Key", "f-1"))
                .andExpect(status().isNotFound());
        verify(recordsIngestion, never()).createFromRecords(any());
    }

    @Test
    void eventsArePagedForTheClientsBankOnly() throws Exception {
        Created a = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_EVENTS"), null, null);
        when(eventLog.page(eq(bankA.getBankId()), eq(10L), eq(2))).thenReturn(List.of(
                new IntegrationEventLog.Event(11, FlowEvent.INGESTION_VALIDATED, Instant.now(),
                        "IngestionJob", "7", Map.of("jobId", 7))));
        mvc.perform(get(V1 + "/events").param("after", "10").param("limit", "2")
                        .header("Authorization", "ApiKey " + a.secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value(11))
                .andExpect(jsonPath("$.events[0].type").value("INGESTION_VALIDATED"))
                .andExpect(jsonPath("$.events[0].entity.type").value("IngestionJob"))
                .andExpect(jsonPath("$.next").value(11));
        verify(eventLog).page(bankA.getBankId(), 10L, 2);
    }

    @Test
    void theTokenEndpointFollowsRfc6749() throws Exception {
        Created oauth = create(makerA, checkerA, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"), null, null);
        // client_secret_post works too.
        mvc.perform(post("/api/integration/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&client_id=" + oauth.clientId
                                + "&client_secret=" + oauth.secret))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andExpect(jsonPath("$.scope").value("INTEGRATION_INGEST"));
        mvc.perform(post("/api/integration/oauth/token").header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        mvc.perform(post("/api/integration/oauth/token")
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                (oauth.clientId + ":wrong").getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
        // An API-key client cannot get tokens.
        Created key = create(makerA, checkerA, "API_KEY", List.of("INTEGRATION_INGEST"), null,
                null);
        mvc.perform(post("/api/integration/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&client_id=" + key.clientId
                                + "&client_secret=" + key.secret.substring(key.secret.indexOf('.') + 1)))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ helpers

    private record Created(int id, String clientId, String secret) {
    }

    private Created create(AppUser maker, AppUser checker, String method, List<String> scopes,
                           List<String> cidrs, String pem) throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("name", method + " client " + UUID.randomUUID().toString().substring(0, 4));
        request.put("authMethod", method);
        request.put("scopes", scopes);
        if (cidrs != null) {
            request.put("allowedCidrs", cidrs);
        }
        if (pem != null) {
            request.put("certificatePem", pem);
        }
        JsonNode pending = JSON.readTree(createRequest(maker, request, 202));
        int changeId = pending.path("pendingChangeId").asInt();
        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", bearer(checker)))
                .andExpect(status().isOk());
        int id = Integer.parseInt(jdbc.queryForObject(
                "SELECT TargetEntityId FROM PendingChange WHERE PendingChangeId = ?",
                String.class, changeId));
        String clientId = jdbc.queryForObject("SELECT ClientId FROM ApiClient WHERE ApiClientId = ?",
                String.class, id);
        if ("MTLS".equals(method)) {
            return new Created(id, clientId, null);
        }
        JsonNode secret = JSON.readTree(mvc.perform(get(CLIENTS + "/" + id + "/initial-secret")
                        .header("Authorization", bearer(maker)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return new Created(id, secret.path("clientId").asText(), secret.path("secret").asText());
    }

    private String createRequest(AppUser maker, Map<String, ?> body, int expected)
            throws Exception {
        return mvc.perform(post(CLIENTS).header("Authorization", bearer(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
    }

    private void revoke(Created client) throws Exception {
        mvc.perform(post(CLIENTS + "/" + client.id + "/revoke")
                        .header("Authorization", bearer(checkerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    private String token(Created client, String scope) throws Exception {
        String form = "grant_type=client_credentials" + (scope == null ? "" : "&scope=" + scope);
        return JSON.readTree(mvc.perform(post("/api/integration/oauth/token")
                        .header("Authorization", basic(client))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("access_token").asText();
    }

    private static String basic(Created client) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (client.clientId + ":" + client.secret).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder builder,
                                                         Created client, String key) {
        return builder.header("Authorization", "ApiKey " + client.secret)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private AppUser user(String prefix, Bank bank, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(prefix + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode("Sup3r-secret-ws26!"));
        entity.setDisplayName(prefix);
        entity.setBankId(bank.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private Template template(String name, AppUser owner, TemplateVisibility visibility) {
        Template entity = new Template(0, name, "integration test");
        entity.setOwnerUserId(owner.getAppUserId());
        entity.setBankId(owner.getBankId());
        entity.setVisibility(visibility);
        return templates.save(entity);
    }

    private IngestionJob validatedJob(AppUser owner, Template template) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(owner.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName("rows.csv");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setMappedRowsJson("{\"columns\":[\"Name\",\"Amount\"],\"rows\":["
                + "{\"Name\":\"Alice\",\"Amount\":\"10\"},{\"Name\":\"\",\"Amount\":\"x\"}]}");
        job.setValidationResultJson("[{\"row\":2,\"fieldName\":\"Name\",\"rawValue\":\"\","
                + "\"errorMessage\":\"Name is required\",\"ruleType\":\"REQUIRED\"}]");
        return jobs.save(job);
    }
}
