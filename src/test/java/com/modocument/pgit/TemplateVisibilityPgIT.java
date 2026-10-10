package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Bank-shared templates (ws22, V220) on the REAL PostgreSQL schema with row
 * level security enforced (app on {@code modocument_app}): sharing widens a
 * template to its own bank and NEVER beyond — the visibility predicate pins
 * the bank, and RLS on Template still hides every other bank's rows even
 * when they are shared. Also proves the V220 column default and CHECK.
 */
class TemplateVisibilityPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BANK_A = 330;
    private static final int BANK_B = 430;
    private static final int OWNER_A = 9330;     // ADMIN, owns both bank-A templates
    private static final int WRITER_A = 9331;    // READ_WRITE colleague
    private static final int ADMIN_B = 9430;     // ADMIN of bank B
    private static final int PRIVATE_A = 7350;
    private static final int SHARED_A = 7351;
    private static final int SHARED_B = 7450;

    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            for (String sql : SEED) {
                statement.execute(sql);
            }
        }
        seeded = true;
    }

    @Test
    void aSameBankColleagueReadsTheSharedTemplateButNotThePrivateOne() throws Exception {
        assertThat(status(WRITER_A, BANK_A, Role.READ_WRITE,
                get("/Templates/GetById/" + SHARED_A))).isEqualTo(200);
        assertThat(status(WRITER_A, BANK_A, Role.READ_WRITE,
                get("/TemplateFields/GetAll?templateId=" + SHARED_A))).isEqualTo(200);
        assertThat(status(WRITER_A, BANK_A, Role.READ_WRITE,
                get("/Templates/GetById/" + PRIVATE_A))).isEqualTo(404);
        assertThat(listedIds(WRITER_A, BANK_A, Role.READ_WRITE))
                .contains(SHARED_A).doesNotContain(PRIVATE_A, SHARED_B);
        // Read, not write: the colleague holds no template:publish.
        assertThat(status(WRITER_A, BANK_A, Role.READ_WRITE,
                json(put("/Templates/Update"), "{\"templateId\":" + SHARED_A
                        + ",\"name\":\"Renamed\",\"description\":\"x\"}"))).isEqualTo(403);
    }

    @Test
    void anotherBankNeverSeesASharedTemplate() throws Exception {
        assertThat(status(ADMIN_B, BANK_B, Role.ADMIN, get("/Templates/GetById/" + SHARED_A)))
                .isEqualTo(404);
        assertThat(status(ADMIN_B, BANK_B, Role.ADMIN,
                get("/TemplateFields/GetAll?templateId=" + SHARED_A))).isEqualTo(404);
        assertThat(status(ADMIN_B, BANK_B, Role.ADMIN,
                json(put("/Templates/" + SHARED_A + "/Visibility"), "{\"visibility\":\"PRIVATE\"}")))
                .isEqualTo(404);
        assertThat(listedIds(ADMIN_B, BANK_B, Role.ADMIN))
                .contains(SHARED_B).doesNotContain(SHARED_A, PRIVATE_A);
    }

    @Test
    void rowLevelSecurityStillHidesOtherBanksSharedRows() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false); // set_config(..., true) is tx-scoped
            setTenant(app, String.valueOf(BANK_B));
            assertThat(count(app, "SELECT count(*) FROM template WHERE templateid = " + SHARED_A))
                    .as("a BANK-shared row of bank A is invisible to tenant B").isZero();
            assertThat(count(app, "SELECT count(*) FROM template WHERE visibility = 'BANK'"
                    + " AND bankid <> " + BANK_B))
                    .as("no other bank's shared template is visible at all").isZero();
            assertThat(count(app, "SELECT count(*) FROM template WHERE templateid = " + SHARED_B))
                    .isOne();
            try (Statement statement = app.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE template SET visibility = 'PRIVATE'"
                        + " WHERE templateid = " + SHARED_A)).isZero();
            }
            app.rollback();
        }
    }

    @Test
    void theColumnDefaultsToPrivateAndOnlyAcceptsTheVocabulary() throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                // A previous-release INSERT (no Visibility column) lands PRIVATE.
                statement.execute("INSERT INTO Template (TemplateId, Name, Description,"
                        + " OwnerUserId, BankId) VALUES (7352, 'Old release', 'x', "
                        + OWNER_A + ", " + BANK_A + ")");
                try (ResultSet rs = statement.executeQuery(
                        "SELECT Visibility FROM Template WHERE TemplateId = 7352")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("PRIVATE");
                }
            }
            assertThatThrownBy(() -> {
                try (Statement statement = owner.createStatement()) {
                    statement.execute("UPDATE Template SET Visibility = 'PUBLIC'"
                            + " WHERE TemplateId = 7352");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("ck_template_visibility");
            owner.rollback();
        }
    }

    @Test
    void theDashboardCountsPrivateAndSharedTemplatesPerBank() throws Exception {
        JsonNode body = MAPPER.readTree(mvc.perform(withToken(get("/api/admin/dashboard"),
                        OWNER_A, BANK_A, Role.ADMIN))
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("templates").get("private").asLong()).isEqualTo(1);
        assertThat(body.get("templates").get("shared").asLong()).isEqualTo(1);
    }

    // ----------------------------------------------------------- helpers

    private int status(int userId, int bankId, Role role, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(withToken(request, userId, bankId, role))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> listedIds(int userId, int bankId, Role role) throws Exception {
        JsonNode list = MAPPER.readTree(mvc.perform(withToken(get("/Templates/GetAll"),
                        userId, bankId, role))
                .andReturn().getResponse().getContentAsString());
        List<Integer> ids = new ArrayList<>();
        list.forEach(node -> ids.add(node.get("templateId").asInt()));
        return ids;
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request,
                                                    int userId, int bankId, Role role) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            try (ResultSet ignored = statement.executeQuery()) {
                // set_config returns the value; nothing to assert.
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static final String USER_COLUMNS = "INSERT INTO AppUsers (AppUserId, Email, "
            + "PasswordHash, DisplayName, SettingsJson, CreatedDate, UpdatedDate, BankId, Role, "
            + "TokenVersion, Disabled, MustChangePassword) VALUES ";

    private static final String[] SEED = {
            "INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(330, 'TVIS_A', 'Visibility Bank A'), (430, 'TVIS_B', 'Visibility Bank B') "
                    + "ON CONFLICT DO NOTHING",
            USER_COLUMNS
                    + "(9330, 'tvis-a-owner@example.com', '{noop}x', 'Tvis A Owner', '{}', now(), now(), 330, 'ADMIN', 0, false, false), "
                    + "(9331, 'tvis-a-writer@example.com', '{noop}x', 'Tvis A Writer', '{}', now(), now(), 330, 'READ_WRITE', 0, false, false), "
                    + "(9430, 'tvis-b-admin@example.com', '{noop}x', 'Tvis B Admin', '{}', now(), now(), 430, 'ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO Template (TemplateId, Name, Description, OwnerUserId, BankId, Visibility)"
                    + " VALUES "
                    + "(7350, 'Tvis Private A', 'seed', 9330, 330, 'PRIVATE'), "
                    + "(7351, 'Tvis Shared A', 'seed', 9330, 330, 'BANK'), "
                    + "(7450, 'Tvis Shared B', 'seed', 9430, 430, 'BANK') "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId,"
                    + " BankId) VALUES (7360, 7351, 'Amount', 1, 330) ON CONFLICT DO NOTHING",
    };
}
