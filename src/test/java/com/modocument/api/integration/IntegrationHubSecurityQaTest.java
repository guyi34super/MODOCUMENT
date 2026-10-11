package com.modocument.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.EgressRuleStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.infrastructure.integration.egress.BankEgressGuard;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.OutboundDispatcher;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.integration.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA's security cases of the Bank Integration Hub that no engineer suite
 * covered at the merge (docs/qa/integration-security-tests.md, Wave 4 map).
 * Each test names its S-case. H2 + MockMvc on the real beans, with:
 * <ul>
 *   <li>the SSRF policy ON (the default test profile switches it off for the
 *       extraction crawler), so {@code BankEgressGuard} behaves as in
 *       production: a private address needs an APPROVED egress rule;</li>
 *   <li>the API-client export cap at 1 row, so the cap refusal of
 *       {@code /jobs/{id}/results} is reachable with two records.</li>
 * </ul>
 * Open bugs found here are {@code @Disabled("BUG-xx …")} with the repro.
 */
@TestPropertySource(properties = {
        "modocument.security.ssrf.enabled=true",
        "modocument.export.caps.API_CLIENT=1",
        "modocument.integration.clients.verify-per-ip-max=100000",
        "modocument.integration.api.token-per-ip-max=100000"})
class IntegrationHubSecurityQaTest extends IntegrationTestSupport {

    private static final String V1 = "/api/integration/v1";
    private static final String CLIENTS = "/api/admin/integrations/api-clients";
    private static final String CONNECTIONS = "/api/admin/integrations/connections";
    private static final String FLOWS = "/api/admin/integrations/flows";
    private static final String TOKEN = "/api/integration/oauth/token";

    @Autowired
    private BankEgressRuleJpaRepository egressRules;
    @Autowired
    private BankEgressGuard egressGuard;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private ApiClientIssuer apiClientIssuer;
    @Autowired
    private DocInstanceJpaRepository docInstances;
    @Autowired
    private RecordsIngestion recordsIngestion;
    @Autowired
    private OutboundDispatcher dispatcher;
    @Autowired
    private OutboundDeliveryJpaRepository deliveries;
    @Autowired
    private com.modocument.infrastructure.alerting.AlertSweepJob alertSweep;

    private final List<Integer> docs = new ArrayList<>();

    @AfterEach
    void cleanQaRows() {
        int id = bank.getBankId();
        for (Integer doc : docs) {
            docInstances.deleteById(doc);
        }
        docs.clear();
        for (String table : List.of("IdempotencyKey", "ApiClientInitialSecret", "ApiClientSecret",
                "IntegrationAgent", "ApiClient", "BankEgressRule", "AlertLog", "SettingValue")) {
            try {
                jdbc.update("DELETE FROM " + table + " WHERE BankId = ?", id);
            } catch (org.springframework.dao.DataAccessException absent) {
                // a table this schema version does not have
            }
        }
        egressGuard.invalidate(id);
    }

    // =================================================================== SSRF

