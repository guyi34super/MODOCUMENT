package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.usecase.IntegrationApiUseCase;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintJpaRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionJpaRepository;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetJpaRepository;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.integration.ApiClientKind;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.ImportDetailsJpaRepository;
import com.modocument.infrastructure.persistence.ImportHistoryJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.PendingChangeJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws28 read paths, proven with Hibernate {@link Statistics}: each list costs
 * the SAME number of SQL statements whether it shows one row or many (no
 * N+1), and the associations that went LAZY still serialize (open-in-view is
 * off). Every scenario runs in its own fresh bank so the measured rows are
 * exactly the ones seeded here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadPathQueryCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JwtService jwtService;
    @Autowired private BankJpaRepository banks;
    @Autowired private AppUserJpaRepository users;
    @Autowired private TemplateJpaRepository templates;
    @Autowired private TemplateFieldsJpaRepository fields;
    @Autowired private IngestionJobJpaRepository jobs;
    @Autowired private ImportHistoryJpaRepository imports;
    @Autowired private ImportDetailsJpaRepository importDetails;
    @Autowired private PendingChangeJpaRepository changes;
    @Autowired private DocBlueprintJpaRepository blueprints;
    @Autowired private DocBlueprintSectionJpaRepository sections;
    @Autowired private DocInstanceJpaRepository instances;
    @Autowired private DocContentBlockJpaRepository blocks;
    @Autowired private DocImageAssetJpaRepository assets;
    @Autowired private IntegrationApiUseCase integrationApi;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    // ---- measurement -------------------------------------------------------------

    /** SQL statements Hibernate prepared while {@code work} ran. */
    private long statements(String label, Callable<?> work) throws Exception {
        statistics.clear();
        work.call();
        long count = statistics.getPrepareStatementCount();
        System.out.printf("ws28 statements %-40s %d%n", label, count);
        return count;
    }

    private JsonNode getJson(String url, AppUser as) throws Exception {
        return JSON.readTree(mvc.perform(withAuth(get(url), as))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, AppUser as) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(as));
    }

    // ---- fixtures ----------------------------------------------------------------

    private Bank bank() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return banks.save(new Bank("Q" + tag, "Query bank " + tag));
    }

    private AppUser user(Bank bank, Role role) {
        AppUser user = new AppUser();
        user.setEmail("ws28-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("ws28 " + role.name().toLowerCase());
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setSettingsJson("{}");
        return users.save(user);
    }

    private Template template(AppUser owner, boolean shared, String... fieldNames) {
        Template template = new Template();
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(owner.getBankId());
        template.setName("ws28-" + UUID.randomUUID());
        template.setDescription("d");
        template.setVisibility(shared ? TemplateVisibility.BANK : TemplateVisibility.PRIVATE);
        template = templates.save(template);
        for (String name : fieldNames) {
            TemplateFields field = new TemplateFields();
            field.setTemplateId(template.getTemplateId());
            field.setBankId(owner.getBankId());
            field.setFieldName(name);
            field.setTemplateDataTypeId(1);
            fields.save(field);
        }
        return template;
    }

    private IngestionJob job(AppUser owner, Template template, String mappingJson, Integer errors) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(owner.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName("file-" + UUID.randomUUID() + ".csv");
        job.setStatus(JobStatus.COMPLETED);
        job.setCurrentStep(mappingJson == null ? JobStep.PARSED : JobStep.MAPPED);
        job.setParsedTableJson("{\"columns\":[\"A\"],\"rows\":[{\"A\":\"1\"}]}");
        if (mappingJson != null) {
            job.setMappingJson(mappingJson);
            job.setMappedRowsJson("{\"columns\":[],\"rows\":[]}");
        }
        if (errors != null) {
            job.setValidationResultJson("[]");
            job.setValidationErrorCount(errors);
            job.setCurrentStep(JobStep.VALIDATED);
        }
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        return jobs.save(job);
    }

    private ImportHistory importOf(AppUser owner, Template template, int details) {
        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(owner.getAppUserId());
        history.setBankId(owner.getBankId());
        history.setTemplateId(template.getTemplateId());
        history.setFileName("import-" + UUID.randomUUID() + ".csv");
        history.setImportDate(LocalDateTime.now());
        history = imports.save(history);
        for (int i = 0; i < details; i++) {
            ImportDetails detail = new ImportDetails();
            detail.setImportHistoryId(history.getImportHistoryId());
            detail.setBankId(owner.getBankId());
            detail.setProcessStepId(1 + i % 3);
            detail.setRawJson("{\"step\":" + i + "}");
            importDetails.save(detail);
        }
        return history;
    }

    // ---- ingestion list summary ----------------------------------------------------------

    @Test
    void theJobSummaryCostsTheSameStatementsForTwoJobsAsForTwelve() throws Exception {
        Bank bank = bank();
        AppUser few = user(bank, Role.READ_WRITE);
        Template one = template(few, false, "Name", "City");
        job(few, one, null, null);
        job(few, one, null, null);

        AppUser many = user(bank, Role.READ_WRITE);
        List<Template> four = List.of(template(many, false, "Name", "City", "Age"),
                template(many, false, "X"), template(many, false, "Y"), template(many, false, "Z"));
        IngestionJob mapped = null;
        IngestionJob validated = null;
        for (int i = 0; i < 12; i++) {
            Template template = four.get(i % 4);
            if (i == 4) {
                mapped = job(many, template, "{\"name\":\"Name\",\"town\":\"City\",\"x\":null}", null);
            } else if (i == 8) {
                validated = job(many, template, "{\"name\":\"Name\"}", 3);
            } else {
                job(many, template, null, null);
            }
        }

        String url = "/api/ingestion/jobs?view=summary&page=0&size=50";
        long forTwo = statements("summary 2 jobs", () -> getJson(url, few));
        long forTwelve = statements("summary 12 jobs", () -> getJson(url, many));
        assertThat(forTwelve).as("summary statements: 2 jobs %d, 12 jobs %d", forTwo, forTwelve)
                .isEqualTo(forTwo);

        JsonNode page = getJson(url, many);
        assertThat(page.get("total").asLong()).isEqualTo(12);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(page.get("items")).hasSize(12);
        JsonNode newest = page.get("items").get(0);
        assertThat(newest.has("parsedTable")).as("no checkpoint JSON in the summary").isFalse();
        assertThat(newest.has("mappedRows")).isFalse();
        for (String field : List.of("ingestionJobId", "fileName", "templateId", "templateName", "status",
                "currentStep", "parsedBy", "parseFallbackReason", "parseConfidence", "parseLowConfidence",
                "validationErrorCount", "unmappedFieldCount", "createdDate", "updatedDate")) {
            assertThat(newest.has(field)).as(field).isTrue();
        }
        Map<Integer, JsonNode> byId = new java.util.HashMap<>();
        page.get("items").forEach(item -> byId.put(item.get("ingestionJobId").asInt(), item));
        // Mapped Name + City of {Name, City, Age}: one field left.
        assertThat(byId.get(mapped.getIngestionJobId()).get("unmappedFieldCount").asInt()).isEqualTo(1);
        assertThat(byId.get(mapped.getIngestionJobId()).get("templateName").asText())
                .isEqualTo(four.get(0).getName());
        assertThat(byId.get(validated.getIngestionJobId()).get("validationErrorCount").asInt()).isEqualTo(3);
        // Not mapped yet: every template field is unmapped; never validated: null.
        IngestionJob unmapped = jobs.findByOwnerUserId(many.getAppUserId()).stream()
                .filter(j -> j.getTemplateId() == four.get(0).getTemplateId() && j.getMappingJson() == null)
                .findFirst().orElseThrow();
        assertThat(byId.get(unmapped.getIngestionJobId()).get("unmappedFieldCount").asInt()).isEqualTo(3);
        assertThat(byId.get(unmapped.getIngestionJobId()).get("validationErrorCount").isNull()).isTrue();

        JsonNode second = getJson("/api/ingestion/jobs?view=summary&page=1&size=5", many);
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.get("total").asLong()).isEqualTo(12);
        assertThat(getJson("/api/ingestion/jobs?view=summary&page=0&size=5000", many).get("size").asInt())
                .isEqualTo(200);
    }

    @Test
    void theLegacyUnpagedListIsCappedAtTheTwoHundredNewestJobs() throws Exception {
        Bank bank = bank();
        AppUser owner = user(bank, Role.READ_WRITE);
        Template template = template(owner, false, "Name");
        List<IngestionJob> all = new ArrayList<>();
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 205; i++) {
            IngestionJob job = new IngestionJob();
            job.setOwnerUserId(owner.getAppUserId());
            job.setBankId(bank.getBankId());
            job.setTemplateId(template.getTemplateId());
            job.setFileName("cap-" + i + ".csv");
            job.setCreatedDate(start.plusSeconds(i));
            job.setUpdatedDate(start.plusSeconds(i));
            all.add(job);
        }
        all = jobs.saveAll(all);

        JsonNode list = getJson("/api/ingestion/jobs", owner);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(200);
        Set<Integer> ids = new java.util.HashSet<>();
        list.forEach(job -> ids.add(job.get("ingestionJobId").asInt()));
        assertThat(ids).contains(all.get(204).getIngestionJobId())
                .doesNotContain(all.get(0).getIngestionJobId(), all.get(4).getIngestionJobId());
        // The old order (oldest first) inside the cap.
        assertThat(list.get(0).get("ingestionJobId").asInt()).isEqualTo(all.get(5).getIngestionJobId());
        mvc.perform(withAuth(get("/api/ingestion/jobs?view=bogus"), owner)).andExpect(status().isBadRequest());
    }

    // ---- dashboard -----------------------------------------------------------------

    @Test
    void theDashboardSummaryCostsTheSameStatementsWhateverTheHistory() throws Exception {
        Bank bank = bank();
        AppUser small = user(bank, Role.READ_WRITE);
        Template t1 = template(small, false, "Name");
        job(small, t1, null, null);
        importOf(small, t1, 1);

        AppUser large = user(bank, Role.READ_WRITE);
        Template shared = template(large, true, "Name", "City");
        template(large, false, "Other");
        for (int i = 0; i < 11; i++) {
            IngestionJob job = job(large, shared, null, null);
            if (i % 3 == 0) {
                job.setStatus(JobStatus.FAILED);
                jobs.save(job);
            }
        }
        for (int i = 0; i < 7; i++) {
            importOf(large, shared, 2);
        }

        long forSmall = statements("dashboard small", () -> getJson("/api/dashboard/summary", small));
        long forLarge = statements("dashboard large", () -> getJson("/api/dashboard/summary", large));
        assertThat(forLarge).as("dashboard statements: small %d, large %d", forSmall, forLarge)
                .isEqualTo(forSmall);

        JsonNode summary = getJson("/api/dashboard/summary", large);
        // Own two + the small user's... no: small's template is private, so 2.
        assertThat(summary.get("templates").asLong()).isEqualTo(2);
        assertThat(summary.get("ingestion").get("total").asLong()).isEqualTo(11);
        assertThat(summary.get("ingestion").get("byStatus").get("FAILED").asLong()).isEqualTo(4);
        assertThat(summary.get("ingestion").get("byStatus").get("COMPLETED").asLong()).isEqualTo(7);
        assertThat(summary.get("ingestion").get("recent")).hasSize(8);
        assertThat(summary.get("ingestion").get("recent").get(0).has("unmappedFieldCount")).isTrue();
        assertThat(summary.get("imports").get("total").asLong()).isEqualTo(7);
        assertThat(summary.get("imports").get("recent")).hasSize(5);
        JsonNode recentImport = summary.get("imports").get("recent").get(0);
        assertThat(recentImport.get("templateName").asText()).isEqualTo(shared.getName());
        assertThat(recentImport.get("importDate").isNull()).isFalse();
        assertThat(summary.get("extraction").has("failed")).isTrue();
        assertThat(summary.get("extraction").has("running")).isTrue();
        // The small user sees the bank-shared template too: own 1 + shared 1.
        assertThat(getJson("/api/dashboard/summary", small).get("templates").asLong()).isEqualTo(2);
    }

    // ---- ImportDetails / ImportHistory / TemplateFields (LAZY associations) ------------

    @Test
    void importDetailsGetAllIsOneBankFilteredQueryAndStillCarriesItsAssociations() throws Exception {
        Bank bank = bank();
        AppUser few = user(bank, Role.READ_WRITE);
        Template t = template(few, false, "Name");
        importOf(few, t, 3);
        AppUser many = user(bank, Role.READ_WRITE);
        Template t2 = template(many, false, "Name");
        for (int i = 0; i < 4; i++) {
            importOf(many, i % 2 == 0 ? t2 : template(many, false, "F" + i), 3);
        }
        // Someone else's imports in the same bank never show.
        importOf(user(bank, Role.READ_WRITE), t, 2);

        long forFew = statements("ImportDetails 3 details", () -> getJson("/ImportDetails/GetAll", few));
        long forMany = statements("ImportDetails 12 details", () -> getJson("/ImportDetails/GetAll", many));
        assertThat(forMany).as("GetAll statements: 3 details %d, 12 details %d", forFew, forMany)
                .isEqualTo(forFew);

        JsonNode all = getJson("/ImportDetails/GetAll", many);
        assertThat(all).hasSize(12);
        JsonNode first = all.get(0);
        assertThat(first.get("importHistory").get("template").get("name").asText()).startsWith("ws28-");
        assertThat(first.get("processStep").get("name").asText()).isNotBlank();
        assertThat(first.get("importHistory").has("hibernateLazyInitializer")).isFalse();

        int detailId = first.get("importDetailsId").asInt();
        JsonNode byId = getJson("/ImportDetails/GetById/" + detailId, many);
        assertThat(byId.get("importHistory").get("template").get("name").asText()).startsWith("ws28-");

        JsonNode histories = getJson("/ImportHistory/GetAll", many);
        assertThat(histories).hasSize(4);
        assertThat(histories.get(0).get("template").get("name").asText()).startsWith("ws28-");
        int historyId = histories.get(0).get("importHistoryId").asInt();
        assertThat(getJson("/ImportHistory/GetById/" + historyId, many).get("template").get("name").asText())
                .startsWith("ws28-");
    }

    @Test
    void templateFieldsLoadTheirTemplateAndTypeInOneStatementAndSerializeAfterAnUpdate() throws Exception {
        Bank bank = bank();
        AppUser owner = user(bank, Role.READ_WRITE);
        Template small = template(owner, false, "A");
        Template large = template(owner, false, "A", "B", "C", "D", "E", "F");

        long one = statements("TemplateFields 1 field",
                () -> getJson("/TemplateFields/GetAll?templateId=" + small.getTemplateId(), owner));
        long six = statements("TemplateFields 6 fields",
                () -> getJson("/TemplateFields/GetAll?templateId=" + large.getTemplateId(), owner));
        assertThat(six).as("fields statements: 1 field %d, 6 fields %d", one, six).isEqualTo(one);

        JsonNode list = getJson("/TemplateFields/GetAll?templateId=" + large.getTemplateId(), owner);
        assertThat(list).hasSize(6);
        assertThat(list.get(0).get("template").get("name").asText()).isEqualTo(large.getName());
        assertThat(list.get(0).get("templateDataType").get("dataType").asText()).isEqualTo("Text");

        int fieldId = list.get(0).get("templateFieldsId").asInt();
        assertThat(getJson("/TemplateFields/GetById/" + fieldId, owner).get("template").get("name").asText())
                .isEqualTo(large.getName());
        String updated = mvc.perform(withAuth(put("/TemplateFields/Update/" + fieldId), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateFieldsId\":" + fieldId + ",\"templateId\":" + large.getTemplateId()
                                + ",\"fieldName\":\"A2\",\"templateDataTypeId\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(updated).get("template").get("name").asText()).isEqualTo(large.getName());
        assertThat(JSON.readTree(updated).get("fieldName").asText()).isEqualTo("A2");
    }

    // ---- doc creation ----------------------------------------------------------------

    private DocInstance submitted(AppUser author, int sectionsPerBlueprint) {
        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(author.getAppUserId());
        blueprint.setBankId(author.getBankId());
        blueprint.setName("bp-" + UUID.randomUUID());
        blueprint.setFileName("bp.docx");
        blueprint.setCreatedDate(LocalDateTime.now());
        blueprint.setUpdatedDate(LocalDateTime.now());
        blueprint = blueprints.save(blueprint);
        List<DocBlueprintSection> made = new ArrayList<>();
        for (int s = 0; s < sectionsPerBlueprint; s++) {
            DocBlueprintSection section = new DocBlueprintSection();
            section.setBlueprintId(blueprint.getDocBlueprintId());
            section.setBankId(author.getBankId());
            section.setOrderIndex(s);
            section.setTitle("Section " + s);
            made.add(sections.save(section));
        }
        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(author.getAppUserId());
        instance.setBankId(author.getBankId());
        instance.setBlueprintId(blueprint.getDocBlueprintId());
        instance.setTitle("Doc " + UUID.randomUUID());
        instance.setStatus(DocInstance.STATUS_SUBMITTED);
        instance.setSubmittedByUserId(author.getAppUserId());
        instance.setSubmittedAt(LocalDateTime.now());
        instance.setCreatedDate(LocalDateTime.now());
        instance.setUpdatedDate(LocalDateTime.now());
        instance = instances.save(instance);
        DocImageAsset asset = new DocImageAsset();
        asset.setInstanceId(instance.getDocInstanceId());
        asset.setBankId(author.getBankId());
        asset.setFileName("img.png");
        asset.setOriginalFileName("photo.png");
        asset.setContentType("image/png");
        asset.setUploadedDate(LocalDateTime.now());
        asset = assets.save(asset);
        for (DocBlueprintSection section : made) {
            DocContentBlock text = new DocContentBlock();
            text.setInstanceId(instance.getDocInstanceId());
            text.setBankId(author.getBankId());
            text.setSectionId(section.getDocBlueprintSectionId());
            text.setBlockType(DocContentBlock.TYPE_PARAGRAPH);
            text.setTextContent("text");
            blocks.save(text);
            DocContentBlock image = new DocContentBlock();
            image.setInstanceId(instance.getDocInstanceId());
            image.setBankId(author.getBankId());
            image.setSectionId(section.getDocBlueprintSectionId());
            image.setBlockType(DocContentBlock.TYPE_IMAGE);
            image.setOrderIndex(1);
            image.setAssetId(asset.getDocImageAssetId());
            blocks.save(image);
        }
        return instance;
    }

    @Test
    void theReleaseQueueBatchLoadsBlueprintsBlocksAssetsAndUsers() throws Exception {
        Bank one = bank();
        AppUser approverOne = user(one, Role.APPROVER);
        submitted(user(one, Role.READ_WRITE), 2);

        Bank four = bank();
        AppUser approverFour = user(four, Role.APPROVER);
        for (int i = 0; i < 4; i++) {
            submitted(user(four, Role.READ_WRITE), 2);
        }

        String url = "/api/doccreation/instances/pending-release";
        long forOne = statements("release queue 1 doc", () -> getJson(url, approverOne));
        long forFour = statements("release queue 4 docs", () -> getJson(url, approverFour));
        assertThat(forFour).as("release queue statements: 1 doc %d, 4 docs %d", forOne, forFour)
                .isEqualTo(forOne);

        JsonNode queue = getJson(url, approverFour);
        assertThat(queue).hasSize(4);
        JsonNode first = queue.get(0);
        assertThat(first.get("ownerEmail").asText()).startsWith("ws28-");
        assertThat(first.get("submittedByEmail").asText()).startsWith("ws28-");
        assertThat(first.get("sections")).hasSize(2);
        assertThat(first.get("sections").get(0).get("blocks")).hasSize(2);
        assertThat(first.get("sections").get(0).get("blocks").toString()).contains("photo.png");
    }

    @Test
    void theBlueprintListCountsSectionsAndInstancesWithTwoGroupedQueries() throws Exception {
        Bank bank = bank();
        AppUser few = user(bank, Role.READ_WRITE);
        submitted(few, 1);
        AppUser many = user(bank, Role.READ_WRITE);
        for (int i = 0; i < 5; i++) {
            submitted(many, 3);
        }
        long forOne = statements("blueprints 1", () -> getJson("/api/doccreation/blueprints", few));
        long forFive = statements("blueprints 5", () -> getJson("/api/doccreation/blueprints", many));
        assertThat(forFive).as("blueprint list statements: 1 %d, 5 %d", forOne, forFive).isEqualTo(forOne);

        JsonNode list = getJson("/api/doccreation/blueprints", many);
        assertThat(list).hasSize(5);
        assertThat(list.get(0).get("sectionCount").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("instanceCount").asInt()).isEqualTo(1);
    }

    // ---- pending changes ---------------------------------------------------------------

    private void change(AppUser maker, PendingChangeStatus status) {
        PendingChange change = new PendingChange();
        change.setBankId(maker.getBankId());
        change.setChangeType(ChangeType.TEMPLATE_DELETE);
        change.setTargetEntityType("Template");
        change.setTargetEntityId("1");
        change.setPayloadJson("{}");
        change.setStatus(status);
        change.setMakerUserId(maker.getAppUserId());
        change.setRequestedAt(LocalDateTime.now());
        changes.save(change);
    }

    @Test
    void thePendingChangeQueuePagesInTheDatabaseAndLooksUsersUpOnce() throws Exception {
        Bank one = bank();
        AppUser adminOne = user(one, Role.ADMIN);
        change(user(one, Role.READ_WRITE), PendingChangeStatus.REQUESTED);

        Bank six = bank();
        AppUser adminSix = user(six, Role.ADMIN);
        for (int i = 0; i < 6; i++) {
            change(user(six, Role.READ_WRITE), PendingChangeStatus.REQUESTED);
        }

        String url = "/api/admin/changes?page=0&size=50";
        long forOne = statements("pending changes 1", () -> getJson(url, adminOne));
        long forSix = statements("pending changes 6", () -> getJson(url, adminSix));
        assertThat(forSix).as("pending change statements: 1 %d, 6 %d", forOne, forSix).isEqualTo(forOne);

        JsonNode page = getJson(url, adminSix);
        assertThat(page.get("total").asLong()).isEqualTo(6);
        assertThat(page.get("items")).hasSize(6);
        assertThat(page.get("items").get(0).get("makerEmail").asText()).startsWith("ws28-");
        JsonNode second = getJson("/api/admin/changes?page=1&size=4&status=REQUESTED", adminSix);
        assertThat(second.get("items")).hasSize(2);
        assertThat(second.get("total").asLong()).isEqualTo(6);
        // No page parameter: the old plain list.
        JsonNode plain = getJson("/api/admin/changes", adminSix);
        assertThat(plain.isArray()).isTrue();
        assertThat(plain).hasSize(6);
        // A plain maker sees only their own rows.
        AppUser maker = user(six, Role.READ_WRITE);
        change(maker, PendingChangeStatus.REQUESTED);
        assertThat(getJson(url, maker).get("total").asLong()).isEqualTo(1);
    }

    // ---- integration API /templates ------------------------------------------------------

    @Test
    void theIntegrationTemplateListReadsAllFieldsInOneQuery() throws Exception {
        Bank one = bank();
        AppUser ownerOne = user(one, Role.ADMIN);
        template(ownerOne, true, "A", "B");
        Bank five = bank();
        AppUser ownerFive = user(five, Role.ADMIN);
        for (int i = 0; i < 5; i++) {
            template(ownerFive, true, "A", "B", "C");
        }
        template(ownerFive, false, "Private");
        ClientPrincipal clientOne = client(ownerOne);
        ClientPrincipal clientFive = client(ownerFive);

        long forOne = statements("integration templates 1",
                () -> integrationApi.templates(clientOne));
        long forFive = statements("integration templates 5",
                () -> integrationApi.templates(clientFive));
        assertThat(forFive).as("integration templates statements: 1 %d, 5 %d", forOne, forFive)
                .isEqualTo(forOne);
        List<Map<String, Object>> listed = integrationApi.templates(clientFive);
        assertThat(listed).hasSize(5);
        assertThat((List<?>) listed.get(0).get("fields")).hasSize(3);
    }

    private static ClientPrincipal client(AppUser owner) {
        return new ClientPrincipal(1, owner.getBankId(), "client", ApiClientKind.SYSTEM,
                ApiAuthMethod.API_KEY, Set.of(), owner.getAppUserId(), null);
    }

    // ---- template owner names ----------------------------------------------------------------

    @Test
    void templateOwnerNamesComeFromTheOwnersShownOnly() throws Exception {
        Bank bank = bank();
        AppUser me = user(bank, Role.READ_WRITE);
        AppUser colleague = user(bank, Role.READ_WRITE);
        for (int i = 0; i < 5; i++) {
            user(bank, Role.READ_ONLY);
        }
        Template shared = template(colleague, true, "A");
        JsonNode list = getJson("/Templates/GetAll", me);
        JsonNode row = null;
        for (JsonNode template : list) {
            if (template.get("templateId").asInt() == shared.getTemplateId()) {
                row = template;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("ownerName").asText()).isEqualTo(colleague.getDisplayName());
    }
}
