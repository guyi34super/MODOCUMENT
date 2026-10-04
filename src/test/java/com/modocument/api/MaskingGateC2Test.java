package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate C2 (PRIV-004 / PRIV-005): NIC and account numbers are masked for
 * every role in screens, reports and exports — a sample of each channel
 * shows only masked values.
 *
 * <p>Seeds a template whose fields carry explicit PII tags (NIC, account)
 * plus one auto-classified field (Email, tag persisted on first use), and a
 * completed ingestion job holding the raw values. Then samples every
 * channel: (1) EXPORT — csv, json and xlsx downloads, the per-owner archive
 * copy and the database export all carry only masked values; (2) REPORT —
 * the generated document's text is shape-masked; (3) SCREEN_READONLY — the
 * owner, downgraded to READ_ONLY (jobs are owner-scoped, so that is the
 * only role/ownership combination that can reach this screen), sees masked
 * payloads; (4) SCREEN_WORKING — the owner as READ_WRITE sees raw values,
 * because cell editing requires them; (5) DSR — the ws14 disclosure export
 * stays complete/raw by design (PRIV-016). The masking-matrix doc itself is
 * pinned by MaskingMatrixDocTest.
 */
@SpringBootTest(properties = {
        "modocument.storage.root=target/ws12-c2-storage",
        "modocument.doccreation.section-ai-enabled=false",
        "modocument.doccreation.generate-ai-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaskingGateC2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORAGE = Path.of("target/ws12-c2-storage");

    private static final String RAW_NIC = "M090976290148E";
    private static final String RAW_ACCOUNT = "00445566778899";
    private static final String RAW_EMAIL = "jane.k@acme.mu";
    private static final String MASKED_NIC = "********";
    private static final String MASKED_ACCOUNT = "****8899";
    private static final String MASKED_EMAIL = "j***@acme.mu";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsJpaRepository templateFields;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DocBlueprintUseCase blueprints;
    @Autowired
    private DocInstanceUseCase instances;

    private Bank bank;
    private AppUser owner;
    private AppUser admin;
    private IngestionJob job;
    private TemplateFields emailField;
    private TemplateFields cityField;

    @BeforeEach
    void seedTemplateAndCompletedJob() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("C2" + tag.substring(0, 6).toUpperCase(), "Masking Bank"));

        owner = user("c2-owner-" + tag, Role.READ_WRITE);
        admin = user("c2-admin-" + tag, Role.ADMIN);

        Template template = new Template();
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setName("c2-template-" + tag);
        template = templates.save(template);

        field(template, "CustomerNic", PiiKind.NIC);          // explicit tag
        field(template, "AccountNumber", PiiKind.ACCOUNT_NUMBER); // explicit tag
        emailField = field(template, "Email", null);          // auto-classified
        cityField = field(template, "City", null);            // auto-classified NONE

        job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setTemplateId(template.getTemplateId());
        job.setFileName("c2-upload-" + tag + ".csv");
        job.setParsedTableJson(table("nic no", "acct", "e-mail", "town"));
        job.setMappedRowsJson(table("CustomerNic", "AccountNumber", "Email", "City"));
        job.setValidationResultJson("[]");
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        job = jobs.save(job);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private AppUser user(String name, Role role) {
        AppUser user = new AppUser();
        user.setEmail(name + "@example.com");
        user.setPasswordHash("{noop}unused");
        user.setDisplayName(name);
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        return users.save(user);
    }

    private TemplateFields field(Template template, String name, PiiKind kind) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(template.getTemplateId());
        field.setBankId(bank.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(1);
        field.setPiiKind(kind);
        return templateFields.save(field);
    }

    /** One row of raw PII under the given column names. */
    private static String table(String nicCol, String acctCol, String emailCol, String cityCol) {
        return "{\"columns\":[\"" + nicCol + "\",\"" + acctCol + "\",\"" + emailCol + "\",\""
                + cityCol + "\"],\"rows\":[{\"" + nicCol + "\":\"" + RAW_NIC + "\",\""
                + acctCol + "\":\"" + RAW_ACCOUNT + "\",\"" + emailCol + "\":\"" + RAW_EMAIL
                + "\",\"" + cityCol + "\":\"Curepipe\"}]}";
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private byte[] export(String format) throws Exception {
        MvcResult result = mvc.perform(
                        get("/api/ingestion/jobs/" + job.getIngestionJobId() + "/export/file")
                                .param("format", format)
                                .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    private static void assertMaskedAndNoRawValues(String content, String channel) {
        assertThat(content).as("%s shows masked values", channel)
                .contains(MASKED_NIC).contains(MASKED_ACCOUNT).contains(MASKED_EMAIL)
                .contains("Curepipe");
        assertThat(content).as("%s carries zero raw PII", channel)
                .doesNotContain(RAW_NIC).doesNotContain(RAW_ACCOUNT).doesNotContain(RAW_EMAIL);
    }

    // ---- (1) EXPORT: file formats, archive copy, database --------------------

    @Test
    void exportChannelMasksEveryFormatTheArchiveCopyAndTheDatabaseExport() throws Exception {
        // CSV and JSON, parsed.
        String csv = new String(export("csv"), StandardCharsets.UTF_8);
        assertMaskedAndNoRawValues(csv, "csv export");

        String json = new String(export("json"), StandardCharsets.UTF_8);
        assertMaskedAndNoRawValues(json, "json export");
        JsonNode rows = MAPPER.readTree(json);
        assertThat(rows.get(0).get("CustomerNic").asText()).isEqualTo(MASKED_NIC);
        assertThat(rows.get(0).get("AccountNumber").asText()).isEqualTo(MASKED_ACCOUNT);
        assertThat(rows.get(0).get("Email").asText()).isEqualTo(MASKED_EMAIL);
        assertThat(rows.get(0).get("City").asText()).isEqualTo("Curepipe");

        // XLSX, parsed cell by cell.
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export("xlsx")))) {
            List<String> cells = new ArrayList<>();
            workbook.getSheetAt(0).forEach(row ->
                    row.forEach(cell -> cells.add(cell.getStringCellValue())));
            assertMaskedAndNoRawValues(String.join("\n", cells), "xlsx export");
        }

        // The per-owner archive copy is the same masked bytes.
        Path archived = STORAGE.resolve("exports").resolve("u" + owner.getAppUserId())
                .resolve("job" + job.getIngestionJobId() + ".csv");
        assertThat(archived).exists();
        assertMaskedAndNoRawValues(Files.readString(archived), "archive copy");

        // Database export: the same row transform lands in the target table.
        String targetDb = "jdbc:h2:mem:ws12c2db" + job.getIngestionJobId() + ";DB_CLOSE_DELAY=-1";
        mvc.perform(post("/api/ingestion/jobs/" + job.getIngestionJobId() + "/export/database")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdbcUrl\":\"" + targetDb
                                + "\",\"tableName\":\"ExportedRows\"}"))
                .andExpect(status().isOk());
        try (Connection connection = DriverManager.getConnection(targetDb);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM \"ExportedRows\"")) {
            assertThat(rs.next()).isTrue();
            StringBuilder all = new StringBuilder();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                all.append(rs.getString(c)).append('\n');
            }
            assertMaskedAndNoRawValues(all.toString(), "database export");
        }

        // PRIV-004: the untagged fields' first classification was persisted
        // (visible and editable in the template editor from now on).
        assertThat(templateFields.findById(emailField.getTemplateFieldsId()).orElseThrow()
                .getPiiKind()).isEqualTo(PiiKind.EMAIL);
        assertThat(templateFields.findById(cityField.getTemplateFieldsId()).orElseThrow()
                .getPiiKind()).isEqualTo(PiiKind.NONE);
    }

    // ---- (2) REPORT: the generated document ----------------------------------

    @Test
    void reportChannelMasksPiiShapesInGeneratedDocuments() throws Exception {
        authenticateAs(owner);

        BlueprintDto blueprint = blueprints.create(new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                blueprintDocx()), "C2 Report", null);
        int instanceId = instances.create(blueprint.blueprintId(), "C2 instance").instanceId();
        int sectionId = blueprint.sections().get(0).sectionId();

        AddBlockRequest paragraph = new AddBlockRequest();
        paragraph.setBlockType("PARAGRAPH");
        paragraph.setText("Issued to " + RAW_NIC + ", account " + RAW_ACCOUNT
                + ", contact " + RAW_EMAIL + ", of Curepipe.");
        instances.addBlock(instanceId, sectionId, paragraph);

        GenerationResultDto generated = instances.generate(instanceId);
        Path docx = STORAGE.resolve("exports").resolve(generated.docxFileName());
        assertThat(docx).exists();

        String text = String.join("\n", paragraphTexts(Files.readAllBytes(docx)));
        assertMaskedAndNoRawValues(text, "generated document");
        assertThat(text).contains("Issued to " + MASKED_NIC + ", account " + MASKED_ACCOUNT
                + ", contact " + MASKED_EMAIL + ", of Curepipe.");

        // The STORED block keeps the user's raw text: masking is render-time
        // only (the builder screen and DSR search still see the true values).
        assertThat(instances.getBuilder(instanceId).sections().stream()
                .flatMap(s -> s.blocks().stream())
                .anyMatch(b -> b.text() != null && b.text().contains(RAW_NIC)))
                .isTrue();
    }

    // ---- (3+4) SCREENS: READ_ONLY masked, the working wizard raw -------------

    @Test
    void readOnlyScreenMasksTheOwnersJobWhileTheWorkingScreenStaysRaw() throws Exception {
        // SCREEN_WORKING: the owner (READ_WRITE) edits true values.
        JsonNode working = jobDetail(bearer(owner));
        assertThat(working.get("mappedRows").get("rows").get(0).get("CustomerNic").asText())
                .isEqualTo(RAW_NIC);
        assertThat(working.get("parsedTable").get("rows").get(0).get("nic no").asText())
                .isEqualTo(RAW_NIC);

        // SCREEN_READONLY: the same owner, downgraded to READ_ONLY (the one
        // role/ownership combination that reaches this screen — ownership
        // 404s every cross-user read before masking is consulted).
        owner.setRole(Role.READ_ONLY);
        owner = users.save(owner);
        JsonNode readOnly = jobDetail(bearer(owner));
        JsonNode mappedRow = readOnly.get("mappedRows").get("rows").get(0);
        assertThat(mappedRow.get("CustomerNic").asText()).isEqualTo(MASKED_NIC);
        assertThat(mappedRow.get("AccountNumber").asText()).isEqualTo(MASKED_ACCOUNT);
        assertThat(mappedRow.get("Email").asText()).isEqualTo(MASKED_EMAIL);
        assertThat(mappedRow.get("City").asText()).isEqualTo("Curepipe");
        // The parsed (pre-mapping) payload masks by header+shape inference.
        JsonNode parsedRow = readOnly.get("parsedTable").get("rows").get(0);
        assertThat(parsedRow.get("nic no").asText()).isEqualTo(MASKED_NIC);
        assertThat(parsedRow.get("town").asText()).isEqualTo("Curepipe");
        assertThat(readOnly.toString())
                .doesNotContain(RAW_NIC).doesNotContain(RAW_ACCOUNT).doesNotContain(RAW_EMAIL);

        // The list endpoint masks the same way.
        MvcResult list = mvc.perform(get("/api/ingestion/jobs")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(list.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(RAW_NIC).doesNotContain(RAW_ACCOUNT);

        // Restored to READ_WRITE, the working screen is raw again.
        owner.setRole(Role.READ_WRITE);
        owner = users.save(owner);
        assertThat(jobDetail(bearer(owner)).get("mappedRows").get("rows").get(0)
                .get("CustomerNic").asText()).isEqualTo(RAW_NIC);
    }

    // ---- (5) DSR: disclosure stays complete -----------------------------------

    @Test
    void dsrExportStaysUnmaskedByDesign() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/dsr/export")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + RAW_NIC + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String exportJson = zipEntry(result.getResponse().getContentAsByteArray(),
                "dsr-export.json");
        assertThat(exportJson).contains(RAW_NIC).contains(RAW_ACCOUNT);
        JsonNode export = MAPPER.readTree(exportJson);
        assertThat(export.get("totalMatches").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(export.get("stores").get("ingestionJobs").get(0)
                .get("mappedRows").get(0).get("CustomerNic").asText()).isEqualTo(RAW_NIC);
    }

    // ---- helpers ---------------------------------------------------------------

    private JsonNode jobDetail(String authorization) throws Exception {
        MvcResult result = mvc.perform(get("/api/ingestion/jobs/" + job.getIngestionJobId())
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String zipEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("ZIP entry " + name + " not found");
    }

    private void authenticateAs(AppUser user) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(String.valueOf(user.getAppUserId()))
                .claim("bank_id", user.getBankId())
                .claim("roles", List.of(user.getRole().name()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static byte[] blueprintDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("C2 Masking Report");
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Details");
            doc.createParagraph().createRun().setText("Standard detail notes.");
            XWPFParagraph signOff = doc.createParagraph();
            signOff.setStyle("Heading1");
            signOff.createRun().setText("Sign-off");
            doc.createParagraph().createRun().setText("Prepared by the team.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> paragraphTexts(byte[] docx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<String> texts = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    texts.add(p.getText());
                }
            }
            return texts;
        }
    }
}
