package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.ValidationRuleJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data templates over HTTP (docs/doccreation/data-templates-contract.md §2):
 * create, read, rename, list with counts and delete; owner and bank
 * isolation (404) and permissions (403); every field check and rule check
 * (400); the revision (409) and the RulesRevision bump of the PDF templates
 * that use a data template; delete refused while one does (409); rules of
 * removed fields deleted; testing sample values; the copy from a flow
 * template (preview, kinds, PII, conversion with the EMAIL preset and the
 * Dayforce message, skipped types); counts-only audit.
 */
class DataTemplatesApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/doccreation/data-templates";
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

    private Bank bank;
    private Bank otherBank;
    private AppUser owner;
    private AppUser colleague;
    private AppUser reader;
    private AppUser stranger;
    private Template customers;
    private String tag;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("DT" + tag.substring(0, 6).toUpperCase(), "Data templates bank"));
        otherBank = banks.save(new Bank("DX" + tag.substring(0, 6).toUpperCase(), "Other bank"));
        owner = user(bank, "dt-owner-" + tag, Role.READ_WRITE);
        colleague = user(bank, "dt-colleague-" + tag, Role.READ_WRITE);
        reader = user(bank, "dt-reader-" + tag, Role.READ_ONLY);
        stranger = user(otherBank, "dt-stranger-" + tag, Role.READ_WRITE);

        customers = template(owner, "Customers " + tag, TemplateVisibility.PRIVATE);
        field(customers, "FirstName", 1, PiiKind.NAME);
        field(customers, "Email", 1, PiiKind.EMAIL);
        field(customers, "date_of_birth", 3, null);
        field(customers, "Amount", 2, null);
        field(customers, "Notes", 1, null);
        field(customers, "Start date", 3, null);
        field(customers, "start_date", 1, null);
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
        flowRule(customers, "Start date", RuleType.REGEX, "{\"Pattern\":\"" + json(EMAIL_PRESET) + "\"}");
    }

    @AfterEach
    void cleanUp() {
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM DocDataFieldRule WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocDataField WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocDataTemplate WHERE BankId = ?", bankId);
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

    private void field(Template template, String name, int dataTypeId, PiiKind piiKind) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(template.getTemplateId());
        field.setBankId(template.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(dataTypeId);
        field.setPiiKind(piiKind);
        templateFields.save(field);
    }

    private void flowRule(Template template, String fieldName, RuleType type, String config) {
        ValidationRule rule = new ValidationRule(template.getTemplateId(), fieldName, type, config);
        rule.setBankId(template.getBankId());
        flowRules.save(rule);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, AppUser user, int expected) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + jwtService.issueToken(user)))
                .andExpect(status().is(expected)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, AppUser user, int expected)
            throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user, expected);
    }

    private int create(String name) throws Exception {
        return send(post(BASE), "{\"name\":\"" + name + "\"}", owner, 201).get("id").asInt();
    }

    private JsonNode putFields(int id, Integer baseRevision, String fieldsJson, AppUser user, int expected)
            throws Exception {
        String body = "{" + (baseRevision == null ? "" : "\"baseRevision\":" + baseRevision + ",")
                + "\"fields\":" + fieldsJson + "}";
        return send(put(BASE + "/" + id + "/fields"), body, user, expected);
    }

    private JsonNode putFields(int id, Integer baseRevision, String fieldsJson, int expected) throws Exception {
        return putFields(id, baseRevision, fieldsJson, owner, expected);
    }

    private JsonNode putRules(int id, Integer baseRevision, String rulesJson, int expected) throws Exception {
        String body = "{" + (baseRevision == null ? "" : "\"baseRevision\":" + baseRevision + ",")
                + "\"rules\":" + rulesJson + "}";
        return send(put(BASE + "/" + id + "/rules"), body, owner, expected);
    }

    private JsonNode read(int id, AppUser user, int expected) throws Exception {
        return call(get(BASE + "/" + id), user,
                expected);
    }

    /** A person/order field set: full_name, email, age (NUMBER), dob (DATE), status (CHOICE), agree, photo. */
    private static final String FIELDS = "["
            + "{\"name\":\"full_name\",\"label\":\"Full name\",\"kind\":\"TEXT\",\"piiKind\":\"NAME\","
            + "\"props\":{\"placeholder\":\"Jane Doe\",\"maxLength\":40}},"
            + "{\"name\":\"email\",\"label\":\"Email\",\"kind\":\"TEXT\"},"
            + "{\"name\":\"age\",\"label\":\"Age\",\"kind\":\"NUMBER\",\"props\":{\"numberFormat\":{\"decimals\":0}}},"
            + "{\"name\":\"dob\",\"label\":\"Date of birth\",\"kind\":\"DATE\"},"
            + "{\"name\":\"status\",\"label\":\"Status\",\"kind\":\"CHOICE\","
            + "\"props\":{\"options\":[\"Single\",\"Married\"]}},"
            + "{\"name\":\"agree\",\"label\":\"I agree\",\"kind\":\"CHECKBOX\"},"
            + "{\"name\":\"photo\",\"label\":\"Photo\",\"kind\":\"IMAGE\"}]";

    private int withFields() throws Exception {
        int id = create("People " + tag);
        putFields(id, 0, FIELDS, 200);
        return id;
    }

    private int blueprintUsing(Bank in, AppUser by, Integer dataTemplateId, String name) {
        jdbc.update("INSERT INTO DocBlueprint (OwnerUserId, BankId, Name, CurrentVersion, RulesRevision, Kind, "
                        + "DataTemplateId, CreatedDate) VALUES (?, ?, ?, 1, 0, 'PDF_OVERLAY', ?, ?)",
                by.getAppUserId(), in.getBankId(), name, dataTemplateId, LocalDateTime.now());
        Integer id = jdbc.queryForObject("SELECT DocBlueprintId FROM DocBlueprint WHERE BankId = ? AND Name = ?",
                Integer.class, in.getBankId(), name);
        return id == null ? 0 : id;
    }

    private int blueprintRevision(int blueprintId) {
        Integer revision = jdbc.queryForObject("SELECT RulesRevision FROM DocBlueprint WHERE DocBlueprintId = ?",
                Integer.class, blueprintId);
        return revision == null ? -1 : revision;
    }

    private List<String> auditValues(String action) {
        return jdbc.queryForList("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent WHERE BankId = ? AND Action = ? "
                        + "ORDER BY AuditEventId",
                String.class, bank.getBankId(), action);
    }

    private static JsonNode named(JsonNode list, String key, String value) {
        for (JsonNode entry : list) {
            if (value.equals(entry.get(key).asText())) {
                return entry;
            }
        }
        throw new AssertionError("no entry with " + key + " = " + value);
    }

    private static List<JsonNode> rulesOf(JsonNode dto, String fieldName) {
        List<JsonNode> out = new ArrayList<>();
        dto.get("rules").forEach(r -> {
            if (fieldName.equals(r.get("fieldName").asText())) {
                out.add(r);
            }
        });
        return out;
    }

    private int ruleRows(int id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DocDataFieldRule WHERE DataTemplateId = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    void aDataTemplateIsCreatedReadRenamedListedAndDeletedWithCountsOnlyAudit() throws Exception {
        JsonNode created = send(post(BASE), "{\"name\":\"  People  \",\"description\":\"Who we serve\"}", owner,
                201);
        int id = created.get("id").asInt();
        assertThat(created.get("name").asText()).isEqualTo("People");
        assertThat(created.get("description").asText()).isEqualTo("Who we serve");
        assertThat(created.get("revision").asInt()).isZero();
        assertThat(created.get("fields")).isEmpty();
        assertThat(created.get("rules")).isEmpty();
        assertThat(created.get("usedBy")).isEmpty();
        assertThat(created.get("sourceTemplateId").isNull()).isTrue();
        assertThat(created.get("ruleTypes")).hasSize(11);
        JsonNode dateRange = named(created.get("ruleTypes"), "type", "DATE_RANGE");
        assertThat(dateRange.get("kinds")).extracting(JsonNode::asText).containsExactly("DATE", "TEXT");
        assertThat(dateRange.get("configKeys")).extracting(JsonNode::asText).containsExactly("Min", "Max");
        assertThat(read(id, owner, 200)).isEqualTo(created);

        JsonNode renamed = send(put(BASE + "/" + id), "{\"name\":\"Customers\",\"description\":\" \"}", owner, 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Customers");
        assertThat(renamed.get("description").isNull()).isTrue();
        assertThat(renamed.get("revision").asInt()).as("details are not fields or rules").isZero();

        JsonNode withFields = putFields(id, 0, FIELDS, 200);
        assertThat(withFields.get("revision").asInt()).isEqualTo(1);
        assertThat(withFields.get("fields")).extracting(f -> f.get("name").asText())
                .containsExactly("full_name", "email", "age", "dob", "status", "agree", "photo");
        JsonNode fullName = withFields.get("fields").get(0);
        assertThat(fullName.get("label").asText()).isEqualTo("Full name");
        assertThat(fullName.get("kind").asText()).isEqualTo("TEXT");
        assertThat(fullName.get("piiKind").asText()).isEqualTo("NAME");
        assertThat(fullName.get("props").get("placeholder").asText()).isEqualTo("Jane Doe");
        assertThat(fullName.get("props").get("maxLength").asInt()).isEqualTo(40);
        assertThat(fullName.get("sortOrder").asInt()).isZero();
        assertThat(named(withFields.get("fields"), "name", "status").get("props").get("options"))
                .extracting(JsonNode::asText).containsExactly("Single", "Married");
        assertThat(named(withFields.get("fields"), "name", "email").get("props").isNull()).isTrue();

        JsonNode withRules = putRules(id, 1, "[{\"fieldName\":\"full_name\",\"ruleType\":\"REQUIRED\"},"
                + "{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\",\"message\":\"Use your work address.\"},"
                + "{\"fieldName\":\"age\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":18,\"Max\":\"120\"},"
                + "\"severity\":\"warning\"},"
                + "{\"fieldName\":\"full_name\",\"ruleType\":\"LENGTH\",\"config\":{\"Max\":40,\"Other\":1}}]", 200);
        assertThat(withRules.get("revision").asInt()).isEqualTo(2);
        assertThat(withRules.get("rules")).extracting(r -> r.get("fieldName").asText() + "/"
                + r.get("ruleType").asText() + "/" + r.get("sortOrder").asInt())
                .as("in field order, then the order sent")
                .containsExactly("full_name/REQUIRED/0", "full_name/LENGTH/1", "email/EMAIL/0", "age/RANGE/0");
        JsonNode age = rulesOf(withRules, "age").get(0);
        assertThat(age.get("severity").asText()).isEqualTo("WARNING");
        assertThat(age.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(age.get("config").get("Max").decimalValue()).isEqualByComparingTo("120");
        assertThat(age.get("id").asLong()).isPositive();
        assertThat(rulesOf(withRules, "full_name").get(1).get("config").toString())
                .as("normalised, unknown keys dropped").isEqualTo("{\"Max\":40}");
        assertThat(read(id, owner, 200)).isEqualTo(withRules);

        JsonNode listed = named(call(get(BASE),
                owner, 200), "name", "Customers");
        assertThat(listed.get("id").asInt()).isEqualTo(id);
        assertThat(listed.get("fieldCount").asInt()).isEqualTo(7);
        assertThat(listed.get("ruleCount").asInt()).isEqualTo(4);
        assertThat(listed.get("usedBy").asInt()).isZero();
        assertThat(listed.get("sourceTemplateId").isNull()).isTrue();
        assertThat(listed.get("updatedAt").asText()).isNotBlank();

        call(delete(BASE + "/" + id), owner, 204);
        read(id, owner, 404);
        assertThat(ruleRows(id)).isZero();

        List<String> saved = auditValues("DOC_DATA_TEMPLATE_SAVED");
        assertThat(saved).hasSize(4);
        assertThat(saved.get(0)).contains("\"change\":\"CREATED\"").contains("\"revision\":0");
        assertThat(saved.get(1)).contains("\"change\":\"DETAILS\"");
        assertThat(saved.get(2)).contains("\"change\":\"FIELDS\"").contains("\"fields\":7")
                .contains("\"revision\":1");
        assertThat(saved.get(3)).contains("\"change\":\"RULES\"").contains("\"rules\":4")
                .contains("\"revision\":2");
        assertThat(auditValues("DOC_DATA_TEMPLATE_DELETED")).singleElement()
                .satisfies(v -> assertThat(v).contains("\"fields\":7").contains("\"rules\":4"));
        String all = String.join(" ", saved);
        assertThat(all).doesNotContain("work address").doesNotContain("Jane").doesNotContain("Customers")
                .doesNotContain("Max");
    }

    @Test
    void onlyTheOwnerInTheirBankReachesADataTemplateAndWritingNeedsDocWrite() throws Exception {
        int id = withFields();
        read(id, colleague, 404);
        read(id, stranger, 404);
        read(id, reader, 404);
        read(424242, owner, 404);
        send(put(BASE + "/" + id), "{\"name\":\"Mine\"}", colleague, 404);
        send(put(BASE + "/" + id), "{\"name\":\"Mine\"}", stranger, 404);
        putFields(id, null, "[]", colleague, 404);
        send(put(BASE + "/" + id + "/rules"), "{\"rules\":[]}", stranger, 404);
        send(post(BASE + "/" + id + "/rules/test"), "{\"values\":{}}", colleague, 404);
        call(delete(BASE + "/" + id), colleague, 404);
        call(delete(BASE + "/" + id), stranger, 404);
        assertThat(call(get(BASE), colleague,
                200)).isEmpty();
        assertThat(call(get(BASE), stranger,
                200)).isEmpty();

        // READ_ONLY reads (its own, empty, list) and cannot write
        assertThat(call(get(BASE), reader,
                200)).isEmpty();
        send(post(BASE), "{\"name\":\"Mine\"}", reader, 403);
        send(put(BASE + "/" + id), "{\"name\":\"Mine\"}", reader, 403);
        putFields(id, null, "[]", reader, 403);
        send(put(BASE + "/" + id + "/rules"), "{\"rules\":[]}", reader, 403);
        call(delete(BASE + "/" + id), reader, 403);
        send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"Copy\"}", reader, 403);

        JsonNode unchanged = read(id, owner, 200);
        assertThat(unchanged.get("revision").asInt()).isEqualTo(1);
        assertThat(unchanged.get("fields")).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND Action = "
                + "'ACCESS_DENIED' AND EntityType = 'DocDataTemplate'", Integer.class, bank.getBankId()))
                .as("owner-scoped 404s are audited as probes").isPositive();
    }

    // -------------------------------------------------------------- checks

    @Test
    void everyFieldCheckAnswers400AndKeepsTheFields() throws Exception {
        int id = withFields();
        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 501; i++) {
            tooMany.append(i == 0 ? "" : ",").append("{\"name\":\"f").append(i)
                    .append("\",\"label\":\"F\",\"kind\":\"TEXT\"}");
        }
        tooMany.append("]");
        List<String> bad = List.of(
                "[{\"name\":\"Full Name\",\"label\":\"Full name\",\"kind\":\"TEXT\"}]",
                "[{\"name\":\"" + "a".repeat(65) + "\",\"label\":\"A\",\"kind\":\"TEXT\"}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"TEXT\"},{\"name\":\"a\",\"label\":\"B\",\"kind\":\"TEXT\"}]",
                "[{\"name\":\"a\",\"label\":\" \",\"kind\":\"TEXT\"}]",
                "[{\"name\":\"a\",\"label\":\"" + "x".repeat(201) + "\",\"kind\":\"TEXT\"}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"SIGNATURE\"}]",
                "[{\"name\":\"a\",\"label\":\"A\"}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"TEXT\",\"piiKind\":\"SECRET\"}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"CHOICE\"}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"CHOICE\",\"props\":{\"options\":[\"x\",\"x\"]}}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"DATE\",\"props\":{\"dateFormat\":\"yy\"}}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"DATE\",\"props\":{\"defaultValue\":\"soon\"}}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"NUMBER\",\"props\":{\"numberFormat\":{\"decimals\":9}}}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"TEXT\",\"props\":{\"maxLength\":0}}]",
                "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"TEXT\",\"props\":{\"placeholder\":\""
                        + "p".repeat(201) + "\"}}]",
                "[null]",
                tooMany.toString());
        for (String fieldsJson : bad) {
            JsonNode error = putFields(id, null, fieldsJson, 400);
            assertThat(error.get("error").asText()).as(fieldsJson.length() > 120 ? fieldsJson.substring(0, 120)
                    : fieldsJson).isNotBlank();
        }
        assertThat(putFields(id, null, "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"CHOICE\"}]", 400)
                .get("error").asText()).as("the region checker's wording").startsWith("Field \"a\": ");
        assertThat(putFields(id, null, tooMany.toString(), 400).get("error").asText()).contains("500");
        send(put(BASE + "/" + id + "/fields"), "{\"baseRevision\":1}", owner, 400);
        JsonNode after = read(id, owner, 200);
        assertThat(after.get("revision").asInt()).isEqualTo(1);
        assertThat(after.get("fields")).hasSize(7);

        // properties of another kind are dropped, not refused
        JsonNode dropped = putFields(id, 1, "[{\"name\":\"a\",\"label\":\"A\",\"kind\":\"TEXT\","
                + "\"props\":{\"dateFormat\":\"dd/MM/yyyy\",\"options\":[\"x\"]}}]", 200);
        assertThat(dropped.get("fields").get(0).get("props").isNull()).isTrue();

        // and the details have their own checks
        send(post(BASE), "{\"name\":\" \"}", owner, 400);
        send(post(BASE), "{\"name\":\"" + "n".repeat(201) + "\"}", owner, 400);
        send(put(BASE + "/" + id), "{\"name\":\"Ok\",\"description\":\"" + "d".repeat(1001) + "\"}", owner, 400);
    }

    @Test
    void everyRuleCheckAnswers400WithFieldNamesAndDataFieldKinds() throws Exception {
        int id = withFields();
        String fiftyOne = "{\"fieldName\":\"email\",\"ruleType\":\"REQUIRED\"},".repeat(51);
        List<String> bad = List.of(
                "[{\"fieldName\":\"email\",\"ruleType\":\"SPELLCHECK\"}]",
                "[{\"fieldName\":\"nope\",\"ruleType\":\"REQUIRED\"}]",
                "[{\"fieldName\":\"age\",\"ruleType\":\"EMAIL\"}]",
                "[{\"fieldName\":\"photo\",\"ruleType\":\"LENGTH\",\"config\":{\"Max\":3}}]",
                "[{\"fieldName\":\"agree\",\"ruleType\":\"COMPARE\",\"config\":{\"Field\":\"age\",\"Op\":\"=\"}}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"REGEX\",\"config\":{\"Pattern\":\"([a-z\"}}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"LENGTH\",\"config\":{\"Min\":5,\"Max\":2}}]",
                "[{\"fieldName\":\"age\",\"ruleType\":\"RANGE\",\"config\":{}}]",
                "[{\"fieldName\":\"dob\",\"ruleType\":\"DATE_RANGE\",\"config\":{\"Min\":\"yesterday\"}}]",
                "[{\"fieldName\":\"dob\",\"ruleType\":\"DATE_FORMAT\",\"config\":{\"Formats\":[\"ppp{\"]}}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"email\"}}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"ghost\"}}]",
                "[{\"fieldName\":\"age\",\"ruleType\":\"COMPARE\",\"config\":{\"Field\":\"dob\",\"Op\":\"<>\"}}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\",\"message\":\"" + "m".repeat(301) + "\"}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\",\"severity\":\"FATAL\"}]",
                "[{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\",\"origin\":\"REGION_FLAG\"}]",
                "[" + fiftyOne.substring(0, fiftyOne.length() - 1) + "]");
        for (String rulesJson : bad) {
            JsonNode error = putRules(id, null, rulesJson, 400);
            assertThat(error.get("error").asText()).as(rulesJson.length() > 120 ? rulesJson.substring(0, 120)
                    : rulesJson).isNotBlank();
        }
        assertThat(putRules(id, null, "[{\"fieldName\":\"nope\",\"ruleType\":\"REQUIRED\"}]", 400).get("error")
                .asText()).contains("nope").contains("not in the data template");
        assertThat(putRules(id, null, "[{\"fieldName\":\"age\",\"ruleType\":\"EMAIL\"}]", 400).get("error")
                .asText()).contains("does not apply to a number field");
        assertThat(read(id, owner, 200).get("revision").asInt()).isEqualTo(1);
        assertThat(ruleRows(id)).isZero();

        // what fits: REQUIRED on a photo and a checkbox, REQUIRED_IF and COMPARE naming other fields, IMPORTED
        JsonNode saved = putRules(id, 1, "[{\"fieldName\":\"photo\",\"ruleType\":\"REQUIRED\"},"
                + "{\"fieldName\":\"agree\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"email\"}},"
                + "{\"fieldName\":\"age\",\"ruleType\":\"COMPARE\",\"config\":{\"Field\":\"dob\",\"Op\":\"!=\"}},"
                + "{\"fieldName\":\"status\",\"ruleType\":\"ALLOWED_VALUES\",\"config\":{\"Values\":[\"Single\"]},"
                + "\"origin\":\"IMPORTED\",\"sourceTemplateId\":" + customers.getTemplateId()
                + ",\"sourceFieldName\":\"Status\"}]", 200);
        assertThat(saved.get("rules")).hasSize(4);
        JsonNode imported = rulesOf(saved, "status").get(0);
        assertThat(imported.get("origin").asText()).isEqualTo("IMPORTED");
        assertThat(imported.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
        assertThat(imported.get("sourceFieldName").asText()).isEqualTo("Status");
    }

    // --------------------------------------------------- revision, PDFs

    @Test
    void aStaleRevisionIs409AndEverySaveRaisesTheRevisionOfThePdfTemplatesUsingIt() throws Exception {
        int id = create("Shared " + tag);
        int other = create("Other " + tag);
        int pdf = blueprintUsing(bank, owner, id, "Form A " + tag);
        int unrelated = blueprintUsing(bank, owner, other, "Form B " + tag);
        int foreignBank = blueprintUsing(otherBank, stranger, id, "Form C " + tag);

        JsonNode shown = read(id, owner, 200);
        assertThat(shown.get("usedBy")).singleElement().satisfies(u -> {
            assertThat(u.get("blueprintId").asInt()).isEqualTo(pdf);
            assertThat(u.get("name").asText()).isEqualTo("Form A " + tag);
        });

        putFields(id, 0, FIELDS, 200);
        assertThat(blueprintRevision(pdf)).isEqualTo(1);
        putRules(id, 1, "[{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\"}]", 200);
        assertThat(blueprintRevision(pdf)).isEqualTo(2);
        assertThat(blueprintRevision(unrelated)).isZero();
        assertThat(blueprintRevision(foreignBank)).as("another bank's row is never touched").isZero();

        // stale revisions change nothing
        putRules(id, 1, "[]", 409);
        JsonNode stale = putFields(id, 0, "[]", 409);
        assertThat(stale.get("error").asText()).contains("revision 2");
        JsonNode after = read(id, owner, 200);
        assertThat(after.get("revision").asInt()).isEqualTo(2);
        assertThat(after.get("rules")).hasSize(1);
        assertThat(blueprintRevision(pdf)).isEqualTo(2);

        // renaming is not a fields or rules save
        send(put(BASE + "/" + id), "{\"name\":\"Renamed\"}", owner, 200);
        assertThat(blueprintRevision(pdf)).isEqualTo(2);

        JsonNode listed = named(call(get(BASE),
                owner, 200), "name", "Renamed");
        assertThat(listed.get("usedBy").asInt()).isEqualTo(1);

        // delete is refused while a PDF template uses it
        JsonNode refused = call(delete(BASE + "/" + id), owner, 409);
        assertThat(refused.get("error").asText()).contains("1 PDF template");
        assertThat(read(id, owner, 200).get("fields")).hasSize(7);
        jdbc.update("UPDATE DocBlueprint SET DataTemplateId = NULL WHERE DocBlueprintId = ?", pdf);
        call(delete(BASE + "/" + id), owner, 204);
        read(id, owner, 404);
    }

    @Test
    void removedFieldsTakeTheirRulesAlongAndAKindChangeMustFitTheRules() throws Exception {
        int id = withFields();
        putRules(id, 1, "[{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\"},"
                + "{\"fieldName\":\"full_name\",\"ruleType\":\"REQUIRED\"},"
                + "{\"fieldName\":\"agree\",\"ruleType\":\"REQUIRED_IF\",\"config\":{\"Field\":\"email\"}},"
                + "{\"fieldName\":\"age\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":18}}]", 200);
        assertThat(ruleRows(id)).isEqualTo(4);

        // age becomes a checkbox: its RANGE rule would not apply
        String ageAsCheckbox = FIELDS.replace("\"name\":\"age\",\"label\":\"Age\",\"kind\":\"NUMBER\"",
                "\"name\":\"age\",\"label\":\"Age\",\"kind\":\"CHECKBOX\"");
        assertThat(putFields(id, 2, ageAsCheckbox, 400).get("error").asText())
                .startsWith("Field \"age\": ").contains("RANGE").contains("a checkbox field");
        assertThat(ruleRows(id)).isEqualTo(4);

        // email removed: its EMAIL rule and the REQUIRED_IF naming it go; the others stay
        String withoutEmail = FIELDS.replace("{\"name\":\"email\",\"label\":\"Email\",\"kind\":\"TEXT\"},", "");
        JsonNode saved = putFields(id, 2, withoutEmail, 200);
        assertThat(saved.get("fields")).extracting(f -> f.get("name").asText()).doesNotContain("email");
        assertThat(saved.get("rules")).extracting(r -> r.get("fieldName").asText() + "/"
                + r.get("ruleType").asText()).containsExactly("full_name/REQUIRED", "age/RANGE");
        assertThat(ruleRows(id)).isEqualTo(2);
        assertThat(saved.get("revision").asInt()).isEqualTo(3);
        assertThat(auditValues("DOC_DATA_TEMPLATE_SAVED").get(3)).contains("\"change\":\"FIELDS\"")
                .contains("\"fields\":6").contains("\"rules\":2");
    }

    // ---------------------------------------------------------------- test

    @Test
    void sampleValuesAreTestedByFieldNameAgainstTheSavedOrTheGivenRules() throws Exception {
        int id = withFields();
        putRules(id, 1, "[{\"fieldName\":\"full_name\",\"ruleType\":\"REQUIRED\"},"
                + "{\"fieldName\":\"email\",\"ruleType\":\"EMAIL\"},"
                + "{\"fieldName\":\"age\",\"ruleType\":\"RANGE\",\"config\":{\"Min\":18},\"severity\":\"WARNING\"},"
                + "{\"fieldName\":\"photo\",\"ruleType\":\"REQUIRED\"}]", 200);

        JsonNode failed = send(post(BASE + "/" + id + "/rules/test"),
                "{\"values\":{\"email\":\"not-an-address\",\"age\":\"12\",\"status\":\"Divorced\"}}", owner, 200);
        assertThat(failed.get("state").asText()).isEqualTo("DRAFT");
        assertThat(failed.get("rulesRevision").asInt()).isEqualTo(2);
        assertThat(failed.get("errors")).extracting(e -> e.get("regionKey").asText() + "/" + e.get("code").asText())
                .containsExactlyInAnyOrder("full_name/REQUIRED", "email/EMAIL", "status/CHOICE_INVALID",
                        "photo/REQUIRED");
        assertThat(failed.get("warnings")).extracting(e -> e.get("regionKey").asText()).containsExactly("age");
        assertThat(named(failed.get("errors"), "regionKey", "full_name").get("message").asText())
                .isEqualTo("Full name is required.");
        assertThat(named(failed.get("errors"), "regionKey", "email").get("ruleId").asLong()).isPositive();
        assertThat(failed.toString()).as("messages never contain values").doesNotContain("not-an-address")
                .doesNotContain("Divorced");

        JsonNode passed = send(post(BASE + "/" + id + "/rules/test"), "{\"values\":{\"full_name\":\"Jane\","
                + "\"email\":\"jane@example.com\",\"age\":\"40\",\"photo\":\"placed\"}}", owner, 200);
        assertThat(passed.get("state").asText()).isEqualTo("VALIDATED");
        assertThat(passed.get("errors")).isEmpty();

        // unsaved rules are checked like a save, and tested instead of the saved ones
        JsonNode unsaved = send(post(BASE + "/" + id + "/rules/test"), "{\"values\":{\"dob\":\"2999-01-01\"},"
                + "\"rules\":[{\"fieldName\":\"dob\",\"ruleType\":\"DATE_RANGE\",\"config\":{\"Max\":\"today\"}}]}",
                owner, 200);
        assertThat(unsaved.get("errors")).extracting(e -> e.get("regionKey").asText() + "/" + e.get("code").asText())
                .containsExactly("dob/DATE_RANGE");
        send(post(BASE + "/" + id + "/rules/test"), "{\"values\":{},\"rules\":[{\"fieldName\":\"nope\","
                + "\"ruleType\":\"REQUIRED\"}]}", owner, 400);
        assertThat(read(id, owner, 200).get("revision").asInt()).as("nothing is stored").isEqualTo(2);
        // READ_ONLY may test, but only its own templates
        send(post(BASE + "/" + id + "/rules/test"), "{\"values\":{}}", reader, 404);
    }

    // ---------------------------------------------------------------- copy

    @Test
    void theCopyPreviewShowsKindsPiiConvertedAndSkippedRules() throws Exception {
        JsonNode preview = call(get(BASE + "/copy-preview").param("templateId", String.valueOf(customers.getTemplateId())),
                owner, 200);
        assertThat(preview.get("templateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(preview.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(preview.get("fields")).extracting(f -> f.get("name").asText() + "/" + f.get("dataType").asText()
                        + "/" + f.get("kind").asText())
                .containsExactly("FirstName/Text/TEXT", "Email/Text/TEXT", "date_of_birth/Date/DATE",
                        "Amount/Number/NUMBER", "Notes/Text/TEXT", "Start date/Date/DATE", "start_date/Text/TEXT");

        JsonNode first = named(preview.get("fields"), "name", "FirstName");
        assertThat(first.get("piiKind").asText()).isEqualTo("NAME");
        assertThat(first.get("rules")).extracting(r -> r.get("ruleType").asText()).containsExactly("REQUIRED",
                "REGEX");
        assertThat(first.get("rules").get(1).get("message").asText()).as("the Dayforce wording")
                .startsWith("First Name must be no longer than 25 characters");
        JsonNode email = named(preview.get("fields"), "name", "Email");
        assertThat(email.get("piiKind").asText()).isEqualTo("EMAIL");
        assertThat(email.get("rules")).singleElement().satisfies(r -> {
            assertThat(r.get("ruleType").asText()).as("the EMAIL preset").isEqualTo("EMAIL");
            assertThat(r.get("config").toString()).isEqualTo("{}");
        });
        JsonNode dob = named(preview.get("fields"), "name", "date_of_birth");
        assertThat(dob.get("piiKind").isNull()).isTrue();
        assertThat(dob.get("rules")).extracting(r -> r.get("ruleType").asText())
                .containsExactly("DATE_FORMAT", "EXPIRY_DATE");
        JsonNode amount = named(preview.get("fields"), "name", "Amount");
        assertThat(amount.get("rules")).extracting(r -> r.get("ruleType").asText()).containsExactly("REGEX",
                "RANGE");
        assertThat(amount.get("rules").get(0).get("message").isNull()).as("generic text is not carried").isTrue();
        JsonNode notes = named(preview.get("fields"), "name", "Notes");
        assertThat(notes.get("rules")).isEmpty();
        assertThat(notes.get("skipped")).extracting(s -> s.get("flowRuleType").asText() + "/"
                        + s.get("reason").asText())
                .containsExactlyInAnyOrder("CUSTOM/can't be imported", "TYPE/can't be imported",
                        "CONDITIONAL_REQUIREMENT/can't be imported");
        JsonNode start = named(preview.get("fields"), "name", "Start date");
        assertThat(start.get("rules")).isEmpty();
        assertThat(start.get("skipped")).singleElement().satisfies(s -> {
            assertThat(s.get("flowRuleType").asText()).isEqualTo("REGEX");
            assertThat(s.get("reason").asText()).isEqualTo("EMAIL does not apply to a date field");
        });

        // a flow template the caller cannot read is a 404; one is required
        Template foreign = template(stranger, "Foreign", TemplateVisibility.BANK);
        call(get(BASE + "/copy-preview")
                .param("templateId", String.valueOf(foreign.getTemplateId())), owner, 404);
        call(get(BASE + "/copy-preview")
                .param("templateId", String.valueOf(customers.getTemplateId())), colleague, 404);
        call(get(BASE + "/copy-preview"),
                owner, 400);
    }

    @Test
    void aCopyMakesAnEditableDataTemplateWithImportedRules() throws Exception {
        JsonNode copied = send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"From customers\",\"fields\":[\"Amount\",\"FirstName\",\"Email\",\"Notes\","
                + "\"Start date\",\"start_date\"]}", owner, 201);
        int id = copied.get("id").asInt();
        assertThat(copied.get("name").asText()).isEqualTo("From customers");
        assertThat(copied.get("revision").asInt()).isZero();
        assertThat(copied.get("sourceTemplateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(copied.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
        assertThat(copied.get("fields")).as("the flow template's order, slugged and unique names")
                .extracting(f -> f.get("name").asText() + "/" + f.get("label").asText() + "/"
                        + f.get("kind").asText() + "/" + f.get("sourceFieldName").asText())
                .containsExactly("firstname/FirstName/TEXT/FirstName", "email/Email/TEXT/Email",
                        "amount/Amount/NUMBER/Amount", "notes/Notes/TEXT/Notes",
                        "start_date/Start date/DATE/Start date", "start_date_2/start_date/TEXT/start_date");
        assertThat(named(copied.get("fields"), "name", "firstname").get("piiKind").asText()).isEqualTo("NAME");
        assertThat(named(copied.get("fields"), "name", "amount").get("piiKind").isNull()).isTrue();

        assertThat(copied.get("rules")).extracting(r -> r.get("fieldName").asText() + "/"
                + r.get("ruleType").asText()).containsExactly("firstname/REQUIRED", "firstname/REGEX",
                "email/EMAIL", "amount/REGEX", "amount/RANGE");
        assertThat(copied.get("rules")).allSatisfy(r -> {
            assertThat(r.get("origin").asText()).isEqualTo("IMPORTED");
            assertThat(r.get("sourceTemplateId").asInt()).isEqualTo(customers.getTemplateId());
            assertThat(r.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
            assertThat(r.get("severity").asText()).isEqualTo("ERROR");
        });
        JsonNode regex = rulesOf(copied, "firstname").get(1);
        assertThat(regex.get("sourceFieldName").asText()).isEqualTo("FirstName");
        assertThat(regex.get("message").asText()).startsWith("First Name must be");
        assertThat(regex.get("config").get("Pattern").asText()).isEqualTo(FIRST_NAME_PATTERN);
        assertThat(rulesOf(copied, "amount").get(1).get("config").get("Max").asInt()).isEqualTo(100);

        // everything stays editable: the rules as received save again
        JsonNode resaved = putRules(id, 0, copied.get("rules").toString(), 200);
        assertThat(resaved.get("rules")).hasSize(5);
        assertThat(resaved.get("rules")).allSatisfy(r -> assertThat(r.get("origin").asText()).isEqualTo("IMPORTED"));

        JsonNode listed = named(call(get(BASE),
                owner, 200), "name", "From customers");
        assertThat(listed.get("sourceTemplateName").asText()).isEqualTo(customers.getName());
        assertThat(listed.get("fieldCount").asInt()).isEqualTo(6);

        List<String> audit = auditValues("DOC_DATA_TEMPLATE_COPIED");
        assertThat(audit).singleElement().satisfies(v -> assertThat(v)
                .contains("\"templateId\":" + customers.getTemplateId()).contains("\"fields\":6")
                .contains("\"rules\":5").contains("\"skipped\":4"));
        assertThat(audit.get(0)).doesNotContain("Pattern").doesNotContain("First Name must")
                .doesNotContain("FirstName");

        // without a list every field is copied; unknown fields and unreadable templates are refused
        JsonNode all = send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"All\"}", owner, 201);
        assertThat(all.get("fields")).hasSize(7);
        assertThat(named(all.get("fields"), "name", "date_of_birth").get("kind").asText()).isEqualTo("DATE");
        send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"Bad\",\"fields\":[\"Ghost\"]}", owner, 400);
        send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\" \"}", owner, 400);
        send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"Theirs\"}", stranger, 404);
        send(post(BASE + "/from-flow-template"), "{\"name\":\"None\"}", owner, 400);
        assertThat(auditValues("DOC_DATA_TEMPLATE_COPIED")).hasSize(2);
    }
}
