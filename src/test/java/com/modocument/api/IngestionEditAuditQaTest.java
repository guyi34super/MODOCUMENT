package com.modocument.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.ai.AiCircuitBreaker;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.StubAiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA, optimization round, test plan A6/A7 (contracts §4): the two ingestion
 * edit paths the plan names next to the ones {@code AuditTrailGapsTest}
 * covers. Both change a job's mapped rows and both are UNAUDITED today, so
 * the repros are {@code @Disabled} with their bug ids (docs/qa/optimization-signoff.md):
 * <ul>
 *   <li>BUG-10: {@code PUT /api/ingestion/jobs/{id}/mapping} (the manual
 *       mapping editor) rewrites the mapped rows without an
 *       {@code INGESTION_REMAPPED} row;</li>
 *   <li>BUG-11: {@code POST /api/ingestion/jobs/{id}/chat-edit} (the AI edits
 *       cell values) writes no {@code INGESTION_ROWS_EDITED} row.</li>
 * </ul>
 * Runs in the shared AI-stub context; the chat edit gets a real (loopback)
 * model answer through the whole AI gateway.
 */
class IngestionEditAuditQaTest extends AiStubApiTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "{\"columns\":[\"Name\",\"City\"],"
            + "\"rows\":[{\"Name\":\"Ada\",\"City\":\"London\"},{\"Name\":\"Grace\",\"City\":\"Arlington\"}]}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AiCircuitBreaker circuitBreaker;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUser writer;
    private String auth;

    @BeforeEach
    void freshUserAndStub() {
        AppUser user = new AppUser();
        user.setEmail("qa-edit-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("qa-edit");
        user.setBankId(1);
        user.setRole(Role.READ_WRITE);
        user.setSettingsJson("{}");
        writer = users.save(user);
        auth = "Bearer " + jwtService.issueToken(writer);
        circuitBreaker.reset();
        AI.resetCalls();
    }

    @AfterEach
    void resetStub() {
        AI.mode(StubAiServer.Mode.ERROR_500);
        AI.answer("{}");
    }

    private IngestionJob validatedJob() throws Exception {
        String body = """
                {"name": "qa-edit-%s", "fields": [
                  {"fieldName": "Name", "dataType": "Text"},
                  {"fieldName": "City", "dataType": "Text"}]}""".formatted(UUID.randomUUID());
        String created = mvc.perform(post("/Templates/CreateWithFields").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(writer.getAppUserId());
        job.setBankId(1);
        job.setTemplateId(JSON.readTree(created).get("templateId").asInt());
        job.setFileName("qa-edit-" + UUID.randomUUID() + ".csv");
        job.setParsedTableJson(TABLE);
        job.setMappingJson("{\"Name\":\"Name\",\"City\":\"City\"}");
        job.setMappedRowsJson(TABLE);
        job.setValidationResultJson("[]");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        return jobs.save(job);
    }

    private List<String> audit(String action, IngestionJob job) {
        return jdbc.queryForList("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent WHERE Action = ? "
                        + "AND EntityType = 'IngestionJob' AND EntityId = ? AND ActorUserId = ?",
                String.class, action, String.valueOf(job.getIngestionJobId()), writer.getAppUserId());
    }

    @Test
    @Disabled("BUG-10: PUT /api/ingestion/jobs/{id}/mapping rewrites the mapped rows without an "
            + "INGESTION_REMAPPED audit row (contracts §4, test plan A6)")
    void aManualMappingChangeIsAuditedOnce() throws Exception {
        IngestionJob job = validatedJob();

        mvc.perform(put("/api/ingestion/jobs/" + job.getIngestionJobId() + "/mapping")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"Name\":\"City\",\"City\":\"Name\"}}"))
                .andExpect(status().isOk());

        assertThat(audit("INGESTION_REMAPPED", job)).as("one INGESTION_REMAPPED row").hasSize(1);
    }

    @Test
    @Disabled("BUG-11: POST /api/ingestion/jobs/{id}/chat-edit changes cell values without an "
            + "INGESTION_ROWS_EDITED audit row (contracts §4, test plan A7)")
    void aChatEditIsAuditedOnceWithoutCellValues() throws Exception {
        IngestionJob job = validatedJob();
        AI.mode(StubAiServer.Mode.OK);
        AI.answer("{\"columns\":[\"Name\",\"City\"],\"rows\":[{\"Name\":\"Ada\",\"City\":\"Paris\"},"
                + "{\"Name\":\"Grace\",\"City\":\"Paris\"}]}");

        String edited = mvc.perform(post("/api/ingestion/jobs/" + job.getIngestionJobId() + "/chat-edit")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"Set City to Paris for all rows\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(edited).contains("Paris");
        assertThat(AI.calls()).isEqualTo(1);

        List<String> rows = audit("INGESTION_ROWS_EDITED", job);
        assertThat(rows).as("one INGESTION_ROWS_EDITED row").hasSize(1);
        assertThat(rows.get(0)).doesNotContain("Paris").doesNotContain("London");
    }
}
