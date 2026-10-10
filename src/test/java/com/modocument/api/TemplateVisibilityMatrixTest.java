package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.UploadedFile;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.UploadedFileJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Risk #1 of phase 2 (template visibility must never leak): EVERY
 * template-related route, for every kind of caller, against a PRIVATE and a
 * BANK-shared template (ws22, docs/phase2/template-visibility.md).
 *
 * <p>Callers: the owner (an ADMIN of bank A), a READ_WRITE and an ADMIN
 * colleague of bank A, a READ_ONLY colleague (read routes), and an ADMIN of
 * bank B. Expected:
 * <ul>
 *   <li><b>read / use</b> — owner always; a colleague of the same bank only
 *       the BANK template; bank B never. Refusal is 404 (indistinguishable
 *       from a missing id).</li>
 *   <li><b>write</b> — PRIVATE: the owner only (everyone else 404); BANK:
 *       template:publish holders (ADMIN) only — a colleague who can see it
 *       gets 403, and so does its owner once they lose template:publish;
 *       bank B 404.</li>
 * </ul>
 * On H2 there is no RLS: the explicit bank pin in the read predicate is the
 * only wall between banks here, which is exactly what this proves.
 * TemplateVisibilityPgIT proves RLS still blocks cross-bank access on
 * PostgreSQL. The template maker/checker gate is OFF in the H2 suite
 * (application-test.yml), so update/delete apply directly; the gated
 * submit/apply path is TemplateVisibilityMakerCheckerTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TemplateVisibilityMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsRepository fields;
    @Autowired
    private ValidationRuleRepository rules;
    @Autowired
    private UploadedFileJpaRepository uploads;
    @Autowired
    private IngestionJobJpaRepository ingestionJobs;
    @Autowired
    private ExtractionJobStore extractionJobs;
    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private Bank bankA;
    private AppUser owner;          // ADMIN, bank A — owns PRIV and SHARED
    private AppUser writer;         // READ_WRITE colleague, bank A
    private AppUser colleagueAdmin; // ADMIN colleague, bank A (template:publish)
    private AppUser reader;         // READ_ONLY colleague, bank A
    private AppUser foreignAdmin;   // ADMIN, bank B
    private Template priv;
    private Template shared;
    /** BANK template whose owner holds no template:publish (e.g. a demoted admin). */
    private Template sharedOwnedByWriter;

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bankA = banks.save(new Bank("TVA" + tag.substring(0, 4).toUpperCase(), "Visibility A"));
        Bank bankB = banks.save(new Bank("TVB" + tag.substring(4).toUpperCase(),
                "Visibility B"));
        owner = user("owner", bankA, Role.ADMIN);
        writer = user("writer", bankA, Role.READ_WRITE);
        colleagueAdmin = user("coadmin", bankA, Role.ADMIN);
        reader = user("reader", bankA, Role.READ_ONLY);
        foreignAdmin = user("foreign", bankB, Role.ADMIN);
        priv = template("Private", owner, TemplateVisibility.PRIVATE);
        shared = template("Shared", owner, TemplateVisibility.BANK);
        sharedOwnedByWriter = template("Writer shared", writer, TemplateVisibility.BANK);
    }

    // ------------------------------------------------------------- reads

    @Test
    void readRoutesFollowOwnerOrSameBankShared() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Template t : List.of(priv, shared)) {
            int fieldId = fieldOf(t);
            for (AppUser actor : List.of(owner, writer, colleagueAdmin, reader, foreignAdmin)) {
                int expected = readable(actor, t) ? 200 : 404;
                expect(failures, actor, t, "GET /Templates/GetById",
                        get("/Templates/GetById/" + t.getTemplateId()), expected);
                expect(failures, actor, t, "GET /TemplateFields/GetAll",
                        get("/TemplateFields/GetAll?templateId=" + t.getTemplateId()), expected);
                expect(failures, actor, t, "GET /TemplateFields/GetById",
                        get("/TemplateFields/GetById/" + fieldId), expected);
                expect(failures, actor, t, "GET /ValidationRules/GetByTemplate",
                        get("/ValidationRules/GetByTemplate/" + t.getTemplateId()), expected);
                expect(failures, actor, t, "POST /Validation/validate",
                        json(post("/Validation/validate"), Map.of(
                                "templateId", t.getTemplateId(),
                                "rows", List.of(Map.of("Amount", "12")))), expected);

                boolean listed = listedIds(actor).contains(t.getTemplateId());
                if (listed != readable(actor, t)) {
                    failures.add(label(actor, t) + " GET /Templates/GetAll listed=" + listed);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void theListingCarriesVisibilityOwnershipAndEditability() throws Exception {
        JsonNode asWriter = templateView(writer, shared);
        assertThat(asWriter.get("visibility").asText()).isEqualTo("BANK");
        assertThat(asWriter.get("ownedByMe").asBoolean()).isFalse();
        assertThat(asWriter.get("editable").asBoolean()).isFalse();
        assertThat(asWriter.get("ownerName").asText()).isEqualTo(owner.getDisplayName());

        JsonNode asColleagueAdmin = templateView(colleagueAdmin, shared);
        assertThat(asColleagueAdmin.get("editable").asBoolean()).isTrue();

        JsonNode asOwner = templateView(owner, priv);
        assertThat(asOwner.get("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(asOwner.get("ownedByMe").asBoolean()).isTrue();
        assertThat(asOwner.get("editable").asBoolean()).isTrue();
        assertThat(asOwner.get("ownerName").isNull()).isTrue();

        // Its owner lost template:publish: readable, no longer editable.
        assertThat(templateView(writer, sharedOwnedByWriter).get("editable").asBoolean())
                .isFalse();
    }

    // -------------------------------------------------------------- uses

    @Test
    void useRoutesAcceptASameBankSharedTemplateOnly() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Template t : List.of(priv, shared)) {
            for (AppUser actor : List.of(owner, writer, colleagueAdmin, foreignAdmin)) {
                boolean usable = readable(actor, t);
                expect(failures, actor, t, "POST /api/ingestion/jobs (create)",
                        json(post("/api/ingestion/jobs"), Map.of(
                                "fileName", uploadOwnedBy(actor),
                                "templateId", t.getTemplateId(),
                                "autoRun", false)),
                        usable ? 201 : 404);
                expect(failures, actor, t, "POST /api/extraction/sources",
                        json(post("/api/extraction/sources"), websiteSource(t.getTemplateId())),
                        usable ? 201 : 404);
                String sourceId = sourceOf(actor);
                expect(failures, actor, t, "POST /api/extraction/jobs",
                        json(post("/api/extraction/jobs"), Map.of(
                                "sourceId", sourceId,
                                "templateId", t.getTemplateId(),
                                "autoStart", false)),
                        usable ? 201 : 404);
                // The bridge gates on the template before reading records: the
                // seeded job has none, so passing the gate answers 409.
                expect(failures, actor, t, "POST /api/extraction/jobs/{id}/bridge",
                        json(post("/api/extraction/jobs/" + completedExtractionOf(actor)
                                + "/bridge"), Map.of("templateId", t.getTemplateId())),
                        usable ? 409 : 404);
                expect(failures, actor, t, "POST /ImportHistory/Create",
                        json(post("/ImportHistory/Create"), Map.of(
                                "fileName", "import-" + tag + ".csv",
                                "templateId", t.getTemplateId())),
                        usable ? 201 : 404);
            }
        }
        assertThat(failures).isEmpty();
    }

    // ------------------------------------------------------------ writes

    @Test
    void writeRoutesNeedOwnershipOrTemplatePublish() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Template t : List.of(priv, shared, sharedOwnedByWriter)) {
            for (AppUser actor : List.of(owner, writer, colleagueAdmin, foreignAdmin)) {
                int fieldId = fieldOf(t);
                expect(failures, actor, t, "PUT /Templates/Update",
                        json(put("/Templates/Update"), Map.of(
                                "templateId", t.getTemplateId(),
                                "name", t.getName(),
                                "description", "edited by " + actor.getDisplayName())),
                        writeStatus(actor, t, 201));
                expect(failures, actor, t, "POST /TemplateFields/Create",
                        json(post("/TemplateFields/Create"), Map.of(
                                "templateId", t.getTemplateId(),
                                "templateDataTypeId", 1,
                                "fieldName", "F" + UUID.randomUUID().toString().substring(0, 6))),
                        writeStatus(actor, t, 201));
                expect(failures, actor, t, "PUT /TemplateFields/Update",
                        json(put("/TemplateFields/Update/" + fieldId), Map.of(
                                "templateFieldsId", fieldId,
                                "templateId", t.getTemplateId(),
                                "templateDataTypeId", 1,
                                "fieldName", "Amount")),
                        writeStatus(actor, t, 201));
                expect(failures, actor, t, "DELETE /TemplateFields/{id}",
                        delete("/TemplateFields/" + fieldOf(t)), writeStatus(actor, t, 204));
                expect(failures, actor, t, "PUT /ValidationRules/Upsert",
                        json(put("/ValidationRules/Upsert"), Map.of(
                                "templateId", t.getTemplateId(),
                                "fieldName", "Amount",
                                "ruleType", "REQUIRED")),
                        writeStatus(actor, t, 200));
                ruleOf(t);
                expect(failures, actor, t, "DELETE /ValidationRules",
                        delete("/ValidationRules?templateId=" + t.getTemplateId()
                                + "&fieldName=Amount&ruleType=REQUIRED"),
                        writeStatus(actor, t, 204));
                ruleOf(t);
                expect(failures, actor, t, "DELETE /ValidationRules/{t}/{f}/{r}",
                        delete("/ValidationRules/" + t.getTemplateId() + "/Amount/REQUIRED"),
                        writeStatus(actor, t, 204));
                // The visibility route itself demands template:publish first.
                int visibility = isAdmin(actor) ? writeStatus(actor, t, 200) : 403;
                expect(failures, actor, t, "PUT /Templates/{id}/Visibility",
                        json(put("/Templates/" + t.getTemplateId() + "/Visibility"),
                                Map.of("visibility", t.getVisibility().name())),
                        visibility);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void deleteFollowsTheWriteRuleToo() throws Exception {
        List<String> failures = new ArrayList<>();
        for (TemplateVisibility visibility : TemplateVisibility.values()) {
            for (AppUser actor : List.of(owner, writer, colleagueAdmin, foreignAdmin)) {
                Template scratch = template("Scratch", owner, visibility);
                expect(failures, actor, scratch, "DELETE /Templates/{id}",
                        delete("/Templates/" + scratch.getTemplateId()),
                        writeStatus(actor, scratch, 204));
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void creatingASharedTemplateNeedsTemplatePublish() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "Created shared " + tag,
                "visibility", "BANK",
                "fields", List.of(Map.of("fieldName", "Amount", "dataType", "Text")));
        assertThat(status(writer, json(post("/Templates/CreateWithFields"), body)))
                .isEqualTo(403);

        String created = mvc.perform(json(post("/Templates/CreateWithFields"), body)
                        .header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = MAPPER.readTree(created);
        assertThat(response.get("visibility").asText()).isEqualTo("BANK");
        // ... and is immediately usable by a colleague.
        assertThat(status(writer, get("/Templates/GetById/" + response.get("templateId").asInt())))
                .isEqualTo(200);

        assertThat(status(owner, json(post("/Templates/CreateWithFields"), Map.of(
                "name", "Bad visibility " + tag,
                "visibility", "PUBLIC",
                "fields", List.of(Map.of("fieldName", "Amount", "dataType", "Text"))))))
                .isEqualTo(400);
    }

    // ------------------------------------------------- visibility switch

    @Test
    void sharingIsAuditedAndUnsharingHidesTheTemplateFromColleagues() throws Exception {
        assertThat(status(writer, get("/Templates/GetById/" + priv.getTemplateId())))
                .isEqualTo(404);

        assertThat(status(owner, json(put("/Templates/" + priv.getTemplateId() + "/Visibility"),
                Map.of("visibility", "BANK")))).isEqualTo(200);
        assertThat(status(writer, get("/Templates/GetById/" + priv.getTemplateId())))
                .isEqualTo(200);
        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT OldValues, NewValues FROM AuditEvent WHERE Action = ?"
                        + " AND EntityType = 'Template' AND EntityId = ?",
                "TEMPLATE_VISIBILITY_CHANGED", String.valueOf(priv.getTemplateId()));
        assertThat(audit).hasSize(1);
        assertThat(text(audit.get(0).get("OLDVALUES"))).contains("PRIVATE");
        assertThat(text(audit.get(0).get("NEWVALUES"))).contains("BANK");

        // Another admin of the bank may make it private again: it is the
        // owner's alone once more.
        assertThat(status(colleagueAdmin,
                json(put("/Templates/" + priv.getTemplateId() + "/Visibility"),
                        Map.of("visibility", "PRIVATE")))).isEqualTo(200);
        assertThat(status(writer, get("/Templates/GetById/" + priv.getTemplateId())))
                .isEqualTo(404);
        assertThat(status(colleagueAdmin, get("/Templates/GetById/" + priv.getTemplateId())))
                .isEqualTo(404);
        assertThat(status(owner, get("/Templates/GetById/" + priv.getTemplateId())))
                .isEqualTo(200);

        assertThat(status(owner, json(put("/Templates/" + priv.getTemplateId() + "/Visibility"),
                Map.of("visibility", "EVERYONE")))).isEqualTo(400);
    }

    @Test
    void aSharedTemplateInActiveUseByColleaguesCanNeitherBeDeletedNorUnshared()
            throws Exception {
        IngestionJob running = new IngestionJob();
        running.setBankId(bankA.getBankId());
        running.setOwnerUserId(writer.getAppUserId());
        running.setTemplateId(shared.getTemplateId());
        running.setFileName("in-use-" + tag + ".csv");
        running.setStatus(JobStatus.PENDING);
        running.setCreatedDate(LocalDateTime.now());
        ingestionJobs.save(running);

        assertThat(status(colleagueAdmin, delete("/Templates/" + shared.getTemplateId())))
                .isEqualTo(409);
        assertThat(status(owner, json(put("/Templates/" + shared.getTemplateId() + "/Visibility"),
                Map.of("visibility", "PRIVATE")))).isEqualTo(409);
        assertThat(status(writer, get("/Templates/GetById/" + shared.getTemplateId())))
                .isEqualTo(200);

        // Once the colleague's job has finished, unsharing goes through.
        running.setStatus(JobStatus.COMPLETED);
        ingestionJobs.save(running);
        assertThat(status(owner, json(put("/Templates/" + shared.getTemplateId() + "/Visibility"),
                Map.of("visibility", "PRIVATE")))).isEqualTo(200);
    }

    // ----------------------------------------------------------- helpers

    /** H2 hands JSON columns back as bytes. */
    private static String text(Object jsonColumn) {
        return jsonColumn instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(jsonColumn);
    }

    private static boolean readable(AppUser actor, Template t) {
        return actor.getAppUserId() == t.getOwnerUserId()
                || (t.getVisibility() == TemplateVisibility.BANK
                && actor.getBankId() == t.getBankId());
    }

    private static boolean isAdmin(AppUser actor) {
        return actor.getRole() == Role.ADMIN;
    }

    /** ok when the actor may change the template, else 404 (cannot read) / 403 (read only). */
    private static int writeStatus(AppUser actor, Template t, int ok) {
        if (!readable(actor, t)) {
            return 404;
        }
        if (t.getVisibility() == TemplateVisibility.BANK) {
            return isAdmin(actor) ? ok : 403;
        }
        return actor.getAppUserId() == t.getOwnerUserId() ? ok : 404;
    }

    private void expect(List<String> failures, AppUser actor, Template t, String route,
                        MockHttpServletRequestBuilder request, int expected) throws Exception {
        int actual = status(actor, request);
        if (actual != expected) {
            failures.add(label(actor, t) + " " + route + ": expected " + expected + " got "
                    + actual);
        }
    }

    private String label(AppUser actor, Template t) {
        return "[" + actor.getDisplayName().split(" ")[0] + "/" + actor.getRole() + " on "
                + t.getName().split(" ")[0] + " " + t.getVisibility() + "]";
    }

    private int status(AppUser actor, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(actor)))
                .andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
    }

    private List<Integer> listedIds(AppUser actor) throws Exception {
        JsonNode list = MAPPER.readTree(mvc.perform(get("/Templates/GetAll")
                        .header("Authorization", bearer(actor)))
                .andReturn().getResponse().getContentAsString());
        List<Integer> ids = new ArrayList<>();
        list.forEach(node -> ids.add(node.get("templateId").asInt()));
        return ids;
    }

    private JsonNode templateView(AppUser actor, Template t) throws Exception {
        JsonNode list = MAPPER.readTree(mvc.perform(get("/Templates/GetAll")
                        .header("Authorization", bearer(actor)))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode node : list) {
            if (node.get("templateId").asInt() == t.getTemplateId()) {
                return node;
            }
        }
        throw new AssertionError("template " + t.getTemplateId() + " not listed");
    }

    private AppUser user(String prefix, Bank bank, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(prefix + "-" + tag + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName(prefix + " " + tag);
        entity.setBankId(bank.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        return users.save(entity);
    }

    private Template template(String name, AppUser templateOwner, TemplateVisibility visibility) {
        Template entity = new Template(0, name + " " + UUID.randomUUID(), "matrix " + tag);
        entity.setOwnerUserId(templateOwner.getAppUserId());
        entity.setBankId(templateOwner.getBankId());
        entity.setVisibility(visibility);
        Template saved = templates.save(entity);
        fieldOf(saved);
        ruleOf(saved);
        return saved;
    }

    /** The template's "Amount" field, re-created when a write route deleted it. */
    private int fieldOf(Template t) {
        for (TemplateFields existing : fields.listByTemplateId(t.getTemplateId())) {
            if ("Amount".equals(existing.getFieldName())) {
                return existing.getTemplateFieldsId();
            }
        }
        TemplateFields field = new TemplateFields();
        field.setTemplateId(t.getTemplateId());
        field.setBankId(t.getBankId());
        field.setFieldName("Amount");
        field.setTemplateDataTypeId(1);
        fields.add(field);
        return field.getTemplateFieldsId();
    }

    private void ruleOf(Template t) {
        ValidationRule rule = new ValidationRule(t.getTemplateId(), "Amount",
                RuleType.REQUIRED, "{}");
        rule.setBankId(t.getBankId());
        rules.save(rule);
    }

    /** An upload row owned by the actor (the JSON job create checks file ownership first). */
    private String uploadOwnedBy(AppUser actor) {
        UploadedFile file = new UploadedFile();
        file.setStoredName("matrix-" + UUID.randomUUID() + ".csv");
        file.setOriginalName("matrix.csv");
        file.setOwnerUserId(actor.getAppUserId());
        file.setBankId(actor.getBankId());
        file.setSizeBytes(10);
        file.setSha256Hex(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        file.setUploadedAt(LocalDateTime.now());
        return uploads.save(file).getStoredName();
    }

    private static Map<String, Object> websiteSource(Integer templateId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Matrix source " + UUID.randomUUID());
        body.put("type", "WEBSITE");
        body.put("termsAcknowledged", true);
        if (templateId != null) {
            body.put("templateId", templateId);
        }
        body.put("web", Map.of("seedUrls", List.of("https://example.com/"),
                "allowedDomains", List.of("example.com")));
        return body;
    }

    /** A template-less source of the actor's own. */
    private String sourceOf(AppUser actor) throws Exception {
        String body = mvc.perform(json(post("/api/extraction/sources"), websiteSource(null))
                        .header("Authorization", bearer(actor)))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("id").asText();
    }

    private String completedExtractionOf(AppUser actor) {
        ExtractionJob job = new ExtractionJob();
        job.setId(UUID.randomUUID().toString());
        job.setOwnerUserId(actor.getAppUserId());
        job.setBankId(actor.getBankId());
        job.setSourceId("matrix-source-" + tag);
        job.setStatus(ExtractionJobStatus.COMPLETED);
        job.setCreatedDate(Instant.now());
        return extractionJobs.save(job).getId();
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }
}
