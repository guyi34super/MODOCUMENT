package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.entity.DocDataField;
import com.modocument.doccreation.entity.DocDataFieldRule;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocDataFieldJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.usecase.TemplateRecordIndexer;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document creation flow's PDF template ↔ data template part
 * (docs/doccreation/data-templates-contract.md §3–§5) over HTTP: choosing a
 * data template pre-fills the mapping (same name, suggested, kind
 * compatibility), mapping saves are checked (400) and raise the rules
 * revision, the effective rules (data template rules re-keyed onto the
 * mapped regions, REQUIRED_IF re-keyed, unmapped references are setup
 * warnings) validate documents and show in the field checks and in
 * {@code GET /rules} ({@code inherited}), a rules change refuses generate
 * and download until the document is validated again, the documents list's
 * validation state, the fill preview's {@code wouldFail}, the managed
 * PRIMARY data source (a hand-made one is never touched), the template
 * list's counts, and owner/bank isolation (404).
 */
class DataMappingApiTest extends IsolatedStorageApiTestSupport {

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
    private DocDataTemplateJpaRepository dataTemplates;
    @Autowired
    private DocDataFieldJpaRepository dataFields;
    @Autowired
    private DocDataFieldRuleJpaRepository dataRules;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsJpaRepository templateFields;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private TemplateRecordIndexer indexer;

