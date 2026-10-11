package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.SettingsCache;
import com.modocument.infrastructure.transform.code.ExtractionCorpus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws23 on the REAL PostgreSQL schema (V230 via Flyway, app on
 * {@code modocument_app} with row level security enforced): the extraction
 * mode, the field hints and the parse provenance are stored in the V230
 * columns, the CHECK constraints hold, provenance is tenant-scoped like the
 * job itself, and a bank whose {@code ai.enabled} setting is off gets its
 * table from code with an {@code INGESTION_PARSE_FALLBACK} audit row in its
 * own bank.
 */
class IngestionFallbackPgIT extends PgSpringItSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BANK_A = 2310;
    private static final int BANK_B = 2320;
    private static final int USER_A = 92310;
    private static final int USER_B = 92320;

    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SettingsCache settingsCache;

    @TempDir
    Path documents;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(" + BANK_A + ", 'WS23_A', 'Extraction Bank A'), "
                    + "(" + BANK_B + ", 'WS23_B', 'Extraction Bank B') ON CONFLICT DO NOTHING");
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, Disabled, "
                    + "MustChangePassword) VALUES "
                    + "(" + USER_A + ", 'ws23-a@example.com', '{noop}x', 'WS23 A', '{}', now(), now(), "
                    + BANK_A + ", 'READ_WRITE', 0, false, false), "
                    + "(" + USER_B + ", 'ws23-b@example.com', '{noop}x', 'WS23 B', '{}', now(), now(), "
                    + BANK_B + ", 'READ_WRITE', 0, false, false) ON CONFLICT DO NOTHING");
        }
        seeded = true;
    }

    private String bearer(int userId, int bankId) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(Role.READ_WRITE);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    private int statementTemplate(String auth, String extractionMode) throws Exception {
        String body = """
                {"name": "ws23-pg-%s", "extractionMode": %s, "fields": [
                  {"fieldName": "Account Number", "dataType": "Text"},
                  {"fieldName": "Transaction Date", "dataType": "Date", "dateFormats": ["dd/MM/yyyy"]},
                  {"fieldName": "Description", "dataType": "Text"},
                  {"fieldName": "Amount", "dataType": "Number"},
                  {"fieldName": "Reference", "dataType": "Text",
                   "labelPattern": "Ref(erence)?\\\\.?", "aliases": ["Ref"]}
                ]}""".formatted(UUID.randomUUID(),
                extractionMode == null ? "null" : "\"" + extractionMode + "\"");
        String response = mvc.perform(post("/Templates/CreateWithFields")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("templateId").asInt();
    }

    private JsonNode upload(String auth, int templateId) throws Exception {
        Path docx = ExtractionCorpus.generate("statement-blocks.docx", documents);
        MockMultipartFile file = new MockMultipartFile("file",
                "statement-" + UUID.randomUUID() + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(docx));
        String response = mvc.perform(multipart("/api/ingestion/jobs").file(file)
                        .param("templateId", String.valueOf(templateId))
                        .param("runUntil", "VALIDATED")
                        .param("wait", "true")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    @Test
    void theV230ColumnsExistWithTheirChecks() throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(columnType(owner, "template", "extractionmode")).isEqualTo("character varying");
            assertThat(columnType(owner, "templatefields", "aliases")).isEqualTo("text");
            assertThat(columnType(owner, "templatefields", "labelpattern")).isEqualTo("character varying");
            assertThat(columnType(owner, "templatefields", "valuepattern")).isEqualTo("character varying");
            assertThat(columnType(owner, "templatefields", "dateformats")).isEqualTo("text");
            assertThat(columnType(owner, "ingestionjob", "parsedby")).isEqualTo("character varying");
            assertThat(columnType(owner, "ingestionjob", "parsestrategy")).isEqualTo("character varying");
            assertThat(columnType(owner, "ingestionjob", "parseconfidence")).isEqualTo("numeric");
            assertThat(columnType(owner, "ingestionjob", "parsefallbackreason"))
                    .isEqualTo("character varying");

            owner.setAutoCommit(false);
            assertThatThrownBy(() -> execute(owner, "INSERT INTO Template (Name, OwnerUserId, BankId, "
                    + "ExtractionMode) VALUES ('ws23-bad-mode', " + USER_A + ", " + BANK_A
                    + ", 'SOMETIMES')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_template_extractionmode");
            owner.rollback();
            assertThatThrownBy(() -> execute(owner, "WITH t AS (INSERT INTO Template (Name, "
                    + "OwnerUserId, BankId) VALUES ('ws23-ck-" + UUID.randomUUID() + "', " + USER_A
                    + ", " + BANK_A + ") RETURNING TemplateId) "
                    + "INSERT INTO IngestionJob (TemplateId, FileName, CurrentStep, Status, "
                    + "CreatedDate, UpdatedDate, OwnerUserId, BankId, ParsedBy) "
                    + "SELECT TemplateId, 'ws23.csv', 'UPLOADED', 'PENDING', now(), now(), "
                    + USER_A + ", " + BANK_A + ", 'HUMAN' FROM t"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_ingestionjob_parsedby");
            owner.rollback();
        }
    }

    @Test
    void codeOnlyProvenanceAndHintsAreStoredAndTenantScoped() throws Exception {
        String auth = bearer(USER_A, BANK_A);
        int templateId = statementTemplate(auth, "CODE_ONLY");

        JsonNode job = upload(auth, templateId);

        assertThat(job.get("currentStep").asText()).as(job.toString()).isEqualTo("VALIDATED");
        assertThat(job.get("parsedBy").asText()).isEqualTo("CODE");
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("MODE_CODE_ONLY");
        int jobId = job.get("ingestionJobId").asInt();

        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(text(owner, "SELECT ExtractionMode FROM Template WHERE TemplateId = " + templateId))
                    .isEqualTo("CODE_ONLY");
            assertThat(text(owner, "SELECT Aliases FROM TemplateFields WHERE TemplateId = "
                    + templateId + " AND FieldName = 'Reference'")).isEqualTo("[\"Ref\"]");
            assertThat(text(owner, "SELECT LabelPattern FROM TemplateFields WHERE TemplateId = "
                    + templateId + " AND FieldName = 'Reference'")).isEqualTo("Ref(erence)?\\.?");
            assertThat(text(owner, "SELECT ParsedBy || '/' || ParseStrategy || '/' || "
                    + "ParseFallbackReason FROM IngestionJob WHERE IngestionJobId = " + jobId))
                    .isEqualTo("CODE/KEY_VALUE/MODE_CODE_ONLY");
            assertThat(text(owner, "SELECT ParseConfidence FROM IngestionJob WHERE IngestionJobId = "
                    + jobId)).matches("0\\.\\d{3}|1\\.000");
        }
        // The provenance lives on the RLS-protected job row: invisible to bank B.
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(BANK_B));
            assertThat(count(app, "SELECT count(*) FROM IngestionJob WHERE IngestionJobId = " + jobId))
                    .isZero();
            assertThat(execute(app, "UPDATE IngestionJob SET ParsedBy = 'AI' WHERE IngestionJobId = "
                    + jobId)).isZero();
            app.rollback();
            setTenant(app, String.valueOf(BANK_A));
            assertThat(text(app, "SELECT ParsedBy FROM IngestionJob WHERE IngestionJobId = " + jobId))
                    .isEqualTo("CODE");
            app.rollback();
        }
    }

    @Test
    void aBankWithAiSwitchedOffGetsCodeAndAFallbackAuditInItsOwnBank() throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            execute(owner, "INSERT INTO BankSetting (BankId, SettingKey, ValueText, Version, UpdatedAt) "
                    + "VALUES (" + BANK_A + ", 'ai.enabled', 'false', 1, now()) "
                    + "ON CONFLICT (BankId, SettingKey) DO UPDATE SET ValueText = 'false'");
        }
        settingsCache.reload();
        try {
            String auth = bearer(USER_A, BANK_A);
            JsonNode job = upload(auth, statementTemplate(auth, null));

            assertThat(job.get("currentStep").asText()).as(job.toString()).isEqualTo("VALIDATED");
            assertThat(job.get("parsedBy").asText()).isEqualTo("CODE");
            assertThat(job.get("parseFallbackReason").asText()).isEqualTo("AI_DISABLED");
            try (Connection owner = ownerDataSource().getConnection()) {
                assertThat(count(owner, "SELECT count(*) FROM AuditEvent WHERE Action = "
                        + "'INGESTION_PARSE_FALLBACK' AND BankId = " + BANK_A + " AND EntityId = '"
                        + job.get("ingestionJobId").asInt() + "'")).isOne();
            }
        } finally {
            try (Connection owner = ownerDataSource().getConnection()) {
                execute(owner, "DELETE FROM BankSetting WHERE BankId = " + BANK_A
                        + " AND SettingKey = 'ai.enabled'");
            }
            settingsCache.reload();
        }
    }

    // ------------------------------------------------------------- plumbing

    private static String columnType(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
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

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
