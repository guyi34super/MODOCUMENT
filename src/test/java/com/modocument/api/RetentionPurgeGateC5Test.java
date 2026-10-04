package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetJpaRepository;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.UploadedFile;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.extraction.sql.ExtractionProcessedRecordJpaRepository;
import com.modocument.infrastructure.extraction.sql.ExtractionRawRecordJpaRepository;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.ImportDetailsJpaRepository;
import com.modocument.infrastructure.persistence.ImportHistoryJpaRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate C5 (PRIV-013): 30-day automatic deletion of uploaded data. Seeds a
 * test record of every customer-data category 31 days old (plus 29-day-old
 * controls), runs the purge through the manual admin trigger, and asserts:
 * files are gone from disk, data columns are nulled with PurgedAt set,
 * UploadedFile rows are gone, the RETENTION_PURGE_COMPLETED audit event
 * (the "job log") carries the correct per-category counts — and the
 * 29-day-old record survives untouched.
 */
@SpringBootTest(properties = {
        "modocument.retention.enabled=true",
        "modocument.storage.root=target/ws14-c5-storage"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetentionPurgeGateC5Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORAGE = Path.of("target/ws14-c5-storage");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private UploadedFileJpaRepository uploads;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private ImportHistoryJpaRepository importHistory;
    @Autowired
    private ImportDetailsJpaRepository importDetails;
    @Autowired
    private ExtractionRawRecordJpaRepository extractionRaw;
    @Autowired
    private ExtractionProcessedRecordJpaRepository extractionProcessed;
    @Autowired
    private DocInstanceJpaRepository docInstances;
    @Autowired
    private DocContentBlockJpaRepository docBlocks;
    @Autowired
    private DocImageAssetJpaRepository docAssets;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private Bank bank;
    private AppUser admin;
    private Template template;
    private String tag;

    @BeforeEach
    void seedBankAndAdmin() throws Exception {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("C5" + tag.substring(0, 6).toUpperCase(), "Retention Bank"));
        admin = new AppUser();
        admin.setEmail("c5-admin-" + tag + "@example.com");
        admin.setPasswordHash("{noop}unused");
        admin.setDisplayName("C5 Admin");
        admin.setBankId(bank.getBankId());
        admin.setRole(Role.ADMIN);
        admin.setSettingsJson("{}");
        admin.setCreatedDate(LocalDateTime.now());
        admin = users.save(admin);
        template = new Template();
        template.setOwnerUserId(admin.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setName("c5-template-" + tag);
        template = templates.save(template);
        Files.createDirectories(STORAGE.resolve("uploads"));
        Files.createDirectories(STORAGE.resolve("exports"));
    }

    private String bearer() {
        return "Bearer " + jwtService.issueToken(admin);
    }

    private UploadedFile seedUpload(String storedName, int daysOld) throws Exception {
        Files.writeString(STORAGE.resolve("uploads").resolve(storedName), "name,nic\nx,y\n");
        UploadedFile row = new UploadedFile();
        row.setStoredName(storedName);
        row.setOriginalName(storedName);
        row.setOwnerUserId(admin.getAppUserId());
        row.setBankId(bank.getBankId());
        row.setSizeBytes(10);
        row.setUploadedAt(LocalDateTime.now().minusDays(daysOld));
        return uploads.save(row);
    }

    private IngestionJob seedJob(String fileName, int daysOld) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(admin.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName(fileName);
        job.setParsedTableJson("{\"columns\":[\"nic\"],\"rows\":[{\"nic\":\"X123\"}]}");
        job.setMappedRowsJson("{\"columns\":[\"Nic\"],\"rows\":[{\"Nic\":\"X123\"}]}");
        job.setValidationResultJson("[]");
        job.setCreatedDate(LocalDateTime.now().minusDays(daysOld));
        job.setUpdatedDate(LocalDateTime.now().minusDays(daysOld));
        return jobs.save(job);
    }

    @Test
    void thirtyOneDayOldDataIsPurgedAndTwentyNineDayOldSurvives() throws Exception {
        // ---- seed: 31-day-old records of every category ----
        UploadedFile oldUpload = seedUpload("c5-old-" + tag + ".csv", 31);
        IngestionJob oldJob = seedJob(oldUpload.getStoredName(), 31);
        Path archive = STORAGE.resolve("exports").resolve("u" + admin.getAppUserId())
                .resolve("job" + oldJob.getIngestionJobId() + ".csv");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "Nic\nX123\n");

        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(admin.getAppUserId());
        history.setBankId(bank.getBankId());
        history.setImportDate(LocalDateTime.now().minusDays(31));
        history.setFileName("c5-import-" + tag + ".csv");
        history.setTemplateId(template.getTemplateId());
        history = importHistory.save(history);
        ImportDetails details = new ImportDetails();
        details.setImportHistoryId(history.getImportHistoryId());
        details.setBankId(bank.getBankId());
        details.setProcessStepId(1);
        details.setRawJson("{\"nic\":\"X123\"}");
        details = importDetails.save(details);

        ExtractionRawRecordEntity raw = new ExtractionRawRecordEntity();
        raw.setJobId("c5-job-" + tag);
        raw.setBankId(bank.getBankId());
        raw.setSourceId("c5-src-" + tag);
        raw.setUri("https://example.test/c5");
        raw.setBody("page body naming X123");
        raw.setTruncated(false);
        raw.setContentHash("c5hash" + tag);
        raw.setFetchedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        raw = extractionRaw.save(raw);

        ExtractionProcessedRecordEntity processed = new ExtractionProcessedRecordEntity();
        processed.setJobId("c5-job-" + tag);
        processed.setBankId(bank.getBankId());
        processed.setFieldsJson("{\"nic\":\"X123\"}");
        processed.setDedupKey("c5-dedup-" + tag);
        processed.setProcessedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        processed = extractionProcessed.save(processed);

        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(admin.getAppUserId());
        instance.setBankId(bank.getBankId());
        instance.setBlueprintId(1);
        instance.setTitle("C5 doc");
        instance.setCreatedDate(LocalDateTime.now().minusDays(40));
        instance.setUpdatedDate(LocalDateTime.now().minusDays(31));
        instance = docInstances.save(instance);
        DocContentBlock block = new DocContentBlock();
        block.setInstanceId(instance.getDocInstanceId());
        block.setBankId(bank.getBankId());
        block.setSectionId(1);
        block.setBlockType(DocContentBlock.TYPE_PARAGRAPH);
        block.setOrderIndex(0);
        block.setTextContent("Paragraph about X123");
        docBlocks.save(block);
        DocImageAsset asset = new DocImageAsset();
        asset.setInstanceId(instance.getDocInstanceId());
        asset.setBankId(bank.getBankId());
        asset.setFileName("c5-asset-" + tag + ".png");
        asset.setOriginalFileName("photo.png");
        asset.setContentType("image/png");
        asset.setUploadedDate(LocalDateTime.now().minusDays(31));
        docAssets.save(asset);
        Path assetFile = STORAGE.resolve("uploads").resolve(asset.getFileName());
        Files.writeString(assetFile, "png-bytes");

        // ---- controls: 29 days old, must survive ----
        UploadedFile youngUpload = seedUpload("c5-young-" + tag + ".csv", 29);
        IngestionJob youngJob = seedJob(youngUpload.getStoredName(), 29);

        // ---- run: the manual admin trigger (gate evidence path) ----
        MvcResult run = mvc.perform(post("/api/admin/retention/run")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode counts = MAPPER.readTree(run.getResponse().getContentAsString())
                .get("yourBank");
        assertThat(counts.get("uploadedFiles").asInt()).isEqualTo(1);
        assertThat(counts.get("ingestionJobs").asInt()).isEqualTo(1);
        assertThat(counts.get("exportArchives").asInt()).isEqualTo(1);
        assertThat(counts.get("importDetailRows").asInt()).isEqualTo(1);
        assertThat(counts.get("extractionRawBodies").asInt()).isEqualTo(1);
        assertThat(counts.get("extractionProcessedFields").asInt()).isEqualTo(1);
        assertThat(counts.get("docInstances").asInt()).isEqualTo(1);
        assertThat(counts.get("docContentBlocks").asInt()).isEqualTo(1);
        assertThat(counts.get("docImageAssets").asInt()).isEqualTo(1);

        // ---- the 31-day-old record is GONE ----
        assertThat(Files.exists(STORAGE.resolve("uploads").resolve(oldUpload.getStoredName())))
                .as("uploaded file deleted from disk").isFalse();
        assertThat(uploads.findById(oldUpload.getUploadedFileId())).isEmpty();
        assertThat(Files.exists(archive)).as("export archive copy deleted").isFalse();
        assertThat(Files.exists(assetFile)).as("doc image asset file deleted").isFalse();

        IngestionJob purgedJob = jobs.findById(oldJob.getIngestionJobId()).orElseThrow();
        assertThat(purgedJob.getParsedTableJson()).isNull();
        assertThat(purgedJob.getMappedRowsJson()).isNull();
        assertThat(purgedJob.getValidationResultJson()).isNull();
        assertThat(purgedJob.getPurgedAt()).isNotNull();
        assertThat(purgedJob.getFileName()).as("job skeleton remains").isNotNull();

        assertThat(importDetails.findById(details.getImportDetailsId()).orElseThrow()
                .getRawJson()).isNull();
        assertThat(extractionRaw.findById(raw.getId()).orElseThrow().getBody()).isNull();
        assertThat(extractionProcessed.findById(processed.getId()).orElseThrow()
                .getFieldsJson()).isNull();

        DocInstance purgedInstance = docInstances.findById(instance.getDocInstanceId()).orElseThrow();
        assertThat(purgedInstance.getContentPurgedAt()).isNotNull();
        assertThat(purgedInstance.getTitle()).as("instance shell remains").isEqualTo("C5 doc");
        assertThat(docBlocks.findByInstanceIdOrderBySectionIdAscOrderIndexAsc(
                instance.getDocInstanceId())).isEmpty();
        assertThat(docAssets.findByInstanceIdOrderByDocImageAssetId(
                instance.getDocInstanceId())).isEmpty();

        // ---- the 29-day-old record SURVIVES ----
        assertThat(Files.exists(STORAGE.resolve("uploads").resolve(youngUpload.getStoredName())))
                .isTrue();
        assertThat(uploads.findById(youngUpload.getUploadedFileId())).isPresent();
        IngestionJob survivor = jobs.findById(youngJob.getIngestionJobId()).orElseThrow();
        assertThat(survivor.getParsedTableJson()).isNotNull();
        assertThat(survivor.getPurgedAt()).isNull();

        // ---- the job log: RETENTION_PURGE_COMPLETED audit row per bank ----
        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'RETENTION_PURGE_COMPLETED' "
                        + "AND BankId = ? AND Outcome = 'SUCCESS'",
                Integer.class, bank.getBankId());
        assertThat(audited).isEqualTo(1);

        // ---- and the status endpoint surfaces it with the counts ----
        MvcResult statusResult = mvc.perform(get("/api/admin/retention/status")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = MAPPER.readTree(
                statusResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(statusBody.get("retentionDays").asInt()).isEqualTo(30);
        JsonNode lastRun = statusBody.get("lastRun");
        assertThat(lastRun.isNull()).isFalse();
        assertThat(lastRun.get("counts").get("uploadedFiles").asInt()).isEqualTo(1);
        assertThat(lastRun.get("counts").get("ingestionJobs").asInt()).isEqualTo(1);
    }

    @Test
    void jobDeletionEndpointRemovesRowFilesAndArchiveAndAudits() throws Exception {
        UploadedFile upload = seedUpload("c5-del-" + tag + ".csv", 2);
        IngestionJob job = seedJob(upload.getStoredName(), 2);
        Path archive = STORAGE.resolve("exports").resolve("u" + admin.getAppUserId())
                .resolve("job" + job.getIngestionJobId() + ".json");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "[]");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/ingestion/jobs/" + job.getIngestionJobId())
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        assertThat(jobs.findById(job.getIngestionJobId())).isEmpty();
        assertThat(uploads.findById(upload.getUploadedFileId())).isEmpty();
        assertThat(Files.exists(STORAGE.resolve("uploads").resolve(upload.getStoredName())))
                .isFalse();
        assertThat(Files.exists(archive)).isFalse();

        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'JOB_DELETED' "
                        + "AND EntityId = ? AND Outcome = 'SUCCESS'",
                Integer.class, String.valueOf(job.getIngestionJobId()));
        assertThat(audited).isEqualTo(1);
    }
}