    private Bank bank;
    private Bank otherBank;
    private AppUser owner;
    private AppUser colleague;
    private AppUser stranger;
    private Template customers;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        engine.proposedRegions = List.of(
                FakePdfTemplateEngine.region("full_name", "Full name", null, RegionKind.TEXT,
                        new PdfBox(0, 100, 760, 200, 20), 0, null),
                FakePdfTemplateEngine.region("date_of_birth", "Date of birth", null, RegionKind.DATE,
                        new PdfBox(0, 100, 720, 120, 20), 1, null),
                FakePdfTemplateEngine.region("id_number", "NIC", null, RegionKind.TEXT,
                        new PdfBox(0, 100, 680, 120, 20), 2, null),
                FakePdfTemplateEngine.region("notes", "Notes", null, RegionKind.MULTILINE,
                        new PdfBox(0, 100, 600, 300, 60), 3, null),
                FakePdfTemplateEngine.region("agree", "I agree", null, RegionKind.CHECKBOX,
                        new PdfBox(0, 100, 560, 12, 12), 4, null),
                FakePdfTemplateEngine.region("passport_photo", "Passport photo", null, RegionKind.IMAGE,
                        new PdfBox(0, 400, 400, 120, 150), 5, null));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("DM" + tag.substring(0, 6).toUpperCase(), "Data mapping bank"));
        otherBank = banks.save(new Bank("DX" + tag.substring(0, 6).toUpperCase(), "Other bank"));
        owner = user(bank, "dm-owner-" + tag);
        colleague = user(bank, "dm-colleague-" + tag);
        stranger = user(otherBank, "dm-stranger-" + tag);

        customers = new Template();
        customers.setOwnerUserId(owner.getAppUserId());
        customers.setBankId(bank.getBankId());
        customers.setName("Customers " + tag);
        customers.setVisibility(TemplateVisibility.PRIVATE);
        customers = templates.save(customers);
        flowField("full_name", 1);
        flowField("nic", 1);
        flowField("dob", 3);
    }

    @AfterEach
    void cleanUp() {
        engine.reset();
        SecurityContextHolder.clearContext();
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM TemplateRecordKey WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM TemplateRecord WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintSource WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocFieldRule WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocDataFieldRule WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocDataField WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocDataTemplate WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM ImportDetails WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM IngestionJob WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM TemplateFields WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM Template WHERE BankId = ?", bankId);
        }
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
        banks.delete(otherBank);
    }

    // ------------------------------------------------------------ fixtures

    private AppUser user(Bank in, String name) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(name);
        entity.setBankId(in.getBankId());
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private void flowField(String name, int dataTypeId) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(customers.getTemplateId());
        field.setBankId(bank.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(dataTypeId);
        templateFields.save(field);
    }

    /** A data template of {@code by} with fields {@code name:KIND:label[:sourceFieldName]}. */
    private DocDataTemplate dataTemplate(AppUser by, String name, Integer sourceTemplateId, String... fields) {
        DocDataTemplate template = new DocDataTemplate();
        template.setBankId(by.getBankId());
        template.setOwnerUserId(by.getAppUserId());
        template.setName(name);
        template.setSourceTemplateId(sourceTemplateId);
        template.setCreatedAt(LocalDateTime.now());
        template = dataTemplates.save(template);
        int order = 0;
        for (String spec : fields) {
            String[] parts = spec.split(":");
            DocDataField field = new DocDataField();
            field.setBankId(by.getBankId());
            field.setDataTemplateId(template.getDocDataTemplateId());
            field.setFieldName(parts[0]);
            field.setKind(parts[1]);
            field.setLabel(parts[2]);
            field.setSourceFieldName(parts.length > 3 ? parts[3] : null);
            field.setSortOrder(order++);
            dataFields.save(field);
        }
        return template;
    }

    /** The usual data template: same-name, suggested and unmatched fields. */
    private DocDataTemplate customerData(Integer sourceTemplateId) {
        return dataTemplate(owner, "Customer data", sourceTemplateId,
                "full_name:TEXT:Full name:full_name", "dob:DATE:DOB:dob", "nic:TEXT:National ID:nic",
                "consent:CHECKBOX:Consent");
    }

    private DocDataFieldRule dataRule(DocDataTemplate template, String fieldName, String type, String config) {
        DocDataFieldRule rule = new DocDataFieldRule();
        rule.setBankId(template.getBankId());
        rule.setDataTemplateId(template.getDocDataTemplateId());
        rule.setFieldName(fieldName);
        rule.setRuleType(type);
        rule.setConfigJson(config);
        rule.setUpdatedAt(LocalDateTime.now());
        return dataRules.save(rule);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, AppUser user, int expected) throws Exception {
        MvcResult result = mvc.perform(as(request, user)).andExpect(status().is(expected)).andReturn();
        String type = result.getResponse().getContentType();
        if (type != null && !type.contains("json")) {
            return MAPPER.createObjectNode(); // a download
        }
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return call(request, owner, expected);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int readyTemplate(String name) throws Exception {
        JsonNode draft = call(multipart("/api/doccreation/blueprints")
                .file(new MockMultipartFile("file", "form.pdf", "application/pdf", FakePdfTemplateEngine.blankPdf(1, 0)))
                .param("flow", "BLANK").param("name", name), 201);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", 1);
        body.set("regions", draft.get("regions"));
        body.set("edits", MAPPER.createArrayNode());
        body.put("confirm", true);
        call(json(put("/api/doccreation/blueprints/" + draft.get("id").asInt() + "/overlay"), body.toString()), 200);
        return draft.get("id").asInt();
    }

    private JsonNode choose(int blueprintId, Object dataTemplateId, int expected) throws Exception {
        return call(json(put("/api/doccreation/blueprints/" + blueprintId + "/data-template"),
                "{\"dataTemplateId\":" + dataTemplateId + "}"), expected);
    }

    private JsonNode saveMapping(int blueprintId, String mapping, int expected) throws Exception {
        return call(json(put("/api/doccreation/blueprints/" + blueprintId + "/data-mapping"),
                "{\"mapping\":" + mapping + "}"), expected);
    }

    private int document(int blueprintId) throws Exception {
        return call(json(post("/api/doccreation/instances"),
                "{\"blueprintId\":" + blueprintId + ",\"title\":\"Loan file\"}"), 201).get("id").asInt();
    }

    private JsonNode putValues(int instanceId, String values) throws Exception {
        return call(json(put("/api/doccreation/instances/" + instanceId + "/values"), "{\"values\":" + values + "}"),
                200);
    }

    private JsonNode validate(int instanceId) throws Exception {
        return call(post("/api/doccreation/instances/" + instanceId + "/validate"), 200);
    }

    private int revision(int blueprintId) {
        Integer revision = jdbc.queryForObject("SELECT RulesRevision FROM DocBlueprint WHERE DocBlueprintId = ?",
                Integer.class, blueprintId);
        return revision == null ? -1 : revision;
    }

    private static Map<String, JsonNode> rows(JsonNode dto) {
        Map<String, JsonNode> out = new HashMap<>();
        dto.get("rows").forEach(row -> out.put(row.get("regionKey").asText(), row));
        return out;
    }

    private static Map<String, JsonNode> byKey(JsonNode array, String key) {
        Map<String, JsonNode> out = new HashMap<>();
        array.forEach(node -> out.put(node.get(key).asText(), node));
        return out;
    }

    private JsonNode summaryOf(int instanceId) throws Exception {
        for (JsonNode entry : call(get("/api/doccreation/instances"), 200)) {
            if (entry.get("id").asInt() == instanceId) {
                return entry;
            }
        }
        throw new AssertionError("document " + instanceId + " not listed");
    }

    private List<String> auditValues() {
        return jdbc.queryForList("SELECT NewValues FROM AuditEvent WHERE BankId = ? AND Action = ?", String.class,
                bank.getBankId(), "DOC_BLUEPRINT_DATA_MAPPING_SAVED");
    }

    // -------------------------------------------------------- choose + map

    @Test
    void choosingADataTemplatePrefillsTheMappingBySameNameSuggestionAndKind() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate data = customerData(null);
        int before = revision(blueprintId);

        JsonNode chosen = choose(blueprintId, data.getDocDataTemplateId(), 200);

        assertThat(chosen.get("dataTemplateId").asInt()).isEqualTo(data.getDocDataTemplateId());
        assertThat(chosen.get("dataTemplateName").asText()).isEqualTo("Customer data");
        assertThat(chosen.get("revision").asInt()).isEqualTo(before + 1).isEqualTo(revision(blueprintId));
        assertThat(chosen.get("fields")).extracting(f -> f.get("name").asText())
                .containsExactly("full_name", "dob", "nic", "consent");
        Map<String, JsonNode> rows = rows(chosen);
        assertThat(chosen.get("rows")).extracting(r -> r.get("regionKey").asText())
                .containsExactly("full_name", "date_of_birth", "id_number", "notes", "agree", "passport_photo");
        JsonNode fullName = rows.get("full_name");
        assertThat(fullName.get("mappedField").asText()).isEqualTo("full_name");
        assertThat(fullName.get("suggestion").get("sameName").asBoolean()).isTrue();
        assertThat(fullName.get("suggestion").get("score").asDouble()).isEqualTo(1.0);
        assertThat(fullName.get("kind").asText()).isEqualTo("TEXT");
        assertThat(fullName.get("page").asInt()).isZero();
        assertThat(fullName.get("fx").asDouble()).isBetween(0.0, 1.0);
        assertThat(fullName.get("fw").asDouble()).isPositive();
        JsonNode dob = rows.get("date_of_birth");
        assertThat(dob.get("mappedField").asText()).isEqualTo("dob");
        assertThat(dob.get("suggestion").get("sameName").asBoolean()).isFalse();
        assertThat(dob.get("suggestion").get("score").asDouble()).isBetween(0.7, 0.999);
        assertThat(rows.get("id_number").get("mappedField").asText()).as("same name by label (NIC)")
                .isEqualTo("nic");
        // Kind compatibility: a photo box has no IMAGE field; a checkbox only takes a CHECKBOX field.
        assertThat(rows.get("passport_photo").get("mappedField").isNull()).isTrue();
        assertThat(rows.get("passport_photo").get("suggestion").isNull()).isTrue();
        assertThat(rows.get("agree").get("suggestion").isNull()
                || "consent".equals(rows.get("agree").get("suggestion").get("field").asText())).isTrue();
        assertThat(chosen.get("mappableCount").asInt()).as("every region but the photo box").isEqualTo(5);
        assertThat(chosen.get("mappedCount").asInt()).isEqualTo(
                (int) chosen.get("rows").findValues("mappedField").stream().filter(v -> !v.isNull()).count());
        assertThat(chosen.get("mappedCount").asInt()).isGreaterThanOrEqualTo(3);

        JsonNode read = call(get("/api/doccreation/blueprints/" + blueprintId + "/data-mapping"), 200);
        assertThat(read).isEqualTo(chosen);
        // Choosing the same data template again keeps the mapping and the revision.
        assertThat(choose(blueprintId, data.getDocDataTemplateId(), 200).get("revision").asInt())
                .isEqualTo(before + 1);

        // The template list shows the data template and the counts.
        JsonNode listed = byKey(call(get("/api/doccreation/blueprints"), 200), "id").get(String.valueOf(blueprintId));
        assertThat(listed.get("dataTemplateId").asInt()).isEqualTo(data.getDocDataTemplateId());
        assertThat(listed.get("dataTemplateName").asText()).isEqualTo("Customer data");
        assertThat(listed.get("mappedCount").asInt()).isEqualTo(chosen.get("mappedCount").asInt());
        assertThat(listed.get("mappableCount").asInt()).isEqualTo(5);

        List<String> audits = auditValues();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("\"change\":\"CHOSEN\"").doesNotContain("Customer data");
    }

    @Test
    void aMappingSaveIsCheckedAndRaisesTheRevision() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        int untouched = readyTemplate("Other form");
        DocDataTemplate data = customerData(null);

        // No data template chosen yet: nothing to map onto.
        saveMapping(blueprintId, "{\"full_name\":\"full_name\"}", 409);
        choose(blueprintId, data.getDocDataTemplateId(), 200);
        int before = revision(blueprintId);

        saveMapping(blueprintId, "{\"nope\":\"full_name\"}", 400);
        assertThat(saveMapping(blueprintId, "{\"full_name\":\"nope\"}", 400).get("error").asText())
                .contains("not a field of the data template");
        assertThat(saveMapping(blueprintId, "{\"agree\":\"full_name\"}", 400).get("error").asText())
                .contains("agree");
        saveMapping(blueprintId, "{\"full_name\":\"consent\"}", 400);
        saveMapping(blueprintId, "{\"passport_photo\":\"full_name\"}", 400);
        saveMapping(blueprintId, "{\"notes\":\"dob\"}", 400);
        assertThat(revision(blueprintId)).as("a refused save changes nothing").isEqualTo(before);

        // A field may show in several regions; DATE ← TEXT and CHECKBOX ← CHECKBOX fit.
        JsonNode saved = saveMapping(blueprintId, "{\"full_name\":\"full_name\",\"notes\":\"full_name\","
                + "\"agree\":\"consent\",\"date_of_birth\":\"nic\",\"id_number\":\"\"}", 200);
        Map<String, JsonNode> rows = rows(saved);
        assertThat(rows.get("notes").get("mappedField").asText()).isEqualTo("full_name");
        assertThat(rows.get("agree").get("mappedField").asText()).isEqualTo("consent");
        assertThat(rows.get("date_of_birth").get("mappedField").asText()).isEqualTo("nic");
        assertThat(rows.get("id_number").get("mappedField").isNull()).isTrue();
        assertThat(saved.get("mappedCount").asInt()).isEqualTo(4);
        assertThat(saved.get("revision").asInt()).isEqualTo(before + 1).isEqualTo(revision(blueprintId));
        assertThat(jdbc.queryForObject("SELECT DataMappingJson FROM DocBlueprint WHERE DocBlueprintId = ?",
                String.class, blueprintId))
                .isEqualTo("{\"full_name\":\"full_name\",\"date_of_birth\":\"nic\",\"notes\":\"full_name\","
                        + "\"agree\":\"consent\"}");

        // Clearing the choice clears the mapping too; mapping is then refused again.
        JsonNode cleared = choose(blueprintId, null, 200);
        assertThat(cleared.get("dataTemplateId").isNull()).isTrue();
        assertThat(cleared.get("mappedCount").asInt()).isZero();
        assertThat(cleared.get("mappableCount").asInt()).isZero();
        assertThat(cleared.get("fields")).isEmpty();
        assertThat(cleared.get("revision").asInt()).isEqualTo(before + 2);
        assertThat(jdbc.queryForMap("SELECT DataTemplateId, DataMappingJson FROM DocBlueprint "
                + "WHERE DocBlueprintId = ?", blueprintId).values()).containsOnlyNulls();
        saveMapping(blueprintId, "{}", 409);
        call(json(put("/api/doccreation/blueprints/" + blueprintId + "/data-template"), ""), 400);

        JsonNode listed = byKey(call(get("/api/doccreation/blueprints"), 200), "id").get(String.valueOf(untouched));
        assertThat(listed.get("dataTemplateId").isNull()).isTrue();
        assertThat(listed.get("mappedCount").asInt()).isZero();
        assertThat(auditValues()).anyMatch(v -> v.contains("\"change\":\"MAPPING\""))
                .anyMatch(v -> v.contains("\"change\":\"CLEARED\""));
    }

    // ------------------------------------------------------- effective rules

    @Test
    void theDataTemplatesRulesValidateTheMappedFieldsAndARulesChangeNeedsANewValidation() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate data = customerData(null);
        DocDataFieldRule required = dataRule(data, "full_name", "REQUIRED", "{}");
        choose(blueprintId, data.getDocDataTemplateId(), 200);
        int instanceId = document(blueprintId);
        int unchecked = document(blueprintId);
        int failing = document(blueprintId);

        JsonNode draft = validate(instanceId);
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        JsonNode error = draft.get("validation").get("errors").get(0);
        assertThat(error.get("regionKey").asText()).isEqualTo("full_name");
        assertThat(error.get("code").asText()).isEqualTo("REQUIRED");
        assertThat(error.get("ruleId").asLong()).isEqualTo(-required.getDocDataFieldRuleId());
        JsonNode check = draft.get("fieldChecks").get("full_name").get("rules").get(0);
        assertThat(check.get("origin").asText()).isEqualTo("DATA_TEMPLATE");
        assertThat(check.get("sourceTemplateName").asText()).isEqualTo("Customer data");
        assertThat(check.get("id").asLong()).isNegative();
        assertThat(byKey(draft.get("regions"), "key").get("full_name").get("required").asBoolean()).isTrue();
        // The template editor shows the inherited Required flag too.
        JsonNode template = call(get("/api/doccreation/blueprints/" + blueprintId), 200);
        assertThat(byKey(template.get("regions"), "key").get("full_name").get("required").asBoolean()).isTrue();

        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        assertThat(validate(instanceId).get("status").asText()).isEqualTo("VALIDATED");
        call(post("/api/doccreation/instances/" + instanceId + "/generate"), 200);
        call(get("/api/doccreation/instances/" + instanceId + "/download"), 200);
        validate(failing);
        assertThat(summaryOf(instanceId).get("validationState").asText()).isEqualTo("VALIDATED");
        assertThat(summaryOf(unchecked).get("validationState").asText()).isEqualTo("NOT_CHECKED");
        assertThat(summaryOf(unchecked).get("errorCount").asInt()).isZero();
        assertThat(summaryOf(failing).get("validationState").asText()).isEqualTo("ERRORS");
        assertThat(summaryOf(failing).get("errorCount").asInt()).isEqualTo(1);

        // The mapping changes: the generated document must be validated again.
        saveMapping(blueprintId, "{\"full_name\":\"full_name\"}", 200);
        JsonNode refused = call(post("/api/doccreation/instances/" + instanceId + "/generate"), 409);
        assertThat(refused.get("code").asText()).isEqualTo("NOT_VALIDATED");
        assertThat(refused.get("error").asText())
                .isEqualTo("The rules changed since this document was validated; save it again to re-check.");
        assertThat(call(get("/api/doccreation/instances/" + instanceId + "/download"), 409).get("code").asText())
                .isEqualTo("NOT_VALIDATED");
        assertThat(summaryOf(instanceId).get("validationState").asText()).isEqualTo("RULES_CHANGED");
        assertThat(summaryOf(instanceId).get("status").asText()).isEqualTo("GENERATED");

        assertThat(validate(instanceId).get("validation").get("state").asText()).isEqualTo("VALIDATED");
        call(post("/api/doccreation/instances/" + instanceId + "/generate"), 200);
        call(get("/api/doccreation/instances/" + instanceId + "/download"), 200);
        assertThat(summaryOf(instanceId).get("validationState").asText()).isEqualTo("VALIDATED");

        // Choosing (or clearing) the data template changes the rules as well.
        choose(blueprintId, null, 200);
        call(post("/api/doccreation/instances/" + instanceId + "/generate"), 409);
        // Without the data template the field is no longer required.
        assertThat(byKey(validate(failing).get("regions"), "key").get("full_name").get("required").asBoolean())
                .isFalse();
        assertThat(summaryOf(failing).get("validationState").asText()).isEqualTo("VALIDATED");
    }

    @Test
    void requiredIfIsRekeyedToTheMappedRegionAndTheRulesListShowsTheInheritedRules() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate data = customerData(null);
        DocDataFieldRule requiredIf = dataRule(data, "nic", "REQUIRED_IF", "{\"Field\":\"dob\"}");
        DocDataFieldRule dangling = dataRule(data, "full_name", "REQUIRED_IF", "{\"Field\":\"consent\"}");
        choose(blueprintId, data.getDocDataTemplateId(), 200);
        // consent stays unmapped: the rule naming it becomes a setup warning
        saveMapping(blueprintId, "{\"full_name\":\"full_name\",\"date_of_birth\":\"dob\",\"id_number\":\"nic\"}",
                200);

        JsonNode rules = call(get("/api/doccreation/blueprints/" + blueprintId + "/rules"), 200);
        assertThat(rules.get("rules")).as("the PDF's own rules only").isEmpty();
        Map<String, JsonNode> inherited = byKey(rules.get("inherited"), "regionKey");
        assertThat(inherited).containsOnlyKeys("id_number", "full_name");
        JsonNode rekeyed = inherited.get("id_number");
        assertThat(rekeyed.get("id").asLong()).isEqualTo(-requiredIf.getDocDataFieldRuleId());
        assertThat(rekeyed.get("origin").asText()).isEqualTo("DATA_TEMPLATE");
        assertThat(rekeyed.get("ruleType").asText()).isEqualTo("REQUIRED_IF");
        assertThat(rekeyed.get("config").get("Field").asText()).isEqualTo("date_of_birth");
        assertThat(rekeyed.get("sourceTemplateName").asText()).isEqualTo("Customer data");
        assertThat(rekeyed.get("orphan").asBoolean()).isFalse();

        int instanceId = document(blueprintId);
        putValues(instanceId, "{\"date_of_birth\":\"1990-01-01\",\"full_name\":\"Jane\"}");
        JsonNode validation = validate(instanceId).get("validation");
        assertThat(validation.get("errors")).extracting(e -> e.get("regionKey").asText() + ":" + e.get("code").asText())
                .containsExactly("id_number:REQUIRED_IF");
        assertThat(validation.get("errors").get(0).get("message").asText()).contains("Date of birth");
        assertThat(validation.get("setupWarnings")).extracting(w -> w.get("ruleId").asLong())
                .containsExactly(-dangling.getDocDataFieldRuleId());

        // /rules/test checks the effective rules as well (keys are region keys).
        JsonNode tested = call(json(post("/api/doccreation/blueprints/" + blueprintId + "/rules/test"),
                "{\"values\":{\"date_of_birth\":\"1990-01-01\"}}"), 200);
        assertThat(tested.get("errors")).extracting(e -> e.get("regionKey").asText()).contains("id_number");
        JsonNode testedWithUnsaved = call(json(post("/api/doccreation/blueprints/" + blueprintId + "/rules/test"),
                "{\"values\":{\"date_of_birth\":\"1990-01-01\",\"id_number\":\"J1\"},\"rules\":[{\"regionKey\":"
                        + "\"notes\",\"ruleType\":\"REQUIRED\",\"config\":{}}]}"), 200);
        assertThat(testedWithUnsaved.get("errors")).extracting(e -> e.get("regionKey").asText())
                .containsExactly("notes");

        // Saving the PDF's own rules leaves the inherited ones alone.
        int base = rules.get("revision").asInt();
        JsonNode saved = call(json(put("/api/doccreation/blueprints/" + blueprintId + "/rules"),
                "{\"baseRevision\":" + base + ",\"rules\":[{\"regionKey\":\"notes\",\"ruleType\":\"LENGTH\","
                        + "\"config\":{\"Max\":40}}]}"), 200);
        assertThat(saved.get("rules")).hasSize(1);
        assertThat(saved.get("inherited")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocFieldRule WHERE BlueprintId = ?", Integer.class,
                blueprintId)).isEqualTo(1);

        // An overlay save with the inherited Required flag adds no PDF rule of its own.
        dataRule(data, "full_name", "REQUIRED", "{}");
        JsonNode template = call(get("/api/doccreation/blueprints/" + blueprintId), 200);
        ObjectNode overlay = MAPPER.createObjectNode();
        overlay.put("baseVersion", template.get("currentVersion").asInt());
        overlay.set("regions", template.get("regions"));
        overlay.set("edits", MAPPER.createArrayNode());
        overlay.put("confirm", true);
        assertThat(byKey(template.get("regions"), "key").get("full_name").get("required").asBoolean()).isTrue();
        call(json(put("/api/doccreation/blueprints/" + blueprintId + "/overlay"), overlay.toString()), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocFieldRule WHERE BlueprintId = ? "
                + "AND RuleType = 'REQUIRED'", Integer.class, blueprintId)).isZero();
    }

    // -------------------------------------------------------- managed source

    @Test
    void theManagedPrimarySourceFollowsTheMappingAndAHandMadeOneIsNeverTouched() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate copied = customerData(customers.getTemplateId());
        DocDataTemplate scratch = dataTemplate(owner, "From scratch", null, "full_name:TEXT:Full name");

        choose(blueprintId, copied.getDocDataTemplateId(), 200);
        JsonNode sources = call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200);
        assertThat(sources).hasSize(1);
        JsonNode managed = sources.get(0);
        assertThat(managed.get("role").asText()).isEqualTo("PRIMARY");
        assertThat(managed.get("managed").asBoolean()).isTrue();
        assertThat(managed.get("templateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(managed.get("mapping").get("full_name").asText()).isEqualTo("full_name");
        assertThat(managed.get("mapping").get("date_of_birth").asText()).isEqualTo("dob");
        assertThat(managed.get("mapping").get("id_number").asText()).isEqualTo("nic");

        saveMapping(blueprintId, "{\"full_name\":\"full_name\",\"notes\":\"nic\"}", 200);
        JsonNode updated = call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200).get(0);
        assertThat(updated.get("id").asLong()).isEqualTo(managed.get("id").asLong());
        assertThat(MAPPER.convertValue(updated.get("mapping"), Map.class))
                .isEqualTo(Map.of("full_name", "full_name", "notes", "nic"));

        // A data template without a flow template: the managed source goes.
        choose(blueprintId, scratch.getDocDataTemplateId(), 200);
        assertThat(call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200)).isEmpty();
        choose(blueprintId, copied.getDocDataTemplateId(), 200);
        assertThat(call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200)).hasSize(1);
        choose(blueprintId, null, 200);
        assertThat(call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200)).isEmpty();
        assertThat(auditValues()).anyMatch(v -> v.contains("\"managedSource\":\"CREATED\""))
                .anyMatch(v -> v.contains("\"managedSource\":\"DELETED\""));

        // A PRIMARY made by hand stays exactly as it was.
        int handMade = readyTemplate("Hand-made form");
        JsonNode own = call(json(post("/api/doccreation/blueprints/" + handMade + "/sources"),
                "{\"templateId\":" + customers.getTemplateId() + ",\"role\":\"PRIMARY\"}"), 201);
        assertThat(own.get("managed").asBoolean()).isFalse();
        call(json(put("/api/doccreation/blueprints/" + handMade + "/sources/" + own.get("id").asLong()),
                "{\"mapping\":{\"notes\":\"full_name\"}}"), 200);
        choose(handMade, copied.getDocDataTemplateId(), 200);
        saveMapping(handMade, "{\"full_name\":\"full_name\"}", 200);
        choose(handMade, scratch.getDocDataTemplateId(), 200);
        JsonNode kept = call(get("/api/doccreation/blueprints/" + handMade + "/sources"), 200);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).get("id").asLong()).isEqualTo(own.get("id").asLong());
        assertThat(kept.get(0).get("managed").asBoolean()).isFalse();
        assertThat(MAPPER.convertValue(kept.get(0).get("mapping"), Map.class)).isEqualTo(Map.of("notes", "full_name"));
    }

    @Test
    void theManagedSourceMapsOnlyTheFieldsOfTheMainFlowTemplate() throws Exception {
        Template orders = new Template();
        orders.setOwnerUserId(owner.getAppUserId());
        orders.setBankId(bank.getBankId());
        orders.setName("Orders " + UUID.randomUUID().toString().substring(0, 8));
        orders.setVisibility(TemplateVisibility.PRIVATE);
        orders = templates.save(orders);
        int blueprintId = readyTemplate("Merged form");
        DocDataTemplate merged = customerData(customers.getTemplateId());
        // V333: full_name came from the main flow template, nic from another one with a same-named field
        jdbc.update("UPDATE DocDataField SET SourceTemplateId = ? WHERE DataTemplateId = ? AND FieldName = ?",
                customers.getTemplateId(), merged.getDocDataTemplateId(), "full_name");
        jdbc.update("UPDATE DocDataField SET SourceTemplateId = ? WHERE DataTemplateId = ? AND FieldName = ?",
                orders.getTemplateId(), merged.getDocDataTemplateId(), "nic");

        JsonNode chosen = choose(blueprintId, merged.getDocDataTemplateId(), 200);
        assertThat(rows(chosen).get("id_number").get("mappedField").asText()).isEqualTo("nic");
        JsonNode managed = call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), 200).get(0);
        assertThat(managed.get("templateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(MAPPER.convertValue(managed.get("mapping"), Map.class)).as("nic is not the main template's")
                .isEqualTo(Map.of("full_name", "full_name", "date_of_birth", "dob"));
    }

    // ------------------------------------------------------- fill preview

    @Test
    void theFillPreviewMarksTheValuesThatWouldFailTheirChecks() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate data = customerData(customers.getTemplateId());
        dataRule(data, "full_name", "LENGTH", "{\"Max\":4}");
        dataRule(data, "dob", "DATE_RANGE", "{\"Max\":\"-18y\"}");
        choose(blueprintId, data.getDocDataTemplateId(), 200);
        int instanceId = document(blueprintId);

        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(customers.getTemplateId());
        job.setFileName("dm-" + UUID.randomUUID() + ".csv");
        String rows = "{\"columns\":[\"full_name\",\"nic\",\"dob\"],\"rows\":["
                + "{\"full_name\":\"Jane Citizen\",\"nic\":\"J1234567\",\"dob\":\"2020-01-01\"}]}";
        job.setParsedTableJson(rows);
        job.setMappingJson("{}");
        job.setMappedRowsJson(rows);
        job.setValidationResultJson("[]");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        job = jobs.save(job);
        indexer.reindex(bank.getBankId(), job.getIngestionJobId());
        Long recordId = jdbc.queryForObject("SELECT TemplateRecordId FROM TemplateRecord WHERE IngestionJobId = ?",
                Long.class, job.getIngestionJobId());

        JsonNode preview = call(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                .param("recordId", String.valueOf(recordId)), 200);
        assertThat(preview.get("mappingSource").asText()).as("the managed source's mapping").isEqualTo("SAVED");
        Map<String, JsonNode> fields = byKey(preview.get("fields"), "regionKey");
        assertThat(fields.get("full_name").get("wouldFail")).extracting(c -> c.get("code").asText())
                .containsExactly("LENGTH");
        assertThat(fields.get("date_of_birth").get("wouldFail")).extracting(c -> c.get("code").asText())
                .containsExactly("DATE_RANGE");
        assertThat(fields.get("id_number").get("wouldFail")).isEmpty();
        assertThat(preview.get("fields").findValues("wouldFail").toString())
                .doesNotContain("Jane").doesNotContain("2020");
        // Nothing was written by the preview.
        assertThat(call(get("/api/doccreation/instances/" + instanceId), 200).get("values")).isEmpty();
    }

    // ------------------------------------------------------------ isolation

    @Test
    void otherOwnersAndBanksGetA404() throws Exception {
        int blueprintId = readyTemplate("Loan form");
        DocDataTemplate own = customerData(null);
        DocDataTemplate colleagues = dataTemplate(colleague, "Colleague data", null, "full_name:TEXT:Full name");
        DocDataTemplate foreign = dataTemplate(stranger, "Foreign data", null, "full_name:TEXT:Full name");

        int before = revision(blueprintId);
        choose(blueprintId, colleagues.getDocDataTemplateId(), 404);
        choose(blueprintId, foreign.getDocDataTemplateId(), 404);
        choose(blueprintId, 999999, 404);
        assertThat(revision(blueprintId)).isEqualTo(before);
        choose(blueprintId, own.getDocDataTemplateId(), 200);

        for (AppUser other : List.of(colleague, stranger)) {
            call(get("/api/doccreation/blueprints/" + blueprintId + "/data-mapping"), other, 404);
            call(json(put("/api/doccreation/blueprints/" + blueprintId + "/data-template"),
                    "{\"dataTemplateId\":null}"), other, 404);
            call(json(put("/api/doccreation/blueprints/" + blueprintId + "/data-mapping"),
                    "{\"mapping\":{}}"), other, 404);
        }
        int colleaguesBlueprint;
        AppUser saved = owner;
        try {
            owner = colleague;
            colleaguesBlueprint = readyTemplate("Colleague form");
            choose(colleaguesBlueprint, own.getDocDataTemplateId(), 404);
        } finally {
            owner = saved;
        }
        assertThat(call(get("/api/doccreation/blueprints/" + blueprintId + "/data-mapping"), 200)
                .get("dataTemplateId").asInt()).isEqualTo(own.getDocDataTemplateId());
    }
}
