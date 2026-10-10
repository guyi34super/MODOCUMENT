package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE C6 GATE: seeded bad input is rejected at BOTH layers.
 *
 * <ul>
 *   <li><b>Application layer</b> — an invalid request body is stopped by Bean
 *       Validation with a 400 and a field→message map, before any use case
 *       runs;</li>
 *   <li><b>Database layer</b> — bypassing the application checks and driving
 *       the database directly, the duplicate TemplateFields(TemplateId,
 *       FieldName) row is refused by the schema itself (the V30 unique
 *       constraint, mirrored onto H2 through the entity), and the API path
 *       that races past the app check answers 409;</li>
 *   <li><b>No leaking</b> — the constraint-violation answer carries the
 *       requestId for log correlation but neither SQL fragments nor the
 *       offending values.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: the 409 path needs the first
 * row really committed so the second request hits the constraint the way a
 * production race would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrityGateC6Test {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUser writer;
    private String auth;
    private Template template;

    @BeforeEach
    void writerWithTemplate() {
        AppUser entity = new AppUser();
        entity.setEmail("c6-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("C6 Gate");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        writer = users.save(entity);
        auth = "Bearer " + jwtService.issueToken(writer);

        Template owned = new Template(0, "C6-" + UUID.randomUUID(), "gate");
        owned.setOwnerUserId(writer.getAppUserId());
        owned.setBankId(writer.getBankId());
        template = templates.save(owned);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM TemplateFields WHERE TemplateId IN "
                + "(SELECT TemplateId FROM Template WHERE OwnerUserId = ?)",
                writer.getAppUserId());
        jdbc.update("DELETE FROM Template WHERE OwnerUserId = ?", writer.getAppUserId());
        users.deleteById(writer.getAppUserId());
    }

    // ------------------------------------------------- application layer ----

    @Test
    void invalidDtoIsRejectedWithAFieldMapBeforeAnyUseCaseRuns() throws Exception {
        // Blank name + no fields: both Bean Validation violations at once.
        mvc.perform(post("/Templates/CreateWithFields").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"fields\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed."))
                .andExpect(jsonPath("$.fields.name").isNotEmpty())
                .andExpect(jsonPath("$.fields.fields").isNotEmpty());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM Template WHERE OwnerUserId = ?", Integer.class,
                writer.getAppUserId()))
                .as("nothing may be persisted for an invalid definition")
                .isEqualTo(1); // only the fixture template
    }

    @Test
    void oversizedFieldIsRejectedBySchemaValidationNotTheDatabase() throws Exception {
        String overlong = "x".repeat(300); // TemplateFields.FieldName is varchar(255)
        mvc.perform(post("/TemplateFields/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"fieldName\":\"" + overlong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.fieldName").isNotEmpty());
    }

    // -------------------------------------------------- database layer ----

    @Test
    void duplicateTemplateFieldsRowIsRefusedByTheSchemaItself() {
        // Straight SQL — no controller, no use case, no hand-rolled check.
        String insert = "INSERT INTO TemplateFields (TemplateId, BankId, FieldName, "
                + "TemplateDataTypeId) VALUES (?, ?, ?, ?)";
        jdbc.update(insert, template.getTemplateId(), writer.getBankId(), "Amount", 1);

        assertThatThrownBy(() ->
                jdbc.update(insert, template.getTemplateId(), writer.getBankId(), "Amount", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateTemplateNamePerOwnerIsRefusedByTheSchemaItself() {
        String insert = "INSERT INTO Template (OwnerUserId, BankId, Name, Description) "
                + "VALUES (?, ?, ?, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, writer.getAppUserId(), writer.getBankId(),
                template.getName(), "same name, same owner"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------ API race + leak regression ----

    @Test
    void racePastTheAppCheckAnswers409WithoutLeakingSqlOrValues() throws Exception {
        // /Templates/Create has no hand-rolled duplicate-name check, so the
        // second call models the race that slips past app-level blocking and
        // is caught only by the V30 unique constraint.
        String secret = "Secret-Client-Codename-" + UUID.randomUUID();
        String body = "{\"name\":\"" + secret + "\",\"description\":\"d\"}";
        mvc.perform(post("/Templates/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String requestId = "c6-leak-" + UUID.randomUUID();
        MvcResult conflict = mvc.perform(post("/Templates/Create")
                        .header("Authorization", auth)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("The request conflicts with existing data."))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andReturn();

        // Neither SQL fragments nor the offending value may reach the client
        // (PostgreSQL constraint errors spell out the VALUES — PII risk).
        String answer = conflict.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        assertThat(answer)
                .doesNotContain(secret.toLowerCase(Locale.ROOT))
                .doesNotContain("sql")
                .doesNotContain("unique")
                .doesNotContain("constraint")
                .doesNotContain("violation")
                .doesNotContain("insert");
    }
}
