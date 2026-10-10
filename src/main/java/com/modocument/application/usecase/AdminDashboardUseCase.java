package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AdminDashboardDto;
import com.modocument.application.dto.AdminDashboardDto.Alerts;
import com.modocument.application.dto.AdminDashboardDto.Approvals;
import com.modocument.application.dto.AdminDashboardDto.BankInfo;
import com.modocument.application.dto.AdminDashboardDto.Extraction;
import com.modocument.application.dto.AdminDashboardDto.Ingestion;
import com.modocument.application.dto.AdminDashboardDto.Integrations;
import com.modocument.application.dto.AdminDashboardDto.Retention;
import com.modocument.application.dto.AdminDashboardDto.Settings;
import com.modocument.application.dto.AdminDashboardDto.StatusCounts;
import com.modocument.application.dto.AdminDashboardDto.Templates;
import com.modocument.application.dto.AdminDashboardDto.Users;
import com.modocument.application.dto.AdminDashboardDto.Version;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.PendingChangeJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.retention.RetentionProperties;
import com.modocument.infrastructure.retention.RetentionRunHistory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank admin dashboard aggregate (phase 2, ws22): one read of every
 * counter the overview needs, for the CALLER'S bank only, cached for
 * {@code modocument.dashboard.cache-ttl} (default 30 s) per bank.
 *
 * <p><b>Tenancy.</b> AppUsers and UserMfa are NOT under RLS, so every user
 * and MFA count names the bank explicitly; the job, template, alert and
 * approval tables ARE RLS-scoped on PostgreSQL and are filtered by BankId
 * anyway (defense in depth, and the only boundary on H2). Unlike the
 * owner-scoped listings, the counts here are bank-wide. The cache is keyed
 * by bank id, so one bank's numbers are never served to another.
 *
 * <p><b>No content.</b> Only counts and timestamps leave this class — no
 * emails, names, file names or template names (the bank's own code and
 * name are the only labels).
 */
@Service
public class AdminDashboardUseCase {

    private static final int RECENT_DAYS = 7;
    private static final List<String> PARSED_BY = List.of("AI", "CODE", "AI_PARTIAL");
    /** Audit keys of RETENTION_PURGE_COMPLETED that are not purge counts. */
    private static final List<String> RETENTION_NON_COUNTS = List.of("retentionDays", "cutoff");

    private final CurrentUserProvider currentUser;
    private final BankJpaRepository banks;
    private final AppUserJpaRepository users;
    private final PendingChangeJpaRepository pendingChanges;
    private final DocInstanceJpaRepository docInstances;
    private final IngestionJobJpaRepository ingestionJobs;
    private final ExtractionJobStore extractionJobs;
    private final AlertLogJpaRepository alerts;
    private final TemplateJpaRepository templates;
    private final RetentionProperties retention;
    private final RetentionRunHistory retentionHistory;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnlyTx;
    private final long cacheTtlNanos;

    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public AdminDashboardUseCase(CurrentUserProvider currentUser, BankJpaRepository banks,
                                 AppUserJpaRepository users,
                                 PendingChangeJpaRepository pendingChanges,
                                 DocInstanceJpaRepository docInstances,
                                 IngestionJobJpaRepository ingestionJobs,
                                 ExtractionJobStore extractionJobs,
                                 AlertLogJpaRepository alerts, TemplateJpaRepository templates,
                                 RetentionProperties retention,
                                 RetentionRunHistory retentionHistory,
                                 ObjectProvider<BuildProperties> buildProperties,
                                 ObjectProvider<GitProperties> gitProperties,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${modocument.dashboard.cache-ttl:30s}")
                                 Duration cacheTtl) {
        this.currentUser = currentUser;
        this.banks = banks;
        this.users = users;
        this.pendingChanges = pendingChanges;
        this.docInstances = docInstances;
        this.ingestionJobs = ingestionJobs;
        this.extractionJobs = extractionJobs;
        this.alerts = alerts;
        this.templates = templates;
        this.retention = retention;
        this.retentionHistory = retentionHistory;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
        this.objectMapper = objectMapper;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.cacheTtlNanos = Math.max(0, cacheTtl.toNanos());
    }

    /** The caller's bank's dashboard, at most {@code cache-ttl} old. */
    public AdminDashboardDto dashboard() {
        int bankId = currentUser.requireBankId();
        long now = System.nanoTime();
        Cached hit = cache.get(bankId);
        if (hit != null && now - hit.computedAtNanos() < cacheTtlNanos) {
            return hit.dashboard();
        }
        AdminDashboardDto fresh = readOnlyTx.execute(tx -> compute(bankId));
        evictExpired(now);
        cache.put(bankId, new Cached(fresh, now));
        return fresh;
    }

    private AdminDashboardDto compute(int bankId) {
        LocalDateTime now = LocalDateTime.now();
        Instant nowInstant = Instant.now();
        return new AdminDashboardDto(
                nowInstant,
                bank(bankId),
                users(bankId, now),
                new Approvals(
                        pendingChanges.countByBankIdAndStatus(bankId,
                                PendingChangeStatus.REQUESTED),
                        docInstances.countByBankIdAndStatus(bankId,
                                DocInstance.STATUS_SUBMITTED)),
                ingestion(bankId, now),
                new Extraction(new StatusCounts(extractionLast7d(bankId, nowInstant))),
                alerts(bankId, nowInstant),
                retention(bankId),
                // ws22 V220: private = owner-only, shared = visible to the bank.
                new Templates(
                        templates.countByBankIdAndVisibility(bankId, TemplateVisibility.PRIVATE),
                        templates.countByBankIdAndVisibility(bankId, TemplateVisibility.BANK)),
                new Settings(pendingChanges.countByBankIdAndStatusAndChangeType(bankId,
                        PendingChangeStatus.REQUESTED, ChangeType.SETTING_UPDATE), 0),
                version(),
                // The integration hub (ws25/ws26) has no data yet: null = "not
                // tracked" (the UI shows "—"), never a misleading zero.
                Integrations.notTrackedYet());
    }

