package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ai.AiCircuitBreaker;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.settings.SettingsCache;
import com.modocument.infrastructure.transform.code.ExtractionCorpus;
import com.modocument.testsupport.StubAiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA suite (phase 2, Wave 2): extraction mode resolution across features,
 * over HTTP. {@code IngestionFallbackIntegrationTest} proves each mode when
 * the TEMPLATE names it; this proves the rest of the chain of AC-EXT-01 —
 * <b>template mode ?? bank default setting ?? AI_WITH_CODE_FALLBACK</b> —
 * with the bank default set the way an administrator sets it (ws20 settings
 * API), and the bank's AI master switch (a SECURITY setting, so through
 * maker/checker) turning every LLM call off.
 *
 * <p>The model is a loopback stub answering 500 and counting calls, so "the
 * LLM was never called" is a fact, not a mock expectation. Each test runs in
 * its own bank, so bank settings never leak into other suites.
 */
@SpringBootTest(properties = "modocument.ai.http.read-timeout=2s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExtractionModeResolutionApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubAiServer AI = new StubAiServer();

    @TempDir
    static Path storageRoot;

    @TempDir
    Path documents;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("modocument.storage.root", () -> storageRoot.toString());
        registry.add("spring.ai.openai.base-url", AI::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        AI.close();
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AiCircuitBreaker circuitBreaker;
    @Autowired
    private SettingsCache settingsCache;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Integer> createdUsers = new ArrayList<>();
    private Bank bank;
    private String admin;
    private String secondAdmin;
    private String writer;

    @BeforeEach
    void freshBank() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bank = banks.save(new Bank("QM" + tag, "QA mode bank " + tag));
        admin = bearer(user(Role.ADMIN, tag));
        secondAdmin = bearer(user(Role.ADMIN, tag));
        writer = bearer(user(Role.READ_WRITE, tag));
        circuitBreaker.reset();
        AI.mode(StubAiServer.Mode.ERROR_500);
        AI.resetCalls();
        settingsCache.reload();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM BankSetting WHERE BankId = ?", bank.getBankId());
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ?", bank.getBankId());
        createdUsers.forEach(users::deleteById);
        createdUsers.clear();
        settingsCache.reload();
    }

    @Test
    void aBankDefaultOfCodeOnlyAppliesToTemplatesWithoutAModeAndNeverCallsTheModel()
            throws Exception {
        setOperational(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"");

        JsonNode job = upload(statementTemplate(null));

        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("parsedBy").asText()).isEqualTo("CODE");
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("MODE_CODE_ONLY");
        assertThat(AI.calls()).as("CODE_ONLY by bank default must not reach the model")
                .isZero();
    }

    @Test
    void aTemplateModeOverridesTheBankDefault() throws Exception {
        setOperational(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"");

        // The template insists on AI only: the model is tried (and fails), no fallback.
        JsonNode job = upload(statementTemplate("AI_ONLY"));

        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("FAILED");
        assertThat(job.get("parsedBy").isNull()).isTrue();
        assertThat(AI.calls()).isPositive();
    }

    @Test
    void aBankDefaultOfAiOnlyFailsLikeBeforeWhenTheModelFails() throws Exception {
        setOperational(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"AI_ONLY\"");

        JsonNode job = upload(statementTemplate(null));

        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("FAILED");
        assertThat(job.get("parsedBy").isNull()).isTrue();
        assertThat(AI.calls()).isPositive();
    }

    @Test
    void theBanksAiSwitchOffFallsBackWithAiDisabledAndNoModelCall() throws Exception {
        // ai.enabled is SECURITY tier: 202, then a second admin approves.
        String submitted = mvc.perform(put("/api/admin/settings")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change(SettingKeys.AI_ENABLED, "false")))
                .andReturn().getResponse().getContentAsString();
        JsonNode pending = JSON.readTree(submitted).get("pendingChange");
        assertThat(pending).as(submitted).isNotNull();
        assertThat(mvc.perform(post("/api/admin/changes/"
                        + pending.get("pendingChangeId").asInt() + "/approve")
                        .header("Authorization", secondAdmin))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        JsonNode job = upload(statementTemplate(null));

        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("parsedBy").asText()).isEqualTo("CODE");
        assertThat(job.get("parseFallbackReason").asText()).isEqualTo("AI_DISABLED");
        assertThat(AI.calls()).isZero();
    }

    // ------------------------------------------------------------ helpers

    private AppUser user(Role role, String tag) {
        AppUser user = new AppUser();
        user.setEmail("qa-mode-" + role.name().toLowerCase() + "-" + UUID.randomUUID()
                + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("QA mode " + role + " " + tag);
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(user);
        createdUsers.add(saved.getAppUserId());
        return saved;
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private static String change(String key, String jsonValue) {
        return "{\"changes\":[{\"key\":\"" + key + "\",\"value\":" + jsonValue
                + ",\"expectedVersion\":0}]}";
    }

    /** An OPERATIONAL setting: applies at once (200). */
    private void setOperational(String key, String jsonValue) throws Exception {
        String body = mvc.perform(put("/api/admin/settings")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change(key, jsonValue)))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = JSON.readTree(body);
        assertThat(result.path("applied").path(0).path("key").asText()).as(body).isEqualTo(key);
        assertThat(result.path("pendingChange").isNull()).isTrue();
    }

    private int statementTemplate(String extractionMode) throws Exception {
        String body = """
                {"name": "qa-mode-%s", "extractionMode": %s, "fields": [
                  {"fieldName": "Account Number", "dataType": "Text"},
                  {"fieldName": "Transaction Date", "dataType": "Date"},
                  {"fieldName": "Description", "dataType": "Text"},
                  {"fieldName": "Amount", "dataType": "Number"},
                  {"fieldName": "Reference", "dataType": "Text",
                   "labelPattern": "Ref(erence)?\\\\.?", "aliases": ["Ref"]}
                ]}""".formatted(UUID.randomUUID(),
                extractionMode == null ? "null" : "\"" + extractionMode + "\"");
        String response = mvc.perform(post("/Templates/CreateWithFields")
                        .header("Authorization", writer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("templateId").asInt();
    }

    /** The corpus's label:value DOCX — no deterministic fast path reads it. */
    private JsonNode upload(int templateId) throws Exception {
        Path docx = ExtractionCorpus.generate("statement-blocks.docx", documents);
        MockMultipartFile file = new MockMultipartFile("file",
                "statement-" + UUID.randomUUID() + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(docx));
        String response = mvc.perform(multipart("/api/ingestion/jobs").file(file)
                        .param("templateId", String.valueOf(templateId))
                        .param("runUntil", "VALIDATED")
                        .param("wait", "true")
                        .header("Authorization", writer))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }
}
