package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.application.usecase.ValidatedRows;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.usecase.TemplateRecordIndexer;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.retention.RetentionPurger;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
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
 * Flow records in document creation (docs/doccreation/flow-records-contract.md)
 * over HTTP: the record index (only valid rows, kept in step with remap/edit/
 * re-validation, job deletion, retention and DSR), search and read with the
 * visibility rules (own jobs; every job of a BANK-shared template; never
 * another user's job on a private template, never another bank) and masking
 * per role, the data sources of a template (auto-mapping, 409/400 rules), the
 * fill preview and fill (saved and automatic mapping, linked records with
 * alternatives, field selection, overwrite modes, locked 409) and the upgrade
 * to the current template version.
 */
class FlowRecordsApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private IngestionJobJpaRepository jobs;
    @Autowired
    private TemplateRecordIndexer indexer;
    @Autowired
    private RetentionPurger purger;
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
    private AppUser admin;
    private AppUser stranger;
    private Template customers;
    private Template accounts;
    private IngestionJob ownJob;
    private IngestionJob colleagueJobOnPrivate;
    private IngestionJob olderAccounts;
    private IngestionJob newerAccounts;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        engine.proposedRegions = List.of(
                FakePdfTemplateEngine.region("full_name", "Full name", "Applicant", RegionKind.TEXT,
                        new PdfBox(0, 100, 700, 200, 20), 0, null),
                FakePdfTemplateEngine.region("id_number", "NIC", "Applicant", RegionKind.TEXT,
                        new PdfBox(0, 100, 660, 200, 20), 1, null),
                FakePdfTemplateEngine.region("date_of_birth", "Date of birth", "Applicant", RegionKind.DATE,
                        new PdfBox(0, 100, 620, 200, 20), 2, null),
                FakePdfTemplateEngine.region("account_number", "Account number", "Bank", RegionKind.TEXT,
                        new PdfBox(0, 100, 580, 200, 20), 3, null),
                FakePdfTemplateEngine.region("notes", "Notes", "Bank", RegionKind.MULTILINE,
                        new PdfBox(0, 100, 480, 300, 60), 4, null),
                FakePdfTemplateEngine.region("passport_photo", "Passport photo", "Photos", RegionKind.IMAGE,
                        new PdfBox(0, 400, 300, 120, 150), 5, null));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("FR" + tag.substring(0, 6).toUpperCase(), "Flow records bank"));
        otherBank = banks.save(new Bank("FX" + tag.substring(0, 6).toUpperCase(), "Other bank"));
        owner = user(bank, "fr-owner-" + tag, Role.READ_WRITE);
        colleague = user(bank, "fr-colleague-" + tag, Role.READ_WRITE);
        reader = user(bank, "fr-reader-" + tag, Role.READ_ONLY);
        admin = user(bank, "fr-admin-" + tag, Role.ADMIN);
        stranger = user(otherBank, "fr-stranger-" + tag, Role.READ_WRITE);

        customers = template(owner, "Customers " + tag, TemplateVisibility.PRIVATE);
        field(customers, "full_name", 1, null);
        field(customers, "nic", 1, PiiKind.NIC);
        field(customers, "dob", 3, PiiKind.NONE);
        accounts = template(colleague, "Accounts " + tag, TemplateVisibility.BANK);
        field(accounts, "national_id", 1, PiiKind.NIC);
        field(accounts, "account_number", 1, PiiKind.ACCOUNT_NUMBER);

        ownJob = job(owner, customers, LocalDateTime.now().minusHours(3),
                "{\"columns\":[\"full_name\",\"nic\",\"dob\"],\"rows\":["
                        + "{\"full_name\":\"Jane Doe\",\"nic\":\"J1234567\",\"dob\":\"1990-01-01\"},"
                        + "{\"full_name\":\"Bob Invalid\",\"nic\":\"B7654321\",\"dob\":\"1970-02-02\"},"
                        + "{\"full_name\":\"Zoë Smith\",\"nic\":\"Z9999999\",\"dob\":\"1985-05-05\"}]}",
                "[{\"row\":2,\"fieldName\":\"nic\",\"rawValue\":\"B7654321\",\"errorMessage\":\"bad\"}]");
        colleagueJobOnPrivate = job(colleague, customers, LocalDateTime.now().minusHours(2),
                "{\"columns\":[\"full_name\",\"nic\"],\"rows\":[{\"full_name\":\"Hidden Person\",\"nic\":\"H111\"}]}",
                "[]");
        olderAccounts = job(colleague, accounts, LocalDateTime.now().minusDays(2),
                "{\"columns\":[\"national_id\",\"account_number\"],\"rows\":["
                        + "{\"national_id\":\"J-123 4567\",\"account_number\":\"00445566778899\"}]}", "[]");
        newerAccounts = job(colleague, accounts, LocalDateTime.now().minusDays(1),
                "{\"columns\":[\"national_id\",\"account_number\"],\"rows\":["
                        + "{\"national_id\":\"j1234567\",\"account_number\":\"11112222333344\"}]}", "[]");
        for (IngestionJob job : List.of(ownJob, colleagueJobOnPrivate, olderAccounts, newerAccounts)) {
            indexer.reindex(bank.getBankId(), job.getIngestionJobId());
        }
    }

    @AfterEach
    void cleanUp() {
        engine.reset();
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM TemplateRecordKey WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM TemplateRecord WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintSource WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
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

    private void field(Template template, String name, int dataTypeId, PiiKind kind) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(template.getTemplateId());
        field.setBankId(template.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(dataTypeId);
        field.setPiiKind(kind);
        templateFields.save(field);
    }

    private IngestionJob job(AppUser by, Template template, LocalDateTime updated, String mappedRows,
                             String validation) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(by.getAppUserId());
        job.setBankId(by.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName("fr-" + UUID.randomUUID() + ".csv");
        job.setParsedTableJson(mappedRows);
        job.setMappingJson("{}");
        job.setMappedRowsJson(mappedRows);
        job.setValidationResultJson(validation);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(updated);
        job.setUpdatedDate(updated);
        return jobs.save(job);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, AppUser user, int expected) throws Exception {
        MvcResult result = mvc.perform(as(request, user)).andExpect(status().is(expected)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode search(AppUser user, Template template, String q, int expected) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/doccreation/records")
                .param("templateId", String.valueOf(template.getTemplateId()));
        if (q != null) {
            request.param("q", q);
        }
        return call(request, user, expected);
    }

    private long recordCount(IngestionJob job) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM TemplateRecord WHERE IngestionJobId = ?",
                Long.class, job.getIngestionJobId());
        return count == null ? 0 : count;
    }

    private long recordId(IngestionJob job, int rowIndex) {
        Long id = jdbc.queryForObject("SELECT TemplateRecordId FROM TemplateRecord WHERE IngestionJobId = ? "
                + "AND RowIndex = ?", Long.class, job.getIngestionJobId(), rowIndex);
        return id == null ? -1 : id;
    }

    private List<String> auditValues(String action) {
        return jdbc.queryForList("SELECT NewValues FROM AuditEvent WHERE BankId = ? AND Action = ?",
                String.class, bank.getBankId(), action);
    }

    private int readyBlueprint() throws Exception {
        JsonNode draft = json(mvc.perform(as(multipart("/api/doccreation/blueprints")
                                .file(new MockMultipartFile("file", "form.pdf", "application/pdf",
                                        FakePdfTemplateEngine.blankPdf(1, 0)))
                                .param("flow", "BLANK").param("name", "Loan form"), owner))
                .andExpect(status().isCreated()).andReturn());
        saveOverlay(draft.get("id").asInt(), 1, (ArrayNode) draft.get("regions"));
        return draft.get("id").asInt();
    }

    private JsonNode saveOverlay(int blueprintId, int baseVersion, ArrayNode regions) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", baseVersion);
        body.set("regions", regions);
        body.set("edits", MAPPER.createArrayNode());
        body.put("confirm", true);
        return call(put("/api/doccreation/blueprints/" + blueprintId + "/overlay")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()), owner, 200);
    }

    private int document(int blueprintId) throws Exception {
        return call(post("/api/doccreation/instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Loan\"}"), owner, 201).get("id").asInt();
    }

    private JsonNode addSource(int blueprintId, String body, int expected) throws Exception {
        return call(post("/api/doccreation/blueprints/" + blueprintId + "/sources")
                .contentType(MediaType.APPLICATION_JSON).content(body), owner, expected);
    }

    private JsonNode fill(int instanceId, String body, int expected) throws Exception {
        return call(post("/api/doccreation/instances/" + instanceId + "/fill-from-record")
                .contentType(MediaType.APPLICATION_JSON).content(body), owner, expected);
    }

    // --------------------------------------------------------------- index

    @Test
    void onlyValidRowsAreIndexedWithFoldedSearchTextAndKeys() {
        assertThat(recordCount(ownJob)).isEqualTo(2);
        List<Integer> rows = jdbc.queryForList("SELECT RowIndex FROM TemplateRecord WHERE IngestionJobId = ? "
                + "ORDER BY RowIndex", Integer.class, ownJob.getIngestionJobId());
        assertThat(rows).containsExactly(1, 3);
        String searchText = jdbc.queryForObject("SELECT SearchText FROM TemplateRecord WHERE IngestionJobId = ? "
                + "AND RowIndex = 3", String.class, ownJob.getIngestionJobId());
        assertThat(searchText).isEqualTo("zoe smith z9999999 1985-05-05");
        String key = jdbc.queryForObject("SELECT KeyValue FROM TemplateRecordKey WHERE TemplateRecordId = ? "
                + "AND FieldName = 'national_id'", String.class, recordId(olderAccounts, 1));
        assertThat(key).isEqualTo("j1234567");
        // Re-indexing is idempotent: delete, then insert again.
        indexer.reindex(bank.getBankId(), ownJob.getIngestionJobId());
        assertThat(recordCount(ownJob)).isEqualTo(2);
    }

    @Test
    void rowEditsRevalidationDeletionAndRetentionKeepTheIndexInStep() throws Exception {
        JsonNode rows = MAPPER.readTree(ownJob.getMappedRowsJson()).get("rows");
        call(put("/api/ingestion/jobs/" + ownJob.getIngestionJobId() + "/rows")
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":" + rows + "}"), owner, 200);
        assertThat(recordCount(ownJob)).as("an edited job is MAPPED again: no records").isZero();

        call(post("/api/ingestion/jobs/" + ownJob.getIngestionJobId() + "/revalidate"), owner, 200);
        IngestionJob revalidated = jobs.findById(ownJob.getIngestionJobId()).orElseThrow();
        assertThat(revalidated.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        int valid = ValidatedRows.of(revalidated).valid().size();
        assertThat(valid).isPositive();
        assertThat(recordCount(ownJob)).as("re-validation re-indexes the valid rows").isEqualTo(valid);

        call(post("/api/ingestion/jobs/" + ownJob.getIngestionJobId() + "/remap"), owner, 200);
        assertThat(recordCount(ownJob)).as("a remap resets the job to MAPPED").isZero();

        call(delete("/api/ingestion/jobs/" + olderAccounts.getIngestionJobId()), colleague, 204);
        assertThat(recordCount(olderAccounts)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TemplateRecordKey WHERE BankId = ? AND KeyValue = ?",
                Long.class, bank.getBankId(), "00445566778899")).isZero();

        purger.purgeBank(bank.getBankId(), LocalDateTime.now().plusDays(1), Instant.now().plusSeconds(86400), 30);
        assertThat(recordCount(newerAccounts)).as("the retention purge takes the records along").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TemplateRecord WHERE BankId = ?", Long.class,
                bank.getBankId())).isZero();
    }

    @Test
    void dataSubjectRequestsFindExportAndEraseRecords() throws Exception {
        JsonNode hits = call(post("/api/admin/dsr/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"Jane Doe\"}"), admin, 200);
        JsonNode store = null;
        for (JsonNode s : hits.get("stores")) {
            if ("templateRecords".equals(s.get("store").asText())) {
                store = s;
            }
        }
        assertThat(store).isNotNull();
        assertThat(store.get("matches").asInt()).isEqualTo(1);

        JsonNode erased = call(post("/api/admin/dsr/erase").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"Jane Doe\",\"confirm\":true}"), admin, 200);
        assertThat(erased.get("counts").get("templateRecords").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TemplateRecord WHERE BankId = ? "
                + "AND SearchText LIKE '%jane%'", Long.class, bank.getBankId())).isZero();
        assertThat(recordCount(ownJob)).as("other subjects' records stay").isEqualTo(1);
    }

    // -------------------------------------------------------------- search

    @Test
    void searchMatchesEveryWordAccentInsensitivelyNewestFirstAndAudits() throws Exception {
        JsonNode jane = search(owner, customers, "JANE doe", 200);
        assertThat(jane.get("total").asLong()).isEqualTo(1);
        JsonNode item = jane.get("items").get(0);
        assertThat(item.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(item.get("jobId").asInt()).isEqualTo(ownJob.getIngestionJobId());
        assertThat(item.get("rowIndex").asInt()).isEqualTo(1);
        assertThat(item.get("values").get("full_name").asText()).isEqualTo("Jane Doe");
        assertThat(item.get("values").get("nic").asText()).as("READ_WRITE works unmasked").isEqualTo("J1234567");

        assertThat(search(owner, customers, "zoe", 200).get("total").asLong()).isEqualTo(1);
        assertThat(search(owner, customers, "bob", 200).get("total").asLong())
                .as("an invalid row is not a record").isZero();
        assertThat(search(owner, customers, "jane zoe", 200).get("total").asLong()).isZero();
        JsonNode all = search(owner, customers, null, 200);
        assertThat(all.get("total").asLong()).isEqualTo(2);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);

        JsonNode byField = call(get("/api/doccreation/records").param("templateId",
                        String.valueOf(customers.getTemplateId())).param("field", "nic").param("q", "j-1234"),
                owner, 200);
        assertThat(byField.get("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/doccreation/records").param("templateId", String.valueOf(customers.getTemplateId()))
                .param("field", "full_name").param("q", "j1234567"), owner, 200).get("total").asLong()).isZero();
        call(get("/api/doccreation/records").param("templateId", String.valueOf(customers.getTemplateId()))
                .param("field", "nope").param("q", "x"), owner, 400);
        call(get("/api/doccreation/records"), owner, 400);
        JsonNode capped = call(get("/api/doccreation/records").param("templateId",
                String.valueOf(accounts.getTemplateId())).param("size", "500"), owner, 200);
        assertThat(capped.get("size").asInt()).isEqualTo(100);
        assertThat(capped.get("items").get(0).get("jobId").asInt())
                .as("newest first").isEqualTo(newerAccounts.getIngestionJobId());

        List<String> audits = auditValues("TEMPLATE_RECORDS_SEARCHED");
        assertThat(audits).isNotEmpty();
        assertThat(String.join("\n", audits)).contains("queryHash", "count")
                .doesNotContainIgnoringCase("jane").doesNotContain("J1234567");
    }

    @Test
    void visibilityIsOwnJobsOrBankSharedTemplatesWithinTheBank() throws Exception {
        assertThat(search(owner, customers, "hidden", 200).get("total").asLong())
                .as("a colleague's job on my PRIVATE template is not visible").isZero();
        search(colleague, customers, null, 404);
        JsonNode shared = search(owner, accounts, null, 200);
        assertThat(shared.get("total").asLong()).as("BANK-shared template: every job").isEqualTo(2);
        search(stranger, accounts, null, 404);

        long janeRecord = recordId(ownJob, 1);
        long hidden = recordId(colleagueJobOnPrivate, 1);
        call(get("/api/doccreation/records/" + hidden), owner, 404);
        call(get("/api/doccreation/records/" + janeRecord), colleague, 404);
        call(get("/api/doccreation/records/" + janeRecord), stranger, 404);
        JsonNode one = call(get("/api/doccreation/records/" + janeRecord), owner, 200);
        assertThat(one.get("recordId").asLong()).isEqualTo(janeRecord);
        assertThat(one.get("values").get("full_name").asText()).isEqualTo("Jane Doe");
        assertThat(auditValues("SENSITIVE_DATA_VIEWED")).anySatisfy(v -> assertThat(v).contains("template-record"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND Action = ? "
                + "AND EntityType = 'TemplateRecord'", Long.class, bank.getBankId(), "SENSITIVE_DATA_VIEWED"))
                .isEqualTo(1);
    }

    @Test
    void aReadOnlyCallerSeesMaskedValuesAndCannotFill() throws Exception {
        JsonNode masked = search(reader, accounts, null, 200);
        JsonNode values = masked.get("items").get(0).get("values");
        assertThat(values.get("national_id").asText()).isEqualTo("********");
        assertThat(values.get("account_number").asText()).isEqualTo("****3344");
        JsonNode one = call(get("/api/doccreation/records/" + recordId(newerAccounts, 1)), reader, 200);
        assertThat(one.get("values").get("account_number").asText()).isEqualTo("****3344");

        int blueprintId = readyBlueprint();
        int instanceId = document(blueprintId);
        call(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                .param("recordId", String.valueOf(recordId(newerAccounts, 1))), reader, 403);
    }

    // -------------------------------------------------------------- sources

    @Test
    void sourcesAreAutoMappedAndCheckedOnEveryChange() throws Exception {
        int blueprintId = readyBlueprint();
        int customersId = customers.getTemplateId();
        int accountsId = accounts.getTemplateId();

        JsonNode primary = addSource(blueprintId, "{\"templateId\":" + customersId + ",\"role\":\"PRIMARY\"}", 201);
        assertThat(primary.get("role").asText()).isEqualTo("PRIMARY");
        assertThat(primary.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(primary.get("mapping").get("full_name").asText()).isEqualTo("full_name");
        assertThat(primary.get("mapping").get("id_number").asText()).as("label NIC = field nic").isEqualTo("nic");
        assertThat(primary.get("mapping").get("date_of_birth").asText()).as("alias dob").isEqualTo("dob");
        assertThat(primary.get("mapping").has("passport_photo")).isFalse();
        assertThat(primary.get("fields").get(2).get("name").asText()).isEqualTo("dob");
        assertThat(primary.get("fields").get(2).get("dataType").asText()).isEqualTo("Date");

        addSource(blueprintId, "{\"templateId\":" + accountsId + ",\"role\":\"PRIMARY\"}", 409);
        addSource(blueprintId, "{\"templateId\":" + customersId + ",\"role\":\"LINKED\","
                + "\"linkFieldPrimary\":\"nic\",\"linkFieldThis\":\"nic\"}", 409);
        addSource(blueprintId, "{\"templateId\":" + accountsId + ",\"role\":\"LINKED\"}", 400);
        addSource(blueprintId, "{\"templateId\":" + accountsId + ",\"role\":\"LINKED\","
                + "\"linkFieldPrimary\":\"nope\",\"linkFieldThis\":\"national_id\"}", 400);
        addSource(blueprintId, "{\"templateId\":" + accountsId + ",\"role\":\"OTHER\"}", 400);
        JsonNode linked = addSource(blueprintId, "{\"templateId\":" + accountsId + ",\"role\":\"LINKED\","
                + "\"linkFieldPrimary\":\"nic\",\"linkFieldThis\":\"national_id\"}", 201);
        assertThat(linked.get("linkFieldThis").asText()).isEqualTo("national_id");
        assertThat(linked.get("mapping").get("account_number").asText()).isEqualTo("account_number");
        long linkedId = linked.get("id").asLong();
        long primaryId = primary.get("id").asLong();

        String base = "/api/doccreation/blueprints/" + blueprintId + "/sources/";
        call(put(base + primaryId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapping\":{\"passport_photo\":\"full_name\"}}"), owner, 400);
        call(put(base + primaryId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapping\":{\"nope\":\"full_name\"}}"), owner, 400);
        call(put(base + primaryId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapping\":{\"full_name\":\"nope\"}}"), owner, 400);
        JsonNode saved = call(put(base + primaryId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapping\":{\"full_name\":\"full_name\",\"notes\":\"full_name\",\"id_number\":null}}"),
                owner, 200);
        assertThat(saved.get("mapping").get("notes").asText()).as("one field, two regions").isEqualTo("full_name");
        assertThat(saved.get("mapping").has("id_number")).isFalse();
        assertThat(saved.get("suggestions")).anySatisfy(s -> {
            assertThat(s.get("regionKey").asText()).isEqualTo("id_number");
            assertThat(s.get("fieldName").asText()).isEqualTo("nic");
            assertThat(s.get("sameName").asBoolean()).isTrue();
        });
        call(put(base + linkedId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapping\":{},\"linkFieldThis\":\"nope\"}"), owner, 400);

        JsonNode list = call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), owner, 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("role").asText()).isEqualTo("PRIMARY");
        call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), colleague, 404);
        call(get("/api/doccreation/blueprints/" + blueprintId + "/sources"), reader, 404);

        call(delete(base + primaryId), owner, 409);
        call(delete(base + linkedId), owner, 204);
        call(delete(base + primaryId), owner, 204);
        call(delete(base + primaryId), owner, 404);
        assertThat(auditValues("DOC_BLUEPRINT_MAPPING_SAVED")).hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(v -> assertThat(v).doesNotContain("Jane"));
    }

    // ----------------------------------------------------------------- fill

    @Test
    void fillUsesTheSavedPrimaryAndTheNewestLinkedRecordWithAlternatives() throws Exception {
        int blueprintId = readyBlueprint();
        addSource(blueprintId, "{\"templateId\":" + customers.getTemplateId() + ",\"role\":\"PRIMARY\"}", 201);
        addSource(blueprintId, "{\"templateId\":" + accounts.getTemplateId() + ",\"role\":\"LINKED\","
                + "\"linkFieldPrimary\":\"nic\",\"linkFieldThis\":\"national_id\"}", 201);
        int instanceId = document(blueprintId);
        call(put("/api/doccreation/instances/" + instanceId + "/values").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"full_name\":\"Typed by hand\"}}"), owner, 200);
        long jane = recordId(ownJob, 1);
        long newer = recordId(newerAccounts, 1);
        long older = recordId(olderAccounts, 1);

        JsonNode preview = call(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                .param("recordId", String.valueOf(jane)), owner, 200);
        assertThat(preview.get("mappingSource").asText()).isEqualTo("SAVED");
        assertThat(preview.get("recordId").asLong()).isEqualTo(jane);
        JsonNode link = preview.get("linked").get(0);
        assertThat(link.get("templateId").asInt()).isEqualTo(accounts.getTemplateId());
        assertThat(link.get("templateName").asText()).isEqualTo(accounts.getName());
        assertThat(link.get("matchedBy").asText()).isEqualTo("national_id");
        assertThat(link.get("recordId").asLong()).as("newest wins").isEqualTo(newer);
        assertThat(link.get("alternatives")).extracting(JsonNode::asLong).containsExactly(older);
        Map<String, JsonNode> fields = byRegion(preview.get("fields"));
        assertThat(fields).containsOnlyKeys("full_name", "id_number", "date_of_birth", "account_number");
        assertThat(fields.get("full_name").get("value").asText()).isEqualTo("Jane Doe");
        assertThat(fields.get("full_name").get("currentValue").asText()).isEqualTo("Typed by hand");
        assertThat(fields.get("full_name").get("willOverwrite").asBoolean()).isTrue();
        assertThat(fields.get("full_name").get("sameName").asBoolean()).isTrue();
        assertThat(fields.get("account_number").get("value").asText()).isEqualTo("11112222333344");
        assertThat(fields.get("account_number").get("sourceTemplateId").asInt()).isEqualTo(accounts.getTemplateId());
        assertThat(fields.get("date_of_birth").get("sourceField").asText()).isEqualTo("dob");

        // Field selection + EMPTY_ONLY + an alternative linked record.
        JsonNode filled = fill(instanceId, "{\"recordId\":" + jane + ",\"linkedRecordIds\":{\""
                + accounts.getTemplateId() + "\":" + older + "},\"fields\":[\"full_name\",\"account_number\"],"
                + "\"overwrite\":\"EMPTY_ONLY\"}", 200);
        assertThat(filled.get("values").get("full_name").asText()).isEqualTo("Typed by hand");
        assertThat(filled.get("values").get("account_number").asText()).isEqualTo("00445566778899");

        // ws33: the document shows which flow fields its data sources map onto each field.
        JsonNode nameChecks = filled.get("fieldChecks").get("full_name").get("mappedFrom").get(0);
        assertThat(nameChecks.get("templateName").asText()).isEqualTo(customers.getName());
        assertThat(nameChecks.get("fieldName").asText()).isEqualTo("full_name");
        assertThat(nameChecks.get("role").asText()).isEqualTo("PRIMARY");
        JsonNode accountChecks = filled.get("fieldChecks").get("account_number").get("mappedFrom").get(0);
        assertThat(accountChecks.get("templateId").asInt()).isEqualTo(accounts.getTemplateId());
        assertThat(accountChecks.get("role").asText()).isEqualTo("LINKED");
        assertThat(filled.get("values").has("id_number")).as("not selected").isFalse();
        assertThat(filled.get("sourceRecords")).extracting(r -> r.get("recordId").asLong())
                .containsExactlyInAnyOrder(jane, older);
        assertThat(filled.get("sourceRecords")).anySatisfy(r -> {
            assertThat(r.get("role").asText()).isEqualTo("LINKED");
            assertThat(r.get("templateName").asText()).isEqualTo(accounts.getName());
        });

        // ALL overwrites; fields default to every previewed one.
        JsonNode all = fill(instanceId, "{\"recordId\":" + jane + ",\"linkedRecordIds\":{},\"overwrite\":\"ALL\"}",
                200);
        assertThat(all.get("values").get("full_name").asText()).isEqualTo("Jane Doe");
        assertThat(all.get("values").get("id_number").asText()).isEqualTo("J1234567");
        assertThat(all.get("values").get("account_number").asText()).isEqualTo("11112222333344");
        assertThat(all.get("sourceRecords")).hasSize(3);

        fill(instanceId, "{\"recordId\":" + jane + ",\"linkedRecordIds\":{\"" + accounts.getTemplateId()
                + "\":" + recordId(ownJob, 3) + "},\"overwrite\":\"ALL\"}", 400);
        fill(instanceId, "{\"recordId\":" + jane + ",\"fields\":[\"nope\"],\"overwrite\":\"ALL\"}", 400);
        fill(instanceId, "{\"recordId\":" + jane + ",\"overwrite\":\"SOMETIMES\"}", 400);
        fill(instanceId, "{\"recordId\":" + recordId(colleagueJobOnPrivate, 1) + ",\"overwrite\":\"ALL\"}", 404);

        List<String> audits = auditValues("DOC_FILLED_FROM_RECORD");
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0)).contains("regionKeys", "recordIds", String.valueOf(jane))
                .doesNotContain("Jane Doe").doesNotContain("00445566778899");
    }

    @Test
    void withoutSavedSourcesTheMappingIsAutomaticAndTheLinkedRecordCanBeTheAnchor() throws Exception {
        int blueprintId = readyBlueprint();
        int instanceId = document(blueprintId);
        long zoe = recordId(ownJob, 3);
        JsonNode preview = call(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                .param("recordId", String.valueOf(zoe)), owner, 200);
        assertThat(preview.get("mappingSource").asText()).isEqualTo("AUTO");
        assertThat(preview.get("linked")).isEmpty();
        Map<String, JsonNode> fields = byRegion(preview.get("fields"));
        assertThat(fields.get("full_name").get("value").asText()).isEqualTo("Zoë Smith");
        assertThat(fields.get("date_of_birth").get("value").asText()).isEqualTo("1985-05-05");
        assertThat(fields).doesNotContainKey("account_number");

        // A record of a LINKED source finds its primary through the key in reverse.
        addSource(blueprintId, "{\"templateId\":" + customers.getTemplateId() + ",\"role\":\"PRIMARY\"}", 201);
        addSource(blueprintId, "{\"templateId\":" + accounts.getTemplateId() + ",\"role\":\"LINKED\","
                + "\"linkFieldPrimary\":\"nic\",\"linkFieldThis\":\"national_id\"}", 201);
        JsonNode fromLinked = call(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                .param("recordId", String.valueOf(recordId(olderAccounts, 1))), owner, 200);
        assertThat(fromLinked.get("mappingSource").asText()).isEqualTo("SAVED");
        assertThat(fromLinked.get("linked").get(0).get("recordId").asLong()).isEqualTo(recordId(ownJob, 1));
        assertThat(fromLinked.get("linked").get(0).get("matchedBy").asText()).isEqualTo("nic");
        Map<String, JsonNode> both = byRegion(fromLinked.get("fields"));
        assertThat(both.get("account_number").get("value").asText()).isEqualTo("00445566778899");
        assertThat(both.get("full_name").get("value").asText()).isEqualTo("Jane Doe");
    }

    @Test
    void aSubmittedDocumentIsLockedForFillAndUpgrade() throws Exception {
        int blueprintId = readyBlueprint();
        int instanceId = document(blueprintId);
        jdbc.update("UPDATE DocInstance SET Status = 'SUBMITTED' WHERE DocInstanceId = ?", instanceId);
        fill(instanceId, "{\"recordId\":" + recordId(ownJob, 1) + ",\"overwrite\":\"ALL\"}", 409);
        call(post("/api/doccreation/instances/" + instanceId + "/upgrade"), owner, 409);
        jdbc.update("UPDATE DocInstance SET Status = 'RELEASED' WHERE DocInstanceId = ?", instanceId);
        call(post("/api/doccreation/instances/" + instanceId + "/upgrade"), owner, 409);
    }

    // -------------------------------------------------------------- upgrade

    @Test
    void upgradeKeepsValuesByKeyAndReportsTheDroppedOnes() throws Exception {
        int blueprintId = readyBlueprint();
        int instanceId = document(blueprintId);
        call(put("/api/doccreation/instances/" + instanceId + "/values").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"full_name\":\"Jane\",\"notes\":\"to drop\"}}"), owner, 200);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        mvc.perform(as(multipart("/api/doccreation/instances/" + instanceId + "/images")
                        .file(new MockMultipartFile("files", "passport.png", "image/png", png.toByteArray())), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigned[0].regionKey").value("passport_photo"));

        JsonNode before = call(get("/api/doccreation/instances/" + instanceId), owner, 200);
        assertThat(before.get("canEditLayout").asBoolean()).isTrue();
        assertThat(before.get("blueprintEdits").isArray()).isTrue();
        assertThat(before.get("sourceRecords")).isEmpty();
        assertThat(before.has("droppedKeys")).isFalse();
        JsonNode same = call(post("/api/doccreation/instances/" + instanceId + "/upgrade"), owner, 200);
        assertThat(same.get("droppedKeys")).isEmpty();

        // Version 2 without "notes" and the photo box (as the builder saves a layout).
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode region : before.get("regions")) {
            String key = region.get("key").asText();
            if (!key.equals("notes") && !key.equals("passport_photo")) {
                kept.add(region);
            }
        }
        ObjectNode moved = (ObjectNode) kept.get(0);
        moved.put("fy", moved.get("fy").asDouble() + 0.01);
        assertThat(saveOverlay(blueprintId, 1, kept).get("version").asInt()).isEqualTo(2);

        JsonNode upgraded = call(post("/api/doccreation/instances/" + instanceId + "/upgrade"), owner, 200);
        assertThat(upgraded.get("blueprintVersion").asInt()).isEqualTo(2);
        assertThat(upgraded.get("droppedKeys")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("notes", "passport_photo");
        assertThat(upgraded.get("values").get("full_name").asText()).isEqualTo("Jane");
        assertThat(upgraded.get("values").has("notes")).isFalse();
        assertThat(upgraded.get("images").get(0).get("regionKey").isNull()).as("back to the tray").isTrue();
        call(post("/api/doccreation/instances/" + instanceId + "/upgrade"), colleague, 404);
    }

    private static Map<String, JsonNode> byRegion(JsonNode fields) {
        Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
        for (JsonNode field : fields) {
            byKey.put(field.get("regionKey").asText(), field);
        }
        return byKey;
    }
}
