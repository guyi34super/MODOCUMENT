package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AlertStatus;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.PendingChangeJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.UserMfaJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/admin/dashboard (phase 2, ws22): every counter matches what was
 * seeded for the caller's bank, NOTHING of a second bank leaks into it
 * (AppUsers/UserMfa are not under RLS, so the explicit BankId filters are
 * the only boundary there — and the only one at all on H2), the response
 * carries no row content (no emails, names, template names, other banks'
 * codes), the per-bank cache never serves one bank's numbers to another,
 * and only METRICS_READ (ADMIN) may read it.
 *
 * <p>Bank B is seeded with MORE of everything than bank A, so any missing
 * bank filter shows up as an inflated count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDashboardIsolationTest {

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
    private UserMfaJpaRepository userMfa;
    @Autowired
    private IngestionJobJpaRepository ingestionJobs;
    @Autowired
    private AlertLogJpaRepository alerts;
    @Autowired
    private PendingChangeJpaRepository pendingChanges;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private DocInstanceJpaRepository docInstances;
    @Autowired
    private ExtractionJobStore extractionJobs;
    @Autowired
    private AuditRecorder audit;

    private String tag;
    private Bank bankA;
    private Bank bankB;
    private AppUser adminA;
    private AppUser adminB;
    private final List<AppUser> createdUsers = new ArrayList<>();
    private final List<Object> createdRows = new ArrayList<>();

    @BeforeEach
    void seedBanks() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bankA = banks.save(new Bank("DBA" + tag.substring(0, 4).toUpperCase(),
                "Dashboard Bank A"));
        bankB = banks.save(new Bank("DBB" + tag.substring(4).toUpperCase(),
                "Dashboard Bank B"));
        adminA = user("dash-admin-a", bankA, Role.ADMIN);
        adminB = user("dash-admin-b", bankB, Role.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        for (Object row : createdRows) {
            if (row instanceof IngestionJob job) {
                ingestionJobs.deleteById(job.getIngestionJobId());
            } else if (row instanceof AlertLog alert) {
                alerts.deleteById(alert.getAlertLogId());
            } else if (row instanceof PendingChange change) {
                pendingChanges.deleteById(change.getPendingChangeId());
            } else if (row instanceof Template template) {
                templates.deleteById(template.getTemplateId());
            } else if (row instanceof DocInstance instance) {
                docInstances.deleteById(instance.getDocInstanceId());
            } else if (row instanceof UserMfa mfa) {
                userMfa.deleteById(mfa.getUserId());
            }
        }
        createdRows.clear();
        createdUsers.forEach(user -> users.deleteById(user.getAppUserId()));
        createdUsers.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    @Test
    void countsMatchTheCallersBankAndNothingOfTheOtherBankLeaks() throws Exception {
        // ---- bank A: the numbers we expect back
        mfa(adminA);
        AppUser secondAdminA = user("dash-admin2-a", bankA, Role.ADMIN); // no MFA
        AppUser writerA = user("dash-writer-a", bankA, Role.READ_WRITE);
        writerA.setLockedUntil(LocalDateTime.now().plusMinutes(20));
        writerA.setMustChangePassword(true);
        writerA.setLastLoginAt(LocalDateTime.now().minusDays(2));
        users.save(writerA);
        AppUser leaverA = user("dash-leaver-a", bankA, Role.READ_ONLY);
        leaverA.setDisabled(true);
        leaverA.setLastLoginAt(LocalDateTime.now().minusDays(40));
        users.save(leaverA);
        // An expired lock is not "locked".
        secondAdminA.setLockedUntil(LocalDateTime.now().minusMinutes(5));
        users.save(secondAdminA);

        ingestion(bankA, adminA, JobStatus.COMPLETED, LocalDateTime.now());
        ingestion(bankA, adminA, JobStatus.COMPLETED, LocalDateTime.now());
        ingestion(bankA, writerA, JobStatus.FAILED, LocalDateTime.now().minusDays(3));
        ingestion(bankA, writerA, JobStatus.FAILED, LocalDateTime.now().minusDays(10));
        extraction(bankA, adminA, ExtractionJobStatus.RUNNING, Instant.now());
        extraction(bankA, writerA, ExtractionJobStatus.COMPLETED,
                Instant.now().minus(2, ChronoUnit.DAYS));
        extraction(bankA, writerA, ExtractionJobStatus.FAILED,
                Instant.now().minus(12, ChronoUnit.DAYS));
        Instant newestAlertA = Instant.now().minus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MILLIS);
        alert(bankA, AlertSeverity.HIGH, newestAlertA);
        alert(bankA, AlertSeverity.MEDIUM, Instant.now().minus(9, ChronoUnit.DAYS));
        pending(bankA, adminA, ChangeType.USER_ROLE_CHANGE);
        pending(bankA, adminA, ChangeType.SETTING_UPDATE);
        template(bankA, adminA, "Secret Template A1");
        template(bankA, writerA, "Secret Template A2");
        docInstance(bankA, writerA, DocInstance.STATUS_SUBMITTED);
        docInstance(bankA, writerA, DocInstance.STATUS_RELEASED);
        retentionRun(bankA, 3, 2);

        // ---- bank B: MORE of everything, all of it must stay invisible to A
        mfa(adminB);
        for (int i = 0; i < 4; i++) {
            AppUser b = user("dash-b" + i, bankB, Role.ADMIN);
            b.setLockedUntil(LocalDateTime.now().plusMinutes(20));
            b.setMustChangePassword(true);
            b.setLastLoginAt(LocalDateTime.now());
            users.save(b);
            mfa(b);
            ingestion(bankB, b, JobStatus.FAILED, LocalDateTime.now());
            extraction(bankB, b, ExtractionJobStatus.FAILED, Instant.now());
            alert(bankB, AlertSeverity.CRITICAL, Instant.now());
            pending(bankB, adminB, ChangeType.SETTING_UPDATE);
            template(bankB, b, "Secret Template B" + i);
            docInstance(bankB, b, DocInstance.STATUS_SUBMITTED);
        }
        retentionRun(bankB, 50, 49);

        String json = mvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = MAPPER.readTree(json);

        assertThat(body.get("bank").get("code").asText()).isEqualTo(bankA.getCode());
        assertThat(body.get("bank").get("name").asText()).isEqualTo("Dashboard Bank A");

        JsonNode u = body.get("users");
        assertThat(u.get("total").asLong()).isEqualTo(4);
        assertThat(u.get("active").asLong()).isEqualTo(3);
        assertThat(u.get("disabled").asLong()).isEqualTo(1);
        assertThat(u.get("locked").asLong()).isEqualTo(1);
        assertThat(u.get("mustChangePassword").asLong()).isEqualTo(1);
        assertThat(map(u.get("byRole"))).isEqualTo(Map.of(
                "ADMIN", 2L, "APPROVER", 0L, "READ_WRITE", 1L, "READ_ONLY", 1L));
        assertThat(u.get("mfaEnrolled").asLong()).isEqualTo(1);
        assertThat(u.get("adminsWithoutMfa").asLong()).isEqualTo(1);
        assertThat(u.get("activeLast7d").asLong()).isEqualTo(1);

        assertThat(body.get("approvals").get("pendingChanges").asLong()).isEqualTo(2);
        assertThat(body.get("approvals").get("pendingReleases").asLong()).isEqualTo(1);

        JsonNode ingestion = body.get("ingestion");
        assertThat(ingestion.get("today").get("byStatus").get("COMPLETED").asLong())
                .isEqualTo(2);
        assertThat(ingestion.get("today").get("byStatus").get("FAILED").asLong()).isZero();
        assertThat(ingestion.get("last7d").get("byStatus").get("COMPLETED").asLong())
                .isEqualTo(2);
        assertThat(ingestion.get("last7d").get("byStatus").get("FAILED").asLong())
                .isEqualTo(1);
        assertThat(ingestion.get("failures7d").asLong()).isEqualTo(1);
        assertThat(map(ingestion.get("parsedBy")))
                .isEqualTo(Map.of("AI", 0L, "CODE", 0L, "AI_PARTIAL", 0L));

        JsonNode extraction = body.get("extraction").get("last7d").get("byStatus");
        assertThat(extraction.get("RUNNING").asLong()).isEqualTo(1);
        assertThat(extraction.get("COMPLETED").asLong()).isEqualTo(1);
        assertThat(extraction.get("FAILED").asLong()).isZero();

        JsonNode alertsNode = body.get("alerts");
        assertThat(alertsNode.get("last7dBySeverity").get("HIGH").asLong()).isEqualTo(1);
        assertThat(alertsNode.get("last7dBySeverity").get("MEDIUM").asLong()).isZero();
        assertThat(alertsNode.get("last7dBySeverity").get("CRITICAL").asLong()).isZero();
        assertThat(Instant.parse(alertsNode.get("lastAt").asText())).isEqualTo(newestAlertA);

        JsonNode retention = body.get("retention");
        assertThat(retention.get("days").asInt()).isEqualTo(30);
        assertThat(retention.get("lastRunAt").isNull()).isFalse();
        assertThat(retention.get("lastPurged").asLong()).isEqualTo(5);

        assertThat(body.get("templates").get("private").asLong()).isEqualTo(2);
        assertThat(body.get("templates").get("shared").asLong()).isZero();
        assertThat(body.get("settings").get("pendingSecurityChanges").asLong()).isEqualTo(1);
        assertThat(body.get("integrations").get("runs24h").get("failed").asLong()).isZero();

        // Counts only: no account, template or other-bank identifier anywhere.
        assertThat(json)
                .doesNotContain("@example.com")
                .doesNotContain("dash-")
                .doesNotContain("Secret Template")
                .doesNotContain(bankB.getCode())
                .doesNotContain("Dashboard Bank B");

        // And bank B sees its own numbers, not A's cached ones.
        JsonNode bodyB = MAPPER.readTree(mvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(bodyB.get("bank").get("code").asText()).isEqualTo(bankB.getCode());
        assertThat(bodyB.get("users").get("total").asLong()).isEqualTo(5);
        assertThat(bodyB.get("users").get("mfaEnrolled").asLong()).isEqualTo(5);
        assertThat(bodyB.get("templates").get("private").asLong()).isEqualTo(4);
        assertThat(bodyB.get("retention").get("lastPurged").asLong()).isEqualTo(99);
    }

    @Test
    void theNumbersAreCachedPerBankForThirtySeconds() throws Exception {
        template(bankA, adminA, "Cached Template 1");
        JsonNode first = dashboard(adminA);
        assertThat(first.get("templates").get("private").asLong()).isEqualTo(1);

        // Within the TTL the same snapshot is served, even after a change.
        template(bankA, adminA, "Cached Template 2");
        JsonNode second = dashboard(adminA);
        assertThat(second.get("generatedAt").asText())
                .isEqualTo(first.get("generatedAt").asText());
        assertThat(second.get("templates").get("private").asLong()).isEqualTo(1);

        // Another bank's first call computes its own numbers.
        assertThat(dashboard(adminB).get("templates").get("private").asLong()).isZero();
    }

    @Test
    void onlyMetricsReadersMayReadIt() throws Exception {
        AppUser writer = user("dash-writer", bankA, Role.READ_WRITE);
        AppUser approver = user("dash-approver", bankA, Role.APPROVER);
        mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(approver)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ seeding

    private JsonNode dashboard(AppUser caller) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
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
        AppUser saved = users.save(entity);
        createdUsers.add(saved);
        return saved;
    }

    private void mfa(AppUser user) {
        UserMfa row = new UserMfa();
        row.setUserId(user.getAppUserId());
        row.setSecretCiphertext("unused");
        row.setEnabledAt(Instant.now());
        row.setCreatedAt(Instant.now());
        createdRows.add(userMfa.save(row));
    }

    private void ingestion(Bank bank, AppUser owner, JobStatus status, LocalDateTime created) {
        IngestionJob job = new IngestionJob();
        job.setBankId(bank.getBankId());
        job.setOwnerUserId(owner.getAppUserId());
        job.setTemplateId(1);
        job.setFileName("customer-file-" + tag + ".csv");
        job.setStatus(status);
        job.setCreatedDate(created);
        job.setUpdatedDate(created);
        createdRows.add(ingestionJobs.save(job));
    }

    private void extraction(Bank bank, AppUser owner, ExtractionJobStatus status,
                            Instant created) {
        ExtractionJob job = new ExtractionJob();
        job.setId(UUID.randomUUID().toString());
        job.setBankId(bank.getBankId());
        job.setOwnerUserId(owner.getAppUserId());
        job.setSourceId("source-" + tag);
        job.setStatus(status);
        job.setCreatedDate(created);
        extractionJobs.save(job);
    }

    private void alert(Bank bank, AlertSeverity severity, Instant created) {
        AlertLog alert = new AlertLog();
        alert.setBankId(bank.getBankId());
        alert.setRuleName("dash-rule");
        alert.setSeverity(severity);
        alert.setDedupeKey(UUID.randomUUID().toString().replace("-", ""));
        alert.setSummary("dash alert " + tag);
        alert.setCreatedAt(created);
        alert.setStatus(AlertStatus.SENT);
        createdRows.add(alerts.save(alert));
    }

    private void pending(Bank bank, AppUser maker, ChangeType type) {
        PendingChange change = new PendingChange();
        change.setBankId(bank.getBankId());
        change.setChangeType(type);
        change.setTargetEntityType(type.targetEntityType());
        change.setPayloadJson("{\"targetEmail\":\"" + maker.getEmail() + "\"}");
        change.setStatus(PendingChangeStatus.REQUESTED);
        change.setMakerUserId(maker.getAppUserId());
        change.setRequestedAt(LocalDateTime.now());
        createdRows.add(pendingChanges.save(change));
    }

    private void template(Bank bank, AppUser owner, String name) {
        Template template = new Template(0, name + " " + tag, "d");
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(bank.getBankId());
        createdRows.add(templates.save(template));
    }

    private void docInstance(Bank bank, AppUser owner, String status) {
        DocInstance instance = new DocInstance();
        instance.setBankId(bank.getBankId());
        instance.setOwnerUserId(owner.getAppUserId());
        instance.setTitle("Secret Template doc " + tag);
        instance.setStatus(status);
        instance.setCreatedDate(LocalDateTime.now());
        instance.setUpdatedDate(LocalDateTime.now());
        createdRows.add(docInstances.save(instance));
    }

    /** The per-bank RETENTION_PURGE_COMPLETED evidence the purger writes. */
    private void retentionRun(Bank bank, int uploadedFiles, int ingestionJobsPurged) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("retentionDays", 30);
        evidence.put("cutoff", LocalDate.now().minusDays(30).toString());
        evidence.put("uploadedFiles", uploadedFiles);
        evidence.put("ingestionJobs", ingestionJobsPurged);
        audit.record(AuditEventSpec.builder("RETENTION_PURGE_COMPLETED", AuditOutcome.SUCCESS)
                .entity("Bank", String.valueOf(bank.getBankId()))
                .bankId(bank.getBankId())
                .newValues(evidence)
                .build());
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private static Map<String, Long> map(JsonNode node) {
        Map<String, Long> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> values.put(field.getKey(),
                field.getValue().asLong()));
        return values;
    }
}
