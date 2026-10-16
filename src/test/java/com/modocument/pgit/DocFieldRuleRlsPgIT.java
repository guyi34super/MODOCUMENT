package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.TenantContext;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws33 (V321) on the REAL schema with row level security ENFORCED: a
 * template's field rules are visible and writable in their own bank only;
 * the V321 backfill turns the Required flag of the current version's
 * regions into REQUIRED rules (once, current version only); and the rules
 * journey over HTTP (overlay Required flag, save under the row lock,
 * template delete) runs under RLS.
 */
class DocFieldRuleRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 3310;
    private static final int BANK_B = 3320;
    private static final int USER_A = 33100;
    private static final int BLUEPRINT_A = 33100;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DocFieldRuleJpaRepository rules;
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
                    + "(3310, 'RULES_A', 'Field rules A'), (3320, 'RULES_B', 'Field rules B') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, SettingsJson, "
                    + "CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(33100, 'rules-a@example.com', '{noop}x', 'Rules A', '{}', now(), now(), 3310, 'READ_WRITE', 0) "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("DELETE FROM DocFieldRule WHERE BankId IN (3310, 3320)");
            sql.execute("DELETE FROM DocTemplateRegion WHERE BankId IN (3310, 3320)");
            sql.execute("INSERT INTO DocBlueprint (DocBlueprintId, OwnerUserId, Name, FileName, FlowKey, "
                    + "CreatedDate, UpdatedDate, BankId, Kind, CurrentVersion, PageCount, UploadFlow) VALUES "
                    + "(33100, 33100, 'Rules A', 'doccreation/b3310/blueprint33100/original.pdf', 'DEFAULT', now(), "
                    + "now(), 3310, 'PDF_OVERLAY', 2, 1, 'BLANK') ON CONFLICT DO NOTHING");
        }
    }

    private static DocFieldRule rule(int bankId, String key) {
        DocFieldRule rule = new DocFieldRule();
        rule.setBankId(bankId);
        rule.setBlueprintId(BLUEPRINT_A);
        rule.setRegionKey(key);
        rule.setRuleType("LENGTH");
        rule.setConfigJson("{\"Max\":20}");
        rule.setUpdatedAt(LocalDateTime.now());
        return rule;
    }

    @Test
    void rulesStayInTheirBank() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                rules.save(rule(BANK_A, "name"))));
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BankId = 3310 AND RegionKey = 'name'"))
                .isEqualTo(1);

        List<DocFieldRule> own = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                rules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(BANK_A,
                        BLUEPRINT_A)));
        assertThat(own).extracting(DocFieldRule::getRegionKey).contains("name");

        // Bank B sees nothing and deletes nothing, even naming bank A.
        List<DocFieldRule> foreign = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                rules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(BANK_A,
                        BLUEPRINT_A)));
        assertThat(foreign).isEmpty();
        Integer deleted = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                rules.deleteBlueprint(BANK_A, BLUEPRINT_A)));
        assertThat(deleted).isZero();
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BankId = 3310 AND RegionKey = 'name'"))
                .isEqualTo(1);

        // WITH CHECK: bank B cannot write a rule stamped with bank A.
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                rules.saveAndFlush(rule(BANK_A, "forged"))))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE RegionKey = 'forged'")).isZero();

        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                rules.deleteBlueprint(BANK_A, BLUEPRINT_A)));
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BankId = 3310 AND RegionKey = 'name'"))
                .isZero();
    }

    @Test
    void theV321BackfillMakesTheCurrentVersionsRequiredFlagsRules() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("DELETE FROM DocFieldRule WHERE BlueprintId = 33100");
            sql.execute("DELETE FROM DocTemplateRegion WHERE BlueprintId = 33100");
            sql.execute(regionInsert(1, "old_only", true));
            sql.execute(regionInsert(2, "nic", true));
            sql.execute(regionInsert(2, "notes", false));
            String backfill = backfillStatement();
            sql.execute(backfill);
            sql.execute(backfill); // idempotent
        }
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BlueprintId = 33100")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BlueprintId = 33100 AND RegionKey = 'nic' "
                + "AND RuleType = 'REQUIRED' AND Origin = 'REGION_FLAG' AND Severity = 'ERROR' AND BankId = 3310"))
                .isEqualTo(1);
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("DELETE FROM DocFieldRule WHERE BlueprintId = 33100");
            sql.execute("DELETE FROM DocTemplateRegion WHERE BlueprintId = 33100");
        }
    }

    private static String regionInsert(int version, String key, boolean required) {
        return "INSERT INTO DocTemplateRegion (BankId, BlueprintId, Version, RegionKey, Label, Kind, Page, X, Y, W, "
                + "H, FontSize, Color, Align, MaxLines, Required, SortOrder, Source, Confidence) VALUES "
                + "(3310, 33100, " + version + ", '" + key + "', '" + key + "', 'TEXT', 0, 1, 1, 100, 20, 0, "
                + "'#000000', 'LEFT', 1, " + required + ", 0, 'MANUAL', 1)";
    }

    /** The backfill INSERT of the real V321 migration file. */
    private static String backfillStatement() throws IOException {
        try (InputStream in = DocFieldRuleRlsPgIT.class.getResourceAsStream(
                "/db/migration/V321__ws33_doc_field_rule.sql")) {
            assertThat(in).isNotNull();
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = script.indexOf("INSERT INTO DocFieldRule");
            int end = script.indexOf(';', start);
            assertThat(start).isPositive();
            return script.substring(start, end);
        }
    }

    @Test
    void theRulesJourneyRunsUnderRowLevelSecurity() throws Exception {
        AppUser principal = new AppUser();
        principal.setAppUserId(USER_A);
        principal.setBankId(BANK_A);
        principal.setRole(Role.READ_WRITE);
        principal.setTokenVersion(0);
        String token = "Bearer " + jwtService.issueToken(principal);
        engine.reset();

        MvcResult uploaded = mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "rules.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0)))
                        .param("flow", "BLANK")
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fonts[0]").value("LiberationSans"))
                .andReturn();
        JsonNode draft = JSON.readTree(uploaded.getResponse().getContentAsString());
        int blueprintId = draft.get("id").asInt();
        ObjectNode overlay = JSON.createObjectNode();
        overlay.put("baseVersion", 1);
        overlay.set("regions", draft.get("regions"));
        overlay.set("edits", JSON.createArrayNode());
        overlay.put("confirm", true);
        ((ObjectNode) overlay.get("regions").get(0)).put("required", true);
        ((ObjectNode) overlay.get("regions").get(0)).set("props",
                JSON.readTree("{\"placeholder\":\"As on the ID card\"}"));
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/overlay").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(overlay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].required").value(true))
                .andExpect(jsonPath("$.regions[0].props.placeholder").value("As on the ID card"));

        mvc.perform(get("/api/doccreation/blueprints/" + blueprintId + "/rules").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rules[0].ruleType").value("REQUIRED"));
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/rules").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevision\":1,\"rules\":[{\"regionKey\":\"full_name\",\"ruleType\":\"LENGTH\","
                                + "\"config\":{\"Max\":40}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/rules").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseRevision\":1,\"rules\":[]}"))
                .andExpect(status().isConflict());
        assertThat(count("SELECT RulesRevision FROM DocBlueprint WHERE DocBlueprintId = " + blueprintId))
                .isEqualTo(2);

        mvc.perform(delete("/api/doccreation/blueprints/" + blueprintId).header("Authorization", token))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM DocFieldRule WHERE BlueprintId = " + blueprintId)).isZero();
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
