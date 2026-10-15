package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.TenantContext;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.entity.TemplateRecord;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocBlueprintSourceJpaRepository;
import com.modocument.doccreation.repo.TemplateRecordJpaRepository;
import com.modocument.doccreation.usecase.TemplateRecordBackfill;
import com.modocument.doccreation.usecase.TemplateRecordIndexer;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws32 (V310/V311) on the REAL schema with row level security ENFORCED: the
 * flow record index and the data sources of PDF templates are visible and
 * writable in their own bank only, the partial unique index allows one
 * PRIMARY source per template, the record search / fill / upgrade journey
 * runs on PostgreSQL (the JPQL and criteria queries included), records of
 * another bank are never visible over HTTP, and the startup backfill indexes
 * jobs left without records.
 */
class FlowRecordsRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 932;
    private static final int BANK_B = 933;
    private static final int OWNER_A = 9320;
    private static final int COLLEAGUE_A = 9321;
    private static final int USER_B = 9330;
    private static final int CUSTOMERS_A = 93200;
    private static final int ACCOUNTS_A = 93201;
    private static final int CUSTOMERS_B = 93300;
    private static final int JOB_CUSTOMERS_A = 93210;
    private static final int JOB_ACCOUNTS_A = 93211;
    private static final int JOB_CUSTOMERS_B = 93310;
    private static final int BLUEPRINT_A = 93220;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TemplateRecordIndexer indexer;
    @Autowired
    private TemplateRecordBackfill backfill;
    @Autowired
    private TemplateRecordJpaRepository records;
    @Autowired
    private DocBlueprintSourceJpaRepository sources;
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
                    + "(932, 'REC_A', 'Records A'), (933, 'REC_B', 'Records B') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, SettingsJson, "
                    + "CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(9320, 'rec-owner-a@example.com', '{noop}x', 'Owner A', '{}', now(), now(), 932, 'READ_WRITE', 0), "
                    + "(9321, 'rec-colleague-a@example.com', '{noop}x', 'Colleague A', '{}', now(), now(), 932, "
                    + "'READ_WRITE', 0), "
                    + "(9330, 'rec-user-b@example.com', '{noop}x', 'User B', '{}', now(), now(), 933, 'READ_WRITE', 0) "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO Template (TemplateId, Name, Description, OwnerUserId, BankId, Visibility) VALUES "
                    + "(93200, 'Customers A', 'seed', 9320, 932, 'PRIVATE'), "
                    + "(93201, 'Accounts A', 'seed', 9321, 932, 'BANK'), "
                    + "(93300, 'Customers B', 'seed', 9330, 933, 'BANK') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId, "
                    + "BankId, PiiKind) VALUES "
                    + "(93230, 93200, 'full_name', 1, 932, 'NAME'), (93231, 93200, 'nic', 1, 932, 'NIC'), "
                    + "(93232, 93201, 'national_id', 1, 932, 'NIC'), "
                    + "(93233, 93201, 'account_number', 1, 932, 'ACCOUNT_NUMBER'), "
                    + "(93330, 93300, 'full_name', 1, 933, 'NAME') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO IngestionJob (IngestionJobId, TemplateId, FileName, CurrentStep, Status, "
                    + "CreatedDate, UpdatedDate, OwnerUserId, BankId, MappedRowsJson, ValidationResultJson) VALUES "
                    + "(93210, 93200, 'c.csv', 'VALIDATED', 'COMPLETED', now(), now(), 9320, 932, "
                    + "'{\"columns\":[\"full_name\",\"nic\"],\"rows\":[{\"full_name\":\"Jane Doe\",\"nic\":\"J1234567\"},"
                    + "{\"full_name\":\"Bad Row\",\"nic\":\"\"}]}', "
                    + "'[{\"row\":2,\"fieldName\":\"nic\",\"rawValue\":\"\",\"errorMessage\":\"required\"}]'), "
                    + "(93211, 93201, 'a.csv', 'EXPORTED', 'COMPLETED', now(), now(), 9321, 932, "
                    + "'{\"columns\":[\"national_id\",\"account_number\"],\"rows\":[{\"national_id\":\"J-123 4567\","
                    + "\"account_number\":\"00445566778899\"}]}', '[]'), "
                    + "(93310, 93300, 'b.csv', 'VALIDATED', 'COMPLETED', now(), now(), 9330, 933, "
                    + "'{\"columns\":[\"full_name\"],\"rows\":[{\"full_name\":\"Jane Doe\"}]}', '[]') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO DocBlueprint (DocBlueprintId, OwnerUserId, Name, FileName, FlowKey, "
                    + "CreatedDate, UpdatedDate, BankId, Kind, CurrentVersion, PageCount, UploadFlow) VALUES "
                    + "(93220, 9320, 'Records A', 'doccreation/b932/blueprint93220/original.pdf', 'DEFAULT', now(), "
                    + "now(), 932, 'PDF_OVERLAY', 1, 1, 'BLANK') ON CONFLICT DO NOTHING");
        }
    }

    @BeforeEach
    void indexAsEachBank() throws Exception {
        execute("DELETE FROM DocBlueprintSource WHERE BankId IN (932, 933)");
        TenantContext.runAs(BANK_A, () -> {
            indexer.reindex(BANK_A, JOB_CUSTOMERS_A);
            indexer.reindex(BANK_A, JOB_ACCOUNTS_A);
        });
        TenantContext.runAs(BANK_B, () -> {
            indexer.reindex(BANK_B, JOB_CUSTOMERS_B);
        });
    }

    @AfterEach
    void resetEngine() {
        engine.reset();
    }

    @Test
    void recordsAndSourcesStayInTheirBank() throws Exception {
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE BankId = 932")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE BankId = 933")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM TemplateRecordKey WHERE BankId = 932 AND KeyValue = 'j1234567'"))
                .as("the NIC and the national id share one normalised key").isEqualTo(2);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        long recordA = count("SELECT min(TemplateRecordId) FROM TemplateRecord WHERE BankId = 932");
        List<TemplateRecord> foreign = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                records.findByBankId(BANK_A)));
        assertThat(foreign).isEmpty();
        Boolean seenByB = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                records.findById(recordA).isPresent()));
        Boolean seenByA = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                records.findById(recordA).isPresent()));
        assertThat(seenByB).isFalse();
        assertThat(seenByA).isTrue();

        // Bank B's deletes naming bank A touch nothing; its forged writes are refused.
        TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                indexer.jobDeleted(BANK_A, JOB_CUSTOMERS_A)));
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE BankId = 932")).isEqualTo(2);
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status -> {
            TemplateRecord forged = new TemplateRecord();
            forged.setBankId(BANK_A);
            forged.setTemplateId(CUSTOMERS_A);
            forged.setIngestionJobId(JOB_CUSTOMERS_A);
            forged.setRowIndex(99);
            forged.setOwnerUserId(OWNER_A);
            forged.setValuesJson("{}");
            forged.setSearchText("forged");
            forged.setCreatedAt(LocalDateTime.now());
            records.saveAndFlush(forged);
        }))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE SearchText = 'forged'")).isZero();

        // Sources: one PRIMARY per template (partial unique index), bank-scoped.
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                sources.saveAndFlush(source(BANK_A, CUSTOMERS_A, DocBlueprintSource.ROLE_PRIMARY))));
        assertThatThrownBy(() -> TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                sources.saveAndFlush(source(BANK_A, ACCOUNTS_A, DocBlueprintSource.ROLE_PRIMARY)))))
                .isInstanceOf(RuntimeException.class);
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status ->
                sources.saveAndFlush(source(BANK_A, ACCOUNTS_A, DocBlueprintSource.ROLE_LINKED))));
        List<DocBlueprintSource> foreignSources = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                sources.findByBankIdAndBlueprintIdOrderBySortOrderAscDocBlueprintSourceIdAsc(BANK_A, BLUEPRINT_A)));
        assertThat(foreignSources).isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                sources.saveAndFlush(source(BANK_A, CUSTOMERS_B, DocBlueprintSource.ROLE_LINKED)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM DocBlueprintSource WHERE BlueprintId = 93220")).isEqualTo(2);
    }

    @Test
    void searchFillAndUpgradeRunOnPostgresAndNeverCrossBanks() throws Exception {
        String ownerToken = token(OWNER_A, BANK_A);
        String userB = token(USER_B, BANK_B);

        JsonNode found = json(mvc.perform(get("/api/doccreation/records").header("Authorization", ownerToken)
                        .param("templateId", String.valueOf(CUSTOMERS_A)).param("q", "JANE"))
                .andExpect(status().isOk()).andReturn());
        assertThat(found.get("total").asLong()).isEqualTo(1);
        long jane = found.get("items").get(0).get("recordId").asLong();
        assertThat(json(mvc.perform(get("/api/doccreation/records").header("Authorization", ownerToken)
                        .param("templateId", String.valueOf(CUSTOMERS_A)).param("q", "ja%e"))
                .andExpect(status().isOk()).andReturn()).get("total").asLong())
                .as("% is a literal character, not a wildcard").isZero();
        assertThat(json(mvc.perform(get("/api/doccreation/records").header("Authorization", ownerToken)
                        .param("templateId", String.valueOf(CUSTOMERS_A)).param("field", "nic").param("q", "j-123"))
                .andExpect(status().isOk()).andReturn()).get("total").asLong())
                .as("a field search matches the normalised value").isEqualTo(1);
        assertThat(json(mvc.perform(get("/api/doccreation/records").header("Authorization", ownerToken)
                        .param("templateId", String.valueOf(ACCOUNTS_A)))
                .andExpect(status().isOk()).andReturn()).get("total").asLong())
                .as("a colleague's job on a BANK-shared template").isEqualTo(1);

        mvc.perform(get("/api/doccreation/records").header("Authorization", userB)
                        .param("templateId", String.valueOf(CUSTOMERS_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/doccreation/records/" + jane).header("Authorization", userB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/doccreation/records").header("Authorization", ownerToken)
                        .param("templateId", String.valueOf(CUSTOMERS_B)))
                .andExpect(status().isNotFound());
        JsonNode ownB = json(mvc.perform(get("/api/doccreation/records").header("Authorization", userB)
                        .param("templateId", String.valueOf(CUSTOMERS_B)).param("q", "jane"))
                .andExpect(status().isOk()).andReturn());
        assertThat(ownB.get("total").asLong()).isEqualTo(1);
        assertThat(ownB.get("items").get(0).get("recordId").asLong()).isNotEqualTo(jane);

        // A template with sources, a document filled from Jane and her linked account.
        engine.proposedRegions = List.of(
                FakePdfTemplateEngine.region("full_name", "Full name", "A", RegionKind.TEXT,
                        new PdfBox(0, 100, 700, 200, 20), 0, null),
                FakePdfTemplateEngine.region("id_number", "NIC", "A", RegionKind.TEXT,
                        new PdfBox(0, 100, 660, 200, 20), 1, null),
                FakePdfTemplateEngine.region("account_number", "Account number", "A", RegionKind.TEXT,
                        new PdfBox(0, 100, 620, 200, 20), 2, null));
        JsonNode draft = json(mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "pg.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0)))
                        .param("flow", "BLANK").header("Authorization", ownerToken))
                .andExpect(status().isCreated()).andReturn());
        int blueprintId = draft.get("id").asInt();
        ObjectNode overlay = JSON.createObjectNode();
        overlay.put("baseVersion", 1);
        overlay.set("regions", draft.get("regions"));
        overlay.set("edits", JSON.createArrayNode());
        overlay.put("confirm", true);
        mvc.perform(withJson(put("/api/doccreation/blueprints/" + blueprintId + "/overlay"), ownerToken,
                overlay.toString())).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/doccreation/blueprints/" + blueprintId + "/sources"), ownerToken,
                "{\"templateId\":" + CUSTOMERS_A + ",\"role\":\"PRIMARY\"}")).andExpect(status().isCreated());
        mvc.perform(withJson(post("/api/doccreation/blueprints/" + blueprintId + "/sources"), ownerToken,
                "{\"templateId\":" + CUSTOMERS_A + ",\"role\":\"PRIMARY\"}")).andExpect(status().isConflict());
        mvc.perform(withJson(post("/api/doccreation/blueprints/" + blueprintId + "/sources"), ownerToken,
                "{\"templateId\":" + ACCOUNTS_A + ",\"role\":\"LINKED\",\"linkFieldPrimary\":\"nic\","
                        + "\"linkFieldThis\":\"national_id\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/doccreation/blueprints/" + blueprintId + "/sources").header("Authorization", userB))
                .andExpect(status().isNotFound());

        int instanceId = json(mvc.perform(withJson(post("/api/doccreation/instances"), ownerToken,
                        "{\"blueprintId\":" + blueprintId + ",\"title\":\"PG fill\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asInt();
        JsonNode preview = json(mvc.perform(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                        .param("recordId", String.valueOf(jane)).header("Authorization", ownerToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.get("mappingSource").asText()).isEqualTo("SAVED");
        assertThat(preview.get("linked").get(0).get("recordId").isNull()).isFalse();
        JsonNode filled = json(mvc.perform(withJson(post("/api/doccreation/instances/" + instanceId
                        + "/fill-from-record"), ownerToken, "{\"recordId\":" + jane
                        + ",\"linkedRecordIds\":{},\"fields\":[\"full_name\",\"id_number\",\"account_number\"],"
                        + "\"overwrite\":\"ALL\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(filled.get("values").get("full_name").asText()).isEqualTo("Jane Doe");
        assertThat(filled.get("values").get("account_number").asText()).isEqualTo("00445566778899");
        assertThat(filled.get("sourceRecords")).hasSize(2);
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/fill-preview")
                        .param("recordId", String.valueOf(jane)).header("Authorization", userB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/upgrade").header("Authorization", ownerToken))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM DocInstance WHERE DocInstanceId = " + instanceId
                + " AND SourceRecordsJson LIKE '%" + jane + "%'")).isEqualTo(1);
        execute("DELETE FROM DocInstance WHERE DocInstanceId = " + instanceId);
        execute("DELETE FROM DocBlueprintSource WHERE BlueprintId = " + blueprintId);
    }

    @Test
    void theBackfillIndexesJobsLeftWithoutRecords() throws Exception {
        execute("DELETE FROM TemplateRecordKey WHERE BankId = 932");
        execute("DELETE FROM TemplateRecord WHERE BankId = 932");
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE BankId = 932")).isZero();

        assertThat(backfill.runOnce()).isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE BankId = 932")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM TemplateRecord WHERE IngestionJobId = 93210 AND RowIndex = 1"))
                .as("only the valid row of the customers job").isEqualTo(1);
        // Idempotent: nothing left to index in bank A.
        long before = count("SELECT max(TemplateRecordId) FROM TemplateRecord WHERE BankId = 932");
        backfill.runOnce();
        assertThat(count("SELECT max(TemplateRecordId) FROM TemplateRecord WHERE BankId = 932")).isEqualTo(before);
    }

    // ------------------------------------------------------------ helpers

    private static DocBlueprintSource source(int bankId, int templateId, String role) {
        DocBlueprintSource source = new DocBlueprintSource();
        source.setBankId(bankId);
        source.setBlueprintId(BLUEPRINT_A);
        source.setTemplateId(templateId);
        source.setRole(role);
        source.setMappingJson("{}");
        source.setUpdatedAt(LocalDateTime.now());
        if (DocBlueprintSource.ROLE_LINKED.equals(role)) {
            source.setLinkFieldPrimary("nic");
            source.setLinkFieldThis("national_id");
        }
        return source;
    }

    private String token(int userId, int bankId) {
        AppUser principal = new AppUser();
        principal.setAppUserId(userId);
        principal.setBankId(bankId);
        principal.setRole(Role.READ_WRITE);
        principal.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(principal);
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String token,
                                                          String body) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
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

    private static void execute(String statement) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute(statement);
        }
    }
}
