package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Machine identity on the REAL schema with row level security ENFORCED (app
 * on {@code modocument_app}, ws26): the ApiClient/ApiClientSecret lookup that
 * authenticates a client happens BEFORE any tenant is bound — it must work
 * through ApiClientAuthenticator's registered runAsSystem call site (RLS
 * would otherwise return zero rows and every client would be a 401). Also
 * proves under RLS: the maker/checker create (insert WITH CHECK, sealed
 * initial secret), the OAuth2 token + validator branch, the per-call audit and
 * LastUsedAt written under the client's tenant, idempotency rows, revocation,
 * and cross-bank 404.
 */
class ApiClientAuthPgIT extends PgSpringItSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BANK_A = 2610;
    private static final int BANK_B = 2620;
    private static final int MAKER_A = 92610;
    private static final int CHECKER_A = 92611;
    private static final int MAKER_B = 92620;
    private static final int CHECKER_B = 92621;
    private static final int TEMPLATE_A = 72610;
    private static final int TEMPLATE_B = 72620;
    private static final int JOB_A = 72611;

    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SecretVault vault;
    @MockitoBean
    private RecordsIngestion recordsIngestion;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        String users = "INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, Disabled, "
                + "MustChangePassword) VALUES ";
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(2610, 'WS26_A', 'Machine Bank A'), (2620, 'WS26_B', 'Machine Bank B') "
                    + "ON CONFLICT DO NOTHING");
            statement.execute(users
                    + "(92610, 'ws26-maker-a@example.com', '{noop}x', 'A1', '{}', now(), now(), 2610, 'ADMIN', 0, false, false), "
                    + "(92611, 'ws26-checker-a@example.com', '{noop}x', 'A2', '{}', now(), now(), 2610, 'ADMIN', 0, false, false), "
                    + "(92620, 'ws26-maker-b@example.com', '{noop}x', 'B1', '{}', now(), now(), 2620, 'ADMIN', 0, false, false), "
                    + "(92621, 'ws26-checker-b@example.com', '{noop}x', 'B2', '{}', now(), now(), 2620, 'ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO Template (TemplateId, Name, Description, OwnerUserId, "
                    + "BankId, Visibility) VALUES "
                    + "(72610, 'WS26 Shared A', 'seed', 92610, 2610, 'BANK'), "
                    + "(72620, 'WS26 Shared B', 'seed', 92620, 2620, 'BANK') "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO IngestionJob (IngestionJobId, TemplateId, FileName, "
                    + "CurrentStep, Status, CreatedDate, UpdatedDate, OwnerUserId, BankId) VALUES "
                    + "(72611, 72610, 'a.csv', 'UPLOADED', 'PENDING', now(), now(), 92610, 2610) "
                    + "ON CONFLICT DO NOTHING");
        }
        seeded = true;
    }

    @BeforeEach
    void transparentVault() {
        when(vault.seal(anyInt(), any())).thenAnswer(inv -> new SecretVault.Sealed(
                Base64.getEncoder().encodeToString(((SecretMaterial) inv.getArgument(1))
                        .get("clientSecret").getBytes(StandardCharsets.UTF_8)), 1));
        when(vault.open(anyInt(), any())).thenAnswer(inv -> new SecretMaterial(Map.of(
                "clientSecret", new String(Base64.getDecoder().decode(
                        ((SecretVault.Sealed) inv.getArgument(1)).ciphertext()),
                        StandardCharsets.UTF_8))));
    }

    @Test
    void apiKeyClientsAuthenticateUnderRlsAndSeeOnlyTheirBank() throws Exception {
        String keyA = create(MAKER_A, CHECKER_A, BANK_A, "API_KEY",
                List.of("INTEGRATION_INGEST", "INTEGRATION_RESULTS"))[1];
        String keyB = create(MAKER_B, CHECKER_B, BANK_B, "API_KEY",
                List.of("INTEGRATION_RESULTS"))[1];

        mvc.perform(get("/api/integration/v1/templates").header("Authorization", "ApiKey " + keyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(1))
                .andExpect(jsonPath("$.templates[0].templateId").value(TEMPLATE_A));
        mvc.perform(get("/api/integration/v1/jobs/" + JOB_A).header("Authorization", "ApiKey " + keyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_A));
        // Bank B's client: bank A's job does not exist for it.
        mvc.perform(get("/api/integration/v1/jobs/" + JOB_A).header("Authorization", "ApiKey " + keyB))
                .andExpect(status().isNotFound());

        // The audit rows and LastUsedAt landed under the client's own tenant.
        assertThat(count("SELECT count(*) FROM AuditEvent WHERE Action = 'INTEGRATION_API_CALL' "
                + "AND BankId = " + BANK_A)).isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM ApiClient WHERE BankId = " + BANK_A
                + " AND LastUsedAt IS NOT NULL")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void oauthTokensWorkUnderRlsUntilRevoked() throws Exception {
        String[] created = create(MAKER_A, CHECKER_A, BANK_A, "OAUTH2_CLIENT_CREDENTIALS",
                List.of("INTEGRATION_INGEST"));
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (created[0] + ":" + created[1]).getBytes(StandardCharsets.UTF_8));
        String token = JSON.readTree(mvc.perform(post("/api/integration/oauth/token")
                        .header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("access_token").asText();
        mvc.perform(get("/api/integration/v1/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Idempotency rows are written under the client's tenant too.
        when(recordsIngestion.createFromRecords(any())).thenReturn(4711);
        String body = "{\"templateId\":" + TEMPLATE_A + ",\"records\":[{\"a\":\"1\"}]}";
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/integration/v1/records").header("Authorization", "ApiKey none")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        String[] ingest = create(MAKER_A, CHECKER_A, BANK_A, "API_KEY", List.of("INTEGRATION_INGEST"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/integration/v1/records")
                            .header("Authorization", "ApiKey " + ingest[1])
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value(4711));
        }
        assertThat(count("SELECT count(*) FROM IdempotencyKey WHERE BankId = " + BANK_A
                + " AND ResponseStatus = 202")).isGreaterThanOrEqualTo(1);

        int id = Integer.parseInt(created[2]);
        mvc.perform(post("/api/admin/integrations/api-clients/" + id + "/revoke")
                        .header("Authorization", bearer(CHECKER_A, BANK_A)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/integration/v1/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** Maker/checker create under RLS; returns {clientId, secret, apiClientId}. */
    private String[] create(int maker, int checker, int bank, String method, List<String> scopes)
            throws Exception {
        String pending = mvc.perform(post("/api/admin/integrations/api-clients")
                        .header("Authorization", bearer(maker, bank))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name",
                                method + "-" + UUID.randomUUID().toString().substring(0, 6),
                                "authMethod", method, "scopes", scopes))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        int changeId = JSON.readTree(pending).path("pendingChangeId").asInt();
        JsonNode applied = JSON.readTree(mvc.perform(post("/api/admin/changes/" + changeId
                        + "/approve").header("Authorization", bearer(checker, bank)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String id = applied.path("targetEntityId").asText();
        JsonNode secret = JSON.readTree(mvc.perform(get("/api/admin/integrations/api-clients/"
                        + id + "/initial-secret").header("Authorization", bearer(maker, bank)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return new String[] {secret.path("clientId").asText(), secret.path("secret").asText(), id};
    }

    private String bearer(int userId, int bankId) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(Role.ADMIN);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    private static long count(String sql) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             PreparedStatement statement = owner.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
