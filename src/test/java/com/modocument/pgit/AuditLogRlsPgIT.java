package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditLogReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ws28 audit viewer on the REAL schema with row level security enforced
 * (app booted on {@code modocument_app}): a bank admin's
 * {@code /api/admin/audit} and its CSV export return only that bank's rows,
 * the reader asked for ANOTHER bank under a bank's tenant gets nothing (RLS
 * is a second boundary under the explicit bank predicate), and the
 * platform's counts-only read sees every bank through its registered
 * {@code runAsSystem} call site.
 */
class AuditLogRlsPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final int BANK_A = 328;
    private static final int BANK_B = 428;
    private static final int ADMIN_A = 9528;
    private static final int ADMIN_B = 9628;
    private static final int PLATFORM_ADMIN = 9728;

    private static int platformBankId;
    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AuditLogReader reader;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(328, 'AUDIT_A', 'Audit Bank A'), (428, 'AUDIT_B', 'Audit Bank B') "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO Bank (Code, Name, Kind) VALUES ('" + PLATFORM_CODE
                    + "', 'MO Intelligence Platform', 'PLATFORM') ON CONFLICT DO NOTHING");
            try (ResultSet rs = statement.executeQuery("SELECT BankId FROM Bank WHERE Code = '"
                    + PLATFORM_CODE + "' AND Kind = 'PLATFORM'")) {
                assertThat(rs.next()).isTrue();
                platformBankId = rs.getInt(1);
            }
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, "
                    + "Disabled, MustChangePassword) VALUES "
                    + "(9528, 'audit-a-admin@example.com', '{noop}x', 'A', '{}', now(), now(), 328, 'ADMIN', 0, false, false), "
                    + "(9628, 'audit-b-admin@example.com', '{noop}x', 'B', '{}', now(), now(), 428, 'ADMIN', 0, false, false), "
                    + "(9728, 'audit-platform@moi.example', '{noop}x', 'P', '{}', now(), now(), "
                    + platformBankId + ", 'PLATFORM_ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, ActorUserId, "
                    + "Action, EntityType, EntityId, Outcome, NewValues) VALUES "
                    + "(gen_random_uuid(), now(), 328, 9528, 'RLS_AUDIT_A', 'Template', 'a1', 'SUCCESS', '{\"bank\":\"A\"}'), "
                    + "(gen_random_uuid(), now(), 328, 9528, 'RLS_AUDIT_A', 'Template', 'a2', 'DENIED', null), "
                    + "(gen_random_uuid(), now(), 428, 9628, 'RLS_AUDIT_B', 'Template', 'b1', 'SUCCESS', '{\"bank\":\"B\"}'), "
                    + "(gen_random_uuid(), now(), 428, 9628, 'RLS_AUDIT_B', 'Template', 'b2', 'FAILED', null), "
                    + "(gen_random_uuid(), now(), 428, 9628, 'RLS_AUDIT_B', 'Template', 'b3', 'FAILED', null)");
        }
        seeded = true;
    }

    private String bearer(int userId, int bankId, Role role) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    private JsonNode list(int userId, int bankId) throws Exception {
        String json = mvc.perform(get("/api/admin/audit")
                        .param("action", "RLS_AUDIT_A", "RLS_AUDIT_B")
                        .header("Authorization", bearer(userId, bankId, Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json);
    }

    private static List<String> entityIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("events").forEach(e -> ids.add(e.get("entityId").asText()));
        return ids;
    }

    @Test
    void eachBankAdminSeesOnlyItsOwnBanksEvents() throws Exception {
        assertThat(entityIds(list(ADMIN_A, BANK_A))).containsExactlyInAnyOrder("a1", "a2");
        assertThat(entityIds(list(ADMIN_B, BANK_B))).containsExactlyInAnyOrder("b1", "b2", "b3");

        String csv = mvc.perform(get("/api/admin/audit/export")
                        .param("action", "RLS_AUDIT_A", "RLS_AUDIT_B")
                        .header("Authorization", bearer(ADMIN_A, BANK_A, Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("RLS_AUDIT_A").doesNotContain("RLS_AUDIT_B");
    }

    @Test
    void underABanksTenantRlsHidesAnotherBanksRowsEvenWhenTheQueryNamesThatBank()
            throws Exception {
        AuditLogReader.Query bankB = new AuditLogReader.Query(BANK_B, null, null,
                List.of("RLS_AUDIT_B"), null, null, null, null, null, null, null);
        List<AuditEvent> fromA = TenantContext.runAs(BANK_A, () -> reader.page(bankB, 50));
        long countFromA = TenantContext.runAs(BANK_A, () -> reader.count(bankB));
        assertThat(fromA).isEmpty();
        assertThat(countFromA).isZero();

        List<AuditEvent> fromB = TenantContext.runAs(BANK_B, () -> reader.page(bankB, 50));
        assertThat(fromB).hasSize(3);
    }

    @Test
    void thePlatformReadsCountsAcrossBanksButACustomerAdminCannot() throws Exception {
        String json = mvc.perform(get("/api/platform/audit/counts")
                        .header("Authorization", bearer(PLATFORM_ADMIN, platformBankId,
                                Role.PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode a = null;
        JsonNode b = null;
        for (JsonNode bank : MAPPER.readTree(json).get("banks")) {
            if (bank.get("bankId").asInt() == BANK_A) {
                a = bank;
            } else if (bank.get("bankId").asInt() == BANK_B) {
                b = bank;
            }
        }
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(a.get("denied").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(b.get("failed").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(json).doesNotContain("RLS_AUDIT").doesNotContain("a1");

        mvc.perform(get("/api/platform/audit/counts")
                        .header("Authorization", bearer(ADMIN_A, BANK_A, Role.ADMIN)))
                .andExpect(status().isForbidden());
        // The platform's own list stays the platform bank's: no customer rows.
        String own = mvc.perform(get("/api/platform/audit")
                        .param("action", "RLS_AUDIT_A", "RLS_AUDIT_B")
                        .header("Authorization", bearer(PLATFORM_ADMIN, platformBankId,
                                Role.PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(own).get("events")).isEmpty();
    }
}