    private BankInfo bank(int bankId) {
        // The Bank table is RLS-protected; the caller's own row is visible.
        return banks.findById(bankId)
                .map(bank -> new BankInfo(bank.getCode(), bank.getName()))
                .orElseGet(() -> new BankInfo(null, null));
    }

    private Users users(int bankId, LocalDateTime now) {
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            if (role.isBankRole()) {
                byRole.put(role.name(), 0L);
            }
        }
        long total = 0;
        long disabled = 0;
        for (Object[] row : users.countByRoleAndDisabled(bankId)) {
            long count = ((Number) row[2]).longValue();
            total += count;
            if (Boolean.TRUE.equals(row[1])) {
                disabled += count;
            }
            if (row[0] instanceof Role role) {
                byRole.merge(role.name(), count, Long::sum);
            }
        }
        return new Users(total, total - disabled, disabled,
                users.countByBankIdAndLockedUntilAfter(bankId, now),
                users.countByBankIdAndMustChangePasswordTrue(bankId),
                byRole,
                users.countWithMfaEnabled(bankId),
                users.countActiveWithoutMfa(bankId, Role.ADMIN),
                users.countByBankIdAndLastLoginAtGreaterThanEqual(bankId,
                        now.minusDays(RECENT_DAYS)));
    }

    private Ingestion ingestion(int bankId, LocalDateTime now) {
        Map<String, Long> today = jobStatusCounts(ingestionJobs.countByStatusSince(bankId,
                LocalDate.now().atStartOfDay()));
        Map<String, Long> last7d = jobStatusCounts(ingestionJobs.countByStatusSince(bankId,
                now.minusDays(RECENT_DAYS)));
        // Parse provenance lands with ws23 (IngestionJob.ParsedBy); the shape
        // is fixed now so the dashboard tiles do not change later.
        Map<String, Long> parsedBy = new LinkedHashMap<>();
        PARSED_BY.forEach(source -> parsedBy.put(source, 0L));
        return new Ingestion(new StatusCounts(today), new StatusCounts(last7d), parsedBy,
                last7d.getOrDefault(JobStatus.FAILED.name(), 0L));
    }

    private static Map<String, Long> jobStatusCounts(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (Object[] row : rows) {
            if (row[0] instanceof JobStatus status) {
                counts.merge(status.name(), ((Number) row[1]).longValue(), Long::sum);
            }
        }
        return counts;
    }

    private Map<String, Long> extractionLast7d(int bankId, Instant now) {
        Map<ExtractionJobStatus, Long> found = extractionJobs.countByStatusSince(bankId,
                now.minus(RECENT_DAYS, ChronoUnit.DAYS));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ExtractionJobStatus status : ExtractionJobStatus.values()) {
            counts.put(status.name(), found.getOrDefault(status, 0L));
        }
        return counts;
    }

    private Alerts alerts(int bankId, Instant now) {
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (AlertSeverity severity : AlertSeverity.values()) {
            bySeverity.put(severity.name(), 0L);
        }
        for (Object[] row : alerts.countBySeveritySince(bankId,
                now.minus(RECENT_DAYS, ChronoUnit.DAYS))) {
            if (row[0] instanceof AlertSeverity severity) {
                bySeverity.merge(severity.name(), ((Number) row[1]).longValue(), Long::sum);
            }
        }
        return new Alerts(bySeverity, alerts.lastCreatedAt(bankId));
    }

    /**
     * The last run comes from the bank's newest RETENTION_PURGE_COMPLETED
     * audit event (durable evidence, correct across restarts and replicas),
     * never from in-memory job state.
     */
    private Retention retention(int bankId) {
        AuditEvent lastRun = retentionHistory.latestFor(bankId).orElse(null);
        return new Retention(retention.enabled(), retention.customerDataDays(),
                lastRun == null ? null : lastRun.getOccurredAt(),
                lastRun == null ? null : purgedTotal(lastRun.getNewValues()));
    }

    private Long purgedTotal(String newValuesJson) {
        if (newValuesJson == null || newValuesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode values = objectMapper.readTree(newValuesJson);
            long total = 0;
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!RETENTION_NON_COUNTS.contains(field.getKey())
                        && field.getValue().canConvertToLong()) {
                    total += field.getValue().asLong();
                }
            }
            return total;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private Version version() {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        // deployedAt comes from the release recorder (ws24, VERSION_DEPLOYED).
        return new Version(build == null ? null : build.getVersion(),
                git == null ? null : git.getShortCommitId(), null);
    }

    /** Banks are few; still, never let stale entries accumulate. */
    private void evictExpired(long now) {
        cache.entrySet().removeIf(entry ->
                now - entry.getValue().computedAtNanos() >= cacheTtlNanos);
    }

    private record Cached(AdminDashboardDto dashboard, long computedAtNanos) {
    }
}
