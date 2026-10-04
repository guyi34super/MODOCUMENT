package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate C12 (PRIV-016): export and erase one customer's data, round-trip.
 * Seeds a subject (by NIC value) across four stores plus the original
 * upload, then: search finds every occurrence; the export ZIP carries the
 * matching rows (the JSON entry is parsed and checked); erase removes them
 * (with the original file and its row); a second search finds ZERO; a
 * second export is empty; the OTHER subject's rows are intact; and the
 * audit trail shows DSR_SEARCH / DSR_EXPORTED / DSR_ERASED.
 */
@SpringBootTest(properties = {
        "modocument.storage.root=target/ws14-c12-storage"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSubjectRightsGateC12Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORAGE = Path.of("target/ws14-c12-storage");
    private static final String SUBJECT = "ANNA-NIC-7788";
    private static final String OTHER = "OTHR-NIC-9911";

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
    private DocContentBlockJpaRepository docBlocks;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private Bank bank;
    private AppUser admin;
    private String tag;
    private IngestionJob job;
    private UploadedFile upload;
    private ImportDetails details;
    private ExtractionRawRecordEntity raw;
    private ExtractionProcessedRecordEntity processed;
    private DocContentBlock block;

    @BeforeEach
    void seedSubjectAcrossStores() throws Exception {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("C12" + tag.substring(0, 5).toUpperCase(), "DSR Bank"));
        admin = new AppUser();
        admin.setEmail("c12-admin-" + tag + "@example.com");
        admin.setPasswordHash("{noop}unused");
        admin.setDisplayName("C12 Admin");
        admin.setBankId(bank.getBankId());
        admin.setRole(Role.ADMIN);
        admin.setSettingsJson("{}");
        admin.setCreatedDate(LocalDateTime.now());
        admin = users.save(admin);
        Template template = new Template();
        template.setOwnerUserId(admin.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setName("c12-template-" + tag);
        template = templates.save(template);
        Files.createDirectories(STORAGE.resolve("uploads"));

        // Store 1: ingestion job rows (parsed + mapped), two subjects.
        String storedName = "c12-upload-" + tag + ".csv";
        Files.writeString(STORAGE.resolve("uploads").resolve(storedName),
                "nic,name\n" + SUBJECT + ",Anna\n" + OTHER + ",Other\n");
        upload = new UploadedFile();
        upload.setStoredName(storedName);
        upload.setOriginalName("accounts.csv");
        upload.setOwnerUserId(admin.getAppUserId());
        upload.setBankId(bank.getBankId());
        upload.setSizeBytes(10);
        upload.setUploadedAt(LocalDateTime.now());
        upload = uploads.save(upload);

        job = new IngestionJob();
        job.setOwnerUserId(admin.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName(storedName);
        job.setParsedTableJson(table("nic", SUBJECT, OTHER));
        job.setMappedRowsJson(table("Nic", SUBJECT, OTHER));
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        job = jobs.save(job);

        // Store 2: import step payload.
        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(admin.getAppUserId());
        history.setBankId(bank.getBankId());
        history.setImportDate(LocalDateTime.now());
        history.setFileName("c12-import-" + tag + ".csv");
        history.setTemplateId(template.getTemplateId());
        history = importHistory.save(history);
        details = new ImportDetails();
        details.setImportHistoryId(history.getImportHistoryId());
        details.setBankId(bank.getBankId());
        details.setProcessStepId(1);
        details.setRawJson("{\"rows\":[{\"nic\":\"" + SUBJECT + "\"},{\"nic\":\""
                + OTHER + "\"}]}");
        details = importDetails.save(details);

        // Store 3: extraction raw body + processed fields.
        raw = new ExtractionRawRecordEntity();
        raw.setJobId("c12-job-" + tag);
        raw.setBankId(bank.getBankId());
        raw.setSourceId("c12-src-" + tag);
        raw.setUri("https://example.test/c12");
        raw.setBody("Profile page of " + SUBJECT + " among others");
        raw.setTruncated(false);
        raw.setContentHash("c12hash" + tag);
        raw.setFetchedAt(Instant.now());
        raw = extractionRaw.save(raw);
        processed = new ExtractionProcessedRecordEntity();
        processed.setJobId("c12-job-" + tag);
        processed.setBankId(bank.getBankId());
        processed.setFieldsJson("{\"nic\":\"" + SUBJECT + "\",\"city\":\"Port Louis\"}");
        processed.setDedupKey("c12-dedup-" + tag);
        processed.setProcessedAt(Instant.now());
        processed = extractionProcessed.save(processed);

        // Store 4: doc-creation paragraph naming the subject.
        block = new DocContentBlock();
        block.setInstanceId(991);
        block.setBankId(bank.getBankId());
        block.setSectionId(1);
        block.setBlockType(DocContentBlock.TYPE_PARAGRAPH);
        block.setOrderIndex(0);
        block.setTextContent("Contract issued to " + SUBJECT + " on request.");
        block = docBlocks.save(block);
    }

    private static String table(String column, String... nics) {
        StringBuilder rows = new StringBuilder();
        for (String nic : nics) {
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append("{\"").append(column).append("\":\"").append(nic).append("\"}");
        }
        return "{\"columns\":[\"" + column + "\"],\"rows\":[" + rows + "]}";
    }

    private String bearer() {
        return "Bearer " + jwtService.issueToken(admin);
    }

    private JsonNode call(String path, String body) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode search() throws Exception {
        return call("/api/admin/dsr/search", "{\"identifier\":\"" + SUBJECT + "\"}");
    }

    private Map<String, JsonNode> exportZipEntries() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/dsr/export")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + SUBJECT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/zip");

        Map<String, JsonNode> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                if (entry.getName().endsWith(".json")) {
                    entries.put(entry.getName(), MAPPER.readTree(content));
                } else {
                    entries.put(entry.getName(),
                            MAPPER.getNodeFactory().textNode(
                                    new String(content, StandardCharsets.UTF_8)));
                }
            }
        }
        return entries;
    }

    @Test
    void searchExportEraseRoundTrip() throws Exception {
        // ---- 1. search finds the subject in every store ----
        JsonNode found = search();
        assertThat(found.get("totalMatches").asInt()).isGreaterThanOrEqualTo(6);
        Map<String, Integer> byStore = new HashMap<>();
        found.get("stores").forEach(store ->
                byStore.put(store.get("store").asText(), store.get("matches").asInt()));
        assertThat(byStore.get("ingestionJobs")).isEqualTo(2);         // parsed + mapped row
        assertThat(byStore.get("importDetails")).isEqualTo(1);
        assertThat(byStore.get("extractionRawRecords")).isEqualTo(1);
        assertThat(byStore.get("extractionProcessedRecords")).isEqualTo(1);
        assertThat(byStore.get("uploadedFiles")).isEqualTo(1);         // the job's original
        assertThat(byStore.get("docContentBlocks")).isEqualTo(1);

        // ---- 2. export ZIP carries the subject's rows in full ----
        Map<String, JsonNode> zip = exportZipEntries();
        assertThat(zip).containsKeys("dsr-export.json", "dsr-export.txt");
        JsonNode export = zip.get("dsr-export.json");
        assertThat(export.get("identifier").asText()).isEqualTo(SUBJECT);
        JsonNode jobEntry = export.get("stores").get("ingestionJobs").get(0);
        assertThat(jobEntry.get("jobId").asInt()).isEqualTo(job.getIngestionJobId());
        assertThat(jobEntry.get("parsedRows").get(0).get("nic").asText()).isEqualTo(SUBJECT);
        assertThat(jobEntry.get("mappedRows").get(0).get("Nic").asText()).isEqualTo(SUBJECT);
        assertThat(export.get("stores").get("extractionProcessedRecords").get(0)
                .get("fields").get("nic").asText()).isEqualTo(SUBJECT);
        assertThat(export.get("stores").get("docContentBlocks").get(0)
                .get("textContent").asText()).contains(SUBJECT);
        assertThat(export.toString()).doesNotContain(OTHER);
        assertThat(zip.get("dsr-export.txt").asText()).contains(SUBJECT);

        // ---- 3. erase refuses without confirm, then removes the rows ----
        mvc.perform(post("/api/admin/dsr/erase")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + SUBJECT + "\"}"))
                .andExpect(status().isBadRequest());

        JsonNode erased = call("/api/admin/dsr/erase",
                "{\"identifier\":\"" + SUBJECT + "\",\"confirm\":true}");
        assertThat(erased.get("totalRemoved").asInt()).isGreaterThanOrEqualTo(6);
        assertThat(erased.get("counts").get("ingestionJobRows").asInt()).isEqualTo(2);
        assertThat(erased.get("counts").get("uploadedFiles").asInt()).isEqualTo(1);

        // ---- 4. search now finds ZERO, the export is empty ----
        assertThat(search().get("totalMatches").asInt()).isZero();
        JsonNode emptyExport = exportZipEntries().get("dsr-export.json");
        assertThat(emptyExport.get("totalMatches").asInt()).isZero();
        assertThat(emptyExport.get("stores").get("ingestionJobs")).isEmpty();

        // ---- 5. the OTHER subject's data is intact, files are gone ----
        IngestionJob reloaded = jobs.findById(job.getIngestionJobId()).orElseThrow();
        assertThat(reloaded.getParsedTableJson()).contains(OTHER).doesNotContain(SUBJECT);
        assertThat(reloaded.getMappedRowsJson()).contains(OTHER).doesNotContain(SUBJECT);
        assertThat(uploads.findById(upload.getUploadedFileId())).isEmpty();
        assertThat(Files.exists(STORAGE.resolve("uploads").resolve(upload.getStoredName())))
                .isFalse();
        assertThat(importDetails.findById(details.getImportDetailsId()).orElseThrow()
                .getRawJson()).isNull();
        assertThat(extractionRaw.findById(raw.getId())).isEmpty();
        assertThat(extractionProcessed.findById(processed.getId())).isEmpty();
        assertThat(docBlocks.findById(block.getDocContentBlockId())).isEmpty();

        // ---- 6. the audit trail shows the three events, values-free ----
        for (String action : new String[] {"DSR_SEARCH", "DSR_EXPORTED", "DSR_ERASED"}) {
            Integer audited = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND BankId = ? "
                            + "AND Outcome = 'SUCCESS'",
                    Integer.class, action, bank.getBankId());
            assertThat(audited).as(action).isGreaterThanOrEqualTo(1);
        }
        Integer leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND "
                        + "(CAST(NewValues AS VARCHAR) LIKE ? OR EntityId LIKE ?)",
                Integer.class, bank.getBankId(), "%" + SUBJECT + "%", "%" + SUBJECT + "%");
        assertThat(leaked).as("the identifier never enters the audit trail").isZero();
    }

    @Test
    void dsrRoutesRequireUserAdmin() throws Exception {
        AppUser member = new AppUser();
        member.setEmail("c12-member-" + tag + "@example.com");
        member.setPasswordHash("{noop}unused");
        member.setDisplayName("Member");
        member.setBankId(bank.getBankId());
        member.setRole(Role.READ_WRITE);
        member.setSettingsJson("{}");
        member.setCreatedDate(LocalDateTime.now());
        member = users.save(member);

        mvc.perform(post("/api/admin/dsr/search")
                        .header("Authorization", "Bearer " + jwtService.issueToken(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + SUBJECT + "\"}"))
                .andExpect(status().isForbidden());
    }
}
