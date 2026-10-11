package com.modocument.api;

import com.modocument.testsupport.H2ExportTargets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate D2 (PRIV-002): a 10,000-row export by a normal user is BLOCKED and
 * alerted within 5 minutes.
 *
 * <p>A READ_WRITE user (cap 5,000) owns a validated 10,000-row job:
 * <ul>
 *   <li>file export -> 403 {code: EXPORT_CAP_EXCEEDED, cap: 5000, rows: 10000},
 *       an EXPORT_BLOCKED (DENIED) audit row, and an IMMEDIATE AlertLog row
 *       (export-blocked, HIGH) whose CreatedAt is within seconds of the
 *       audit OccurredAt — the "< 5 minutes" evidence — delivered to the
 *       webhook; nothing is exported (no EXPORT_RUN);</li>
 *   <li>the same export with a stated reason succeeds: all 10,000 rows,
 *       EXPORT_OVER_CAP_WITH_REASON (the reason verbatim) + EXPORT_RUN +
 *       an informational export-over-cap alert;</li>
 *   <li>an ADMIN (cap 50,000) exports 10,000 rows normally;</li>
 *   <li>the database export path is capped identically (the target table is
 *       never created without a reason);</li>
 *   <li>an over-long reason is a 400, not a silent truncation.</li>
 * </ul>
 */
