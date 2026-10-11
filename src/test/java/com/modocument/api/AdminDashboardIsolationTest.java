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
import com.modocument.domain.entity.IntegrationAgent;
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
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.domain.model.integration.AgentStatus;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationAgentJpaRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
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
    @Autowired
    private IntegrationAgentJpaRepository agents;
    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private int runSequence;
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
        for (String table : List.of("OutboundDelivery", "IntegrationRun", "IntegrationFlow",
                "IntegrationConnection", "IntegrationAgent")) {
            jdbc.update("DELETE FROM " + table + " WHERE BankId IN (?, ?)",
                    bankA.getBankId(), bankB.getBankId());
        }
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

        ingestion(bankA, adminA, JobStatus.COMPLETED, LocalDateTime.now(), "AI");
        ingestion(bankA, adminA, JobStatus.COMPLETED, LocalDateTime.now(), "CODE");
        ingestion(bankA, writerA, JobStatus.FAILED, LocalDateTime.now().minusDays(3),
                "AI_PARTIAL");
        // Older than 7 days: neither in last7d nor in parsedBy.
        ingestion(bankA, writerA, JobStatus.FAILED, LocalDateTime.now().minusDays(10), "AI");
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
        template(bankA, adminA, "Secret Template A3", TemplateVisibility.BANK);
        docInstance(bankA, writerA, DocInstance.STATUS_SUBMITTED);
        docInstance(bankA, writerA, DocInstance.STATUS_RELEASED);
        retentionRun(bankA, 3, 2);
        // Integration hub: 2 ok + 1 failed in the last 24 h; an older run, a
        // cancelled run and an unfinished one do not count.
        long flowA = flow(bankA, adminA);
        run(bankA, flowA, "SUCCEEDED", Instant.now().minus(1, ChronoUnit.HOURS));
        run(bankA, flowA, "SUCCEEDED", Instant.now().minus(23, ChronoUnit.HOURS));
        run(bankA, flowA, "FAILED", Instant.now().minus(2, ChronoUnit.HOURS));
        run(bankA, flowA, "SUCCEEDED", Instant.now().minus(30, ChronoUnit.HOURS));
        run(bankA, flowA, "CANCELLED", Instant.now());
        delivery(bankA, flowA, "DEAD");
        delivery(bankA, flowA, "DELIVERED");
        // One silent agent (offline), one fresh (online), one never heard
        // from since enrolment (offline), pending and revoked (neither).
        agent(bankA, adminA, AgentStatus.ONLINE, Instant.now().minus(2, ChronoUnit.DAYS));
        agent(bankA, adminA, AgentStatus.ONLINE, Instant.now());
        agent(bankA, adminA, AgentStatus.ONLINE, null);
        agent(bankA, adminA, AgentStatus.PENDING_ENROLMENT, null);
        agent(bankA, adminA, AgentStatus.REVOKED, Instant.now().minus(2, ChronoUnit.DAYS));
        apiCall(bankA);
        apiCall(bankA);
        apiCall(bankA);
        apiCallAt(bankA, Instant.now().minus(2, ChronoUnit.DAYS));

        // ---- bank B: MORE of everything, all of it must stay invisible to A
        long flowB = flow(bankB, adminB);
        for (int i = 0; i < 5; i++) {
            run(bankB, flowB, "SUCCEEDED", Instant.now());
            run(bankB, flowB, "FAILED", Instant.now());
            delivery(bankB, flowB, "DEAD");
            agent(bankB, adminB, AgentStatus.ONLINE, null);
            apiCall(bankB);
        }
        mfa(adminB);
        for (int i = 0; i < 4; i++) {
            AppUser b = user("dash-b" + i, bankB, Role.ADMIN);
            b.setLockedUntil(LocalDateTime.now().plusMinutes(20));
            b.setMustChangePassword(true);
            b.setLastLoginAt(LocalDateTime.now());
            users.save(b);
            mfa(b);
            ingestion(bankB, b, JobStatus.FAILED, LocalDateTime.now(), "AI");
            extraction(bankB, b, ExtractionJobStatus.FAILED, Instant.now());
            alert(bankB, AlertSeverity.CRITICAL, Instant.now());
            pending(bankB, adminB, ChangeType.SETTING_UPDATE);
            template(bankB, b, "Secret Template B" + i);
            template(bankB, b, "Secret Shared B" + i, TemplateVisibility.BANK);
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
                .isEqualTo(Map.of("AI", 1L, "CODE", 1L, "AI_PARTIAL", 1L));

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

        // ws22: private = owner-only, shared = visible to the whole bank.
        assertThat(body.get("templates").get("private").asLong()).isEqualTo(2);
        assertThat(body.get("templates").get("shared").asLong()).isEqualTo(1);
        assertThat(body.get("settings").get("pendingSecurityChanges").asLong()).isEqualTo(1);
        // Integration hub (contracts §3): bank A's own counts only.
        JsonNode hub = body.get("integrations");
        assertThat(hub.get("runs24h").get("ok").asLong()).isEqualTo(2);
        assertThat(hub.get("runs24h").get("failed").asLong()).isEqualTo(1);
        assertThat(hub.get("deadLetters").asLong()).isEqualTo(1);
        assertThat(hub.get("agentsOffline").asLong()).isEqualTo(2);
        assertThat(hub.get("apiCalls24h").asLong()).isEqualTo(3);

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
        assertThat(bodyB.get("templates").get("shared").asLong()).isEqualTo(4);
        assertThat(bodyB.get("retention").get("lastPurged").asLong()).isEqualTo(99);
        assertThat(map(bodyB.get("ingestion").get("parsedBy")))
                .isEqualTo(Map.of("AI", 4L, "CODE", 0L, "AI_PARTIAL", 0L));
        JsonNode hubB = bodyB.get("integrations");
        assertThat(hubB.get("runs24h").get("ok").asLong()).isEqualTo(5);
        assertThat(hubB.get("runs24h").get("failed").asLong()).isEqualTo(5);
        assertThat(hubB.get("deadLetters").asLong()).isEqualTo(5);
        assertThat(hubB.get("agentsOffline").asLong()).isEqualTo(5);
        assertThat(hubB.get("apiCalls24h").asLong()).isEqualTo(5);
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

    private void ingestion(Bank bank, AppUser owner, JobStatus status, LocalDateTime created,
                           String parsedBy) {
        IngestionJob job = new IngestionJob();
        job.setParsedBy(parsedBy);
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
        template(bank, owner, name, TemplateVisibility.PRIVATE);
    }

    private void template(Bank bank, AppUser owner, String name, TemplateVisibility visibility) {
        Template template = new Template(0, name + " " + tag, "d");
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setVisibility(visibility);
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

    /** A REST connection and an outbound flow on it (the runs' and deliveries' parents). */
    private long flow(Bank bank, AppUser by) {
        int bankId = bank.getBankId();
        String name = "dash-hub-" + tag;
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO IntegrationConnection (BankId, Name, Type, Mode, ConfigJson, "
                        + "AuthType, Status, Version, CreatedBy, CreatedAt) "
                        + "VALUES (?, ?, 'REST_API', 'DIRECT', '{}', 'NONE', 'UNTESTED', 0, ?, ?)",
                bankId, name, by.getAppUserId(), now);
        Long connectionId = jdbc.queryForObject("SELECT IntegrationConnectionId FROM "
                + "IntegrationConnection WHERE BankId = ? AND Name = ?", Long.class, bankId, name);
        jdbc.update("INSERT INTO IntegrationFlow (BankId, Name, ConnectionId, Direction, "
                        + "TriggerType, PayloadKinds, Format, MaskPii, Enabled, Version, "
                        + "CreatedBy, CreatedAt) VALUES (?, ?, ?, 'OUTBOUND', 'MANUAL', "
                        + "'JOB_STATUS', 'JSON', true, false, 0, ?, ?)",
                bankId, name, connectionId, by.getAppUserId(), now);
        return jdbc.queryForObject("SELECT IntegrationFlowId FROM IntegrationFlow "
                + "WHERE BankId = ? AND Name = ?", Long.class, bankId, name);
    }

    private void run(Bank bank, long flowId, String status, Instant finishedAt) {
        Long connectionId = jdbc.queryForObject("SELECT ConnectionId FROM IntegrationFlow "
                + "WHERE IntegrationFlowId = ?", Long.class, flowId);
        // ScheduledFor is unique per flow.
        Timestamp scheduledFor = Timestamp.from(Instant.now().plusMillis(runSequence++));
        jdbc.update("INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, Operation, "
                        + "TriggerType, ScheduledFor, Status, Attempt, RecordsIn, RecordsOut, "
                        + "CreatedAt, StartedAt, FinishedAt) "
                        + "VALUES (?, ?, ?, 'WRITE', 'MANUAL', ?, ?, 1, 0, 0, ?, ?, ?)",
                bank.getBankId(), flowId, connectionId, scheduledFor, status,
                Timestamp.from(finishedAt), Timestamp.from(finishedAt),
                Timestamp.from(finishedAt));
    }

    private void delivery(Bank bank, long flowId, String status) {
        jdbc.update("INSERT INTO OutboundDelivery (BankId, FlowId, PayloadKind, PayloadRef, "
                        + "Status, Attempts, CreatedAt) VALUES (?, ?, 'JOB_STATUS', 'job:1', ?, "
                        + "5, ?)",
                bank.getBankId(), flowId, status, Timestamp.from(Instant.now()));
    }

    private void agent(Bank bank, AppUser by, AgentStatus status, Instant lastHeartbeat) {
        IntegrationAgent agent = new IntegrationAgent();
        agent.setBankId(bank.getBankId());
        agent.setName("dash-agent-" + tag + "-" + runSequence++);
        agent.setStatus(status);
        agent.setLastHeartbeatAt(lastHeartbeat);
        agent.setCreatedByUserId(by.getAppUserId());
        agent.setCreatedAt(Instant.now());
        agents.save(agent);
    }

    /** What MachineApiInterceptor records for one Integration API v1 call. */
    private void apiCall(Bank bank) {
        audit.record(AuditEventSpec.builder("INTEGRATION_API_CALL", AuditOutcome.SUCCESS)
                .entity("ApiClient", "1")
                .actorEmail("api-client:dash-" + tag)
                .actorRole("API_CLIENT")
                .bankId(bank.getBankId())
                .newValues(Map.of("method", "GET", "route", "/api/integration/v1/templates"))
                .build());
    }

    private void apiCallAt(Bank bank, Instant occurredAt) {
        jdbc.update("INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, Action, "
                        + "EntityType, EntityId, Outcome) "
                        + "VALUES (?, ?, ?, 'INTEGRATION_API_CALL', 'ApiClient', '1', 'SUCCESS')",
                UUID.randomUUID(), Timestamp.from(occurredAt), bank.getBankId());
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
