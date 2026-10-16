package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Document validation (docs/doccreation/field-validation-contract.md §2 and
 * §4) over HTTP: validate (VALIDATED or DRAFT with its errors, warnings
 * never block, audited), the generate/download gate (draft, changed content,
 * pre-ws33 documents), the promotion to GENERATED, which changes send a
 * document back to DRAFT and which do not, default values on create and
 * upgrade, values drawn in their field's format, record values normalised,
 * and the status filter.
 */
class DocValidationApiTest extends IsolatedStorageApiTestSupport {

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
    @Autowired
    private DocInstanceUseCase instances;

    private Bank bank;
    private AppUser owner;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        engine.proposedRegions = List.of(
                FakePdfTemplateEngine.region("full_name", "Full name", null, RegionKind.TEXT,
                        new PdfBox(0, 100, 760, 200, 20), 0, null),
                FakePdfTemplateEngine.region("dob", "Date of birth", null, RegionKind.DATE,
                        new PdfBox(0, 100, 720, 120, 20), 1, null),
                FakePdfTemplateEngine.region("amount", "Amount", null, RegionKind.NUMBER,
                        new PdfBox(0, 100, 680, 120, 20), 2, null),
                FakePdfTemplateEngine.region("agree", "I agree", null, RegionKind.CHECKBOX,
                        new PdfBox(0, 100, 640, 12, 12), 3, null),
                FakePdfTemplateEngine.region("passport_photo", "Passport photo", null, RegionKind.IMAGE,
                        new PdfBox(0, 400, 600, 120, 150), 4, null),
                FakePdfTemplateEngine.region("house_front", "House front", null, RegionKind.IMAGE,
                        new PdfBox(0, 100, 100, 200, 150), 5, null));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("VAL" + tag.substring(0, 5).toUpperCase(), "Validation bank"));
        owner = user("val-owner-" + tag);
    }

    @AfterEach
    void cleanUp() {
        engine.reset();
        SecurityContextHolder.clearContext();
        engine.onFill = null;
        int bankId = bank.getBankId();
        jdbc.update("DELETE FROM DocFieldRule WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
    }

    // ------------------------------------------------------------ fixtures

    private AppUser user(String name) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(name);
        entity.setBankId(bank.getBankId());
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(owner));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode saveOverlay(int id, int baseVersion, JsonNode regions) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", baseVersion);
        body.set("regions", regions);
        body.set("edits", MAPPER.createArrayNode());
        body.put("confirm", true);
        return json(mvc.perform(as(put("/api/doccreation/blueprints/" + id + "/overlay"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn());
    }

    private int readyTemplate() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "form.pdf", "application/pdf",
                FakePdfTemplateEngine.blankPdf(1, 0));
        JsonNode draft = json(mvc.perform(as(multipart("/api/doccreation/blueprints").file(file)
                        .param("flow", "BLANK").param("name", "Validated form")))
                .andExpect(status().isCreated()).andReturn());
        return saveOverlay(draft.get("id").asInt(), 1, draft.get("regions")).get("id").asInt();
    }

    private int document(int blueprintId) throws Exception {
        return json(mvc.perform(as(post("/api/doccreation/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Loan file\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asInt();
    }

    private JsonNode getDoc(int instanceId) throws Exception {
        return json(mvc.perform(as(get("/api/doccreation/instances/" + instanceId)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode putValues(int instanceId, String valuesJson) throws Exception {
        return json(mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/values"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode validate(int instanceId) throws Exception {
        return json(mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/validate")))
                .andExpect(status().isOk()).andReturn());
    }

    private void validated(int instanceId) throws Exception {
        assertThat(validate(instanceId).get("status").asText()).isEqualTo("VALIDATED");
    }

    private String statusOf(int instanceId) {
        return jdbc.queryForObject("SELECT Status FROM DocInstance WHERE DocInstanceId = ?", String.class,
                instanceId);
    }

    private Map<String, Object> row(int instanceId) {
        return jdbc.queryForMap("SELECT * FROM DocInstance WHERE DocInstanceId = ?", instanceId);
    }

    private DocFieldRule addRule(int blueprintId, String key, String type, String config, String severity) {
        DocFieldRule rule = new DocFieldRule();
        rule.setBankId(bank.getBankId());
        rule.setBlueprintId(blueprintId);
        rule.setRegionKey(key);
        rule.setRuleType(type);
        rule.setConfigJson(config);
        rule.setSeverity(severity);
        rule.setUpdatedAt(LocalDateTime.now());
        return rules.save(rule);
    }

    private void setProps(int blueprintId, int version, String key, String propsJson) {
        assertThat(jdbc.update("UPDATE DocTemplateRegion SET FieldPropsJson = ? "
                + "WHERE BlueprintId = ? AND Version = ? AND RegionKey = ?", propsJson, blueprintId, version, key))
                .isOne();
    }

    private int audits(String outcome, int instanceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = 'DOC_VALIDATED' "
                        + "AND Outcome = ? AND EntityId = ? AND BankId = ?", Integer.class, outcome,
                String.valueOf(instanceId), bank.getBankId());
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private JsonNode upload(int instanceId, String name, boolean autoFill) throws Exception {
        return json(mvc.perform(as(multipart("/api/doccreation/instances/" + instanceId + "/images")
                        .file(new MockMultipartFile("files", name, "image/png", png()))
                        .param("autoFill", String.valueOf(autoFill))))
                .andExpect(status().isCreated()).andReturn());
    }

    private static int imageId(JsonNode doc, String name) {
        for (JsonNode image : doc.get("images")) {
            if (image.get("originalName").asText().equals(name)) {
                return image.get("id").asInt();
            }
        }
        throw new AssertionError("no image " + name);
    }

    private void place(int instanceId, int imageId, String regionKey) throws Exception {
        mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/images/" + imageId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regionKey == null ? "{\"regionKey\":null}" : "{\"regionKey\":\"" + regionKey + "\"}"))
                .andExpect(status().isOk());
    }

    private void authenticateAsOwner() {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(String.valueOf(owner.getAppUserId()))
                .claim("bank_id", owner.getBankId())
                .claim("roles", List.of(owner.getRole().name()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ------------------------------------------------------------- validate

    @Test
    void validateKeepsADraftWithItsErrorsAndValidatesOnceTheyAreFixed() throws Exception {
        int blueprintId = readyTemplate();
        int instanceId = document(blueprintId);
        JsonNode fresh = getDoc(instanceId);
        assertThat(fresh.get("status").asText()).isEqualTo("DRAFT");
        assertThat(fresh.get("validation").get("checked").asBoolean()).as("never validated").isFalse();
        assertThat(fresh.get("validation").get("state").asText()).isEqualTo("DRAFT");
        assertThat(fresh.get("validation").get("errors")).isEmpty();

        DocFieldRule required = addRule(blueprintId, "full_name", "REQUIRED", "{}", "ERROR");
        addRule(blueprintId, "amount", "RANGE", "{\"Max\": 100}", "WARNING");
        addRule(blueprintId, "passport_photo", "RANGE", "{\"Max\": 1}", "ERROR");

        JsonNode failed = validate(instanceId);
        assertThat(failed.get("status").asText()).isEqualTo("DRAFT");
        JsonNode result = failed.get("validation");
        assertThat(result.get("checked").asBoolean()).isTrue();
        assertThat(result.get("state").asText()).isEqualTo("DRAFT");
        assertThat(result.get("validatedAt").isNull()).isTrue();
        assertThat(result.get("errors")).hasSize(1);
        assertThat(result.get("errors").get(0).get("regionKey").asText()).isEqualTo("full_name");
        assertThat(result.get("errors").get(0).get("code").asText()).isEqualTo("REQUIRED");
        assertThat(result.get("errors").get(0).get("ruleId").asLong()).isEqualTo(required.getDocFieldRuleId());
        assertThat(result.get("errors").get(0).get("message").asText()).isEqualTo("Full name is required.");
        assertThat(result.get("setupWarnings")).hasSize(1);
        assertThat(row(instanceId).get("ValidatedHash")).isNull();
        assertThat(audits("FAILED", instanceId)).isOne();
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));

        // Fixed: VALIDATED, the warning reported but not blocking.
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\",\"amount\":\"250\",\"dob\":\"1990-04-01\"}");
        JsonNode passed = validate(instanceId);
        assertThat(passed.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(passed.get("validation").get("state").asText()).isEqualTo("VALIDATED");
        assertThat(passed.get("validation").get("validatedAt").isNull()).isFalse();
        assertThat(passed.get("validation").get("errors")).isEmpty();
        assertThat(passed.get("validation").get("warnings")).hasSize(1);
        assertThat(passed.get("validation").get("warnings").get(0).get("message").asText())
                .isEqualTo("Amount must be a number of at most 100.");
        assertThat(passed.get("validation").get("rulesChangedSinceValidation").asBoolean()).isFalse();
        Map<String, Object> stored = row(instanceId);
        assertThat((String) stored.get("ValidatedHash")).matches("[0-9a-f]{64}");
        assertThat(stored.get("ValidatedAt")).isNotNull();
        assertThat(((Number) stored.get("ValidatedByUserId")).intValue()).isEqualTo(owner.getAppUserId());
        assertThat(((Number) stored.get("ValidatedRulesRevision")).intValue()).isZero();
        assertThat((String) stored.get("ValidationJson")).as("no values are stored with the result")
                .doesNotContain("Jane Citizen").doesNotContain("250").doesNotContain("1990");
        assertThat(audits("SUCCESS", instanceId)).isOne();
        String evidence = jdbc.queryForObject("SELECT NewValues FROM AuditEvent WHERE Action = 'DOC_VALIDATED' "
                        + "AND Outcome = 'FAILED' AND EntityId = ? AND BankId = ?", String.class,
                String.valueOf(instanceId), bank.getBankId());
        assertThat(evidence).contains("full_name").contains("errorKeys").contains("rulesRevision");

        // The template's rules change: the document stays VALIDATED but says so.
        jdbc.update("UPDATE DocBlueprint SET RulesRevision = 1 WHERE DocBlueprintId = ?", blueprintId);
        JsonNode later = getDoc(instanceId);
        assertThat(later.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(later.get("validation").get("rulesChangedSinceValidation").asBoolean()).isTrue();
        assertThat(later.get("validation").get("warnings")).hasSize(1);
    }

    @Test
    void builtInChecksBlockAndTheInstanceCarriesTheFieldsProps() throws Exception {
        int blueprintId = readyTemplate();
        setProps(blueprintId, 1, "full_name", "{\"placeholder\":\"e.g. Jane\",\"maxLength\":5}");
        int instanceId = document(blueprintId);
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\",\"dob\":\"01/04/1990\",\"amount\":\"lots\","
                + "\"agree\":\"maybe\"}");

        JsonNode failed = validate(instanceId);

        assertThat(failed.get("status").asText()).isEqualTo("DRAFT");
        List<String> codes = new ArrayList<>();
        failed.get("validation").get("errors").forEach(e -> codes.add(e.get("code").asText()));
        assertThat(codes).containsExactly("TOO_LONG", "DATE_INVALID", "NUMBER_INVALID", "CHECKBOX_INVALID");
        assertThat(failed.get("validation").toString()).doesNotContain("Jane Citizen").doesNotContain("lots");
        assertThat(failed.get("props").get("full_name").get("placeholder").asText()).isEqualTo("e.g. Jane");
        assertThat(failed.get("props").get("full_name").get("maxLength").asInt()).isEqualTo(5);
        assertThat(failed.get("props").has("passport_photo")).isTrue();
        assertThat(failed.get("props").get("dob").has("empty")).isFalse();
    }

    @Test
    void theDocumentCarriesEachFieldsChecksInPlainWords() throws Exception {
        int blueprintId = readyTemplate();
        setProps(blueprintId, 1, "full_name", "{\"maxLength\":40}");
        addRule(blueprintId, "full_name", "REQUIRED", "{}", "ERROR");
        addRule(blueprintId, "dob", "DATE_RANGE", "{\"Max\":\"-18y\"}", "ERROR");
        addRule(blueprintId, "amount", "COMPARE", "{\"Field\":\"dob\",\"Op\":\">=\"}", "WARNING");
        DocFieldRule imported = addRule(blueprintId, "amount", "RANGE", "{\"Min\":0,\"Max\":100}", "ERROR");
        imported.setOrigin("IMPORTED");
        imported.setSourceTemplateId(987_654);
        imported.setSourceFieldName("loan_amount");
        rules.save(imported);
        // A rule that does not fit its field (RANGE on a photo) is not shown, as the checks skip it.
        addRule(blueprintId, "passport_photo", "RANGE", "{\"Min\":1}", "ERROR");
        int instanceId = document(blueprintId);
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");

        JsonNode doc = getDoc(instanceId);

        JsonNode checks = doc.get("fieldChecks");
        assertThat(checks.has("house_front")).as("every field has an entry").isTrue();
        assertThat(checks.get("full_name").get("rules").get(0).get("summary").asText()).isEqualTo("Required");
        assertThat(checks.get("full_name").get("builtIn")).extracting(JsonNode::asText)
                .containsExactly("At most 40 characters");
        assertThat(checks.get("dob").get("rules").get(0).get("summary").asText())
                .isEqualTo("On or before 18 years ago");
        assertThat(checks.get("dob").get("builtIn")).extracting(JsonNode::asText).containsExactly("A date");
        List<String> amountRules = new ArrayList<>();
        checks.get("amount").get("rules").forEach(r -> amountRules.add(r.get("summary").asText()
                + "|" + r.get("severity").asText() + "|" + r.path("sourceFieldName").asText("-")));
        assertThat(amountRules).containsExactly("Must be ≥ Date of birth|WARNING|-",
                "Between 0 and 100|ERROR|loan_amount");
        assertThat(checks.get("amount").get("rules").get(1).get("sourceTemplateName").asText())
                .isEqualTo("a removed flow template");
        assertThat(checks.get("passport_photo").get("rules")).isEmpty();
        assertThat(checks.get("full_name").get("mappedFrom")).isEmpty();
        assertThat(doc.get("rulesRevision").asInt()).isZero();
        assertThat(doc.get("canEditRules").asBoolean()).isTrue();
        assertThat(checks.toString()).as("template data only").doesNotContain("Jane Citizen");
    }

    // ---------------------------------------------------------------- gates

    @Test
    void generateAndDownloadNeedAValidatedUnchangedDocument() throws Exception {
        int instanceId = document(readyTemplate());
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"))
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        assertThat(engine.lastFillValues).as("nothing was rendered").isNull();

        validated(instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isOk());
        // Validating a generated, unchanged document keeps it GENERATED.
        assertThat(validate(instanceId).get("status").asText()).isEqualTo("GENERATED");
        assertThat(getDoc(instanceId).get("validation").get("state").asText()).isEqualTo("VALIDATED");

        // Content that no longer matches the validated hash is refused.
        jdbc.update("UPDATE DocInstance SET ValuesJson = ? WHERE DocInstanceId = ?",
                "{\"full_name\":\"Someone else\"}", instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));

        // A submitted document is frozen: it is not validated again, and it still downloads.
        jdbc.update("UPDATE DocInstance SET Status = 'SUBMITTED' WHERE DocInstanceId = ?", instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/validate")))
                .andExpect(status().isConflict());
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isOk());
    }

    @Test
    void aGeneratedDocumentFromBeforeValidationStillGenerates() throws Exception {
        int instanceId = document(readyTemplate());
        jdbc.update("UPDATE DocInstance SET Status = 'GENERATED', ValidatedHash = NULL, ValidatedAt = NULL "
                + "WHERE DocInstanceId = ?", instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        assertThat(getDoc(instanceId).get("validation").get("checked").asBoolean()).isFalse();

        // Only a never-validated GENERATED document: one with a validation but no hash is refused.
        jdbc.update("UPDATE DocInstance SET ValidatedAt = ? WHERE DocInstanceId = ?",
                Timestamp.valueOf(LocalDateTime.now()), instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        // The first real change makes it a DRAFT like any other.
        jdbc.update("UPDATE DocInstance SET ValidatedAt = NULL WHERE DocInstanceId = ?", instanceId);
        putValues(instanceId, "{\"full_name\":\"Jane\"}");
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
    }

    @Test
    void generatePromotesOnlyWhenTheDocumentDidNotChangeWhileRendering() throws Exception {
        int instanceId = document(readyTemplate());
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        validated(instanceId);
        engine.onFill = () -> jdbc.update("UPDATE DocInstance SET UpdatedDate = ? WHERE DocInstanceId = ?",
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)), instanceId);

        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "The document changed while it was being generated; generate again."));
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
        assertThat(row(instanceId).get("LastGeneratedDate")).isNull();

        engine.onFill = null;
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
    }

    // ------------------------------------------------------- back to DRAFT

    @Test
    void onlyARealChangeSendsAValidatedDocumentBackToDraft() throws Exception {
        int instanceId = document(readyTemplate());
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        validated(instanceId);
        Object updated = row(instanceId).get("UpdatedDate");

        // An autosave that changes nothing, a new title and a photo for the tray are not changes.
        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}");
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
        mvc.perform(as(put("/api/doccreation/instances/" + instanceId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
        assertThat(row(instanceId).get("UpdatedDate")).as("a rename does not touch").isEqualTo(updated);
        upload(instanceId, "tray.png", false);
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
        int tray = imageId(getDoc(instanceId), "tray.png");
        place(instanceId, tray, null);
        assertThat(statusOf(instanceId)).as("tray to tray").isEqualTo("VALIDATED");

        // A changed value.
        putValues(instanceId, "{\"full_name\":\"Jane Q. Citizen\"}");
        Map<String, Object> changed = row(instanceId);
        assertThat(changed.get("Status")).isEqualTo("DRAFT");
        assertThat(changed.get("ValidatedAt")).isNull();
        assertThat(changed.get("ValidatedHash")).isNull();
        assertThat(changed.get("ValidationJson")).as("the last result is kept").isNotNull();

        // A photo placed into a box, and placed there again (no change).
        validated(instanceId);
        place(instanceId, tray, "passport_photo");
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
        validated(instanceId);
        place(instanceId, tray, "passport_photo");
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");

        // An upload that auto-fills a box.
        JsonNode filled = upload(instanceId, "house_front.png", true);
        assertThat(filled.get("assigned")).hasSize(1);
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");

        // Deleting a tray photo is not a change; deleting a placed one is.
        validated(instanceId);
        upload(instanceId, "spare.png", false);
        int spare = imageId(getDoc(instanceId), "spare.png");
        mvc.perform(as(delete("/api/doccreation/instances/" + instanceId + "/images/" + spare)))
                .andExpect(status().isNoContent());
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
        mvc.perform(as(delete("/api/doccreation/instances/" + instanceId + "/images/" + tray)))
                .andExpect(status().isNoContent());
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");

        // A GENERATED document goes back to DRAFT too.
        validated(instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("GENERATED");
        putValues(instanceId, "{\"amount\":\"12\"}");
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
    }

    @Test
    void upgradeSendsBackToDraftAndFillsTheDefaultsOfNewFieldsOnly() throws Exception {
        int blueprintId = readyTemplate();
        setProps(blueprintId, 1, "full_name", "{\"defaultValue\":\"Jane Default\"}");
        setProps(blueprintId, 1, "agree", "{\"defaultValue\":\"true\"}");
        setProps(blueprintId, 1, "dob", "{\"defaultValue\":\"\"}");
        int instanceId = document(blueprintId);
        JsonNode created = getDoc(instanceId);
        assertThat(created.get("values").get("full_name").asText()).isEqualTo("Jane Default");
        assertThat(created.get("values").get("agree").asText()).isEqualTo("true");
        assertThat(created.get("values").has("dob")).isFalse();
        assertThat(created.get("props").get("full_name").get("defaultValue").asText()).isEqualTo("Jane Default");

        // The user clears the name; version 2 adds a country field.
        putValues(instanceId, "{\"full_name\":\"\"}");
        validated(instanceId);
        JsonNode v1 = json(mvc.perform(as(get("/api/doccreation/blueprints/" + blueprintId)))
                .andExpect(status().isOk()).andReturn());
        ArrayNode regions = v1.get("regions").deepCopy();
        regions.addObject().put("key", "country").put("label", "Country").put("kind", "TEXT").put("page", 0)
                .put("fx", 0.1).put("fy", 0.05).put("fw", 0.3).put("fh", 0.02)
                .put("fontName", "Helvetica").put("fontSize", 0).put("source", "MANUAL");
        assertThat(saveOverlay(blueprintId, 1, regions).get("version").asInt()).isEqualTo(2);
        setProps(blueprintId, 2, "full_name", "{\"defaultValue\":\"Not for old fields\"}");
        setProps(blueprintId, 2, "country", "{\"defaultValue\":\"Mauritius\"}");

        JsonNode upgraded = json(mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/upgrade")))
                .andExpect(status().isOk()).andReturn());

        assertThat(upgraded.get("blueprintVersion").asInt()).isEqualTo(2);
        assertThat(upgraded.get("status").asText()).isEqualTo("DRAFT");
        assertThat(upgraded.get("values").get("country").asText()).isEqualTo("Mauritius");
        assertThat(upgraded.get("values").has("full_name")).as("the cleared field stays cleared").isFalse();
        assertThat(upgraded.get("droppedKeys")).isEmpty();
        assertThat(upgraded.get("validation").has("checked")).isTrue();

        // Upgrading again changes nothing: the validation stays.
        validated(instanceId);
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/upgrade")))
                .andExpect(status().isOk());
        assertThat(statusOf(instanceId)).isEqualTo("VALIDATED");
    }

    // --------------------------------------------------- drawing and fills

    @Test
    void valuesAreDrawnInTheirFieldsFormatInPreviewsAndTheGeneratedPdf() throws Exception {
        int blueprintId = readyTemplate();
        setProps(blueprintId, 1, "dob", "{\"dateFormat\":\"dd/MM/yyyy\"}");
        setProps(blueprintId, 1, "amount",
                "{\"numberFormat\":{\"decimals\":2,\"thousands\":true,\"prefix\":\"Rs \"}}");
        int instanceId = document(blueprintId);
        JsonNode doc = putValues(instanceId, "{\"dob\":\"2026-03-05\",\"amount\":\"1234.5\","
                + "\"full_name\":\"2026-03-05\"}");
        assertThat(doc.get("values").get("dob").asText()).as("stored ISO").isEqualTo("2026-03-05");

        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/pages/0").param("dpi", "72")))
                .andExpect(status().isOk());
        assertThat(engine.lastFillValues).containsEntry("dob", "05/03/2026")
                .containsEntry("amount", "Rs 1,234.50")
                .containsEntry("full_name", "2026-03-05");

        validated(instanceId);
        engine.lastFillValues = null;
        MvcResult download = mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download")))
                .andExpect(status().isOk()).andReturn();
        assertThat(engine.lastFillValues).containsEntry("dob", "05/03/2026")
                .containsEntry("amount", "Rs 1,234.50");
        assertThat(pdfText(download.getResponse().getContentAsByteArray()))
                .contains("dob=05/03/2026").contains("amount=Rs 1,234.50");
    }

    @Test
    void recordValuesAreNormalisedAndAFillThatChangesNothingKeepsTheValidation() throws Exception {
        int instanceId = document(readyTemplate());
        authenticateAsOwner();

        instances.fillValues(instanceId, Map.of("dob", "05/03/2026", "amount", "Rs 1,234.50", "agree", "Yes",
                "full_name", "05/03/2026"), List.of());

        JsonNode doc = getDoc(instanceId);
        assertThat(doc.get("values").get("dob").asText()).isEqualTo("2026-03-05");
        assertThat(doc.get("values").get("amount").asText()).isEqualTo("1234.50");
        assertThat(doc.get("values").get("agree").asText()).isEqualTo("true");
        assertThat(doc.get("values").get("full_name").asText()).as("text is kept").isEqualTo("05/03/2026");

        validated(instanceId);
        // (A MockMvc request clears the security context: authenticate again for direct calls.)
        authenticateAsOwner();
        instances.fillValues(instanceId, Map.of("dob", "5 Mar 2026"), List.of());
        assertThat(statusOf(instanceId)).as("the same date in another layout").isEqualTo("VALIDATED");
        instances.fillValues(instanceId, Map.of("amount", "1,000"), List.of());
        assertThat(statusOf(instanceId)).isEqualTo("DRAFT");
        assertThat(getDoc(instanceId).get("values").get("amount").asText()).isEqualTo("1000");
    }

    // ---------------------------------------------------------------- list

    @Test
    void myDocumentsFilterByStatus() throws Exception {
        int blueprintId = readyTemplate();
        int draft = document(blueprintId);
        int done = document(blueprintId);
        validated(done);

        JsonNode onlyValidated = json(mvc.perform(as(get("/api/doccreation/instances").param("status", "VALIDATED")))
                .andExpect(status().isOk()).andReturn());
        assertThat(onlyValidated).hasSize(1);
        assertThat(onlyValidated.get(0).get("id").asInt()).isEqualTo(done);
        assertThat(onlyValidated.get(0).get("status").asText()).isEqualTo("VALIDATED");
        JsonNode drafts = json(mvc.perform(as(get("/api/doccreation/instances").param("status", "draft")
                        .param("blueprintId", String.valueOf(blueprintId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(drafts).extracting(n -> n.get("id").asInt()).containsExactly(draft);
        assertThat(json(mvc.perform(as(get("/api/doccreation/instances"))).andReturn())).hasSize(2);
        mvc.perform(as(get("/api/doccreation/instances").param("status", "ARCHIVED")))
                .andExpect(status().isBadRequest());
    }
}
