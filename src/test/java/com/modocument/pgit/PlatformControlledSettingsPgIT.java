package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform-controlled settings on the REAL schema with row level security
 * enforced (the app runs as {@code modocument_app}). The platform admins'
 * requests and the approval transaction are bound to the PLATFORM bank,
 * whose tenant can neither see nor write bank A's BankSetting rows — yet a
 * per-bank value must be read (its version) and written in bank A, through
 * the registered {@code SettingChangeService} runAs call site, and then be
 * what bank A's own session and the API-client checks see. Bank B stays
 * untouched. The H2 suite has no RLS.
 */
class PlatformControlledSettingsPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final String KEY = SettingKeys.INTEGRATION_ALLOW_API_KEYS;
    private static final int PLATFORM_A = 926_001;
    private static final int PLATFORM_B = 926_002;
    private static final int ADMIN_A = 926_011;

    private static int platformBankId;
    private static int bankA;
    private static int bankB;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private IntegrationSettings integrationSettings;

    @BeforeAll
    static void seedBanksAndAdmins() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (Code, Name, Kind) VALUES ('" + PLATFORM_CODE
                    + "', 'MO Intelligence Platform', 'PLATFORM') ON CONFLICT DO NOTHING");
            platformBankId = queryInt(owner, "SELECT BankId FROM Bank WHERE Code = ? "
                    + "AND Kind = 'PLATFORM'", PLATFORM_CODE);
            bankA = insertBank(owner, "PCSA" + tag);
            bankB = insertBank(owner, "PCSB" + tag);
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, "
                    + "Disabled, MustChangePassword) VALUES "
                    + user(PLATFORM_A, "pgit-pcs-pa", platformBankId, "PLATFORM_ADMIN") + ", "
                    + user(PLATFORM_B, "pgit-pcs-pb", platformBankId, "PLATFORM_ADMIN") + ", "
                    + user(ADMIN_A, "pgit-pcs-a-" + tag, bankA, "ADMIN")
                    + " ON CONFLICT (AppUserId) DO UPDATE SET BankId = EXCLUDED.BankId");
            statement.execute("DELETE FROM BankSetting WHERE SettingKey = '" + KEY + "' "
                    + "AND BankId = " + platformBankId);
        }
    }

    @Test
    void thePlatformWritesOneBanksValueInsideThatBanksTenant() throws Exception {
        String path = "/api/platform/banks/" + bankA + "/settings";

        // Set: maker, then a second platform admin.
        int first = send(put(path), PLATFORM_A, platformBankId, Role.PLATFORM_ADMIN,
                body("false", 0), 202).get("pendingChange").get("pendingChangeId").asInt();
        assertThat(send(post("/api/admin/changes/" + first + "/approve"), PLATFORM_B,
                platformBankId, Role.PLATFORM_ADMIN, null, 200).get("status").asText())
                .isEqualTo("APPLIED");

        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(queryString(owner, "SELECT ValueText FROM BankSetting "
                    + "WHERE BankId = ? AND SettingKey = ?", bankA, KEY)).isEqualTo("false");
            assertThat(queryInt(owner, "SELECT Version FROM BankSetting "
                    + "WHERE BankId = ? AND SettingKey = ?", bankA, KEY)).isOne();
            assertThat(queryInt(owner, "SELECT count(*) FROM BankSetting "
                    + "WHERE BankId IN (?, ?) AND SettingKey = ?", platformBankId, bankB, KEY))
                    .isZero();
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE Action = "
                    + "'PLATFORM_SETTING_UPDATED' AND BankId = ? AND EntityId = ? "
                    + "AND (NewValues ->> 'bankId')::int = ?", platformBankId, KEY, bankA))
                    .isOne();
        }
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankA))
                .isFalse();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankB))
                .isTrue();

        // Bank A's own session sees it, read-only.
        JsonNode seen = setting(send(get("/api/admin/settings"), ADMIN_A, bankA, Role.ADMIN,
                null, 200));
        assertThat(seen.get("value").asBoolean()).isFalse();
        assertThat(seen.get("source").asText()).isEqualTo("BANK");
        assertThat(seen.get("platformControlled").asBoolean()).isTrue();
        send(put("/api/admin/settings"), ADMIN_A, bankA, Role.ADMIN, body("true", 1), 400);

        // Reset: the submit check reads bank A's row version (1) under bank
        // A's tenant — a platform-tenant read would see no row and refuse
        // the change as stale.
        assertThat(setting(send(get(path), PLATFORM_A, platformBankId, Role.PLATFORM_ADMIN,
                null, 200)).get("version").asInt()).isOne();
        int second = send(put(path), PLATFORM_B, platformBankId, Role.PLATFORM_ADMIN,
                body("null", 1), 202).get("pendingChange").get("pendingChangeId").asInt();
        send(post("/api/admin/changes/" + second + "/approve"), PLATFORM_A, platformBankId,
                Role.PLATFORM_ADMIN, null, 200);
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(queryString(owner, "SELECT ValueText FROM BankSetting "
                    + "WHERE BankId = ? AND SettingKey = ?", bankA, KEY)).isNull();
            assertThat(queryInt(owner, "SELECT Version FROM BankSetting "
                    + "WHERE BankId = ? AND SettingKey = ?", bankA, KEY)).isEqualTo(2);
        }
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankA))
                .isTrue();
    }

    // ------------------------------------------------------------- plumbing

    private static String body(String jsonValue, int expectedVersion) {
        return "{\"changes\":[{\"key\":\"" + KEY + "\",\"value\":" + jsonValue
                + ",\"expectedVersion\":" + expectedVersion + "}]}";
    }

    private static JsonNode setting(JsonNode page) {
        for (JsonNode group : page.get("groups")) {
            for (JsonNode setting : group.get("settings")) {
                if (KEY.equals(setting.get("key").asText())) {
                    return setting;
                }
            }
        }
        throw new AssertionError(KEY + " not on the page");
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int userId, int bankId, Role role,
                          String json, int expectedStatus) throws Exception {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        request.header("Authorization", "Bearer " + jwtService.issueToken(user));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private static String user(int id, String emailPrefix, int bankId, String role) {
        return "(" + id + ", '" + emailPrefix + "@moi.example', '{noop}x', '" + emailPrefix
                + "', '{}', now(), now(), " + bankId + ", '" + role + "', 0, false, false)";
    }

    private static int insertBank(Connection owner, String code) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(
                "INSERT INTO Bank (Code, Name, Kind) VALUES (?, ?, 'CUSTOMER') RETURNING BankId")) {
            statement.setString(1, code);
            statement.setString(2, "Platform-set " + code);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static int queryInt(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static String queryString(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                return rs.getString(1);
            }
        }
    }
}
