package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.ai.AiCircuitBreaker;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.transform.code.ExtractionCorpus;
import com.modocument.testsupport.StubAiServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws23 end to end, over HTTP: an upload whose AI extraction fails is
 * VALIDATED with a table produced by code (AC-EXT-02/04/05/11, QA OBS-03).
 * The AI endpoint is a real loopback HTTP server, so Spring AI's retry
 * policy, the HTTP read timeout, the circuit breaker and the failure
 * classifier all run for real; only the shipped default retry settings
 * apply, plus a 2 s read timeout to keep the hang case short.
 *
 * <p>The document is the corpus's label: value DOCX (four transaction
 * blocks), which no deterministic fast path can read, so the AI is always
 * tried first.
 */
class IngestionFallbackIntegrationTest extends AiStubApiTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path documents;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AiCircuitBreaker circuitBreaker;
    @Autowired
    private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void freshUserAndStub() {
        AppUser user = new AppUser();
        user.setEmail("ws23-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("ws23");
        user.setBankId(1);
        user.setRole(Role.READ_WRITE);
        user.setSettingsJson("{}");
        auth = "Bearer " + jwtService.issueToken(users.save(user));
        circuitBreaker.reset();
        AI.mode(StubAiServer.Mode.ERROR_500);
        AI.resetCalls();
    }

    /** The statement template, with the label regex the corpus uses for "Ref.". */
    private int statementTemplate(String extractionMode) throws Exception {
        String body = """
                {"name": "ws23-statement-%s", "extractionMode": %s, "fields": [
                  {"fieldName": "Account Number", "dataType": "Text"},
                  {"fieldName": "Transaction Date", "dataType": "Date"},
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

    /** Uploads the statement DOCX and waits for VALIDATED (or failure). */
    private JsonNode upload(int templateId) throws Exception {
        Path docx = ExtractionCorpus.generate("statement-blocks.docx", documents);
        // A unique name per upload: the duplicate gate refuses re-uploads.
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

    private int fallbackAudits(JsonNode job) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                        + "'INGESTION_PARSE_FALLBACK' AND EntityId = ?", Integer.class,
                String.valueOf(job.get("ingestionJobId").asInt()));
        return count == null ? 0 : count;
    }

    private static void assertExtractedByCode(JsonNode job) {
        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("currentStep").asText()).isEqualTo("VALIDATED");
        assertThat(job.get("parsedBy").asText()).isEqualTo("CODE");
        assertThat(job.get("parseStrategy").asText()).isEqualTo("KEY_VALUE");
        assertThat(job.get("parseConfidence").asDouble()).isGreaterThanOrEqualTo(0.75);
        // Wave 4: judged against the bank's threshold for the job page callout.
        assertThat(job.get("parseMinConfidence").asDouble()).isEqualTo(0.75);
        assertThat(job.get("parseLowConfidence").asBoolean()).isFalse();
        assertThat(job.get("parsedTable").get("rows")).hasSize(4);
        assertThat(job.get("unmappedFields")).isEmpty();
    }

    @Test
    void aModelThatAnswers500FallsBackToCodeWithinSeconds() throws Exception {
        int templateId = statementTemplate(null);

        long started = System.nanoTime();
        JsonNode job = upload(templateId);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertExtractedByCode(job);
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("ERROR");
        // spring.ai.retry.max-attempts=2: one retry after 1 s, no corrective re-prompt.
        assertThat(AI.calls()).isEqualTo(2);
        assertThat(millis).as("500 → code fallback took %d ms", millis).isLessThan(10_000);
        assertThat(fallbackAudits(job)).isOne();
        System.out.printf("ws23 timing: model 500 -> code fallback, job VALIDATED in %d ms%n", millis);
    }

    @Test
    void aModelThatNeverAnswersTimesOutAndFallsBackToCode() throws Exception {
        AI.mode(StubAiServer.Mode.HANG);
        int templateId = statementTemplate("AI_WITH_CODE_FALLBACK");

        long started = System.nanoTime();
        JsonNode job = upload(templateId);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertExtractedByCode(job);
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("TIMEOUT");
        // A timeout is not retried by Spring AI and not re-prompted by the parse stage.
        assertThat(AI.calls()).isEqualTo(1);
        assertThat(millis).as("hang → code fallback took %d ms", millis).isLessThan(10_000);
        System.out.printf("ws23 timing: hung model (read timeout 2s) -> code fallback, "
                + "job VALIDATED in %d ms%n", millis);
    }

    @Test
    void codeOnlyNeverCallsTheModel() throws Exception {
        JsonNode job = upload(statementTemplate("CODE_ONLY"));

        assertExtractedByCode(job);
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("MODE_CODE_ONLY");
        assertThat(AI.calls()).isZero();
        assertThat(fallbackAudits(job)).isZero();
    }

    @Test
    void aiOnlyFailsTheJobAsBefore() throws Exception {
        JsonNode job = upload(statementTemplate("AI_ONLY"));

        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("currentStep").asText()).isEqualTo("UPLOADED");
        assertThat(job.get("errorMessage").asText()).isNotBlank();
        assertThat(job.get("parsedBy").isNull()).isTrue();
    }

    @Test
    void anOpenCircuitSkipsTheModelAndAiEndpointsAnswer503() throws Exception {
        for (int i = 0; i < 5; i++) {
            circuitBreaker.tryAcquire();
            circuitBreaker.onFailure(new ResourceAccessException("Connection refused"));
        }
        assertThat(circuitBreaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);

        JsonNode job = upload(statementTemplate(null));

        assertExtractedByCode(job);
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("CIRCUIT_OPEN");
        // ws28: /api/aifiles/send is gone; the automapper is another AI
        // endpoint with no code path, so it answers the open circuit's 503.
        mvc.perform(post("/api/automapper/generate")
                        .header("Authorization", auth)
                        .param("extractedFields", "Date,Reference,Amount")
                        .param("filename", job.get("fileName").asText()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("CIRCUIT_OPEN"));
        assertThat(AI.calls()).isZero();
    }

    @Test
    void theTemplateHintsReachTheCodeExtractor() throws Exception {
        // Without the label pattern / alias, "Ref." is not recognised as Reference.
        JsonNode job = upload(statementTemplate("CODE_ONLY"));

        JsonNode firstRow = job.get("parsedTable").get("rows").get(0);
        assertThat(firstRow.toString()).contains("REF-0001");
    }
}