    /**
     * S1: a DIRECT connection to a private address without an APPROVED egress
     * rule is refused BEFORE any socket is opened (a listener on the address
     * sees no connection); the same connection works once the platform
     * approved a rule for exactly that host and port.
     */
    @Test
    void s01PrivateDestinationWithoutARuleNeverOpensASocket() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            AtomicInteger accepted = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        accepted.incrementAndGet();
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                                + "Content-Type: application/json\r\nConnection: close\r\n\r\n{}")
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception closed) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            int port = listener.getLocalPort();
            IntegrationConnection rest = directRest("private api", "http://127.0.0.1:" + port);

            JsonNode refused = json(postAs(admin, CONNECTIONS + "/" + rest.getId() + "/test", null)
                    .andExpect(status().isOk()));
            assertThat(refused.get("ok").asBoolean()).isFalse();
            assertThat(refused.get("message").asText())
                    .contains("127.0.0.1:" + port).contains("no approved egress rule");
            Thread.sleep(200);
            assertThat(accepted.get()).as("no TCP connection before the guard says yes").isZero();

            // The platform approves 127.0.0.1:<port> for THIS bank: the call goes through.
            approvedRule("127.0.0.1", port);
            JsonNode allowed = json(postAs(admin, CONNECTIONS + "/" + rest.getId() + "/test", null)
                    .andExpect(status().isOk()));
            assertThat(allowed.get("ok").asBoolean()).as(allowed.toString()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> accepted.get() >= 1);

            // S3: the rule is host AND port — the next port stays closed.
            IntegrationConnection otherPort = directRest("other port", "http://127.0.0.1:" + (port + 1));
            JsonNode wrongPort = json(postAs(admin, CONNECTIONS + "/" + otherPort.getId() + "/test", null));
            assertThat(wrongPort.get("ok").asBoolean()).isFalse();
            assertThat(wrongPort.get("message").asText()).contains("no approved egress rule");
        }
    }

    /** S2: the cloud metadata endpoint is never contacted, whatever rule exists. */
    @Test
    void s02MetadataDestinationIsRefusedAtConnectEvenWithARule() throws Exception {
        BankEgressRule rule = new BankEgressRule();
        rule.setBankId(bank.getBankId());
        rule.setCidr("169.254.0.0/16");
        rule.setPort(80);
        rule.setStatus(EgressRuleStatus.APPROVED);
        rule.setReason("QA: a rule must not open the metadata endpoint");
        rule.setRequestedByUserId(admin.getAppUserId());
        rule.setApprovedByUserId(secondAdmin.getAppUserId());
        rule.setCreatedAt(Instant.now());
        rule.setApprovedAt(Instant.now());
        egressRules.save(rule);
        egressGuard.invalidate(bank.getBankId());
        IntegrationConnection imds = directRest("imds", "http://169.254.169.254/latest/meta-data");
        JsonNode result = json(postAs(admin, CONNECTIONS + "/" + imds.getId() + "/test", null)
                .andExpect(status().isOk()));
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("message").asText()).contains("cloud metadata endpoint");
    }

    /**
     * BUG-06 (P4): a REST_API/WEBHOOK connection whose base URL is the cloud
     * metadata endpoint is ACCEPTED at submit (202) and can be approved; only
     * the connect-time guard refuses it (S2 above). The security plan (S2)
     * wants 400 at submit, like an egress rule for the same address.
     * Repro: POST /api/admin/integrations/connections
     * {"name":"x","type":"REST_API","mode":"DIRECT","authType":"NONE",
     * "config":{"baseUrl":"http://169.254.169.254/latest/meta-data"}} → 202.
     */
    @Test
    void bug06MetadataDestinationRefusedAtSubmit() throws Exception {
        for (String url : List.of("http://169.254.169.254/latest/meta-data",
                "http://[fd00:ec2::254]/", "http://169.254.170.2/v2/credentials")) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("name", "imds " + UUID.randomUUID().toString().substring(0, 6));
            request.put("type", "REST_API");
            request.put("mode", "DIRECT");
            request.put("authType", "NONE");
            request.put("config", Map.of("baseUrl", url));
            postAs(admin, CONNECTIONS, request).andExpect(status().isBadRequest());
        }
    }

    // ========================================================= SQL and paths

    /** S7: identifier injection in every identifier field is refused at flow save. */
    @Test
    void s07IdentifierInjectionIsRefusedAtFlowSave() throws Exception {
        IntegrationConnection db = dbConnection();
        List<String> evil = List.of("customers; DROP TABLE x", "customers--", "\"customers\"",
                "customers/**/", "pg_catalog.pg_authid", "public.customers",
                "ｃｕｓｔｏｍｅｒｓ",
                // An embedded NUL. (A TRAILING one is trimmed with the other control
                // characters before validation, and the trimmed name is what is used.)
                "cust\u0000omers",
                "c".repeat(129), "");
        List<String> accepted = new ArrayList<>();
        for (String bad : evil) {
            String shown = bad.length() > 24 ? bad.substring(0, 24) + "…" : bad;
            expectRefused(accepted, "table " + shown,
                    dbFlow(db, "INBOUND", Map.of("table", bad, "columns", List.of("id"))));
            expectRefused(accepted, "column " + shown, dbFlow(db, "INBOUND", Map.of("table", "customers",
                    "columns", List.of("id", bad))));
            if (!bad.isEmpty()) {
                expectRefused(accepted, "watermark " + shown, dbFlow(db, "INBOUND", Map.of("table",
                        "customers", "columns", List.of("id"), "watermarkColumn", bad)));
            }
            expectRefused(accepted, "keyColumn " + shown, dbFlow(db, "OUTBOUND", Map.of("table", "staging",
                    "mode", "UPSERT", "keyColumns", List.of(bad))));
        }
        assertThat(accepted).as("identifier inputs accepted at flow save").isEmpty();
        assertThat(flowCount()).isZero();
    }

    /** S8: there is no free-SQL field; a query/sql/where key is refused. */
    @Test
    void s08FreeSqlKeysAreRefused() throws Exception {
        IntegrationConnection db = dbConnection();
        for (String key : List.of("query", "sql", "where", "orderBy")) {
            postAs(admin, FLOWS, dbFlow(db, "INBOUND", Map.of("table", "customers",
                    "columns", List.of("id"), key, "SELECT * FROM pg_authid")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(flowCount()).isZero();
    }

    /** S10: JDBC URL injection through database/host is refused at submit. */
    @Test
    void s10JdbcUrlInjectionIsRefusedAtSubmit() throws Exception {
        List<Map<String, Object>> configs = List.of(
                Map.of("vendor", "POSTGRESQL", "host", "db.bank.example", "database",
                        "bank?socketFactory=org.example.Evil", "username", "r"),
                Map.of("vendor", "POSTGRESQL", "host", "db.bank.example", "database",
                        "bank&loggerFile=/tmp/x", "username", "r"),
                Map.of("vendor", "POSTGRESQL", "host", "db.bank.example/evil", "database", "bank",
                        "username", "r"),
                Map.of("vendor", "POSTGRESQL", "host", "user@db.bank.example", "database", "bank",
                        "username", "r"),
                Map.of("vendor", "POSTGRESQL", "host", "db.bank.example", "database", "bank",
                        "username", "r", "sslmode", "disable&sslfactory=x"));
        for (Map<String, Object> config : configs) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("name", "db " + UUID.randomUUID().toString().substring(0, 6));
            request.put("type", "DATABASE");
            request.put("mode", "DIRECT");
            request.put("authType", "BASIC");
            request.put("config", config);
            request.put("secret", Map.of("password", "pw-never-shown"));
            postAs(admin, CONNECTIONS, request).andExpect(status().isBadRequest());
        }
        assertThat(pendingChangeCount()).isZero();
    }

    /** S11: an SFTP connection without a pinned host key cannot even be requested. */
    @Test
    void s11SftpWithoutAPinnedHostKeyIsRefusedAtSubmit() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "sftp unpinned");
        request.put("type", "SFTP");
        request.put("mode", "DIRECT");
        request.put("authType", "BASIC");
        request.put("config", Map.of("host", "sftp.bank.example", "port", "22", "username", "bank",
                "sftpAuth", "PASSWORD", "folder", "/exports"));
        request.put("secret", Map.of("password", "pw-never-shown"));
        postAs(admin, CONNECTIONS, request).andExpect(status().isBadRequest());
        assertThat(pendingChangeCount()).isZero();
    }

    /** S13: SFTP folders and file names that could leave the configured root are refused at save. */
    @Test
    void s13SftpPathTraversalIsRefusedAtFlowSave() throws Exception {
        IntegrationConnection sftp = sftpConnection();
        List<String> accepted = new ArrayList<>();
        for (String folder : List.of("../", "inbound/../../etc", "/etc", "inbound/%2e%2e/",
                "inbound\\..\\x", "inbound/./x")) {
            expectRefused(accepted, "folder " + folder, flowRequest(sftp, "INBOUND",
                    Map.of("folder", folder, "glob", "*.csv"), "sourceSpec"));
        }
        for (String glob : List.of("../*.csv", "a/*.csv")) {
            expectRefused(accepted, "glob " + glob, flowRequest(sftp, "INBOUND",
                    Map.of("folder", "inbound", "glob", glob), "sourceSpec"));
        }
        for (String pattern : List.of("../x.csv", "a/b.csv", "/etc/passwd", "..\\x.csv")) {
            expectRefused(accepted, "fileNamePattern " + pattern, flowRequest(sftp, "OUTBOUND",
                    Map.of("folder", "outbound", "fileNamePattern", pattern), "targetSpec"));
        }
        assertThat(accepted).as("SFTP path inputs accepted at flow save").isEmpty();
        assertThat(flowCount()).isZero();
    }

    // ================================================================ clients

    /** S18: idempotency keys are per client — the same key on two clients creates two jobs. */
    @Test
    void s18IdempotencyKeysArePerClient() throws Exception {
        Client a = client("API_KEY", List.of("INTEGRATION_INGEST"));
        Client b = client("API_KEY", List.of("INTEGRATION_INGEST"));
        String body = records(List.of(record("Ada Lovelace", "123456789", "10")));
        int jobA = json(apiKeyPost(a, V1 + "/records", "shared-key", body)
                .andExpect(status().isAccepted())).get("jobId").asInt();
        int jobB = json(apiKeyPost(b, V1 + "/records", "shared-key", body)
                .andExpect(status().isAccepted())).get("jobId").asInt();
        assertThat(jobB).isNotEqualTo(jobA);
        // Neither replay tells anything about the other client's job.
        assertThat(json(apiKeyPost(a, V1 + "/records", "shared-key", body)).get("jobId").asInt())
                .isEqualTo(jobA);
        assertThat(json(apiKeyPost(b, V1 + "/records", "shared-key", body)).get("jobId").asInt())
                .isEqualTo(jobB);
        // ... and B cannot read A's job with its own credential (same bank, other client: the
        // job belongs to A's owner, so B gets the bank-scoped answer, never a cross-client leak).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM IdempotencyKey WHERE BankId = ?",
                Integer.class, bank.getBankId())).isEqualTo(2);
    }

    /** S26: an expired client cannot get a token (and its API key is refused). */
    @Test
    void s26AnExpiredClientGetsNoToken() throws Exception {
        Client oauth = client("OAUTH2_CLIENT_CREDENTIALS", List.of("INTEGRATION_INGEST"));
        jdbc.update("UPDATE ApiClient SET ExpiresAt = ? WHERE ApiClientId = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(5)), oauth.id());
        mvc.perform(post(TOKEN).header("Authorization", basic(oauth))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        assertThat(lastAuthFailureReason(oauth.id())).contains("EXPIRED");
    }

    /**
     * S27: a wrong API-key secret, an unknown key prefix and a malformed key
     * get the SAME 401 body (no oracle); so do an unknown client id and a wrong
     * secret at the token endpoint. Every failure is audited.
     */
    @Test
    void s27AuthenticationFailuresAreIndistinguishable() throws Exception {
        Client key = client("API_KEY", List.of("INTEGRATION_INGEST"));
        String prefix = key.secret().substring(0, key.secret().indexOf('.'));
        List<String> bodies = new ArrayList<>();
        for (String presented : List.of(prefix + ".not-the-secret", "mdk_UNKNOWNPREFIX0000.secret",
                "no-dot-at-all")) {
            String body = mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + presented))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            bodies.add(withoutRequestId(body));
        }
        assertThat(bodies).as("same body for every API-key failure").containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).doesNotContain(prefix).doesNotContain("not-the-secret");

        Client oauth = client("OAUTH2_CLIENT_CREDENTIALS", List.of("INTEGRATION_INGEST"));
        String wrongSecret = mvc.perform(post(TOKEN).header("Authorization", basic(oauth.clientId(), "wrong"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post(TOKEN).header("Authorization", basic("mdc_nobody", "wrong"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(withoutRequestId(unknown)).isEqualTo(withoutRequestId(wrongSecret));
        assertThat(lastAuthFailureReason(key.id())).contains("BAD_SECRET");
        assertThat(lastAuthFailureReason(oauth.id())).contains("BAD_SECRET");
    }

    /** S35: results are masked per field classification, capped, and documents must be released. */
    @Test
    void s35ResultsAreMaskedCappedAndOnlyReleasedDocumentsExist() throws Exception {
        Client client = client("API_KEY", List.of("INTEGRATION_INGEST", "INTEGRATION_RESULTS",
                "INTEGRATION_DOCS"));
        int one = json(apiKeyPost(client, V1 + "/records", "r-1",
                records(List.of(record("Ada Lovelace", "123456789", "10"))))
                .andExpect(status().isAccepted())).get("jobId").asInt();
        awaitValidated(client, one);
        String results = mvc.perform(get(V1 + "/jobs/" + one + "/results")
                        .header("Authorization", "ApiKey " + client.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(results).doesNotContain("Ada Lovelace").doesNotContain("123456789");
        assertThat(auditCount("EXPORT_RUN")).isGreaterThanOrEqualTo(1);

        // Above the API-client cap (1 row here): refused, nothing returned.
        int two = json(apiKeyPost(client, V1 + "/records", "r-2",
                records(List.of(record("Bob Stone", "987654321", "20"),
                        record("Cy Young", "555555555", "30"))))
                .andExpect(status().isAccepted())).get("jobId").asInt();
        awaitValidated(client, two);
        String capped = mvc.perform(get(V1 + "/jobs/" + two + "/results")
                        .header("Authorization", "ApiKey " + client.secret()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPORT_CAP_EXCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(capped).doesNotContain("Bob Stone").doesNotContain("987654321");

        // Documents: a SUBMITTED one does not exist for the API; a RELEASED one does.
        int submitted = document(DocInstance.STATUS_SUBMITTED);
        int released = document(DocInstance.STATUS_RELEASED);
        mvc.perform(get(V1 + "/documents/" + submitted).header("Authorization", "ApiKey " + client.secret()))
                .andExpect(status().isNotFound());
        mvc.perform(get(V1 + "/documents/" + released).header("Authorization", "ApiKey " + client.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    /** S42: an agent's client cannot use the integration API; a bank system's client cannot speak the agent protocol. */
    @Test
    void s42AgentAndSystemClientsStayInTheirLanes() throws Exception {
        jdbc.update("INSERT INTO IntegrationAgent (BankId, Name, Status, CreatedByUserId, CreatedAt) "
                        + "VALUES (?, ?, 'PENDING_ENROLMENT', ?, ?)", bank.getBankId(), "qa-agent",
                admin.getAppUserId(), java.sql.Timestamp.from(Instant.now()));
        int agentId = jdbc.queryForObject("SELECT IntegrationAgentId FROM IntegrationAgent WHERE BankId = ? "
                + "AND Name = 'qa-agent'", Integer.class, bank.getBankId());
        ApiClientIssuer.Issued issued = apiClientIssuer.issueAgentClient(bank.getBankId(), agentId,
                "qa-agent", ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, null);
        jdbc.update("UPDATE IntegrationAgent SET ApiClientId = ?, Status = 'ONLINE', EnrolledAt = ? "
                + "WHERE IntegrationAgentId = ?", issued.apiClientId(), java.sql.Timestamp.from(Instant.now()),
                agentId);
        String agentToken = token(issued.clientId(), issued.secret());
        for (String path : List.of(V1 + "/templates", V1 + "/events", V1 + "/jobs/1")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + agentToken))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/integrations/connections").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());

        Client system = client("OAUTH2_CLIENT_CREDENTIALS", List.of("INTEGRATION_INGEST",
                "INTEGRATION_RESULTS", "INTEGRATION_EVENTS"));
        String systemToken = token(system.clientId(), system.secret());
        mvc.perform(get("/api/agent/v1/tasks?wait=0").header("Authorization", "Bearer " + systemToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"9.9.9\"}"))
                .andExpect(status().isForbidden());
        // A scope beyond the client's own can never be requested.
        mvc.perform(post(TOKEN).header("Authorization", basic(system))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&scope=AGENT_CONNECT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    /**
     * S41: guessing enrolment tokens gets one uniform 401 and, past the
     * per-address budget (10 per 15 min), 429 — even for a well-formed token
     * of a real pending agent.
     */
    @Test
    void s41EnrolmentGuessesAreUniformAndRateLimited() throws Exception {
        jdbc.update("INSERT INTO IntegrationAgent (BankId, Name, Status, CreatedByUserId, CreatedAt, "
                        + "EnrolmentTokenHash, EnrolmentExpiresAt) VALUES (?, 'qa-pending', 'PENDING_ENROLMENT', ?, ?, ?, ?)",
                bank.getBankId(), admin.getAppUserId(), java.sql.Timestamp.from(Instant.now()),
                "0".repeat(64), java.sql.Timestamp.from(Instant.now().plusSeconds(900)));
        int agentId = jdbc.queryForObject("SELECT IntegrationAgentId FROM IntegrationAgent WHERE BankId = ? "
                + "AND Name = 'qa-pending'", Integer.class, bank.getBankId());
        List<String> bodies = new ArrayList<>();
        int limited = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            String guess = "mda1." + agentId + "." + UUID.randomUUID().toString().replace("-", "")
                    + "AbCdEfGhIjK";
            var response = mvc.perform(post("/api/agent/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + guess + "\",\"name\":\"x\",\"version\":\"1.0.0\","
                                    + "\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\"}"))
                    .andReturn().getResponse();
            if (response.getStatus() == 429) {
                limited++;
            } else {
                assertThat(response.getStatus()).isEqualTo(401);
                bodies.add(withoutRequestId(response.getContentAsString()));
            }
        }
        assertThat(bodies).isNotEmpty().containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).contains("AGENT_ENROLMENT_INVALID");
        assertThat(limited).as("attempts beyond the per-IP budget").isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT Status FROM IntegrationAgent WHERE IntegrationAgentId = ?",
                String.class, agentId)).isEqualTo("PENDING_ENROLMENT");
    }

    /**
     * S45: claims cannot be forged into privileges — a tampered token fails
     * its signature; a (validly signed) client token that also claims the
     * ADMIN role stays a client; a user token claiming typ=client is not a
     * client.
     */
    @Test
    void s45ForgedClaimsGrantNothing() throws Exception {
        // Tampered payload, original signature: 401.
        String userToken = jwtService.issueToken(admin);
        String[] parts = userToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"ADMIN\"", "\"PLATFORM_ADMIN\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());

        // A real client token plus roles=[ADMIN]: the role claim is ignored.
        Client client = client("OAUTH2_CLIENT_CREDENTIALS", List.of("INTEGRATION_INGEST"));
        String issued = token(client.clientId(), client.secret());
        Map<String, Object> claims = new LinkedHashMap<>(decode(issued));
        claims.put("roles", List.of("ADMIN"));
        claims.put("sub", String.valueOf(admin.getAppUserId()));
        String clientAsAdmin = sign(claims);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + clientAsAdmin))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(get("/api/admin/integrations/api-clients").header("Authorization", "Bearer " + clientAsAdmin))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        // A user token with typ=client added (and no client of that id): not a client, not a user.
        Map<String, Object> userClaims = new LinkedHashMap<>(decode(userToken));
        userClaims.put("typ", "client");
        String userAsClient = sign(userClaims);
        mvc.perform(get(V1 + "/templates").header("Authorization", "Bearer " + userAsClient))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + userAsClient))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    /** S46: a client holding only INTEGRATION_RESULTS cannot post records or files. */
    @Test
    void s46ScopesAreNotWidenedByTheRoute() throws Exception {
        Client results = client("API_KEY", List.of("INTEGRATION_RESULTS"));
        apiKeyPost(results, V1 + "/records", "s46", records(List.of(record("Ada", "1", "1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
        mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + results.secret()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM IngestionJob WHERE BankId = ?",
                Integer.class, bank.getBankId())).isZero();
    }

    // ===================================================== outbound and masking

    /** S33: switching the bank's outbound masking default off is a SECURITY change (202). */
    @Test
    void s33TurningTheMaskingDefaultOffNeedsASecondAdmin() throws Exception {
        String body = "{\"changes\":[{\"key\":\"" + SettingKeys.INTEGRATION_OUTBOUND_PII_MASKING
                + "\",\"value\":false,\"expectedVersion\":0}],\"comment\":\"QA S33\"}";
        JsonNode submitted = json(mvc.perform(put("/api/admin/settings")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()));
        assertThat(submitted.get("applied")).isEmpty();
        assertThat(submitted.get("pendingChange").get("changeType").asText()).isEqualTo("SETTING_UPDATE");
        // Until approved, a new flow still masks.
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        JsonNode flow = json(postAs(admin, FLOWS, webhookFlow(hook, List.of("JOB_STATUS")))
                .andExpect(status().isCreated()));
        assertThat(flow.get("maskPii").asBoolean()).isTrue();
    }

    /** S37: widening an ENABLED outbound flow (payload kinds, row cap) asks a second admin again. */
    @Test
    void s37WideningAnEnabledOutboundFlowNeedsApprovalAgain() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        Map<String, Object> request = webhookFlow(hook, List.of("JOB_STATUS"));
        request.put("rowCap", 10);
        long flowId = json(postAs(admin, FLOWS, request).andExpect(status().isCreated())).get("id").asLong();
        enableWithSecondAdmin(flowId);

        // Another payload kind.
        JsonNode enabled = json(getAs(admin, FLOWS + "/" + flowId));
        request.put("payloadKinds", List.of("JOB_STATUS", "VALIDATED_RECORDS"));
        request.put("expectedVersion", enabled.get("version").asInt());
        putAs(admin, FLOWS + "/" + flowId, request).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("INTEGRATION_FLOW_ENABLE"));
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isFalse();

        // Approve it, then point it at another target path: approval again.
        long pending = json(getAs(admin, FLOWS + "/" + flowId)).get("pendingChangeId").asLong();
        postAs(secondAdmin, "/api/admin/changes/" + pending + "/approve", null).andExpect(status().isOk());
        JsonNode again = json(getAs(admin, FLOWS + "/" + flowId));
        assertThat(again.get("enabled").asBoolean()).isTrue();
        request.put("targetSpec", Map.of("path", "elsewhere"));
        request.put("expectedVersion", again.get("version").asInt());
        putAs(admin, FLOWS + "/" + flowId, request).andExpect(status().isAccepted());
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isFalse();
    }

    /**
     * BUG-08 (P3): raising (or clearing) the row cap of an ENABLED outbound
     * flow is applied directly (200) and the flow keeps sending; the security
     * plan (S37) lists "raise the row cap" as a widening that needs a second
     * admin. IntegrationFlowUseCase.Snapshot#widenedBy does not look at the
     * row cap. The bank's integration.maxRowsPerRun and export caps still
     * bound a run. Repro: enable an outbound flow with rowCap 10 through
     * maker/checker, then PUT the flow with rowCap 10000: 200, still enabled.
     */
    @Test
    void bug08RaisingTheRowCapIsAWidening() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        Map<String, Object> request = webhookFlow(hook, List.of("JOB_STATUS"));
        request.put("rowCap", 10);
        long flowId = json(postAs(admin, FLOWS, request).andExpect(status().isCreated())).get("id").asLong();
        enableWithSecondAdmin(flowId);
        JsonNode enabled = json(getAs(admin, FLOWS + "/" + flowId));
        request.put("rowCap", 10_000);
        request.put("expectedVersion", enabled.get("version").asInt());
        putAs(admin, FLOWS + "/" + flowId, request).andExpect(status().isAccepted());
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isFalse();
    }

    /** S38: an approval of an outbound enable whose flow was edited meanwhile is refused. */
    @Test
    void s38AStaleEnableApprovalIsRefused() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        Map<String, Object> request = webhookFlow(hook, List.of("JOB_STATUS"));
        long flowId = json(postAs(admin, FLOWS, request).andExpect(status().isCreated())).get("id").asLong();
        long changeId = json(postAs(admin, FLOWS + "/" + flowId + "/enable", null)
                .andExpect(status().isAccepted())).get("pendingChangeId").asLong();

        // The maker edits the (still disabled) flow after asking: its version moves.
        JsonNode current = json(getAs(admin, FLOWS + "/" + flowId));
        request.put("targetSpec", Map.of("path", "elsewhere"));
        request.put("expectedVersion", current.get("version").asInt());
        ResultActions edited = putAs(admin, FLOWS + "/" + flowId, request);
        assertThat(edited.andReturn().getResponse().getStatus()).isIn(200, 202, 409);

        if (edited.andReturn().getResponse().getStatus() != 409) {
            postAs(secondAdmin, "/api/admin/changes/" + changeId + "/approve", null)
                    .andExpect(status().isConflict());
        }
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isFalse();
        assertThat(auditCount("INTEGRATION_FLOW_ENABLED")).isZero();
    }

    /**
     * S24: a delivery that keeps failing (here: the egress guard refuses the
     * private webhook receiver) ends DEAD, is audited INTEGRATION_DELIVERY_DEAD
     * and raises the HIGH alert integration-dead-letter.
     */
    @Test
    void s24ADeadLetterRaisesTheHighAlert() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        IntegrationFlow flow = flow("status", hook, FlowDirection.OUTBOUND, FlowTrigger.EVENT,
                Map.of("path", "events"));
        flow.setEventSet(EnumSet.of(FlowEvent.INGESTION_VALIDATED));
        flow.setPayloadKindSet(EnumSet.of(PayloadKind.JOB_STATUS));
        flow.setEnabled(true);
        flows.save(flow);
        TenantContext.runAs(bank.getBankId(), (Runnable) () -> recordsIngestion.createFromRecords(
                new RecordsIngestion.Command(bank.getBankId(), admin.getAppUserId(), templateId, "qa-s24",
                        List.of(Map.of("CustomerName", "Ada Lovelace", "AccountNo", "123456789",
                                "Amount", "10")), "api-client:qa")));
        for (int attempt = 0; attempt < 12; attempt++) {
            jdbc.update("UPDATE OutboundDelivery SET NextAttemptAt = ? WHERE BankId = ? AND Status = 'PENDING'",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), bank.getBankId());
            TenantContext.runAs(bank.getBankId(), (Runnable) () -> dispatcher.dispatchDue(bank.getBankId()));
        }
        var rows = deliveries.findAll().stream().filter(d -> d.getBankId() == bank.getBankId()).toList();
        assertThat(rows).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DEAD);
            assertThat(d.getLastError()).contains("egress");
        });
        assertThat(stubRequests).as("the refused receiver was never called").isEmpty();
        assertThat(auditCount("INTEGRATION_DELIVERY_DEAD")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM AlertLog WHERE BankId = ? AND RuleName = 'integration-dead-letter'",
                Integer.class, bank.getBankId()) >= 1);
        assertThat(jdbc.queryForObject("SELECT Severity FROM AlertLog WHERE BankId = ? "
                + "AND RuleName = 'integration-dead-letter'", String.class, bank.getBankId())).isEqualTo("HIGH");

        // Replay from the run detail ("Retry"): once the platform approved the
        // receiver's address, the SAME delivery (same idempotency key) goes out.
        approvedRule("127.0.0.1", stub.getAddress().getPort());
        long runId = rows.get(0).getRunId();
        String key = rows.get(0).getIdempotencyKey();
        postAs(writer, "/api/admin/integrations/runs/" + runId + "/retry", null).andExpect(status().isOk());
        TenantContext.runAs(bank.getBankId(), (Runnable) () -> dispatcher.dispatchDue(bank.getBankId()));
        var replayed = deliveries.findAll().stream().filter(d -> d.getBankId() == bank.getBankId()).toList();
        assertThat(replayed).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).as(d.getLastError()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(d.getIdempotencyKey()).isEqualTo(key);
        });
        assertThat(stubRequests).hasSize(1);
    }

    /**
     * S34: the outbound payload respects the flow's row cap — above it the
     * delivery is refused before anything leaves (no request to the receiver)
     * and the reason is on the delivery.
     */
    @Test
    void s34AnOutboundPayloadAboveTheRowCapNeverLeaves() throws Exception {
        approvedRule("127.0.0.1", stub.getAddress().getPort());
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        IntegrationFlow flow = flow("capped", hook, FlowDirection.OUTBOUND, FlowTrigger.EVENT,
                Map.of("path", "events"));
        flow.setEventSet(EnumSet.of(FlowEvent.INGESTION_VALIDATED));
        flow.setPayloadKindSet(EnumSet.of(PayloadKind.VALIDATED_RECORDS));
        flow.setRowCap(1);
        flow.setEnabled(true);
        flows.save(flow);
        TenantContext.runAs(bank.getBankId(), (Runnable) () -> recordsIngestion.createFromRecords(
                new RecordsIngestion.Command(bank.getBankId(), admin.getAppUserId(), templateId, "qa-s34",
                        List.of(Map.of("CustomerName", "Ada Lovelace", "AccountNo", "123456789", "Amount", "10"),
                                Map.of("CustomerName", "Bob Stone", "AccountNo", "987654321", "Amount", "20")),
                        "api-client:qa")));
        for (int attempt = 0; attempt < 12; attempt++) {
            jdbc.update("UPDATE OutboundDelivery SET NextAttemptAt = ? WHERE BankId = ? AND Status = 'PENDING'",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), bank.getBankId());
            TenantContext.runAs(bank.getBankId(), (Runnable) () -> dispatcher.dispatchDue(bank.getBankId()));
        }
        var rows = deliveries.findAll().stream().filter(d -> d.getBankId() == bank.getBankId()).toList();
        assertThat(rows).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isNotEqualTo(DeliveryStatus.DELIVERED);
            assertThat(d.getLastError()).contains("row cap");
        });
        assertThat(stubRequests).as("nothing left the bank").isEmpty();
        assertThat(auditCount("EXPORT_RUN")).isZero();
    }

    /** S27 (alert): ten failed client authentications from one address raise api-client-auth-failures. */
    @Test
    void s27RepeatedClientAuthFailuresRaiseTheAlert() throws Exception {
        Client key = client("API_KEY", List.of("INTEGRATION_INGEST"));
        String prefix = key.secret().substring(0, key.secret().indexOf('.'));
        java.sql.Timestamp since = java.sql.Timestamp.from(Instant.now().minusSeconds(1));
        for (int attempt = 0; attempt < 11; attempt++) {
            mvc.perform(get(V1 + "/templates").header("Authorization", "ApiKey " + prefix + ".wrong-" + attempt))
                    .andExpect(status().isUnauthorized());
        }
        alertSweep.runOnce();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AlertLog WHERE RuleName = 'api-client-auth-failures' "
                + "AND CreatedAt >= ?", Integer.class, since)).isGreaterThanOrEqualTo(1);
    }

    // ================================================================== bugs

    /**
     * BUG-07 (P4): renaming a connection answers 200 with the version from
     * BEFORE the rename (the JPA @Version is bumped at commit, after the view
     * was built). A client that sends that version back as expectedVersion on
     * its next edit gets 409 "changed by someone else". Repro: rename
     * (PUT …/connections/{id} with only a new name) → body.version = v; GET
     * → version = v + 1. Found by the E2E AC-INT-01.
     */
    @Test
    void bug07RenameAnswersTheCommittedVersion() throws Exception {
        IntegrationConnection db = dbConnection();
        JsonNode before = json(getAs(admin, CONNECTIONS + "/" + db.getId()));
        Map<String, Object> rename = new LinkedHashMap<>();
        rename.put("name", "renamed " + UUID.randomUUID().toString().substring(0, 6));
        rename.put("type", "DATABASE");
        rename.put("mode", "DIRECT");
        rename.put("authType", before.get("authType").asText());
        rename.put("config", MAPPER.convertValue(before.get("config"), Map.class));
        rename.put("expectedVersion", before.get("version").asInt());
        JsonNode renamed = json(putAs(admin, CONNECTIONS + "/" + db.getId(), rename)
                .andExpect(status().isOk()));
        JsonNode after = json(getAs(admin, CONNECTIONS + "/" + db.getId()));
        assertThat(renamed.get("version").asInt()).isEqualTo(after.get("version").asInt());
    }

    // =============================================================== helpers

    private record Client(int id, String clientId, String secret) {
    }

    private Client client(String method, List<String> scopes) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", method + " qa " + UUID.randomUUID().toString().substring(0, 6));
        request.put("authMethod", method);
        request.put("scopes", scopes);
        long changeId = json(postAs(admin, CLIENTS, request).andExpect(status().isAccepted()))
                .get("pendingChangeId").asLong();
        postAs(secondAdmin, "/api/admin/changes/" + changeId + "/approve", null).andExpect(status().isOk());
        int id = Integer.parseInt(jdbc.queryForObject(
                "SELECT TargetEntityId FROM PendingChange WHERE PendingChangeId = ?", String.class, changeId));
        JsonNode secret = json(getAs(admin, CLIENTS + "/" + id + "/initial-secret").andExpect(status().isOk()));
        return new Client(id, secret.get("clientId").asText(), secret.get("secret").asText());
    }

    private String token(String clientId, String secret) throws Exception {
        return json(mvc.perform(post(TOKEN).header("Authorization", basic(clientId, secret))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isOk())).get("access_token").asText();
    }

    private static String basic(Client client) {
        return basic(client.clientId(), client.secret());
    }

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions apiKeyPost(Client client, String path, String idempotencyKey, String body)
            throws Exception {
        return mvc.perform(post(path).header("Authorization", "ApiKey " + client.secret())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void awaitValidated(Client client, int jobId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            JsonNode job = json(mvc.perform(get(V1 + "/jobs/" + jobId)
                    .header("Authorization", "ApiKey " + client.secret())));
            return "VALIDATED".equals(job.path("currentStep").asText())
                    || "FAILED".equals(job.path("status").asText());
        });
    }

    private static Map<String, Object> record(String name, String account, String amount) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("CustomerName", name);
        record.put("AccountNo", account);
        record.put("Amount", amount);
        return record;
    }

    private String records(List<Map<String, Object>> rows) throws Exception {
        return MAPPER.writeValueAsString(Map.of("templateId", templateId, "records", rows));
    }

    private String lastAuthFailureReason(int apiClientId) {
        List<String> values = jdbc.queryForList("SELECT NewValues FROM AuditEvent WHERE Action = "
                + "'API_CLIENT_AUTH_FAILED' AND EntityId = ? ORDER BY AuditEventId DESC", String.class,
                String.valueOf(apiClientId));
        return values.isEmpty() ? "" : String.valueOf(values.get(0));
    }

    private static String withoutRequestId(String body) {
        return body.replaceAll("\"requestId\"\\s*:\\s*\"[^\"]*\"", "\"requestId\":\"-\"");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String jwt) throws Exception {
        return MAPPER.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
    }

    private String sign(Map<String, Object> claims) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        claims.forEach((name, value) -> {
            switch (name) {
                case "iat", "exp", "nbf" -> builder.claim(name, Instant.ofEpochSecond(((Number) value).longValue()));
                default -> builder.claim(name, value);
            }
        });
        builder.expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                builder.build())).getTokenValue();
    }

    private IntegrationConnection directRest(String name, String baseUrl) {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bank.getBankId());
        connection.setName(name + " " + UUID.randomUUID().toString().substring(0, 6));
        connection.setType(ConnectionType.REST_API);
        connection.setMode(ConnectionMode.DIRECT);
        connection.setConfigJson(IntegrationJson.write(Map.of("baseUrl", baseUrl)));
        connection.setAuthType("NONE");
        connection.setCreatedBy(admin.getAppUserId());
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    private IntegrationConnection dbConnection() {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bank.getBankId());
        connection.setName("core db " + UUID.randomUUID().toString().substring(0, 6));
        connection.setType(ConnectionType.DATABASE);
        connection.setMode(ConnectionMode.DIRECT);
        connection.setConfigJson(IntegrationJson.write(Map.of("vendor", "POSTGRESQL",
                "host", "db.bank.example", "port", "5432", "database", "core", "username", "reader")));
        connection.setAuthType("BASIC");
        var sealed = vault.seal(bank.getBankId(),
                new com.modocument.application.port.integration.SecretMaterial(Map.of("password", "pw")));
        connection.setSecretCiphertext(sealed.ciphertext());
        connection.setSecretKeyVersion(sealed.keyVersion());
        connection.setCreatedBy(admin.getAppUserId());
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    private IntegrationConnection sftpConnection() {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bank.getBankId());
        connection.setName("sftp " + UUID.randomUUID().toString().substring(0, 6));
        connection.setType(ConnectionType.SFTP);
        connection.setMode(ConnectionMode.DIRECT);
        connection.setConfigJson(IntegrationJson.write(Map.of("host", "sftp.bank.example", "port", "22",
                "username", "bank", "sftpAuth", "PASSWORD", "authType", "BASIC", "folder", "/exports",
                "hostKeyFingerprint", "SHA256:" + "A".repeat(43))));
        connection.setAuthType("BASIC");
        var sealed = vault.seal(bank.getBankId(),
                new com.modocument.application.port.integration.SecretMaterial(Map.of("password", "pw")));
        connection.setSecretCiphertext(sealed.ciphertext());
        connection.setSecretKeyVersion(sealed.keyVersion());
        connection.setCreatedBy(admin.getAppUserId());
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    private Map<String, Object> dbFlow(IntegrationConnection connection, String direction, Map<String, Object> spec) {
        return flowRequest(connection, direction, spec, "INBOUND".equals(direction) ? "sourceSpec" : "targetSpec");
    }

    private Map<String, Object> flowRequest(IntegrationConnection connection, String direction,
                                            Map<String, Object> spec, String specKey) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "qa flow " + UUID.randomUUID().toString().substring(0, 6));
        request.put("direction", direction);
        request.put("connectionId", connection.getId());
        request.put("templateId", templateId);
        request.put("trigger", "MANUAL");
        request.put("payloadKinds", List.of("VALIDATED_RECORDS"));
        request.put(specKey, spec);
        return request;
    }

    private Map<String, Object> webhookFlow(IntegrationConnection hook, List<String> kinds) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "qa outbound " + UUID.randomUUID().toString().substring(0, 6));
        request.put("direction", "OUTBOUND");
        request.put("connectionId", hook.getId());
        request.put("templateId", templateId);
        request.put("trigger", "EVENT");
        request.put("events", List.of("INGESTION_VALIDATED"));
        request.put("payloadKinds", kinds);
        request.put("targetSpec", Map.of("path", "events"));
        return request;
    }

    private void enableWithSecondAdmin(long flowId) throws Exception {
        long changeId = json(postAs(admin, FLOWS + "/" + flowId + "/enable", null)
                .andExpect(status().isAccepted())).get("pendingChangeId").asLong();
        postAs(secondAdmin, "/api/admin/changes/" + changeId + "/approve", null).andExpect(status().isOk());
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isTrue();
    }

    private void approvedRule(String host, int port) {
        BankEgressRule rule = new BankEgressRule();
        rule.setBankId(bank.getBankId());
        rule.setHost(host);
        rule.setPort(port);
        rule.setStatus(EgressRuleStatus.APPROVED);
        rule.setReason("QA S1");
        rule.setRequestedByUserId(admin.getAppUserId());
        rule.setApprovedByUserId(secondAdmin.getAppUserId());
        rule.setCreatedAt(Instant.now());
        rule.setApprovedAt(Instant.now());
        egressRules.save(rule);
        egressGuard.invalidate(bank.getBankId());
    }

    private int document(String status) {
        DocInstance instance = new DocInstance();
        instance.setBankId(bank.getBankId());
        instance.setOwnerUserId(admin.getAppUserId());
        instance.setTitle("QA doc " + status);
        instance.setStatus(status);
        if (DocInstance.STATUS_RELEASED.equals(status)) {
            instance.setReleasedAt(LocalDateTime.now());
            instance.setReleasedByUserId(secondAdmin.getAppUserId());
        }
        instance.setCreatedDate(LocalDateTime.now());
        instance.setUpdatedDate(LocalDateTime.now());
        int id = docInstances.save(instance).getDocInstanceId();
        docs.add(id);
        return id;
    }

    /** Posts a flow; a 2xx is recorded as an accepted (= unsafe) input, anything but 400 fails. */
    private void expectRefused(List<String> accepted, String what, Map<String, Object> request) throws Exception {
        int code = postAs(admin, FLOWS, request).andReturn().getResponse().getStatus();
        if (code < 300) {
            accepted.add(what);
        } else {
            assertThat(code).as(what).isEqualTo(400);
        }
    }

    private int flowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM IntegrationFlow WHERE BankId = ?", Integer.class,
                bank.getBankId());
    }

    private int pendingChangeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?", Integer.class,
                bank.getBankId());
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND Action = ?",
                Integer.class, bank.getBankId(), action);
        return count == null ? 0 : count;
    }
}
