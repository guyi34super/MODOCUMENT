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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data templates built from several flow templates over HTTP
 * (docs/doccreation/data-templates-contract.md §2, "Building from several
 * flow templates"): {@code from-flow-templates} and
 * {@code add-from-flow-templates}. Clashing names get the flow template's
 * suffix (then _2), every field remembers its flow template, the main flow
 * template is the one that gave the most fields, a partial selection copies
 * only those fields, adding to a template checks the revision (409) and
 * raises the PDF templates' revision; the 400s, the 404s of unreadable
 * sources and the 403 of a read-only caller; counts-only audit. Document
 * Flow's template list never shows a data template.
 */
class DataTemplateMultiSourceApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/doccreation/data-templates";
    private static final String EMAIL_PRESET = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

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
    private Template orders;
    private String tag;
    /** The slug of {@code orders}' name: "orders_co_" + tag. */
    private String ordersSlug;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        bank = banks.save(new Bank("DM" + tag.substring(0, 6).toUpperCase(), "Multi-source bank"));
        otherBank = banks.save(new Bank("DN" + tag.substring(0, 6).toUpperCase(), "Other bank"));
        owner = user(bank, "dm-owner-" + tag, Role.READ_WRITE);
        colleague = user(bank, "dm-colleague-" + tag, Role.READ_WRITE);
        reader = user(bank, "dm-reader-" + tag, Role.READ_ONLY);
        stranger = user(otherBank, "dm-stranger-" + tag, Role.READ_WRITE);

        customers = template(owner, "Customers " + tag, TemplateVisibility.PRIVATE);
        field(customers, "FirstName", 1, PiiKind.NAME);
        field(customers, "Email", 1, PiiKind.EMAIL);
        field(customers, "Amount", 2, null);
        field(customers, "Notes", 1, null);
        flowRule(customers, "FirstName", RuleType.REQUIRED, "{}");
        flowRule(customers, "Email", RuleType.REGEX, "{\"Pattern\":\"" + json(EMAIL_PRESET) + "\"}");
        flowRule(customers, "Amount", RuleType.RANGE, "{\"Min\":0,\"Max\":100}");
        flowRule(customers, "Notes", RuleType.CUSTOM, "{}");

        // shared with the bank: the owner reads it, it is not theirs
        orders = template(colleague, "Orders & Co " + tag, TemplateVisibility.BANK);
        ordersSlug = "orders_co_" + tag;
        field(orders, "Email", 1, PiiKind.EMAIL);
        field(orders, "Amount", 1, null);
        field(orders, "Order Id", 1, null);
        field(orders, "Ship date", 3, null);
        field(orders, "Ship-date", 1, null);
        flowRule(orders, "Email", RuleType.REQUIRED, "{}");
        flowRule(orders, "Amount", RuleType.RANGE, "{\"Min\":1}");
        flowRule(orders, "Ship date", RuleType.EXPIRY_DATE, "{}");
        flowRule(orders, "Ship date", RuleType.REGEX, "{\"Pattern\":\"" + json(EMAIL_PRESET) + "\"}");
        flowRule(orders, "Order Id", RuleType.TYPE, "{}");
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

    /** {@code {"templateId":id}} or {@code {"templateId":id,"fields":[...]}}. */
    private static String source(Template template, String... fields) {
        StringBuilder out = new StringBuilder("{\"templateId\":" + template.getTemplateId());
        if (fields.length > 0) {
            out.append(",\"fields\":[");
            for (int i = 0; i < fields.length; i++) {
                out.append(i == 0 ? "" : ",").append('"').append(fields[i]).append('"');
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    private JsonNode build(String name, AppUser user, int expected, String... sources) throws Exception {
        return send(post(BASE + "/from-flow-templates"), "{\"name\":\"" + name + "\",\"sources\":["
                + String.join(",", sources) + "]}", user, expected);
    }

    private JsonNode add(int id, Integer baseRevision, AppUser user, int expected, String... sources)
            throws Exception {
        return send(post(BASE + "/" + id + "/add-from-flow-templates"), "{"
                + (baseRevision == null ? "" : "\"baseRevision\":" + baseRevision + ",")
                + "\"sources\":[" + String.join(",", sources) + "]}", user, expected);
    }

    private int blueprintUsing(Integer dataTemplateId, String name) {
        jdbc.update("INSERT INTO DocBlueprint (OwnerUserId, BankId, Name, CurrentVersion, RulesRevision, Kind, "
                        + "DataTemplateId, CreatedDate) VALUES (?, ?, ?, 1, 0, 'PDF_OVERLAY', ?, ?)",
                owner.getAppUserId(), bank.getBankId(), name, dataTemplateId, LocalDateTime.now());
        Integer id = jdbc.queryForObject("SELECT DocBlueprintId FROM DocBlueprint WHERE BankId = ? AND Name = ?",
                Integer.class, bank.getBankId(), name);
        return id == null ? 0 : id;
    }

    private int blueprintRevision(int blueprintId) {
        Integer revision = jdbc.queryForObject("SELECT RulesRevision FROM DocBlueprint WHERE DocBlueprintId = ?",
                Integer.class, blueprintId);
        return revision == null ? -1 : revision;
    }

    private List<String> copiedAudit() {
        return jdbc.queryForList("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent WHERE BankId = ? AND Action = "
                + "'DOC_DATA_TEMPLATE_COPIED' ORDER BY AuditEventId", String.class, bank.getBankId());
    }

    private int dataTemplateRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DocDataTemplate WHERE BankId = ?", Integer.class,
                bank.getBankId());
        return count == null ? 0 : count;
    }

    private static JsonNode named(JsonNode list, String value) {
        for (JsonNode entry : list) {
            if (value.equals(entry.get("name").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no entry named " + value);
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

    /** name/label/source template id/source field, per field in order. */
    private static List<String> fieldLines(JsonNode dto) {
        List<String> out = new ArrayList<>();
        dto.get("fields").forEach(f -> out.add(f.get("name").asText() + "/" + f.get("label").asText() + "/"
                + (f.get("sourceTemplateId").isNull() ? "-" : f.get("sourceTemplateId").asText()) + "/"
                + (f.get("sourceFieldName").isNull() ? "-" : f.get("sourceFieldName").asText())));
        return out;
    }

    // --------------------------------------------------------------- build

    @Test
    void twoFlowTemplatesMergeWithSuffixedClashesAndAPerFieldSource() throws Exception {
        int c = customers.getTemplateId();
        int o = orders.getTemplateId();
        JsonNode built = build("Merged " + tag, owner, 201, source(customers), source(orders));
        assertThat(built.get("revision").asInt()).isZero();
        assertThat(fieldLines(built)).as("the request's order, then each flow template's; clashes suffixed")
                .containsExactly(
                        "firstname/FirstName/" + c + "/FirstName",
                        "email/Email/" + c + "/Email",
                        "amount/Amount/" + c + "/Amount",
                        "notes/Notes/" + c + "/Notes",
                        "email_" + ordersSlug + "/Email (" + orders.getName() + ")/" + o + "/Email",
                        "amount_" + ordersSlug + "/Amount (" + orders.getName() + ")/" + o + "/Amount",
                        "order_id/Order Id/" + o + "/Order Id",
                        "ship_date/Ship date/" + o + "/Ship date",
                        // a clash inside one flow template gets the suffix too
                        "ship_date_" + ordersSlug + "/Ship-date (" + orders.getName() + ")/" + o + "/Ship-date");
        assertThat(built.get("fields")).extracting(f -> f.get("sortOrder").asInt())
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(named(built.get("fields"), "email").get("sourceTemplateName").asText())
                .isEqualTo(customers.getName());
        JsonNode orderEmail = named(built.get("fields"), "email_" + ordersSlug);
        assertThat(orderEmail.get("sourceTemplateName").asText()).isEqualTo(orders.getName());
        assertThat(orderEmail.get("piiKind").asText()).isEqualTo("EMAIL");
        assertThat(named(built.get("fields"), "amount").get("kind").asText()).isEqualTo("NUMBER");
        assertThat(named(built.get("fields"), "amount_" + ordersSlug).get("kind").asText()).isEqualTo("TEXT");

        // the rules follow their field, IMPORTED with the flow template they came from
        assertThat(rulesOf(built, "email")).singleElement().satisfies(r -> {
            assertThat(r.get("ruleType").asText()).isEqualTo("EMAIL");
            assertThat(r.get("sourceTemplateId").asInt()).isEqualTo(c);
        });
        assertThat(rulesOf(built, "email_" + ordersSlug)).singleElement().satisfies(r -> {
            assertThat(r.get("ruleType").asText()).isEqualTo("REQUIRED");
            assertThat(r.get("origin").asText()).isEqualTo("IMPORTED");
            assertThat(r.get("sourceTemplateId").asInt()).isEqualTo(o);
            assertThat(r.get("sourceTemplateName").asText()).isEqualTo(orders.getName());
            assertThat(r.get("sourceFieldName").asText()).isEqualTo("Email");
        });
        assertThat(rulesOf(built, "amount_" + ordersSlug)).singleElement().satisfies(r -> {
            assertThat(r.get("ruleType").asText()).isEqualTo("RANGE");
            assertThat(r.get("config").get("Min").asInt()).isEqualTo(1);
            assertThat(r.get("sourceTemplateId").asInt()).isEqualTo(o);
        });
        assertThat(rulesOf(built, "amount")).singleElement()
                .satisfies(r -> assertThat(r.get("config").get("Max").asInt()).isEqualTo(100));
        assertThat(rulesOf(built, "ship_date")).as("EMAIL does not apply to a date field: skipped")
                .extracting(r -> r.get("ruleType").asText()).containsExactly("EXPIRY_DATE");
        assertThat(built.get("rules")).hasSize(6);

        // customers gave 4 fields, orders 5: orders is the main template
        assertThat(built.get("sourceTemplateId").asInt()).isEqualTo(o);
        assertThat(built.get("sourceTemplateName").asText()).isEqualTo(orders.getName());

        // the merged template edits like any other: the fields as received save again and keep their source
        JsonNode resaved = send(put(BASE + "/" + built.get("id").asInt() + "/fields"),
                "{\"baseRevision\":0,\"fields\":" + built.get("fields") + "}", owner, 200);
        assertThat(fieldLines(resaved)).isEqualTo(fieldLines(built));
        assertThat(resaved.get("revision").asInt()).isEqualTo(1);

        List<String> audit = copiedAudit();
        assertThat(audit).singleElement().satisfies(v -> assertThat(v)
                .contains("\"templates\":[" + c + "," + o + "]").contains("\"fields\":9")
                .contains("\"rules\":6").contains("\"skipped\":3").contains("\"added\":false")
                .doesNotContain("Email").doesNotContain("Customers").doesNotContain("Pattern"));
    }

    @Test
    void theMainTemplateGaveTheMostFieldsTheFirstOnATieAndAPartialSelectionCopiesOnlyThose() throws Exception {
        JsonNode partial = build("Partial " + tag, owner, 201, source(customers, "Email", "FirstName"),
                source(orders, "Order Id", "Email"));
        assertThat(fieldLines(partial)).as("each flow template's own order, whatever the request's order")
                .containsExactly(
                        "firstname/FirstName/" + customers.getTemplateId() + "/FirstName",
                        "email/Email/" + customers.getTemplateId() + "/Email",
                        "email_" + ordersSlug + "/Email (" + orders.getName() + ")/" + orders.getTemplateId() + "/Email",
                        "order_id/Order Id/" + orders.getTemplateId() + "/Order Id");
        assertThat(partial.get("sourceTemplateId").asInt()).as("2 and 2: the first source wins")
                .isEqualTo(customers.getTemplateId());

        JsonNode reversed = build("Reversed " + tag, owner, 201, source(orders, "Order Id"),
                source(customers, "Email", "Notes"));
        assertThat(reversed.get("sourceTemplateId").asInt()).isEqualTo(customers.getTemplateId());
        assertThat(fieldLines(reversed)).containsExactly(
                "order_id/Order Id/" + orders.getTemplateId() + "/Order Id",
                "email/Email/" + customers.getTemplateId() + "/Email",
                "notes/Notes/" + customers.getTemplateId() + "/Notes");

        // one source is the single copy, with the field's source set too
        JsonNode single = send(post(BASE + "/from-flow-template"), "{\"templateId\":" + customers.getTemplateId()
                + ",\"name\":\"Single " + tag + "\",\"fields\":[\"Amount\"]}", owner, 201);
        assertThat(fieldLines(single)).containsExactly("amount/Amount/" + customers.getTemplateId() + "/Amount");
        assertThat(single.get("fields").get(0).get("sourceTemplateName").asText()).isEqualTo(customers.getName());
        assertThat(copiedAudit().get(2)).contains("\"templateId\":" + customers.getTemplateId())
                .contains("\"templates\":[" + customers.getTemplateId() + "]").contains("\"added\":false");
    }

    // ----------------------------------------------------------------- add

    @Test
    void addingToATemplateChecksTheRevisionSuffixesClashesAndRaisesThePdfTemplatesRevision() throws Exception {
        int id = send(post(BASE), "{\"name\":\"Mine " + tag + "\"}", owner, 201).get("id").asInt();
        send(put(BASE + "/" + id + "/fields"), "{\"baseRevision\":0,\"fields\":[{\"name\":\"email\","
                + "\"label\":\"E-mail\",\"kind\":\"TEXT\"}]}", owner, 200);
        int pdf = blueprintUsing(id, "Form " + tag);
        int unrelated = blueprintUsing(null, "Other form " + tag);

        add(id, 0, owner, 409, source(orders));
        assertThat(blueprintRevision(pdf)).isZero();

        JsonNode added = add(id, 1, owner, 200, source(orders, "Ship date", "Email"));
        assertThat(added.get("revision").asInt()).isEqualTo(2);
        assertThat(blueprintRevision(pdf)).isEqualTo(1);
        assertThat(blueprintRevision(unrelated)).isZero();
        assertThat(fieldLines(added)).containsExactly(
                "email/E-mail/-/-",
                "email_" + ordersSlug + "/Email (" + orders.getName() + ")/" + orders.getTemplateId() + "/Email",
                "ship_date/Ship date/" + orders.getTemplateId() + "/Ship date");
        assertThat(added.get("fields")).extracting(f -> f.get("sortOrder").asInt()).containsExactly(0, 1, 2);
        assertThat(named(added.get("fields"), "email").get("sourceTemplateName").isNull()).isTrue();
        assertThat(added.get("sourceTemplateId").asInt()).as("it had none: set to the main one")
                .isEqualTo(orders.getTemplateId());
        assertThat(added.get("rules")).extracting(r -> r.get("fieldName").asText() + "/"
                + r.get("ruleType").asText()).containsExactlyInAnyOrder("email_" + ordersSlug + "/REQUIRED",
                "ship_date/EXPIRY_DATE");

        // more fields from another template: the main template stays
        JsonNode more = add(id, null, owner, 200, source(customers));
        assertThat(more.get("revision").asInt()).isEqualTo(3);
        assertThat(blueprintRevision(pdf)).isEqualTo(2);
        assertThat(more.get("sourceTemplateId").asInt()).isEqualTo(orders.getTemplateId());
        assertThat(more.get("fields")).extracting(f -> f.get("name").asText()).containsExactly("email",
                "email_" + ordersSlug, "ship_date", "firstname", "email_customers_" + tag, "amount", "notes");

        // the suffixed name is taken too: a number follows
        JsonNode again = add(id, 3, owner, 200, source(orders, "Email"));
        assertThat(again.get("fields")).extracting(f -> f.get("name").asText()).endsWith("email_" + ordersSlug
                + "_2");
        assertThat(again.get("revision").asInt()).isEqualTo(4);

        List<String> audit = copiedAudit();
        assertThat(audit).hasSize(3);
        assertThat(audit.get(0)).contains("\"templates\":[" + orders.getTemplateId() + "]")
                .contains("\"fields\":2").contains("\"rules\":2").contains("\"skipped\":1")
                .contains("\"added\":true");
        assertThat(audit).allSatisfy(v -> assertThat(v).doesNotContain("Email").doesNotContain("Orders"));

        // another user's or bank's data template is not found; a read-only caller cannot write
        add(id, null, colleague, 404, source(orders));
        add(id, null, stranger, 404, source(orders));
        add(id, null, reader, 403, source(orders));
        add(424242, null, owner, 404, source(orders));
        assertThat(call(get(BASE + "/" + id), owner, 200).get("revision").asInt()).isEqualTo(4);
    }

    // ------------------------------------------------------- refused calls

    @Test
    void emptyOrUnknownSourcesAre400AndUnreadableOnesAre404() throws Exception {
        Template foreign = template(stranger, "Foreign " + tag, TemplateVisibility.BANK);
        Template colleaguesOwn = template(colleague, "Private " + tag, TemplateVisibility.PRIVATE);
        build("None", owner, 400);
        send(post(BASE + "/from-flow-templates"), "{\"name\":\"Missing\"}", owner, 400);
        build("Empty fields", owner, 400, source(customers), "{\"templateId\":" + orders.getTemplateId()
                + ",\"fields\":[]}");
        build("Ghost", owner, 400, source(customers), source(orders, "Ghost"));
        build("No id", owner, 400, "{\"fields\":[\"Email\"]}");
        build("Twice", owner, 400, source(customers, "Email"), source(customers, "Notes"));
        build(" ", owner, 400, source(customers));
        build("Foreign", owner, 404, source(customers), source(foreign));
        build("Private", owner, 404, source(customers), source(colleaguesOwn));
        build("Reader", reader, 403, source(customers));
        assertThat(dataTemplateRows()).as("nothing was made").isZero();

        int id = build("Base " + tag, owner, 201, source(customers, "Email")).get("id").asInt();
        add(id, 0, owner, 400);
        add(id, 0, owner, 400, "{\"templateId\":" + orders.getTemplateId() + ",\"fields\":[]}");
        add(id, 0, owner, 400, source(orders, "Ghost"));
        add(id, 0, owner, 404, source(foreign));
        JsonNode unchanged = call(get(BASE + "/" + id), owner, 200);
        assertThat(unchanged.get("revision").asInt()).isZero();
        assertThat(unchanged.get("fields")).hasSize(1);
        assertThat(copiedAudit()).hasSize(1);
    }

    // ---------------------------------------------- Document Flow is apart

    @Test
    void documentFlowsTemplateListNeverShowsADataTemplate() throws Exception {
        build("Validation only " + tag, owner, 201, source(customers), source(orders));
        send(post(BASE), "{\"name\":\"Hand made " + tag + "\"}", owner, 201);
        JsonNode flowList = call(get("/Templates/GetAll"), owner, 200);
        List<String> names = new ArrayList<>();
        flowList.forEach(t -> names.add(t.get("name").asText()));
        assertThat(names).contains(customers.getName(), orders.getName())
                .doesNotContain("Validation only " + tag, "Hand made " + tag);
        Integer flowTemplates = jdbc.queryForObject("SELECT COUNT(*) FROM Template WHERE BankId = ?", Integer.class,
                bank.getBankId());
        assertThat(flowTemplates).as("a data template is never a flow template row").isEqualTo(2);
    }
}
