package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation on/off in document creation (docs/doccreation/data-templates-contract.md
 * §8) over HTTP: the template's default for new documents and the create
 * override, generate and download without validation (submit still needs
 * GENERATED), switching a document on (back to DRAFT) and off (status kept),
 * frozen documents, ownership and bank isolation, permissions, the list's
 * {@code validationState} and the audit trail.
 */
class DocValidationSwitchApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FakePdfTemplateEngine engine;
    @Autowired
    private DocFieldRuleJpaRepository rules;

    private Bank bank;
    private Bank otherBank;
    private AppUser owner;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        engine.proposedRegions = List.of(
                FakePdfTemplateEngine.region("full_name", "Full name", null, RegionKind.TEXT,
                        new PdfBox(0, 100, 760, 200, 20), 0, null),
                FakePdfTemplateEngine.region("amount", "Amount", null, RegionKind.NUMBER,
                        new PdfBox(0, 100, 680, 120, 20), 1, null));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("VSW" + tag.substring(0, 5).toUpperCase(), "Validation switch bank"));
        otherBank = banks.save(new Bank("VSX" + tag.substring(0, 5).toUpperCase(), "Other bank"));
        owner = user(bank, "vsw-owner-" + tag, Role.READ_WRITE);
    }

    @AfterEach
    void cleanUp() {
        engine.reset();
        engine.onFill = null;
        SecurityContextHolder.clearContext();
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM DocFieldRule WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
        }
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
        banks.delete(otherBank);
    }

    // ------------------------------------------------------------ fixtures

    private AppUser user(Bank in, String name, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(name);
        entity.setBankId(in.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return as(owner, request);
    }

    private MockHttpServletRequestBuilder as(AppUser user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** A READY template with a REQUIRED rule on full_name (a new, empty document fails validation). */
    private int readyTemplate() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "form.pdf", "application/pdf",
                FakePdfTemplateEngine.blankPdf(1, 0));
        JsonNode draft = json(mvc.perform(as(multipart("/api/doccreation/blueprints").file(file)
                        .param("flow", "BLANK").param("name", "Switch form")))
                .andExpect(status().isCreated()).andReturn());
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", 1);
        body.set("regions", draft.get("regions"));
        body.set("edits", MAPPER.createArrayNode());
        body.put("confirm", true);
        int id = json(mvc.perform(as(put("/api/doccreation/blueprints/" + draft.get("id").asInt() + "/overlay"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn()).get("id").asInt();
        DocFieldRule rule = new DocFieldRule();
        rule.setBankId(bank.getBankId());
        rule.setBlueprintId(id);
        rule.setRegionKey("full_name");
        rule.setRuleType("REQUIRED");
        rule.setConfigJson("{}");
        rule.setSeverity("ERROR");
        rule.setUpdatedAt(LocalDateTime.now());
        rules.save(rule);
        return id;
    }

    private int document(int blueprintId, Boolean validationEnabled) throws Exception {
        String flag = validationEnabled == null ? "" : ",\"validationEnabled\":" + validationEnabled;
        return json(mvc.perform(as(post("/api/doccreation/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Loan file\"" + flag + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asInt();
    }

    private JsonNode getDoc(int instanceId) throws Exception {
        return json(mvc.perform(as(get("/api/doccreation/instances/" + instanceId)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode getBlueprint(int blueprintId) throws Exception {
        return json(mvc.perform(as(get("/api/doccreation/blueprints/" + blueprintId)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode setTemplateValidation(int blueprintId, boolean enabled) throws Exception {
        return json(mvc.perform(as(put("/api/doccreation/blueprints/" + blueprintId + "/validation-setting"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode setDocValidation(int instanceId, boolean enabled) throws Exception {
        return json(mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/validation-setting"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk()).andReturn());
    }

    private void putValues(int instanceId, String valuesJson) throws Exception {
        mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/values"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode validate(int instanceId) throws Exception {
        return json(mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/validate")))
                .andExpect(status().isOk()).andReturn());
    }

    private void generateOk(int instanceId) throws Exception {
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isOk());
    }

    private String statusOf(int instanceId) {
        return jdbc.queryForObject("SELECT Status FROM DocInstance WHERE DocInstanceId = ?", String.class,
                instanceId);
    }

    private Map<String, Object> row(int instanceId) {
        return jdbc.queryForMap("SELECT * FROM DocInstance WHERE DocInstanceId = ?", instanceId);
    }

    private List<Map<String, Object>> settingAudits(String entityType, int entityId) {
        return jdbc.query("SELECT Outcome, NewValues FROM AuditEvent "
                        + "WHERE Action = 'DOC_VALIDATION_SETTING_CHANGED' AND EntityType = ? AND EntityId = ? "
                        + "AND BankId = ? ORDER BY OccurredAt",
                (rs, n) -> Map.<String, Object>of("Outcome", rs.getString("Outcome"),
                        "NewValues", rs.getString("NewValues")),
                entityType, String.valueOf(entityId), bank.getBankId());
    }

    private static JsonNode newValues(Map<String, Object> audit) throws Exception {
        return MAPPER.readTree(String.valueOf(audit.get("NewValues")));
    }

    private JsonNode listEntry(int instanceId) throws Exception {
        for (JsonNode entry : json(mvc.perform(as(get("/api/doccreation/instances")))
                .andExpect(status().isOk()).andReturn())) {
            if (entry.get("id").asInt() == instanceId) {
                return entry;
            }
        }
        throw new AssertionError("document " + instanceId + " not listed");
    }

    // ------------------------------------------------------ template default

    @Test
    void newDocumentsTakeTheTemplatesDefaultAndCreateCanOverrideItBothWays() throws Exception {
        int blueprintId = readyTemplate();
        assertThat(getBlueprint(blueprintId).get("validationEnabled").asBoolean()).as("on by default").isTrue();
        int inherited = document(blueprintId, null);
        assertThat(getDoc(inherited).get("validationEnabled").asBoolean()).isTrue();
        int explicitOff = document(blueprintId, false);
        assertThat(getDoc(explicitOff).get("validationEnabled").asBoolean()).isFalse();

        JsonNode switched = setTemplateValidation(blueprintId, false);
        assertThat(switched.get("id").asInt()).isEqualTo(blueprintId);
        assertThat(switched.get("validationEnabled").asBoolean()).isFalse();
        JsonNode summaries = json(mvc.perform(as(get("/api/doccreation/blueprints")))
                .andExpect(status().isOk()).andReturn());
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).get("validationEnabled").asBoolean()).isFalse();
        assertThat(getDoc(inherited).get("validationEnabled").asBoolean())
                .as("existing documents keep their own choice").isTrue();

        int inheritedOff = document(blueprintId, null);
        assertThat(getDoc(inheritedOff).get("validationEnabled").asBoolean()).isFalse();
        int explicitOn = document(blueprintId, true);
        assertThat(getDoc(explicitOn).get("validationEnabled").asBoolean()).isTrue();

        // The audit: one per real change, the target and the new setting only.
        setTemplateValidation(blueprintId, false);
        List<Map<String, Object>> audits = settingAudits("DocBlueprint", blueprintId);
        assertThat(audits).as("an unchanged setting is not audited").hasSize(1);
        assertThat(audits.get(0).get("Outcome")).isEqualTo("SUCCESS");
        JsonNode values = newValues(audits.get(0));
        assertThat(values.get("target").asText()).isEqualTo("BLUEPRINT");
        assertThat(values.get("enabled").asBoolean()).isFalse();
        setTemplateValidation(blueprintId, true);
        assertThat(settingAudits("DocBlueprint", blueprintId)).hasSize(2);
        assertThat(getBlueprint(blueprintId).get("validationEnabled").asBoolean()).isTrue();

        // {enabled} is required.
        mvc.perform(as(put("/api/doccreation/blueprints/" + blueprintId + "/validation-setting"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------- without validation

    @Test
    void aDocumentWithoutValidationGeneratesAndDownloadsStraightAway() throws Exception {
        int blueprintId = readyTemplate();
        int instanceId = document(blueprintId, false);
        putValues(instanceId, "{\"amount\":\"12\"}");
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
        // Submit still needs a generated document.
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isConflict());

        // full_name is required and empty: validation would fail, but it is off.
        generateOk(instanceId);
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isOk());
        JsonNode doc = getDoc(instanceId);
        assertThat(doc.get("validationEnabled").asBoolean()).isFalse();
        assertThat(doc.get("fieldChecks").get("full_name").get("rules")).as("still informational").hasSize(1);

        // Changed rules do not gate it either.
        jdbc.update("UPDATE DocBlueprint SET RulesRevision = RulesRevision + 1 WHERE DocBlueprintId = ?",
                blueprintId);
        generateOk(instanceId);
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");

        // A download of a never-generated DRAFT generates it first.
        int other = document(blueprintId, false);
        mvc.perform(as(get("/api/doccreation/instances/" + other + "/download")))
                .andExpect(status().isOk());
        assertThat(statusOf(other)).isEqualTo("GENERATED");

        // Validate can still be called: it records the result, a GENERATED document stays GENERATED.
        JsonNode checked = validate(instanceId);
        assertThat(checked.get("status").asText()).isEqualTo("GENERATED");
        assertThat(checked.get("validation").get("errors")).hasSize(1);

        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("SUBMITTED");
    }

    @Test
    void submitNeedsAnUnchangedGeneratedDocumentWithoutValidationToo() throws Exception {
        int instanceId = document(readyTemplate(), false);
        generateOk(instanceId);
        putValues(instanceId, "{\"amount\":\"13\"}");
        assertThat(statusOf(instanceId)).as("a change sends it back to DRAFT").isEqualTo("DRAFT");
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isConflict());
        generateOk(instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isOk());
    }

    @Test
    void switchingValidationWhileRenderingRefusesThePromotion() throws Exception {
        int instanceId = document(readyTemplate(), false);
        engine.onFill = () -> jdbc.update("UPDATE DocInstance SET ValidationEnabled = true WHERE DocInstanceId = ?",
                instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "The document changed while it was being generated; generate again."));
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
        assertThat(row(instanceId).get("LastGeneratedDate")).isNull();
    }

    // ------------------------------------------------------------- switching

    @Test
    void switchingOnSendsAGeneratedDocumentBackToDraftUntilItIsValidated() throws Exception {
        int instanceId = document(readyTemplate(), false);
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        generateOk(instanceId);
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        Object generatedAt = row(instanceId).get("LastGeneratedDate");

        JsonNode on = setDocValidation(instanceId, true);

        assertThat(on.get("id").asInt()).isEqualTo(instanceId);
        assertThat(on.get("status").asText()).isEqualTo("DRAFT");
        assertThat(on.get("validationEnabled").asBoolean()).isTrue();
        Map<String, Object> stored = row(instanceId);
        assertThat(stored.get("ValidatedHash")).isNull();
        assertThat(stored.get("ValidatedAt")).isNull();
        assertThat(stored.get("ValidatedRulesRevision")).isNull();
        assertThat(((java.util.Date) stored.get("UpdatedDate")))
                .as("the generated PDF is stale").isAfter((java.util.Date) generatedAt);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isConflict());

        assertThat(validate(instanceId).get("status").asText()).isEqualTo("VALIDATED");
        generateOk(instanceId);
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isOk());

        List<Map<String, Object>> audits = settingAudits("DocInstance", instanceId);
        assertThat(audits).hasSize(1);
        JsonNode values = newValues(audits.get(0));
        assertThat(values.get("target").asText()).isEqualTo("DOCUMENT");
        assertThat(values.get("enabled").asBoolean()).isTrue();
        assertThat(values.get("status").asText()).isEqualTo("DRAFT");
        assertThat(String.valueOf(audits.get(0).get("NewValues"))).as("no values")
                .doesNotContain("Jane Citizen");
    }

    @Test
    void switchingOnKeepsADraftAndSendsAValidatedDocumentBackToDraft() throws Exception {
        int blueprintId = readyTemplate();
        int draft = document(blueprintId, false);
        assertThat(setDocValidation(draft, true).get("status").asText()).isEqualTo("DRAFT");

        int validatedOff = document(blueprintId, false);
        putValues(validatedOff, "{\"full_name\":\"Jane Citizen\"}");
        assertThat(validate(validatedOff).get("status").asText()).isEqualTo("VALIDATED");
        assertThat(setDocValidation(validatedOff, true).get("status").asText()).isEqualTo("DRAFT");
        assertThat(row(validatedOff).get("ValidatedHash")).isNull();
    }

    @Test
    void switchingOffKeepsTheStatusAndAnUnchangedSettingIsNotAudited() throws Exception {
        int instanceId = document(readyTemplate(), null);
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        assertThat(validate(instanceId).get("status").asText()).isEqualTo("VALIDATED");
        generateOk(instanceId);

        JsonNode off = setDocValidation(instanceId, false);

        assertThat(off.get("status").asText()).isEqualTo("GENERATED");
        assertThat(off.get("validationEnabled").asBoolean()).isFalse();
        assertThat(row(instanceId).get("ValidatedHash")).as("the validation is kept").isNotNull();
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/submit")))
                .andExpect(status().isOk());

        int draft = document(readyTemplate(), null);
        assertThat(setDocValidation(draft, false).get("status").asText()).isEqualTo("DRAFT");
        assertThat(setDocValidation(draft, false).get("status").asText()).isEqualTo("DRAFT");
        List<Map<String, Object>> audits = settingAudits("DocInstance", draft);
        assertThat(audits).as("the second, unchanged switch is not audited").hasSize(1);
        assertThat(newValues(audits.get(0)).get("enabled").asBoolean()).isFalse();
        assertThat(newValues(audits.get(0)).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aSubmittedOrReleasedDocumentCannotBeSwitched() throws Exception {
        int instanceId = document(readyTemplate(), false);
        for (String frozen : List.of("SUBMITTED", "RELEASED")) {
            jdbc.update("UPDATE DocInstance SET Status = ? WHERE DocInstanceId = ?", frozen, instanceId);
            mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/validation-setting"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isConflict());
            assertThat(row(instanceId).get("ValidationEnabled")).isEqualTo(false);
        }
        assertThat(settingAudits("DocInstance", instanceId)).isEmpty();
    }

    // ------------------------------------------------------ access and list

    @Test
    void onlyTheOwnerCanSwitchAndReadersAreRefused() throws Exception {
        int blueprintId = readyTemplate();
        int instanceId = document(blueprintId, null);
        AppUser colleague = user(bank, "vsw-colleague-" + UUID.randomUUID().toString().substring(0, 8),
                Role.READ_WRITE);
        AppUser stranger = user(otherBank, "vsw-stranger-" + UUID.randomUUID().toString().substring(0, 8),
                Role.ADMIN);
        AppUser reader = user(bank, "vsw-reader-" + UUID.randomUUID().toString().substring(0, 8),
                Role.READ_ONLY);

        for (AppUser other : List.of(colleague, stranger)) {
            mvc.perform(as(other, put("/api/doccreation/blueprints/" + blueprintId + "/validation-setting"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isNotFound());
            mvc.perform(as(other, put("/api/doccreation/instances/" + instanceId + "/validation-setting"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(as(reader, put("/api/doccreation/blueprints/" + blueprintId + "/validation-setting"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(reader, put("/api/doccreation/instances/" + instanceId + "/validation-setting"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());

        assertThat(getBlueprint(blueprintId).get("validationEnabled").asBoolean()).isTrue();
        assertThat(getDoc(instanceId).get("validationEnabled").asBoolean()).isTrue();
        assertThat(settingAudits("DocBlueprint", blueprintId)).isEmpty();
        assertThat(settingAudits("DocInstance", instanceId)).isEmpty();
    }

    @Test
    void myDocumentsShowOffWithoutErrorsForDocumentsWithoutValidation() throws Exception {
        int blueprintId = readyTemplate();
        int on = document(blueprintId, null);
        int off = document(blueprintId, false);
        assertThat(validate(on).get("status").asText()).isEqualTo("DRAFT");
        assertThat(validate(off).get("validation").get("errors")).hasSize(1);

        JsonNode onEntry = listEntry(on);
        assertThat(onEntry.get("validationEnabled").asBoolean()).isTrue();
        assertThat(onEntry.get("validationState").asText()).isEqualTo("ERRORS");
        assertThat(onEntry.get("errorCount").asInt()).isOne();
        JsonNode offEntry = listEntry(off);
        assertThat(offEntry.get("validationEnabled").asBoolean()).isFalse();
        assertThat(offEntry.get("validationState").asText()).isEqualTo("OFF");
        assertThat(offEntry.get("errorCount").asInt()).isZero();

        generateOk(off);
        assertThat(listEntry(off).get("validationState").asText()).as("generated without validation")
                .isEqualTo("OFF");
    }
}