class ExportCapGateD2Test extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ROWS = 10_000;
    private static final String REASON = "Quarterly Bank of Mauritius regulatory return, ticket CHG-4411";

    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsJpaRepository templateFields;
    @Autowired
    private IngestionJobJpaRepository jobs;

    private Bank bank;
    private AppUser writer;
    private AppUser admin;

    @BeforeEach
    void seed() {
        // PostgreSQL stores the job checkpoints as `text`; the H2 test schema
        // maps @JdbcTypeCode(LONGVARCHAR) to VARCHAR(32600), too small for a
        // 10,000-row table. Widen it for this suite (idempotent; harmless to
        // every other suite sharing the in-memory database).
        jdbc.execute("ALTER TABLE IngestionJob ALTER COLUMN MappedRowsJson "
                + "SET DATA TYPE CHARACTER VARYING(10000000)");
        jdbc.execute("ALTER TABLE IngestionJob ALTER COLUMN ParsedTableJson "
                + "SET DATA TYPE CHARACTER VARYING(10000000)");
        bank = newBank("D2");
        writer = newUser(bank, "d2-writer", Role.READ_WRITE, null);
        admin = newUser(bank, "d2-admin", Role.ADMIN, null);
    }

    /** A validated, export-ready job of ROWS rows owned by {@code owner}. */
    private IngestionJob bigJob(AppUser owner) {
        Template template = new Template();
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setName("d2-template-" + tag());
        template = templates.save(template);

        TemplateFields field = new TemplateFields();
        field.setTemplateId(template.getTemplateId());
        field.setBankId(bank.getBankId());
        field.setFieldName("City");
        field.setTemplateDataTypeId(1);
        templateFields.save(field);

        StringBuilder rows = new StringBuilder("{\"columns\":[\"City\"],\"rows\":[");
        for (int i = 0; i < ROWS; i++) {
            rows.append(i == 0 ? "" : ",").append("{\"City\":\"Port Louis ").append(i).append("\"}");
        }
        rows.append("]}");

        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName("d2-" + tag() + ".csv");
        job.setParsedTableJson(rows.toString());
        job.setMappedRowsJson(rows.toString());
        job.setValidationResultJson("[]");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        return jobs.save(job);
    }

    private String filePath(IngestionJob job) {
        return "/api/ingestion/jobs/" + job.getIngestionJobId() + "/export/file";
    }

    @Test
    void tenThousandRowExportByAReadWriteUserIsBlockedAuditedAndAlertedWithinSeconds()
            throws Exception {
        IngestionJob job = bigJob(writer);

        MvcResult refused = mvc.perform(get(filePath(job)).param("format", "csv")
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = MAPPER.readTree(refused.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("EXPORT_CAP_EXCEEDED");
        assertThat(body.get("cap").asInt()).isEqualTo(5_000);
        assertThat(body.get("rows").asInt()).isEqualTo(ROWS);
        assertThat(body.get("reasonMaxLength").asInt()).isEqualTo(500);

        // Audited: EXPORT_BLOCKED (DENIED) naming the job, the counts and the actor.
        List<AuditRow> blocked = auditRows(bank, "EXPORT_BLOCKED");
        assertThat(blocked).hasSize(1);
        JsonNode values = MAPPER.readTree(blocked.get(0).newValues());
        assertThat(values.get("rows").asInt()).isEqualTo(ROWS);
        assertThat(values.get("cap").asInt()).isEqualTo(5_000);
        assertThat(values.get("role").asText()).isEqualTo("READ_WRITE");
        Map<String, Object> actor = jdbc.queryForMap(
                "SELECT ActorUserId, Outcome, EntityType, EntityId FROM AuditEvent "
                        + "WHERE AuditEventId = ?", blocked.get(0).id());
        assertThat(actor.get("ACTORUSERID")).isEqualTo(writer.getAppUserId());
        assertThat(actor.get("OUTCOME")).isEqualTo("DENIED");
        assertThat(actor.get("ENTITYID")).isEqualTo(String.valueOf(job.getIngestionJobId()));

        // Alerted IMMEDIATELY (no sweep), from that very audit row.
        AlertLog alert = awaitAlert(bank, "export-blocked");
        assertThat(alert.getAuditEventId()).isEqualTo(blocked.get(0).id());
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(Duration.between(blocked.get(0).occurredAt(), alert.getCreatedAt()))
                .as("blocked -> alerted latency; the gate allows 5 minutes")
                .isLessThan(Duration.ofSeconds(30));
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !webhookAlerts(bank, "export-blocked").isEmpty());

        // Nothing was exported.
        assertThat(auditRows(bank, "EXPORT_RUN")).isEmpty();
        assertThat(jobs.findById(job.getIngestionJobId()).orElseThrow().getCurrentStep())
                .isEqualTo(JobStep.VALIDATED);
    }

    @Test
    void theSameExportWithAStatedReasonSucceedsAndIsRecordedAndAlerted() throws Exception {
        IngestionJob job = bigJob(writer);

        MvcResult result = mvc.perform(post(filePath(job))
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("format", "csv", "reason", REASON))))
                .andExpect(status().isOk())
                .andReturn();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).as("header + every row").isEqualTo(ROWS + 1);

        List<AuditRow> overCap = auditRows(bank, "EXPORT_OVER_CAP_WITH_REASON");
        assertThat(overCap).hasSize(1);
        JsonNode values = MAPPER.readTree(overCap.get(0).newValues());
        assertThat(values.get("reason").asText()).isEqualTo(REASON);
        assertThat(values.get("rows").asInt()).isEqualTo(ROWS);

        List<AuditRow> runs = auditRows(bank, "EXPORT_RUN");
        assertThat(runs).hasSize(1);
        JsonNode run = MAPPER.readTree(runs.get(0).newValues());
        assertThat(run.get("rowCount").asInt()).isEqualTo(ROWS);
        assertThat(run.get("format").asText()).isEqualTo("CSV");
        assertThat(run.get("overCap").asBoolean()).isTrue();
        assertThat(auditRows(bank, "EXPORT_BLOCKED")).isEmpty();

        AlertLog alert = awaitAlert(bank, "export-over-cap");
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.INFO);
        assertThat(alert.getSummary()).as("the free-text reason never enters an alert")
                .doesNotContain("CHG-4411");

        // The GET form accepts the reason as a parameter too (API clients).
        mvc.perform(get(filePath(job)).param("format", "json").param("reason", REASON)
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminUnderItsCapExportsNormally() throws Exception {
        IngestionJob job = bigJob(admin);

        mvc.perform(get(filePath(job)).param("format", "csv").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        List<AuditRow> runs = auditRows(bank, "EXPORT_RUN");
        assertThat(runs).hasSize(1);
        JsonNode run = MAPPER.readTree(runs.get(0).newValues());
        assertThat(run.get("rowCount").asInt()).isEqualTo(ROWS);
        assertThat(run.get("cap").asInt()).isEqualTo(50_000);
        assertThat(run.get("overCap").asBoolean()).isFalse();
        assertThat(auditRows(bank, "EXPORT_BLOCKED")).isEmpty();
        assertThat(auditRows(bank, "EXPORT_OVER_CAP_WITH_REASON")).isEmpty();
    }

    @Test
    void theDatabaseExportPathIsCappedToo() throws Exception {
        IngestionJob job = bigJob(writer);
        String database = "ws18d2db" + job.getIngestionJobId();
        String targetDb = H2ExportTargets.h2Url(database);
        String dbPath = "/api/ingestion/jobs/" + job.getIngestionJobId() + "/export/database";

        MvcResult refused = mvc.perform(post(dbPath)
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                Map.of("vendor", "POSTGRESQL", "host", "localhost", "database", database,
                                        "tableName", "CappedRows"))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(MAPPER.readTree(refused.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("EXPORT_CAP_EXCEEDED");
        assertThat(auditRows(bank, "EXPORT_BLOCKED")).hasSize(1);
        try (Connection connection = DriverManager.getConnection(targetDb);
             ResultSet tables = connection.getMetaData().getTables(null, null, "CappedRows", null)) {
            assertThat(tables.next()).as("the target table was never touched").isFalse();
        }
        awaitAlert(bank, "export-blocked");

        mvc.perform(post(dbPath)
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("vendor", "POSTGRESQL",
                                "host", "localhost", "database", database,
                                "tableName", "CappedRows", "reason", REASON))))
                .andExpect(status().isOk());
        try (Connection connection = DriverManager.getConnection(targetDb);
             ResultSet count = connection.createStatement()
                     .executeQuery("SELECT COUNT(*) FROM \"CappedRows\"")) {
            assertThat(count.next()).isTrue();
            assertThat(count.getInt(1)).isEqualTo(ROWS);
        }
        List<AuditRow> runs = auditRows(bank, "EXPORT_RUN");
        assertThat(runs).hasSize(1);
        JsonNode run = MAPPER.readTree(runs.get(0).newValues());
        assertThat(run.get("type").asText()).isEqualTo("database");
        assertThat(run.get("format").asText()).as("target description, never credentials")
                .doesNotContain("password");
    }

    @Test
    void anOverLongReasonIsRejectedNotTruncated() throws Exception {
        IngestionJob job = bigJob(writer);

        mvc.perform(get(filePath(job)).param("format", "csv").param("reason", "x".repeat(501))
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(filePath(job))
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                Map.of("format", "csv", "reason", "x".repeat(501)))))
                .andExpect(status().isBadRequest());
        assertThat(auditRows(bank, "EXPORT_RUN")).isEmpty();
        assertThat(auditRows(bank, "EXPORT_OVER_CAP_WITH_REASON")).isEmpty();
    }
}
