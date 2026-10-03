package com.modocument.pgit;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE C1 TENANT-ISOLATION GATE: signed in as one bank, every request that
 * uses another bank's IDs is refused — proven over the real HTTP stack
 * (MockMvc with the production security filter chain, app booted on the
 * RLS-subject {@code modocument_app} datasource) AND at the SQL layer with
 * direct JDBC against the row-level-security policies.
 *
 * <p>Fixture: bank A (100) and bank B (200), an ADMIN user in each (ADMIN so
 * any 403/404 below is tenancy, never a missing permission), and one seeded
 * resource per aggregate kind for bank A. Seeding runs as the schema OWNER,
 * which bypasses RLS — itself part of the control assertions.
 */
class TenantIsolationGatePgIT extends PgSpringItSupport {

    private static final int BANK_A = 100;
    private static final int BANK_B = 200;
    private static final int USER_A = 9100;
    private static final int USER_B = 9200;

    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;

    private String bankAToken;
    private String bankBToken;

    @BeforeAll
    static void seedBothBanksAsOwner() throws Exception {
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

    @BeforeEach
    void issueTokens() {
        bankAToken = token(USER_A, BANK_A);
        bankBToken = token(USER_B, BANK_B);
    }

    private String token(int userId, int bankId) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(Role.ADMIN);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    // ------------------------------------------------- HTTP layer (MockMvc)

    @Test
    void everyIdTakingRouteRefusesForeignBankIds() throws Exception {
        // Bank B's ADMIN probing every aggregate with bank A's IDs.
        RequestBuilder[] probes = {
                // Template aggregate (+ fields + rules)
                get("/Templates/GetById/7100").header("Authorization", bankBToken),
                put("/Templates/Update").header("Authorization", bankBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":7100,\"name\":\"pwned\",\"description\":\"x\"}"),
                delete("/Templates/7100").header("Authorization", bankBToken),
                get("/TemplateFields/GetById/7101").header("Authorization", bankBToken),
                delete("/TemplateFields/7101").header("Authorization", bankBToken),
                get("/ValidationRules/GetByTemplate/7100").header("Authorization", bankBToken),
                // Import history + details
                get("/ImportHistory/GetById/7110").header("Authorization", bankBToken),
                delete("/ImportHistory/7110").header("Authorization", bankBToken),
                get("/ImportDetails/GetById/7111").header("Authorization", bankBToken),
                delete("/ImportDetails/7111").header("Authorization", bankBToken),
                // Ingestion job (read, actions, export)
                get("/api/ingestion/jobs/7120").header("Authorization", bankBToken),
                post("/api/ingestion/jobs/7120/cancel").header("Authorization", bankBToken),
                put("/api/ingestion/jobs/7120/mapping").header("Authorization", bankBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"a\":\"b\"}}"),
                get("/api/ingestion/jobs/7120/export/file").header("Authorization", bankBToken),
                // Extraction source + job
                get("/api/extraction/sources/7130").header("Authorization", bankBToken),
                delete("/api/extraction/sources/7130").header("Authorization", bankBToken),
                get("/api/extraction/jobs/7140").header("Authorization", bankBToken),
                post("/api/extraction/jobs/7140/start").header("Authorization", bankBToken),
                get("/api/extraction/jobs/7140/records").header("Authorization", bankBToken),
                get("/api/extraction/jobs/7140/audit").header("Authorization", bankBToken),
                // Doc creation blueprint + instance
                get("/api/doccreation/blueprints/7150").header("Authorization", bankBToken),
                get("/api/doccreation/blueprints/7150/file").header("Authorization", bankBToken),
                delete("/api/doccreation/blueprints/7150").header("Authorization", bankBToken),
                get("/api/doccreation/instances/7160").header("Authorization", bankBToken),
                post("/api/doccreation/instances/7160/generate").header("Authorization", bankBToken),
                get("/api/doccreation/instances/7160/download").header("Authorization", bankBToken),
                // Uploaded file (referenced by stored name)
                post("/api/transform/transform").header("Authorization", bankBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"gate-a.csv\"}"),
                post("/api/ingestion/jobs").header("Authorization", bankBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"gate-a.csv\",\"templateId\":7200,\"autoRun\":false}"),
        };
        for (RequestBuilder probe : probes) {
            assertRefusedWithoutLeaking(probe);
        }

        // And the mirror direction: bank A probing bank B's template.
        assertRefusedWithoutLeaking(
                get("/Templates/GetById/7200").header("Authorization", bankAToken));
    }

    @Test
    void ownBankKeepsWorking() throws Exception {
        // The 403/404s above are tenancy, not blanket denial: the same
        // routes answer for the caller's OWN bank.
        MvcResult own = mvc.perform(get("/Templates/GetById/7200")
                        .header("Authorization", bankBToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(own.getResponse().getContentAsString()).contains("Gate B Template");

        MvcResult all = mvc.perform(get("/Templates/GetAll")
                        .header("Authorization", bankBToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(all.getResponse().getContentAsString())
                .contains("Gate B Template")
                .doesNotContain("Gate A");

        // A legitimate write passes the policies' WITH CHECK end to end.
        mvc.perform(post("/Templates/Create").header("Authorization", bankBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gate B created\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
    }

    private void assertRefusedWithoutLeaking(RequestBuilder probe) throws Exception {
        MvcResult result = mvc.perform(probe).andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("%s %s with a foreign bank's id must be refused",
                        result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isIn(403, 404);
        assertThat(result.getResponse().getContentAsString())
                .as("the refusal must not leak bank A's data")
                .doesNotContain("Gate A");
    }

    // ------------------------------------------------ SQL layer (RLS proof)

    @Test
    void appRoleWithTenantBSeesAndTouchesZeroBankARows() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false); // set_config(..., true) is tx-scoped
            setTenant(app, String.valueOf(BANK_B));

            assertThat(count(app, "SELECT count(*) FROM template WHERE bankid = " + BANK_A))
                    .as("tenant B must see zero bank-A templates").isZero();
            assertThat(count(app, "SELECT count(*) FROM template"))
                    .as("tenant B sees only its own templates")
                    .isEqualTo(count(app, "SELECT count(*) FROM template WHERE bankid = " + BANK_B));
            assertThat(count(app, "SELECT count(*) FROM uploadedfile")).isZero();
            assertThat(count(app, "SELECT count(*) FROM bank"))
                    .as("even the Bank registry row is tenant-scoped").isOne();

            // USING proof: an UPDATE/DELETE against a bank-A row matches
            // nothing — it is invisible, not merely protected.
            try (Statement statement = app.createStatement()) {
                assertThat(statement.executeUpdate(
                        "UPDATE template SET name = 'pwned' WHERE templateid = 7100")).isZero();
                assertThat(statement.executeUpdate(
                        "DELETE FROM template WHERE templateid = 7100")).isZero();
            }

            // WITH CHECK proof: inserting a row stamped with the OTHER bank
            // is rejected outright.
            assertThatThrownBy(() -> {
                try (Statement statement = app.createStatement()) {
                    statement.executeUpdate("INSERT INTO template "
                            + "(templateid, name, description, owneruserid, bankid) "
                            + "VALUES (7999, 'smuggled', 'x', " + USER_B + ", " + BANK_A + ")");
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");

            app.rollback();
        }
    }

    @Test
    void withoutATenantEveryTenantTableFailsClosed() throws Exception {
        List<String> tenantTables = tenantTables();
        assertThat(tenantTables).hasSizeGreaterThanOrEqualTo(20);
        try (Connection app = appDataSource().getConnection()) {
            // No app.tenant bound at all: NULL predicate, zero rows.
            for (String table : tenantTables) {
                assertThat(count(app, "SELECT count(*) FROM " + table))
                        .as("unbound tenant must read ZERO rows from %s", table)
                        .isZero();
            }
            // The empty string (what the transaction manager binds when the
            // thread has no TenantContext) behaves the same.
            app.setAutoCommit(false);
            setTenant(app, "");
            assertThat(count(app, "SELECT count(*) FROM template")).isZero();
            app.rollback();
        }
    }

    @Test
    void systemWildcardAndOwnerSeeEverything() throws Exception {
        // '*' is what TenantContext.runAsSystem binds: cross-tenant on purpose.
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, "*");
            assertThat(count(app, "SELECT count(*) FROM template WHERE bankid = " + BANK_A)).isOne();
            assertThat(count(app, "SELECT count(*) FROM template WHERE bankid = " + BANK_B))
                    .isGreaterThanOrEqualTo(1);
            app.rollback();
        }
        // Control: as the owner the bank-A rows exist and are untouched by
        // every refused probe above.
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM template WHERE bankid = " + BANK_A)).isOne();
            try (Statement statement = owner.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT name FROM template WHERE templateid = 7100")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Gate A Template");
            }
            assertThat(count(owner, "SELECT count(*) FROM uploadedfile WHERE bankid = " + BANK_A)).isOne();
        }
    }

    // ------------------------------------------------------------- plumbing

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

    private static List<String> tenantTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.table_name
                     FROM information_schema.columns c
                     JOIN pg_catalog.pg_tables t
                       ON t.schemaname = c.table_schema AND t.tablename = c.table_name
                     WHERE c.table_schema = 'public'
                       AND lower(c.column_name) = 'bankid'
                       AND lower(c.table_name) <> 'appusers'
                     ORDER BY c.table_name
                     """)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /** Idempotent owner-side fixture: two banks, two admins, bank A owning one resource per aggregate. */
    private static final String[] SEED = {
            "INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(100, 'GATE_A', 'Gate Bank A'), (200, 'GATE_B', 'Gate Bank B') "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, SettingsJson, "
                    + "CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(9100, 'gate-a@example.com', '{noop}x', 'Gate A Admin', '{}', now(), now(), 100, 'ADMIN', 0), "
                    + "(9200, 'gate-b@example.com', '{noop}x', 'Gate B Admin', '{}', now(), now(), 200, 'ADMIN', 0) "
                    + "ON CONFLICT DO NOTHING",
            // Template aggregate: template + field + validation rule (A), mirror template (B).
            "INSERT INTO Template (TemplateId, Name, Description, OwnerUserId, BankId) VALUES "
                    + "(7100, 'Gate A Template', 'seed', 9100, 100), "
                    + "(7200, 'Gate B Template', 'seed', 9200, 200) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId, BankId) "
                    + "VALUES (7101, 7100, 'FieldA', 1, 100) ON CONFLICT DO NOTHING",
            "INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson, BankId) "
                    + "VALUES (7100, 'FieldA', 1, '{}', 100) ON CONFLICT DO NOTHING",
            "INSERT INTO ImportHistory (ImportHistoryId, ImportDate, FileName, CompanyId, TemplateId, "
                    + "OwnerUserId, BankId) VALUES (7110, now(), 'gate-a.csv', 1, 7100, 9100, 100) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO ImportDetails (ImportDetailsId, ImportHistoryId, ProcessStepId, RawJson, BankId) "
                    + "VALUES (7111, 7110, 1, '{}', 100) ON CONFLICT DO NOTHING",
            "INSERT INTO IngestionJob (IngestionJobId, TemplateId, FileName, CurrentStep, Status, "
                    + "CreatedDate, UpdatedDate, OwnerUserId, BankId) "
                    + "VALUES (7120, 7100, 'gate-a.csv', 'UPLOADED', 'PENDING', now(), now(), 9100, 100) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO ExtractionSource (ExtractionSourceId, OwnerUserId, Name, Type, "
                    + "TermsAcknowledged, BankId) VALUES (7130, 9100, 'Gate A Source', 'WEBSITE', true, 100) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO ExtractionJob (ExtractionJobId, OwnerUserId, SourceId, Status, Incremental, BankId) "
                    + "VALUES (7140, 9100, '7130', 'PENDING', false, 100) ON CONFLICT DO NOTHING",
            "INSERT INTO DocBlueprint (DocBlueprintId, OwnerUserId, Name, FileName, FlowKey, "
                    + "CreatedDate, UpdatedDate, BankId) "
                    + "VALUES (7150, 9100, 'Gate A Blueprint', 'gate-a-bp.docx', 'DEFAULT', now(), now(), 100) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO DocBlueprintSection (DocBlueprintSectionId, BlueprintId, OrderIndex, Title, "
                    + "HeadingLevel, AnchorParagraphIndex, BankId) "
                    + "VALUES (7151, 7150, 0, 'Gate A Section', 1, 0, 100) ON CONFLICT DO NOTHING",
            "INSERT INTO DocInstance (DocInstanceId, OwnerUserId, BlueprintId, Title, Status, BankId) "
                    + "VALUES (7160, 9100, 7150, 'Gate A Doc', 'DRAFT', 100) ON CONFLICT DO NOTHING",
            "INSERT INTO UploadedFile (UploadedFileId, StoredName, OriginalName, OwnerUserId, BankId, "
                    + "SizeBytes) VALUES (7170, 'gate-a.csv', 'gate-a.csv', 9100, 100, 3) "
                    + "ON CONFLICT DO NOTHING",
    };
}
