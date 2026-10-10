package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE B8 GATE (SEC-012/SEC-024, report-release separation of duties) over
 * real HTTP semantics: the author of a report cannot release it — EVEN WHEN
 * THEY ALSO HOLD THE APPROVER ROLE. The author generates and submits; their
 * own release attempt (after being moved to APPROVER, so the route-level
 * doc:release check passes) is a hard 409 with a DOC_SELF_RELEASE_BLOCKED
 * (DENIED) audit row and the document stays SUBMITTED; a DIFFERENT approver
 * releases it, and the DOC_RELEASED audit event carries the aiComposed flag
 * plus the review comment (C10 — the recorded human review of AI-written
 * content). Reject-for-rework, the review download's exact authorization
 * boundary, and the SEC-024 layer-3 nightly scan (clean data scans to zero;
 * a manufactured violation is found and audited) are proven here too.
 */
@SpringBootTest(properties = {
        "modocument.doccreation.section-ai-enabled=false",
        "modocument.doccreation.generate-ai-enabled=false",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocReleaseSodIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("modocument.storage.root", () -> storageRoot.toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    private Bank bankA;
    private Bank bankB;
    private AppUser author;
    private AppUser approver;
    private AppUser admin;
    private AppUser thirdWriter;
    private AppUser foreignApprover;
    private final List<Integer> createdUserIds = new ArrayList<>();

    @BeforeEach
    void seedBankWithAuthorApproverAdminAndBystanders() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bankA = banks.save(new Bank("RLA" + tag.substring(0, 4).toUpperCase(), "Release Bank A"));
        bankB = banks.save(new Bank("RLB" + tag.substring(4).toUpperCase(), "Release Bank B"));
        author = storeUser("rel-author-" + tag, bankA.getBankId(), Role.READ_WRITE);
        approver = storeUser("rel-approver-" + tag, bankA.getBankId(), Role.APPROVER);
        admin = storeUser("rel-admin-" + tag, bankA.getBankId(), Role.ADMIN);
        thirdWriter = storeUser("rel-third-" + tag, bankA.getBankId(), Role.READ_WRITE);
        foreignApprover = storeUser("rel-foreign-" + tag, bankB.getBankId(), Role.APPROVER);
    }

    @AfterEach
    void cleanUp() {
        for (Bank bank : List.of(bankA, bankB)) {
            jdbc.update("DELETE FROM PendingChange WHERE BankId = ?", bank.getBankId());
            jdbc.update("DELETE FROM DocContentBlock WHERE BankId = ?", bank.getBankId());
            jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bank.getBankId());
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bank.getBankId());
        }
        for (Integer userId : createdUserIds) {
            jdbc.update("DELETE FROM DocBlueprintSection WHERE BlueprintId IN "
                    + "(SELECT DocBlueprintId FROM DocBlueprint WHERE OwnerUserId = ?)", userId);
            jdbc.update("DELETE FROM DocBlueprint WHERE OwnerUserId = ?", userId);
            users.deleteById(userId);
        }
        createdUserIds.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    // ------------------------------------------------------------- fixtures

    private AppUser storeUser(String emailPrefix, int bankId, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(emailPrefix + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(emailPrefix);
        entity.setBankId(bankId);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        createdUserIds.add(saved.getAppUserId());
        return saved;
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private static byte[] blueprintDocx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("MO Intelligence — Compliance Report");
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Findings");
            doc.createParagraph().createRun().setText("Standard findings notes.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** Uploads a blueprint, builds an instance with one paragraph, generates. */
    private int generatedInstance(AppUser owner, String paragraph) throws Exception {
        MvcResult uploaded = mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "report.docx", DOCX_CONTENT_TYPE,
                                blueprintDocx()))
                        .param("name", "Release blueprint " + UUID.randomUUID())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isCreated())
                .andReturn();
        int blueprintId = MAPPER.readTree(uploaded.getResponse().getContentAsString())
                .get("blueprintId").asInt();

        MvcResult created = mvc.perform(post("/api/doccreation/instances")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId
                                + ",\"title\":\"Compliance report\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        int instanceId = MAPPER.readTree(created.getResponse().getContentAsString())
                .get("instanceId").asInt();

        MvcResult builder = mvc.perform(get("/api/doccreation/instances/" + instanceId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        int sectionId = MAPPER.readTree(builder.getResponse().getContentAsString())
                .get("sections").get(0).get("sectionId").asInt();

        mvc.perform(post("/api/doccreation/instances/" + instanceId
                        + "/sections/" + sectionId + "/blocks")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockType\":\"PARAGRAPH\",\"text\":\""
                                + paragraph + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/generate")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        return instanceId;
    }

    private void submit(AppUser owner, int instanceId) throws Exception {
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/submit")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    private String statusInDb(int instanceId) {
        return jdbc.queryForObject("SELECT Status FROM DocInstance WHERE DocInstanceId = ?",
                String.class, instanceId);
    }

    private int auditCount(String action, String entityId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND EntityId = ?",
                Integer.class, action, entityId);
    }

    // --------------------------------------------------------- THE B8 GATE

    @Test
    void theAuthorCannotReleaseEvenAsApproverADifferentApproverCan() throws Exception {
        int instanceId = generatedInstance(author, "The model wrote this paragraph.");
        submit(author, instanceId);
        assertThat(auditCount("DOC_SUBMITTED", String.valueOf(instanceId))).isOne();
        // Simulate an AI-composed generation (the H2 suite runs with the AI
        // path disabled; generate() sets the flag whenever the composer
        // produced the content).
        jdbc.update("UPDATE DocInstance SET AiComposed = TRUE WHERE DocInstanceId = ?",
                instanceId);

        // C10: the approver's queue carries the aiComposed flag and the
        // actual content blocks — the human sees what the model wrote.
        MvcResult queue = mvc.perform(get("/api/doccreation/instances/pending-release")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entry = findEntry(queue, instanceId);
        assertThat(entry.get("aiComposed").asBoolean()).isTrue();
        assertThat(entry.get("ownerEmail").asText()).isEqualTo(author.getEmail());
        assertThat(entry.toString()).contains("The model wrote this paragraph.");

        // THE GATE: the author now ALSO holds the Approver role (single role
        // per user — the role is replaced, like a promotion), so the
        // route-level doc:release check passes...
        author.setRole(Role.APPROVER);
        users.save(author);
        MvcResult refused = mvc.perform(post("/api/doccreation/instances/" + instanceId
                        + "/release")
                        .header("Authorization", bearer(users.findById(author.getAppUserId())
                                .orElseThrow()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"my own work is fine\"}"))
                // ...and the RUNTIME SoD layer still refuses with a hard 409.
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(MAPPER.readTree(refused.getResponse().getContentAsString())
                .get("error").asText()).contains("Self-release is blocked");
        // The attempt is evidence: DOC_SELF_RELEASE_BLOCKED, outcome DENIED,
        // written in its own transaction so the 409 could not roll it back.
        Map<String, Object> auditRow = jdbc.queryForMap(
                "SELECT Outcome, ActorUserId FROM AuditEvent "
                        + "WHERE Action = 'DOC_SELF_RELEASE_BLOCKED' AND EntityId = ?",
                String.valueOf(instanceId));
        assertThat(auditRow.get("Outcome")).isEqualTo("DENIED");
        assertThat(auditRow.get("ActorUserId")).isEqualTo(author.getAppUserId());
        // Still awaiting a REAL second pair of eyes.
        assertThat(statusInDb(instanceId)).isEqualTo("SUBMITTED");

        // A DIFFERENT approver releases — with the recorded human review.
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/release")
                        .header("Authorization", bearer(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"figures verified against the ledger\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.aiComposed").value(true))
                .andExpect(jsonPath("$.reviewComment")
                        .value("figures verified against the ledger"));
        Map<String, Object> released = jdbc.queryForMap(
                "SELECT ReleasedByUserId, ReviewComment FROM DocInstance "
                        + "WHERE DocInstanceId = ?", instanceId);
        assertThat(released.get("ReleasedByUserId")).isEqualTo(approver.getAppUserId());
        // The C10 evidence: the audit row carries aiComposed and the comment.
        String newValues = jdbc.queryForObject(
                "SELECT NewValues FROM AuditEvent WHERE Action = 'DOC_RELEASED' "
                        + "AND EntityId = ?", String.class, String.valueOf(instanceId));
        assertThat(newValues).contains("\"aiComposed\":true")
                .contains("figures verified against the ledger");

        // A released document cannot be decided again.
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/release")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------- reject path

    @Test
    void rejectionReturnsTheDocumentToGeneratedForRework() throws Exception {
        int instanceId = generatedInstance(author, "Draft paragraph.");
        submit(author, instanceId);

        // The submitter cannot reject their own submission either (same B8
        // check — a party to the document decides nothing about it).
        author.setRole(Role.APPROVER);
        users.save(author);
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/reject")
                        .header("Authorization", bearer(users.findById(author.getAppUserId())
                                .orElseThrow())))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/reject")
                        .header("Authorization", bearer(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"section 2 needs the Q3 figures\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.reviewComment").value("section 2 needs the Q3 figures"));
        assertThat(statusInDb(instanceId)).isEqualTo("GENERATED");
        assertThat(auditCount("DOC_RELEASE_REJECTED", String.valueOf(instanceId))).isOne();

        // The author sees the reviewer's comment on their working copy...
        mvc.perform(get("/api/doccreation/instances/" + instanceId)
                        .header("Authorization", bearer(users.findById(author.getAppUserId())
                                .orElseThrow())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewComment").value("section 2 needs the Q3 figures"));

        // ...and a resubmission (back as the authoring role — doc:generate)
        // starts a fresh review cycle: the old comment is spent.
        author.setRole(Role.READ_WRITE);
        users.save(author);
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/submit")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertThat(jdbc.queryForObject("SELECT ReviewComment FROM DocInstance "
                + "WHERE DocInstanceId = ?", String.class, instanceId)).isNull();
    }

    // ------------------------------------------------- review download rules

    @Test
    void onlySameBankApproversDownloadSubmittedDocumentsForReview() throws Exception {
        int instanceId = generatedInstance(author, "Review me.");
        submit(author, instanceId);

        // The approver reviews the document exactly as generated.
        MvcResult download = mvc.perform(get("/api/doccreation/instances/" + instanceId
                        + "/review-file")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentType()).isEqualTo(DOCX_CONTENT_TYPE);
        assertThat(download.getResponse().getContentAsByteArray()).isNotEmpty();

        // A third READ_WRITE user of the same bank: no doc:release → 403 on
        // the review route, and the owner-scoped download stays a 404.
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/review-file")
                        .header("Authorization", bearer(thirdWriter)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/download")
                        .header("Authorization", bearer(thirdWriter)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/submit")
                        .header("Authorization", bearer(thirdWriter)))
                .andExpect(status().isNotFound());

        // Another bank's approver: the document does not exist for them.
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/review-file")
                        .header("Authorization", bearer(foreignApprover)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/release")
                        .header("Authorization", bearer(foreignApprover)))
                .andExpect(status().isNotFound());
        MvcResult foreignQueue = mvc.perform(get("/api/doccreation/instances/pending-release")
                        .header("Authorization", bearer(foreignApprover)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(foreignQueue.getResponse().getContentAsString())
                .doesNotContain("\"instanceId\":" + instanceId);

        // The AUTHOR keeps their working copy in any status.
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/download")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isOk());
        assertThat(statusInDb(instanceId)).isEqualTo("SUBMITTED");
    }

    @Test
    void anUngeneratedDraftCannotBeSubmitted() throws Exception {
        MvcResult uploaded = mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "report.docx", DOCX_CONTENT_TYPE,
                                blueprintDocx()))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isCreated())
                .andReturn();
        int blueprintId = MAPPER.readTree(uploaded.getResponse().getContentAsString())
                .get("blueprintId").asInt();
        MvcResult created = mvc.perform(post("/api/doccreation/instances")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Draft\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        int instanceId = MAPPER.readTree(created.getResponse().getContentAsString())
                .get("instanceId").asInt();

        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/submit")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isConflict());
        assertThat(statusInDb(instanceId)).isEqualTo("DRAFT");
    }

    // ------------------------------------------- SEC-024 layer 3: the scan

    @Test
    void theNightlyScanIsCleanUntilAViolationIsManufacturedAndThenAuditsIt()
            throws Exception {
        int instanceId = generatedInstance(author, "Scanned paragraph.");
        submit(author, instanceId);
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/release")
                        .header("Authorization", bearer(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk());

        // Legitimately released data scans clean.
        mvc.perform(post("/api/admin/sod/scan").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourBank.findings").value(0));
        String completed = jdbc.queryForObject(
                "SELECT NewValues FROM AuditEvent WHERE Action = 'SOD_SCAN_COMPLETED' "
                        + "AND BankId = ? ORDER BY OccurredAt DESC FETCH FIRST 1 ROW ONLY",
                String.class, bankA.getBankId());
        assertThat(completed).contains("\"findings\":0");

        // MANUFACTURE what the runtime gates make impossible (a direct DB
        // write, as an attacker/bug would): a self-released document and a
        // self-approved change.
        jdbc.update("UPDATE DocInstance SET ReleasedByUserId = OwnerUserId "
                + "WHERE DocInstanceId = ?", instanceId);
        jdbc.update("INSERT INTO PendingChange (BankId, ChangeType, PayloadJson, Status, "
                        + "MakerUserId, CheckerUserId, RequestedAt) "
                        + "VALUES (?, 'USER_DISABLE', '{}', 'APPLIED', ?, ?, ?)",
                bankA.getBankId(), admin.getAppUserId(), admin.getAppUserId(),
                LocalDateTime.now());

        // The scan finds BOTH and writes the alerting evidence.
        mvc.perform(post("/api/admin/sod/scan").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourBank.selfReleasedDocs").value(1))
                .andExpect(jsonPath("$.yourBank.selfApprovedChanges").value(1))
                .andExpect(jsonPath("$.yourBank.findings").value(2));
        assertThat(jdbc.queryForObject(
                "SELECT Outcome FROM AuditEvent "
                        + "WHERE Action = 'SOD_VIOLATION_FOUND' AND EntityType = 'DocInstance' "
                        + "AND EntityId = ?", String.class, String.valueOf(instanceId)))
                .isEqualTo("DENIED");
        assertThat(jdbc.queryForObject(
                "SELECT NewValues FROM AuditEvent "
                        + "WHERE Action = 'SOD_VIOLATION_FOUND' AND EntityType = 'DocInstance' "
                        + "AND EntityId = ?", String.class, String.valueOf(instanceId)))
                .contains("DOC_RELEASE");
        Integer changeViolations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'SOD_VIOLATION_FOUND' "
                        + "AND EntityType = 'PendingChange' AND BankId = ?",
                Integer.class, bankA.getBankId());
        assertThat(changeViolations).isOne();

        // The scan only verifies — a USER_ADMIN-less caller cannot run it.
        mvc.perform(post("/api/admin/sod/scan").header("Authorization", bearer(approver)))
                .andExpect(status().isForbidden());
    }

    private static JsonNode findEntry(MvcResult queue, int instanceId) throws Exception {
        JsonNode rows = MAPPER.readTree(queue.getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if (row.get("instanceId").asInt() == instanceId) {
                return row;
            }
        }
        throw new AssertionError("Instance " + instanceId + " not in the pending-release queue: "
                + rows);
    }
}
