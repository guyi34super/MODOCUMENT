package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.TenantContext;
import com.modocument.doccreation.entity.DocDataField;
import com.modocument.doccreation.entity.DocDataFieldRule;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.repo.DocDataFieldJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws34 (V330/V331) on the REAL schema with row level security ENFORCED: a
 * data template, its fields and its rules are visible and writable in their
 * own bank only (bank B neither reads, deletes nor forges bank A's rows),
 * and the PDF template ↔ data template journey over HTTP (choose, map, the
 * template list's batched counts, validating a document with the effective
 * rules) runs under RLS.
 */
class DocDataTemplateRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 3410;
    private static final int BANK_B = 3420;
    private static final int USER_A = 34100;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DocDataTemplateJpaRepository templates;
    @Autowired
    private DocDataFieldJpaRepository fields;
    @Autowired
    private DocDataFieldRuleJpaRepository rules;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private FakePdfTemplateEngine engine;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(3410, 'DATA_A', 'Data templates A'), (3420, 'DATA_B', 'Data templates B') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, SettingsJson, "
                    + "CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(34100, 'data-a@example.com', '{noop}x', 'Data A', '{}', now(), now(), 3410, 'READ_WRITE', 0) "
                    + "ON CONFLICT DO NOTHING");
            wipe(sql);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        engine.reset();
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            wipe(sql);
        }
    }

    private static void wipe(Statement sql) throws Exception {
        String banks = "(3410, 3420)";
        sql.execute("DELETE FROM DocInstance WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocBlueprintSource WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocFieldRule WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocTemplateRegion WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocTemplateEdit WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocBlueprintVersion WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocBlueprint WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocDataFieldRule WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocDataField WHERE BankId IN " + banks);
        sql.execute("DELETE FROM DocDataTemplate WHERE BankId IN " + banks);
    }

    private static DocDataTemplate template(int bankId, String name) {
        DocDataTemplate template = new DocDataTemplate();
        template.setBankId(bankId);
        template.setOwnerUserId(USER_A);
        template.setName(name);
        template.setCreatedAt(LocalDateTime.now());
        return template;
    }

    private static DocDataField field(int bankId, int templateId, String name, String kind) {
        DocDataField field = new DocDataField();
        field.setBankId(bankId);
        field.setDataTemplateId(templateId);
        field.setFieldName(name);
        field.setLabel(name);
        field.setKind(kind);
        return field;
    }

    private static DocDataFieldRule rule(int bankId, int templateId, String fieldName, String type) {
        DocDataFieldRule rule = new DocDataFieldRule();
        rule.setBankId(bankId);
        rule.setDataTemplateId(templateId);
        rule.setFieldName(fieldName);
        rule.setRuleType(type);
        rule.setConfigJson("{}");
        rule.setUpdatedAt(LocalDateTime.now());
        return rule;
    }

    /** Bank A's data template with one field and one rule, saved under bank A. */
    private int seedBankA(TransactionTemplate transaction, String fieldName) throws Exception {
        return TenantContext.runAs(BANK_A, () -> transaction.execute(status -> {
            int id = templates.save(template(BANK_A, "Customer data")).getDocDataTemplateId();
            fields.save(field(BANK_A, id, fieldName, "TEXT"));
            rules.save(rule(BANK_A, id, fieldName, "REQUIRED"));
            return id;
        }));
    }

    @Test
    void dataTemplatesFieldsAndRulesStayInTheirBank() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int id = seedBankA(transaction, "full_name");
        assertThat(count("SELECT count(*) FROM DocDataTemplate WHERE BankId = 3410")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocDataField WHERE BankId = 3410")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocDataFieldRule WHERE BankId = 3410")).isEqualTo(1);

        // Bank A sees its own rows.
        Optional<DocDataTemplate> own = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                templates.findByDocDataTemplateIdAndBankIdAndOwnerUserId(id, BANK_A, USER_A)));
        assertThat(own).isPresent();
        List<DocDataField> ownFields = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                fields.findByBankIdAndDataTemplateIdOrderBySortOrderAscDocDataFieldIdAsc(BANK_A, id)));
        assertThat(ownFields).hasSize(1);

        // Bank B sees nothing, even naming bank A, and deletes nothing.
        Optional<DocDataTemplate> foreign = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                templates.findByDocDataTemplateIdAndBankId(id, BANK_A)));
        assertThat(foreign).isEmpty();
        Optional<DocDataTemplate> byId = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                templates.findById(id)));
        assertThat(byId).isEmpty();
        List<DocDataField> foreignFields = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                fields.findByBankIdAndDataTemplateIdOrderBySortOrderAscDocDataFieldIdAsc(BANK_A, id)));
        assertThat(foreignFields).isEmpty();
        List<DocDataFieldRule> foreignRules = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                rules.findByBankIdAndDataTemplateIdOrderByFieldNameAscSortOrderAscDocDataFieldRuleIdAsc(BANK_A, id)));
        assertThat(foreignRules).isEmpty();
        Integer rulesDeleted = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                rules.deleteTemplate(BANK_A, id)));
        assertThat(rulesDeleted).isZero();
        Integer fieldsDeleted = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                fields.deleteTemplate(BANK_A, id)));
        assertThat(fieldsDeleted).isZero();
        TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status -> templates.deleteById(id)));
        assertThat(count("SELECT count(*) FROM DocDataTemplate WHERE DocDataTemplateId = " + id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocDataField WHERE DataTemplateId = " + id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocDataFieldRule WHERE DataTemplateId = " + id)).isEqualTo(1);

        // WITH CHECK: bank B cannot write rows stamped with bank A.
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                templates.saveAndFlush(template(BANK_A, "Forged"))))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                fields.saveAndFlush(field(BANK_A, id, "forged", "TEXT"))))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                rules.saveAndFlush(rule(BANK_A, id, "forged", "REQUIRED"))))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM DocDataTemplate WHERE Name = 'Forged'")).isZero();
        assertThat(count("SELECT count(*) FROM DocDataField WHERE FieldName = 'forged'")).isZero();
        assertThat(count("SELECT count(*) FROM DocDataFieldRule WHERE FieldName = 'forged'")).isZero();

        // Bank A deletes its own; the fields and rules cascade.
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status -> templates.deleteById(id)));
        assertThat(count("SELECT count(*) FROM DocDataTemplate WHERE BankId = 3410")).isZero();
        assertThat(count("SELECT count(*) FROM DocDataField WHERE BankId = 3410")).isZero();
        assertThat(count("SELECT count(*) FROM DocDataFieldRule WHERE BankId = 3410")).isZero();
    }

    @Test
    void theMappingJourneyRunsUnderRowLevelSecurity() throws Exception {
        AppUser principal = new AppUser();
        principal.setAppUserId(USER_A);
        principal.setBankId(BANK_A);
        principal.setRole(Role.READ_WRITE);
        principal.setTokenVersion(0);
        String token = "Bearer " + jwtService.issueToken(principal);
        engine.reset();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int dataTemplateId = seedBankA(transaction, "full_name");

        MvcResult uploaded = mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "data.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0)))
                        .param("flow", "BLANK")
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode draft = JSON.readTree(uploaded.getResponse().getContentAsString());
        int blueprintId = draft.get("id").asInt();
        ObjectNode overlay = JSON.createObjectNode();
        overlay.put("baseVersion", 1);
        overlay.set("regions", draft.get("regions"));
        overlay.set("edits", JSON.createArrayNode());
        overlay.put("confirm", true);
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/overlay").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(overlay.toString()))
                .andExpect(status().isOk());

        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/data-template")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTemplateId\":" + dataTemplateId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataTemplateName").value("Customer data"))
                .andExpect(jsonPath("$.rows[0].regionKey").value("full_name"))
                .andExpect(jsonPath("$.rows[0].mappedField").value("full_name"));
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/data-mapping")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"full_name\":\"full_name\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappedCount").value(1));
        mvc.perform(get("/api/doccreation/blueprints").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dataTemplateId").value(dataTemplateId))
                .andExpect(jsonPath("$[0].dataTemplateName").value("Customer data"))
                .andExpect(jsonPath("$[0].mappedCount").value(1));
        assertThat(count("SELECT RulesRevision FROM DocBlueprint WHERE DocBlueprintId = " + blueprintId))
                .isEqualTo(2);

        MvcResult created = mvc.perform(post("/api/doccreation/instances").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Under RLS\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        int instanceId = JSON.readTree(created.getResponse().getContentAsString()).get("id").asInt();
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/validate").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.validation.errors[0].regionKey").value("full_name"))
                .andExpect(jsonPath("$.fieldChecks.full_name.rules[0].origin").value("DATA_TEMPLATE"));
        mvc.perform(get("/api/doccreation/instances").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].validationState").value("ERRORS"))
                .andExpect(jsonPath("$[0].errorCount").value(1));

        mvc.perform(delete("/api/doccreation/instances/" + instanceId).header("Authorization", token))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/doccreation/blueprints/" + blueprintId).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    /** Read as the owner (bypasses RLS): the truth on disk. */
    private static long count(String query) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
