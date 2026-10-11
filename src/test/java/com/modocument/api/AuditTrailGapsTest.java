package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.alerting.AlertRule;
import com.modocument.infrastructure.alerting.AlertRuleCatalogue;
import com.modocument.infrastructure.audit.JpaAuditRecorder;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws28 audit gaps (contracts §4): every new action fires ONCE, with the
 * right actor and entity, through the real HTTP path; ACCESS_DENIED is
 * aggregated; the read audits are deduplicated. (DOC_GENERATED,
 * DOC_DOWNLOADED and the extraction-records read audit are pinned at the
 * controller level: DocInstanceControllerAuditTest,
 * ExtractionJobControllerAuditTest.)
 */
class AuditTrailGapsTest extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Ws28-audit-gap-pass!";

    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JpaAuditRecorder recorder;
    @Autowired
    private AlertRuleCatalogue alertRules;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Bank bank;
    private AppUser writer;
    private AppUser admin;

    @BeforeEach
    void seed() {
        bank = newBank("GAP");
        writer = newUser(bank, "gap-writer", Role.READ_WRITE, passwordEncoder.encode(PASSWORD));
        admin = newUser(bank, "gap-admin", Role.ADMIN, null);
    }

    /** One audit row's who/what. */
    record Row(String action, Integer actorUserId, String entityType, String entityId,
               String outcome, String newValues, String ip) {
    }

    private List<Row> rows(String action) {
        return jdbc.query("SELECT Action, ActorUserId, EntityType, EntityId, Outcome, "
                        + "CAST(NewValues AS VARCHAR), Ip FROM AuditEvent WHERE BankId = ? "
                        + "AND Action = ? ORDER BY OccurredAt",
                (rs, i) -> new Row(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                bank.getBankId(), action);
    }

    private Row once(String action) {
        List<Row> rows = rows(action);
        assertThat(rows).as(action + " fires once").hasSize(1);
        return rows.get(0);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private int createTemplateWithFields(AppUser caller) throws Exception {
        String body = """
                {"name": "gap-%s", "fields": [
                  {"fieldName": "Name", "dataType": "Text"},
                  {"fieldName": "City", "dataType": "Text"}]}""".formatted(UUID.randomUUID());
        MvcResult result = mvc.perform(post("/Templates/CreateWithFields")
                        .header("Authorization", bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("templateId").asInt();
    }

    private IngestionJob validatedJob(int templateId) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(writer.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(templateId);
        job.setFileName("gap-" + UUID.randomUUID() + ".csv");
        String table = "{\"columns\":[\"Name\",\"City\"],\"rows\":[{\"Name\":\"Ada\","
                + "\"City\":\"London\"}]}";
        job.setParsedTableJson(table);
        job.setMappingJson("{\"Name\":\"Name\",\"City\":\"City\"}");
        job.setMappedRowsJson(table);
        job.setValidationResultJson("[]");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        return jobs.save(job);
    }

    @Test
    void templateFieldAndRuleChangesAreAudited() throws Exception {
        MvcResult created = mvc.perform(post("/Templates/Create")
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gap-plain-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        int plainId = json(created).get("templateId").asInt();
        int compositeId = createTemplateWithFields(writer);

        List<Row> templates = rows("TEMPLATE_CREATED");
        assertThat(templates).hasSize(2).allSatisfy(row -> {
            assertThat(row.actorUserId()).isEqualTo(writer.getAppUserId());
            assertThat(row.entityType()).isEqualTo("Template");
        });
        assertThat(templates).extracting(Row::entityId)
                .containsExactly(String.valueOf(plainId), String.valueOf(compositeId));
        assertThat(templates.get(1).newValues()).contains("\"fieldCount\":2");

        MvcResult field = mvc.perform(post("/TemplateFields/Create")
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + compositeId
                                + ",\"fieldName\":\"Country\",\"templateDataTypeId\":1}"))
                .andExpect(status().isCreated()).andReturn();
        int fieldId = json(field).get("templateFieldsId").asInt();
        mvc.perform(put("/TemplateFields/Update/" + fieldId)
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateFieldsId\":" + fieldId + ",\"templateId\":"
                                + compositeId + ",\"fieldName\":\"Nation\",\"templateDataTypeId\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/TemplateFields/" + fieldId).header("Authorization", bearer(writer)))
                .andExpect(status().isNoContent());
        for (String action : List.of("TEMPLATE_FIELD_CREATED", "TEMPLATE_FIELD_UPDATED",
                "TEMPLATE_FIELD_DELETED")) {
            Row row = once(action);
            assertThat(row.actorUserId()).isEqualTo(writer.getAppUserId());
            assertThat(row.entityType()).isEqualTo("TemplateFields");
            assertThat(row.entityId()).isEqualTo(String.valueOf(fieldId));
        }
        assertThat(once("TEMPLATE_FIELD_UPDATED").newValues()).contains("Nation");

        String rule = "{\"templateId\":" + compositeId
                + ",\"fieldName\":\"Name\",\"ruleType\":\"REQUIRED\",\"config\":{}}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/ValidationRules/Upsert").header("Authorization", bearer(writer))
                            .contentType(MediaType.APPLICATION_JSON).content(rule))
                    .andExpect(status().isOk());
        }
        mvc.perform(delete("/ValidationRules").param("templateId", String.valueOf(compositeId))
                        .param("fieldName", "Name").param("ruleType", "REQUIRED")
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isNoContent());
        String ruleId = compositeId + ":Name:REQUIRED";
        for (String action : List.of("VALIDATION_RULE_CREATED", "VALIDATION_RULE_UPDATED",
                "VALIDATION_RULE_DELETED")) {
            Row row = once(action);
            assertThat(row.actorUserId()).isEqualTo(writer.getAppUserId());
            assertThat(row.entityType()).isEqualTo("ValidationRule");
            assertThat(row.entityId()).isEqualTo(ruleId);
        }
    }

    @Test
    void uploadsAndIngestionJobCreationAreAudited() throws Exception {
        int templateId = createTemplateWithFields(writer);
        MockMultipartFile csv = new MockMultipartFile("file", "gap-" + UUID.randomUUID() + ".csv",
                "text/csv", "Name,City\nAda,London\n".getBytes());
        MvcResult uploaded = mvc.perform(multipart("/api/fileupload/upload").file(csv)
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andReturn();
        String storedName = json(uploaded).get("fileName").asText();
        Row upload = once("FILE_UPLOADED");
        assertThat(upload.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(upload.entityType()).isEqualTo("UploadedFile");
        assertThat(upload.entityId()).isEqualTo(storedName.length() > 64
                ? storedName.substring(0, 64) : storedName);

        MvcResult job = mvc.perform(post("/api/ingestion/jobs")
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + storedName + "\",\"templateId\":" + templateId
                                + ",\"autoRun\":false}"))
                .andExpect(status().isCreated()).andReturn();
        int jobId = json(job).get("ingestionJobId").asInt();
        Row created = once("INGESTION_JOB_CREATED");
        assertThat(created.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(created.entityType()).isEqualTo("IngestionJob");
        assertThat(created.entityId()).isEqualTo(String.valueOf(jobId));

        // The ingestion multipart upload stores AND creates: one row of each more.
        MockMultipartFile second = new MockMultipartFile("file", "gap-" + UUID.randomUUID() + ".csv",
                "text/csv", "Name,City\nGrace,Arlington\n".getBytes());
        mvc.perform(multipart("/api/ingestion/jobs").file(second)
                        .param("templateId", String.valueOf(templateId))
                        .param("autoRun", "false")
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isCreated());
        assertThat(rows("FILE_UPLOADED")).hasSize(2);
        assertThat(rows("INGESTION_JOB_CREATED")).hasSize(2);
    }

    @Test
    void jobReadsEditsExportsAndRemapsAreAudited() throws Exception {
        IngestionJob job = validatedJob(createTemplateWithFields(writer));
        String path = "/api/ingestion/jobs/" + job.getIngestionJobId();
        String id = String.valueOf(job.getIngestionJobId());

        for (int i = 0; i < 3; i++) {
            mvc.perform(get(path).header("Authorization", bearer(writer)))
                    .andExpect(status().isOk());
        }
        Row viewed = once("SENSITIVE_DATA_VIEWED");
        assertThat(viewed.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(viewed.entityType()).isEqualTo("IngestionJob");
        assertThat(viewed.entityId()).isEqualTo(id);

        mvc.perform(get(path + "/export/file").param("format", "csv")
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isOk());
        Row downloaded = once("EXPORT_DOWNLOADED");
        assertThat(downloaded.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(downloaded.entityId()).isEqualTo(id);
        assertThat(downloaded.newValues()).contains("\"format\":\"CSV\"");

        mvc.perform(put(path + "/rows").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"Name\":\"Ada\",\"City\":\"Paris\"}]}"))
                .andExpect(status().isOk());
        Row edited = once("INGESTION_ROWS_EDITED");
        assertThat(edited.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(edited.entityId()).isEqualTo(id);
        assertThat(edited.newValues()).contains("\"changedRows\":1").doesNotContain("Paris");

        mvc.perform(post(path + "/remap").header("Authorization", bearer(writer)));
        Row remapped = once("INGESTION_REMAPPED");
        assertThat(remapped.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(remapped.entityId()).isEqualTo(id);
    }

    @Test
    void ownerScopedNotFoundProbesAreAggregatedAccessDenials() throws Exception {
        IngestionJob job = validatedJob(createTemplateWithFields(writer));
        AppUser prober = newUser(bank, "gap-prober", Role.READ_WRITE, null);
        String path = "/api/ingestion/jobs/" + job.getIngestionJobId();

        for (int i = 0; i < 4; i++) {
            mvc.perform(get(path).header("Authorization", bearer(prober)))
                    .andExpect(status().isNotFound());
        }
        Row probe = once("ACCESS_DENIED");
        assertThat(probe.actorUserId()).isEqualTo(prober.getAppUserId());
        assertThat(probe.entityType()).isEqualTo("IngestionJob");
        assertThat(probe.entityId()).isEqualTo(String.valueOf(job.getIngestionJobId()));
        assertThat(probe.outcome()).isEqualTo("DENIED");
        assertThat(probe.newValues()).contains("\"kind\":\"NOT_FOUND_PROBE\"")
                .contains("\"count\":1");

        // The three suppressed probes are written as ONE row once the minute is over.
        recorder.flushAggregatedDenials(Instant.now().plusSeconds(120));
        List<Row> rows = rows("ACCESS_DENIED");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).actorUserId()).isEqualTo(prober.getAppUserId());
        assertThat(rows.get(1).newValues()).contains("\"count\":3");
        // The owner never trips it.
        mvc.perform(get(path).header("Authorization", bearer(writer))).andExpect(status().isOk());
        assertThat(rows("ACCESS_DENIED")).hasSize(2);
    }

    @Test
    void aUseCaseAccessDeniedIsAuditedButAPermissionDenialStaysAuthorizationDenied()
            throws Exception {
        mvc.perform(post("/Templates/CreateWithFields").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gap-shared-" + UUID.randomUUID() + "\","
                                + "\"visibility\":\"BANK\",\"fields\":["
                                + "{\"fieldName\":\"A\",\"dataType\":\"Text\"}]}"))
                .andExpect(status().isForbidden());
        Row denied = once("ACCESS_DENIED");
        assertThat(denied.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(denied.entityType()).isEqualTo("Route");
        assertThat(denied.entityId()).isEqualTo("/Templates/CreateWithFields");
        assertThat(denied.newValues()).contains("\"kind\":\"USE_CASE\"");

        // A @RequiresPermission refusal is the listener's AUTHORIZATION_DENIED, not a second row.
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden());
        assertThat(rows("ACCESS_DENIED")).hasSize(1);
        assertThat(rows("AUTHORIZATION_DENIED")).hasSize(1);
    }

    @Test
    void anInvalidBearerTokenIsAuditedPerAddressAndAMissingOneIsNot() throws Exception {
        String ip = "10.28." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/admin/users").header("Authorization", "Bearer not.a.jwt")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized());
        }
        String anonymousIp = ip + "1";
        mvc.perform(get("/api/admin/users").with(request -> {
            request.setRemoteAddr(anonymousIp);
            return request;
        })).andExpect(status().isUnauthorized());

        List<String> rows = jdbc.queryForList("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent "
                + "WHERE Action = 'ACCESS_DENIED' AND Ip = ?", String.class, ip);
        assertThat(rows).as("aggregated: one row per address per minute").hasSize(1);
        assertThat(rows.get(0)).contains("\"kind\":\"INVALID_TOKEN\"").contains("/api/admin/users")
                .doesNotContain("not.a.jwt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                + "'ACCESS_DENIED' AND Ip = ?", Integer.class, anonymousIp)).isZero();
    }

    @Test
    void aSuccessfulRefreshIsAudited() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + writer.getEmail() + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("modoc_refresh");
        assertThat(cookie).isNotNull();
        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isOk());

        Row refreshed = once("TOKEN_REFRESHED");
        assertThat(refreshed.actorUserId()).isEqualTo(writer.getAppUserId());
        assertThat(refreshed.entityType()).isEqualTo("RefreshTokenFamily");
        assertThat(refreshed.outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void theUserListAndDsrSearchAreReadAuditedOncePerHour() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
        Row users = once("SENSITIVE_DATA_VIEWED");
        assertThat(users.actorUserId()).isEqualTo(admin.getAppUserId());
        assertThat(users.entityType()).isEqualTo("AppUserList");

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/admin/dsr/search").header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"identifier\":\"subject-" + bank.getCode() + "@example.com\"}"))
                    .andExpect(status().isOk());
        }
        List<Row> reads = rows("SENSITIVE_DATA_VIEWED");
        assertThat(reads).hasSize(2);
        assertThat(reads.get(1).entityType()).isEqualTo("DataSubject");
        assertThat(reads.get(1).actorUserId()).isEqualTo(admin.getAppUserId());
        assertThat(reads.get(1).entityId()).doesNotContain("@");
    }

    @Test
    void theAccessDeniedBurstRuleIsInTheCatalogue() {
        AlertRule rule = alertRules.all().stream()
                .filter(r -> r.name().equals("access-denied-burst")).findFirst().orElseThrow();
        assertThat(rule.trigger()).isEqualTo(AlertRule.Trigger.WINDOWED);
        assertThat(rule.severity().name()).isEqualTo("MEDIUM");
        assertThat(rule.actions()).containsExactly("ACCESS_DENIED");
        assertThat(rule.groupBy()).isEqualTo(AlertRule.GroupBy.ACTOR_OR_IP);
    }
}
