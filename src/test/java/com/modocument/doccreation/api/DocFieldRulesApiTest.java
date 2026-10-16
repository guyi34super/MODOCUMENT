package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.pdftemplate.Align;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.RegionSource;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.ValidationRuleJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * The field rules of a PDF template over HTTP
 * (docs/doccreation/field-validation-contract.md §2/§3): read and replace
 * with every save check (400), the revision (409), ownership (404) and
 * permissions (403), counts-only audit; the Required flag derived from the
 * rules and kept in step by the overlay save; field properties and fonts in
 * the template; the import from a chosen flow template (auto-match,
 * conversion table, EMAIL preset, the Dayforce message, skipped types) and
 * from the saved data sources, MERGE and REPLACE; and the rules deleted
 * with their template.
 */
class DocFieldRulesApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMAIL_PRESET = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    private static final String FIRST_NAME_PATTERN = "^[A-Za-z _.&/()@#-]{1,25}$";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsJpaRepository templateFields;
    @Autowired
    private ValidationRuleJpaRepository flowRules;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FakePdfTemplateEngine engine;

    private Bank bank;
    private Bank otherBank;
    private AppUser owner;
    private AppUser colleague;
    private AppUser reader;
    private AppUser stranger;
    private Template customers;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        engine.proposedRegions = List.of(
                region("first_name", "First name", RegionKind.TEXT, 700, 0, true),
                region("email", "Email", RegionKind.TEXT, 660, 1, false),
                region("date_of_birth", "Date of birth", RegionKind.DATE, 620, 2, false),
                region("amount", "Amount", RegionKind.NUMBER, 580, 3, false),
                region("notes", "Notes", RegionKind.MULTILINE, 500, 4, false),
                region("passport_photo", "Passport photo", RegionKind.IMAGE, 300, 5, false),
                region("agree", "I agree", RegionKind.CHECKBOX, 250, 6, false));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("RU" + tag.substring(0, 6).toUpperCase(), "Field rules bank"));
        otherBank = banks.save(new Bank("RX" + tag.substring(0, 6).toUpperCase(), "Other bank"));
        owner = user(bank, "fr-owner-" + tag, Role.READ_WRITE);
        colleague = user(bank, "fr-colleague-" + tag, Role.READ_WRITE);
        reader = user(bank, "fr-reader-" + tag, Role.READ_ONLY);
        stranger = user(otherBank, "fr-stranger-" + tag, Role.READ_WRITE);

        customers = template(owner, "Customers " + tag, TemplateVisibility.PRIVATE);
        field(customers, "FirstName", 1);
        field(customers, "Email", 1);
        field(customers, "date_of_birth", 3);
        field(customers, "Amount", 2);
        field(customers, "Notes", 1);
        flowRule(customers, "FirstName", RuleType.REQUIRED, "{}");
        flowRule(customers, "FirstName", RuleType.REGEX, "{\"Pattern\":\"" + json(FIRST_NAME_PATTERN) + "\"}");
        flowRule(customers, "Email", RuleType.REGEX, "{\"Pattern\":\"" + json(EMAIL_PRESET) + "\"}");
        flowRule(customers, "date_of_birth", RuleType.DATE_FORMAT, "{}");
        flowRule(customers, "date_of_birth", RuleType.EXPIRY_DATE, "{}");
        flowRule(customers, "Amount", RuleType.RANGE, "{\"Min\":0,\"Max\":100}");
        flowRule(customers, "Amount", RuleType.REGEX, "{\"Pattern\":\"^\\\\d+$\"}");
        flowRule(customers, "Notes", RuleType.CUSTOM, "{}");
        flowRule(customers, "Notes", RuleType.TYPE, "{}");
        flowRule(customers, "Notes", RuleType.CONDITIONAL_REQUIREMENT, "{}");
    }

    @AfterEach
    void cleanUp() {
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM DocFieldRule WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintSource WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM ValidationRules WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM TemplateFields WHERE BankId = ?", bankId);
            // The suite shares one H2 schema: a row another suite left behind may name a recycled
            // template id; it would block deleting this suite's template.
            jdbc.update("DELETE FROM ImportDetails WHERE ImportHistoryId IN (SELECT ImportHistoryId FROM "
                    + "ImportHistory WHERE TemplateId IN (SELECT TemplateId FROM Template WHERE BankId = ?))", bankId);
            jdbc.update("DELETE FROM ImportHistory WHERE TemplateId IN (SELECT TemplateId FROM Template "
                    + "WHERE BankId = ?)", bankId);
            jdbc.update("DELETE FROM Template WHERE BankId = ?", bankId);
        }
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
        banks.delete(otherBank);
    }

    // ------------------------------------------------------------ fixtures

    private static TemplateRegion region(String key, String label, RegionKind kind, float y, int sortOrder,
                                         boolean required) {
        float h = kind == RegionKind.CHECKBOX ? 12 : kind == RegionKind.IMAGE ? 120 : 20;
        float w = kind == RegionKind.CHECKBOX ? 12 : 200;
        int maxLines = kind == RegionKind.MULTILINE ? 3 : kind == RegionKind.IMAGE || kind == RegionKind.CHECKBOX
                ? 0 : 1;
        return new TemplateRegion(key, label, null, kind, new PdfBox(0, 100, y, w, h), "Helvetica", 0f, "#000000",
                Align.LEFT, maxLines, null, required, sortOrder, RegionSource.DETECTED, 0.9, null);
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

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

    private Template template(AppUser by, String name, TemplateVisibility visibility) {
        Template template = new Template();
        template.setOwnerUserId(by.getAppUserId());
        template.setBankId(by.getBankId());
        template.setName(name);
        template.setVisibility(visibility);
        return templates.save(template);
    }

    private void field(Template template, String name, int dataTypeId) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(template.getTemplateId());
        field.setBankId(template.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(dataTypeId);
        templateFields.save(field);
    }

    private void flowRule(Template template, String fieldName, RuleType type, String config) {
        ValidationRule rule = new ValidationRule(template.getTemplateId(), fieldName, type, config);
        rule.setBankId(template.getBankId());
        flowRules.save(rule);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, AppUser user, int expected) throws Exception {
        MvcResult result = mvc.perform(as(request, user)).andExpect(status().is(expected)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode uploadDraft() throws Exception {
        MvcResult result = mvc.perform(as(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "form.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0)))
                        .param("flow", "BLANK").param("name", "Customer form"), owner))
                .andExpect(status().isCreated()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private int readyTemplate() throws Exception {
        JsonNode draft = uploadDraft();
        int id = draft.get("id").asInt();
        saveOverlay(id, 1, (ArrayNode) draft.get("regions"), 200);
        return id;
    }

    private JsonNode saveOverlay(int blueprintId, int baseVersion, ArrayNode regions, int expected)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", baseVersion);
        body.set("regions", regions);
        body.set("edits", MAPPER.createArrayNode());
        body.put("confirm", true);
        return call(put("/api/doccreation/blueprints/" + blueprintId + "/overlay")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()), owner, expected);
    }

    private JsonNode blueprint(int id) throws Exception {
        return call(get("/api/doccreation/blueprints/" + id), owner, 200);
    }

    private JsonNode rules(int id, AppUser user, int expected) throws Exception {
        return call(get("/api/doccreation/blueprints/" + id + "/rules"), user, expected);
    }

    private JsonNode putRules(int id, Integer baseRevision, String rulesJson, int expected) throws Exception {
        return putRules(id, baseRevision, rulesJson, owner, expected);
    }

    private JsonNode putRules(int id, Integer baseRevision, String rulesJson, AppUser user, int expected)
            throws Exception {
        String body = "{" + (baseRevision == null ? "" : "\"baseRevision\":" + baseRevision + ",")
                + "\"rules\":" + rulesJson + "}";
        return call(put("/api/doccreation/blueprints/" + id + "/rules").contentType(MediaType.APPLICATION_JSON)
                .content(body), user, expected);
    }

    private JsonNode preview(int id, Integer templateId, AppUser user, int expected) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/doccreation/blueprints/" + id + "/rules/import-preview");
        if (templateId != null) {
            request.param("templateId", String.valueOf(templateId));
        }
        return call(request, user, expected);
    }

    private JsonNode importRules(int id, String body, int expected) throws Exception {
        return call(post("/api/doccreation/blueprints/" + id + "/rules/import")
                .contentType(MediaType.APPLICATION_JSON).content(body), owner, expected);
    }

    private static JsonNode regionOf(JsonNode blueprint, String key) {
        for (JsonNode region : blueprint.get("regions")) {
            if (key.equals(region.get("key").asText())) {
                return region;
            }
        }
        throw new AssertionError("no region " + key);
    }

    private static List<JsonNode> rulesOf(JsonNode rulesDto, String key) {
        List<JsonNode> out = new ArrayList<>();
        rulesDto.get("rules").forEach(r -> {
            if (key.equals(r.get("regionKey").asText())) {
                out.add(r);
            }
        });
        return out;
    }

    private static JsonNode previewField(JsonNode preview, String key) {
        for (JsonNode field : preview.get("fields")) {
            if (key.equals(field.get("regionKey").asText())) {
                return field;
            }
        }
        throw new AssertionError("no preview field " + key);
    }

    private List<String> auditValues(String action) {
        return jdbc.queryForList("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent WHERE BankId = ? AND Action = ? "
                        + "ORDER BY AuditEventId",
                String.class, bank.getBankId(), action);
    }

    private int ruleRows(int blueprintId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DocFieldRule WHERE BlueprintId = ?",
                Integer.class, blueprintId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------ read/save

    @Test
    void rulesAreReadAndReplacedWithARevisionAndCountsOnlyAudit() throws Exception {
        int id = readyTemplate();
        JsonNode initial = rules(id, owner, 200);
        // the upload's required region became a REGION_FLAG rule
        assertThat(initial.get("revision").asInt()).isEqualTo(1);
        assertThat(rulesOf(initial, "first_name")).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("ruleType").asText()).isEqualTo("REQUIRED");
                    assertThat(r.get("origin").asText()).isEqualTo("REGION_FLAG");
                });
        assertThat(initial.get("fields")).extracting(f -> f.get("key").asText())
                .containsExactly("first_name", "email", "date_of_birth", "amount", "notes", "passport_photo",
                        "agree");
        assertThat(initial.get("fields").get(2).get("kind").asText()).isEqualTo("DATE");
        JsonNode dateRange = null;
        for (JsonNode type : initial.get("ruleTypes")) {
            if ("DATE_RANGE".equals(type.get("type").asText())) {
                dateRange = type;
            }
        }
        assertThat(dateRange).isNotNull();
        assertThat(dateRange.get("kinds")).extracting(JsonNode::asText).containsExactly("DATE", "TEXT");
        assertThat(dateRange.get("configKeys")).extracting(JsonNode::asText).containsExactly("Min", "Max");
        assertThat(initial.get("ruleTypes")).hasSize(11);

        String set = "[{\"regionKey\":\"email\",\"ruleType\":\"EMAIL\",\"config\":{}},"
                + "{\"regionKey\":\"date_of_birth\",\"ruleType\":\"DATE_RANGE\",\"config\":{\"Max\":\"-18Y\","
                + "\"Ignored\":1},\"message\":\"You must be an adult.\",\"severity\":\"warning\"},"
                + "{\"regionKey\":\"email\",\"ruleType\":\"regex\",\"config\":{\"Pattern\":\"@acme\\\\.com$\"}},"
                + "{\"regionKey\":\"amount\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":\"1,000\",\"Max\":5000}},"
                + "{\"regionKey\":\"agree\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"email\"}},"
                + "{\"regionKey\":\"notes\",\"ruleType\":\"COMPARE\",\"config\":{\"Field\":\"first_name\","
                + "\"Op\":\"!=\"}}]";
        JsonNode saved = putRules(id, 1, set, 200);
        assertThat(saved.get("revision").asInt()).isEqualTo(2);
        assertThat(saved.get("rules")).hasSize(6);
        assertThat(rulesOf(saved, "first_name")).as("the whole set is replaced").isEmpty();
        List<JsonNode> email = rulesOf(saved, "email");
        assertThat(email).extracting(r -> r.get("ruleType").asText()).containsExactly("EMAIL", "REGEX");
        assertThat(email).extracting(r -> r.get("sortOrder").asInt()).containsExactly(0, 1);
        assertThat(email.get(0).get("id").asLong()).isPositive();
        assertThat(email.get(0).get("origin").asText()).isEqualTo("MANUAL");
        JsonNode dob = rulesOf(saved, "date_of_birth").get(0);
        assertThat(dob.get("config").toString()).as("normalised, unknown keys dropped")
                .isEqualTo("{\"Max\":\"-18y\"}");
        assertThat(dob.get("severity").asText()).isEqualTo("WARNING");
        assertThat(dob.get("message").asText()).isEqualTo("You must be an adult.");
        assertThat(dob.get("orphan").asBoolean()).isFalse();
        assertThat(rulesOf(saved, "amount").get(0).get("config").get("Min").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(rules(id, owner, 200)).isEqualTo(saved);

        // a stale revision is refused and changes nothing
        putRules(id, 1, "[]", 409);
        assertThat(rules(id, owner, 200).get("revision").asInt()).isEqualTo(2);

        List<String> audit = auditValues("DOC_FIELD_RULES_SAVED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("\"revision\":2").contains("\"rules\":6").contains("\"EMAIL\":1")
                .contains("\"WARNING\":1");
        assertThat(audit.get(0)).doesNotContain("acme").doesNotContain("adult").doesNotContain("-18y");
    }

    @Test
    void everySaveCheckAnswers400AndKeepsTheRules() throws Exception {
        int id = readyTemplate();
        String fifty = "{\"regionKey\":\"notes\",\"ruleType\":\"REQUIRED\"},".repeat(51);
        List<String> bad = List.of(
                "[{\"regionKey\":\"email\",\"ruleType\":\"SPELLCHECK\"}]",
                "[{\"regionKey\":\"nope\",\"ruleType\":\"REQUIRED\"}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"REGEX\",\"config\":{\"Pattern\":\"([a-z\"}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"REGEX\",\"config\":{\"Pattern\":\"" + "a".repeat(2001)
                        + "\"}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"LENGTH\",\"config\":{\"Min\":5,\"Max\":2}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"LENGTH\",\"config\":{}}]",
                "[{\"regionKey\":\"amount\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":10,\"Max\":-1}}]",
                "[{\"regionKey\":\"amount\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":\"ten\"}}]",
                "[{\"regionKey\":\"date_of_birth\",\"ruleType\":\"DATE_RANGE\",\"config\":{\"Min\":\"today\","
                        + "\"Max\":\"-1d\"}}]",
                "[{\"regionKey\":\"date_of_birth\",\"ruleType\":\"DATE_RANGE\",\"config\":{\"Min\":\"yesterday\"}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"ALLOWED_VALUES\",\"config\":{\"Values\":["
                        + "\"a\",".repeat(200) + "\"b\"]}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"ALLOWED_VALUES\",\"config\":{\"Values\":[\""
                        + "x".repeat(201) + "\"]}}]",
                "[{\"regionKey\":\"date_of_birth\",\"ruleType\":\"DATE_FORMAT\",\"config\":{\"Formats\":[\"ppp{\"]}}]",
                "[{\"regionKey\":\"date_of_birth\",\"ruleType\":\"DATE_FORMAT\",\"config\":{\"Formats\":["
                        + "\"yyyy\",".repeat(20) + "\"dd\"]}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"email\"}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"ghost\"}}]",
                "[{\"regionKey\":\"amount\",\"ruleType\":\"COMPARE\",\"config\":{\"Field\":\"date_of_birth\","
                        + "\"Op\":\"<>\"}}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"EMAIL\",\"message\":\"" + "m".repeat(301) + "\"}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"EMAIL\",\"severity\":\"FATAL\"}]",
                "[{\"regionKey\":\"email\",\"ruleType\":\"EMAIL\",\"origin\":\"ROBOT\"}]",
                "[" + fifty.substring(0, fifty.length() - 1) + "]");
        for (String rulesJson : bad) {
            JsonNode error = putRules(id, null, rulesJson, 400);
            assertThat(error.get("error").asText()).as(rulesJson.length() > 120 ? rulesJson.substring(0, 120)
                    : rulesJson).isNotBlank();
        }
        JsonNode after = rules(id, owner, 200);
        assertThat(after.get("revision").asInt()).isEqualTo(1);
        assertThat(after.get("rules")).hasSize(1);
        assertThat(putRules(id, null, "[{\"regionKey\":\"nope\",\"ruleType\":\"REQUIRED\"}]", 400).get("error")
                .asText()).contains("nope").contains("current version");
    }

    @Test
    void onlyTheOwnerReachesTheRulesAndWritingNeedsDocWrite() throws Exception {
        int id = readyTemplate();
        rules(id, colleague, 404);
        rules(id, stranger, 404);
        rules(id, reader, 404);
        putRules(id, null, "[]", reader, 403);
        putRules(id, null, "[]", colleague, 404);
        putRules(id, null, "[]", stranger, 404);
        preview(id, null, colleague, 404);
        preview(id, null, reader, 403);
        call(post("/api/doccreation/blueprints/" + id + "/rules/import").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), reader, 403);
        call(post("/api/doccreation/blueprints/" + id + "/rules/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{}}"), colleague, 404);
        rules(424242, owner, 404);
        assertThat(rules(id, owner, 200).get("revision").asInt()).isEqualTo(1);
    }

    // ------------------------------------------- required, props and fonts

    @Test
    void theRequiredFlagIsDerivedFromTheRulesAndTheOverlayKeepsThemInStep() throws Exception {
        JsonNode draft = uploadDraft();
        int id = draft.get("id").asInt();
        assertThat(regionOf(draft, "first_name").get("required").asBoolean()).isTrue();
        assertThat(regionOf(draft, "email").get("required").asBoolean()).isFalse();
        assertThat(draft.get("fonts")).extracting(JsonNode::asText).contains("LiberationSans",
                "LiberationSerif-Bold", "LiberationMono-BoldItalic").hasSize(12);

        // the old Required checkbox: email ticked, first_name unticked
        ArrayNode regions = (ArrayNode) draft.get("regions");
        ((ObjectNode) regionOf(draft, "email")).put("required", true);
        ((ObjectNode) regionOf(draft, "first_name")).put("required", false);
        JsonNode ready = saveOverlay(id, 1, regions, 200);
        assertThat(regionOf(ready, "email").get("required").asBoolean()).isTrue();
        assertThat(regionOf(ready, "first_name").get("required").asBoolean()).isFalse();
        JsonNode afterOverlay = rules(id, owner, 200);
        assertThat(afterOverlay.get("revision").asInt()).isEqualTo(2);
        assertThat(rulesOf(afterOverlay, "email")).singleElement()
                .satisfies(r -> assertThat(r.get("origin").asText()).isEqualTo("MANUAL"));
        assertThat(rulesOf(afterOverlay, "first_name")).isEmpty();

        // the rules are the truth: removing the REQUIRED rule clears the flag although the column says true
        putRules(id, 2, "[{\"regionKey\":\"amount\",\"ruleType\":\"REQUIRED\",\"origin\":\"IMPORTED\","
                + "\"sourceTemplateId\":" + customers.getTemplateId() + ",\"sourceFieldName\":\"Amount\"}]", 200);
        JsonNode shown = blueprint(id);
        assertThat(regionOf(shown, "email").get("required").asBoolean()).isFalse();
        assertThat(regionOf(shown, "amount").get("required").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT Required FROM DocTemplateRegion WHERE BlueprintId = ? AND "
                + "RegionKey = 'email' AND Version = 1", Boolean.class, id)).as("written for rollback").isTrue();

        // unticking an imported REQUIRED keeps it; an unchanged overlay save keeps the revision
        ((ObjectNode) regionOf(shown, "amount")).put("required", false);
        JsonNode v2 = saveOverlay(id, 1, (ArrayNode) shown.get("regions"), 200);
        assertThat(regionOf(v2, "amount").get("required").asBoolean()).isTrue();
        assertThat(rules(id, owner, 200).get("revision").asInt()).isEqualTo(3);
        JsonNode sourced = rulesOf(rules(id, owner, 200), "amount").get(0);
        assertThat(sourced.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
    }

    @Test
    void fieldPropertiesAreSavedWithTheOverlayAndChoiceNeedsOptions() throws Exception {
        int id = readyTemplate();
        JsonNode shown = blueprint(id);
        assertThat(regionOf(shown, "email").get("props").isNull()).isTrue();
        ObjectNode notes = (ObjectNode) regionOf(shown, "notes");
        notes.put("kind", "CHOICE");
        notes.set("props", MAPPER.readTree("{\"options\":[\"Single\",\"Married\"],\"defaultValue\":\"Single\","
                + "\"placeholder\":\"Pick one\",\"dateFormat\":\"dd/MM/yyyy\"}"));
        ((ObjectNode) regionOf(shown, "date_of_birth")).set("props",
                MAPPER.readTree("{\"dateFormat\":\"dd/MM/yyyy\",\"options\":[\"x\"]}"));
        JsonNode saved = saveOverlay(id, 1, (ArrayNode) shown.get("regions"), 200);
        JsonNode choice = regionOf(saved, "notes");
        assertThat(choice.get("kind").asText()).isEqualTo("CHOICE");
        assertThat(choice.get("maxLines").asInt()).isEqualTo(1);
        assertThat(choice.get("props").toString())
                .isEqualTo("{\"placeholder\":\"Pick one\",\"defaultValue\":\"Single\",\"options\":[\"Single\","
                        + "\"Married\"]}");
        assertThat(regionOf(saved, "date_of_birth").get("props").toString())
                .isEqualTo("{\"dateFormat\":\"dd/MM/yyyy\"}");

        // a later version keeps them when the editor sends them back
        JsonNode v3 = saveOverlay(id, 2, (ArrayNode) saved.get("regions"), 200);
        assertThat(regionOf(v3, "notes").get("props")).isEqualTo(choice.get("props"));
        assertThat(blueprint(id).get("version").asInt()).isEqualTo(3);

        ((ObjectNode) regionOf(v3, "notes")).set("props", MAPPER.readTree("{\"options\":[]}"));
        JsonNode error = saveOverlay(id, 3, (ArrayNode) v3.get("regions"), 400);
        assertThat(error.get("error").asText()).startsWith("Field \"notes\": ");
    }

    /** One page with an AcroForm dropdown {@code marital} (Single, Married). */
    private static byte[] pdfWithDropdown() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            PDComboBox marital = new PDComboBox(form);
            marital.setPartialName("marital");
            marital.setDefaultAppearance("/Helv 10 Tf 0 g");
            marital.setOptions(List.of("Single", "Married"));
            PDAnnotationWidget widget = marital.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(100, 700, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            form.getFields().add(marital);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aDropdownProposedByTheEngineTakesItsOptionsFromThePdf() throws Exception {
        TemplateRegion marital = new TemplateRegion("marital_status", "Marital status", null, RegionKind.CHOICE,
                new PdfBox(0, 100, 700, 200, 20), "Helvetica", 0f, "#000000", Align.LEFT, 1, "marital", false, 0,
                RegionSource.ACROFORM, 1.0, null);
        TemplateRegion unknown = new TemplateRegion("plan", "Plan", null, RegionKind.CHOICE,
                new PdfBox(0, 100, 600, 200, 20), "Helvetica", 0f, "#000000", Align.LEFT, 1, "nowhere", false, 1,
                RegionSource.ACROFORM, 1.0, null);
        engine.proposedRegions = List.of(marital, unknown);
        MvcResult result = mvc.perform(as(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "form.pdf", "application/pdf", pdfWithDropdown()))
                        .param("flow", "BLANK"), owner))
                .andExpect(status().isCreated()).andReturn();
        JsonNode draft = MAPPER.readTree(result.getResponse().getContentAsString());
        JsonNode dropdown = regionOf(draft, "marital_status");
        assertThat(dropdown.get("kind").asText()).isEqualTo("CHOICE");
        assertThat(dropdown.get("props").get("options")).extracting(JsonNode::asText)
                .containsExactly("Single", "Married");
        assertThat(regionOf(draft, "plan").get("kind").asText()).as("no options: a text field").isEqualTo("TEXT");
        // the draft confirms as received: the options travel with the region
        JsonNode ready = saveOverlay(draft.get("id").asInt(), 1, (ArrayNode) draft.get("regions"), 200);
        assertThat(regionOf(ready, "marital_status").get("props")).isEqualTo(dropdown.get("props"));
    }

    @Test
    void aRuleOfARegionThatWasRemovedIsAnOrphanAndDeletingTheTemplateDeletesTheRules() throws Exception {
        int id = readyTemplate();
        putRules(id, 1, "[{\"regionKey\":\"notes\",\"ruleType\":\"LENGTH\",\"config\":{\"Max\":200}}]", 200);
        JsonNode shown = blueprint(id);
        ArrayNode without = MAPPER.createArrayNode();
        shown.get("regions").forEach(r -> {
            if (!"notes".equals(r.get("key").asText())) {
                without.add(r);
            }
        });
        saveOverlay(id, 1, without, 200);
        JsonNode after = rules(id, owner, 200);
        assertThat(rulesOf(after, "notes")).singleElement()
                .satisfies(r -> assertThat(r.get("orphan").asBoolean()).isTrue());
        assertThat(after.get("fields")).extracting(f -> f.get("key").asText()).doesNotContain("notes");
        // a save must drop the orphan: its key is not in the current version
        putRules(id, after.get("revision").asInt(), after.get("rules").toString(), 400);

        assertThat(ruleRows(id)).isEqualTo(1);
        call(delete("/api/doccreation/blueprints/" + id), owner, 204);
        assertThat(ruleRows(id)).isZero();
    }

    // ------------------------------------------------------------- import

    @Test
    void aChosenTemplateIsAutoMatchedAndItsRulesConverted() throws Exception {
        int id = readyTemplate();
        JsonNode preview = preview(id, customers.getTemplateId(), owner, 200);
        assertThat(preview.get("source").asText()).isEqualTo("TEMPLATE");
        assertThat(preview.get("templateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(preview.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(preview.get("templateFields")).extracting(JsonNode::asText)
                .containsExactly("FirstName", "Email", "date_of_birth", "Amount", "Notes");
        assertThat(preview.get("fields")).extracting(f -> f.get("regionKey").asText())
                .containsExactly("first_name", "email", "date_of_birth", "amount", "notes");

        JsonNode first = previewField(preview, "first_name");
        assertThat(first.get("fieldName").asText()).isEqualTo("FirstName");
        assertThat(first.get("sameName").asBoolean()).isTrue();
        assertThat(first.get("score").asDouble()).isEqualTo(1.0);
        assertThat(first.get("rules")).extracting(r -> r.get("ruleType").asText()).containsExactly("REQUIRED",
                "REGEX");
        assertThat(first.get("rules").get(1).get("message").asText())
                .as("the flow's own wording, for the SOURCE field name")
                .startsWith("First Name must be no longer than 25 characters");
        JsonNode email = previewField(preview, "email");
        assertThat(email.get("rules")).singleElement().satisfies(r -> {
            assertThat(r.get("ruleType").asText()).as("the EMAIL preset").isEqualTo("EMAIL");
            assertThat(r.get("config").toString()).isEqualTo("{}");
        });
        JsonNode dob = previewField(preview, "date_of_birth");
        assertThat(dob.get("rules")).extracting(r -> r.get("ruleType").asText())
                .containsExactly("DATE_FORMAT", "EXPIRY_DATE");
        assertThat(dob.get("rules").get(0).get("config").toString()).isEqualTo("{\"Formats\":[\"yyyy-MM-dd\"]}");
        JsonNode amount = previewField(preview, "amount");
        assertThat(amount.get("rules")).extracting(r -> r.get("ruleType").asText()).containsExactly("REGEX",
                "RANGE");
        assertThat(amount.get("rules").get(0).get("message").isNull()).as("generic text is not carried").isTrue();
        assertThat(amount.get("rules").get(1).get("config").get("Max").asInt()).isEqualTo(100);
        JsonNode notes = previewField(preview, "notes");
        assertThat(notes.get("rules")).isEmpty();
        assertThat(notes.get("skipped")).extracting(s -> s.get("flowRuleType").asText())
                .containsExactlyInAnyOrder("CUSTOM", "TYPE", "CONDITIONAL_REQUIREMENT");
        assertThat(notes.get("skipped")).allSatisfy(s ->
                assertThat(s.get("reason").asText()).isEqualTo("can't be imported"));

        // a template the caller cannot read is a 404
        Template foreign = template(stranger, "Foreign", TemplateVisibility.BANK);
        preview(id, foreign.getTemplateId(), owner, 404);
        Template colleagues = template(colleague, "Private colleague", TemplateVisibility.PRIVATE);
        preview(id, colleagues.getTemplateId(), owner, 404);
    }

    @Test
    void mergeReplacesOnlyTheSameSourceRulesAndReplaceKeepsManualOnes() throws Exception {
        int id = readyTemplate();
        putRules(id, 1, "[{\"regionKey\":\"first_name\",\"ruleType\":\"REQUIRED\",\"origin\":\"REGION_FLAG\"},"
                + "{\"regionKey\":\"amount\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":5}}]", 200);

        String merge = "{\"baseRevision\":2,\"templateId\":" + customers.getTemplateId() + ",\"mode\":\"MERGE\"}";
        JsonNode merged = importRules(id, merge, 200);
        assertThat(merged.get("revision").asInt()).isEqualTo(3);
        assertThat(rulesOf(merged, "first_name")).extracting(r -> r.get("ruleType").asText() + "/"
                + r.get("origin").asText())
                .as("the identical REQUIRED is not added twice")
                .containsExactly("REQUIRED/REGION_FLAG", "REGEX/IMPORTED");
        JsonNode importedRegex = rulesOf(merged, "first_name").get(1);
        assertThat(importedRegex.get("sourceTemplateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(importedRegex.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
        assertThat(importedRegex.get("sourceFieldName").asText()).isEqualTo("FirstName");
        assertThat(importedRegex.get("message").asText()).startsWith("First Name must be");
        assertThat(rulesOf(merged, "amount")).extracting(r -> r.get("ruleType").asText() + "/"
                + r.get("origin").asText())
                .containsExactly("RANGE/MANUAL", "REGEX/IMPORTED", "RANGE/IMPORTED");
        assertThat(rulesOf(merged, "notes")).isEmpty();
        int total = merged.get("rules").size();

        // the same import again replaces its own rules: nothing doubles
        JsonNode again = importRules(id, merge.replace("\"baseRevision\":2", "\"baseRevision\":3"), 200);
        assertThat(again.get("rules")).hasSize(total);
        importRules(id, merge, 409);

        // REPLACE for amount only: its IMPORTED rules go, the MANUAL one stays, the others are untouched
        flowRules.deleteAll(flowRules.findAll().stream()
                .filter(r -> r.getTemplateId() == customers.getTemplateId() && "Amount".equals(r.getFieldName())
                        && r.getRuleType() == RuleType.REGEX).toList());
        JsonNode replaced = importRules(id, "{\"baseRevision\":4,\"templateId\":" + customers.getTemplateId()
                + ",\"mapping\":{\"amount\":\"Amount\"},\"mode\":\"REPLACE\"}", 200);
        assertThat(rulesOf(replaced, "amount")).extracting(r -> r.get("ruleType").asText() + "/"
                + r.get("origin").asText()).containsExactly("RANGE/MANUAL", "RANGE/IMPORTED");
        assertThat(rulesOf(replaced, "first_name")).hasSize(2);
        assertThat(rulesOf(replaced, "email")).hasSize(1);

        List<String> audit = auditValues("DOC_FIELD_RULES_IMPORTED");
        assertThat(audit).hasSize(3);
        assertThat(audit.get(2)).contains("\"mode\":\"REPLACE\"").contains("\"source\":\"TEMPLATE\"")
                .contains("\"fields\":1");
        assertThat(String.join(" ", audit)).doesNotContain("Pattern").doesNotContain("First Name must");

        importRules(id, "{\"templateId\":" + customers.getTemplateId() + ",\"mode\":\"SOMETIMES\"}", 400);
    }

    @Test
    void withoutATemplateTheSavedDataSourcesAreUsed() throws Exception {
        int id = readyTemplate();
        call(post("/api/doccreation/blueprints/" + id + "/sources").contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":" + customers.getTemplateId() + "}"), owner, 201);

        JsonNode preview = preview(id, null, owner, 200);
        assertThat(preview.get("source").asText()).isEqualTo("DATA_SOURCES");
        assertThat(preview.get("templateId").isNull()).isTrue();
        assertThat(preview.get("templateFields")).extracting(JsonNode::asText).contains("FirstName", "Amount");
        JsonNode email = previewField(preview, "email");
        assertThat(email.get("templateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(email.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(email.get("fieldName").asText()).isEqualTo("Email");
        assertThat(email.get("score").isNull()).isTrue();

        // the client's mapping narrows the sources' mapping
        JsonNode imported = importRules(id, "{\"mapping\":{\"email\":\"Email\",\"amount\":\"\"}}", 200);
        assertThat(rulesOf(imported, "email")).extracting(r -> r.get("ruleType").asText()).containsExactly("EMAIL");
        assertThat(rulesOf(imported, "amount")).extracting(r -> r.get("ruleType").asText())
                .containsExactly("REGEX", "RANGE");
        assertThat(rulesOf(imported, "date_of_birth")).isEmpty();
        assertThat(auditValues("DOC_FIELD_RULES_IMPORTED").get(0)).contains("\"source\":\"DATA_SOURCES\"")
                .contains("\"mode\":\"MERGE\"");
    }
}
