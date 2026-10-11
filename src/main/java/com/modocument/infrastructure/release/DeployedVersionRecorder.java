package com.modocument.infrastructure.release;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.application.usecase.AnnouncementUseCase;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.DeployedVersionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Records a release the first time it starts in this environment (ws24,
 * docs/release/runbook.md), once the application is ready:
 * <ol>
 *   <li><b>Claim</b> — insert {@code DeployedVersion(version)} in its own
 *       transaction. The version is the primary key, so of all the tasks a
 *       rolling deploy starts exactly ONE insert succeeds ("first task
 *       wins"); a duplicate key means another task (or an earlier start of
 *       the same version) already did everything below, and this task
 *       stops.</li>
 *   <li><b>Audit</b> — the winner writes {@value #VERSION_DEPLOYED} in every
 *       bank: {@code runAsSystem} lists the RLS-protected Bank table, then
 *       {@code runAs(bank)} writes each bank's row inside that tenant
 *       (registered call sites, see {@link TenantContext}). One bank's
 *       failure is logged and does not stop the others.</li>
 *   <li><b>Announce</b> — the winner publishes the RELEASE banner, owned by
 *       the platform bank ({@code runAs(platform bank)}), shown for
 *       {@code modocument.release.banner-days} (default 7). Skipped while
 *       no platform bank exists.</li>
 * </ol>
 * A recording failure is logged and never stops the application from
 * serving: the deployment record is evidence, not a dependency. Switch it
 * off with {@code modocument.release.record-deployment=false}. The version
 * comes from the build ({@link SystemVersionService#version()}); a build
 * without one (a plain IDE run) records nothing.
 */
@Component
public class DeployedVersionRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeployedVersionRecorder.class);

    /** Audit action written in every bank by the winning task (contracts §5). */
    public static final String VERSION_DEPLOYED = "VERSION_DEPLOYED";

    /** What a call to {@link #record} did. */
    public enum Outcome {
        /** This task won: row inserted, banks audited, banner published (if possible). */
        RECORDED,
        /** Another task (or an earlier start) already recorded this version. */
        ALREADY_RECORDED,
        /** Nothing to record: no version, or recording switched off. */
        SKIPPED
    }

    private final SystemVersionService versions;
    private final DeployedVersionJpaRepository deployments;
    private final BankJpaRepository banks;
    private final PlatformBankLookup platformBank;
    private final AnnouncementUseCase announcements;
    private final AuditRecorder audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final TransactionTemplate readOnlyNewTx;
    private final boolean enabled;
    private final Duration bannerDuration;

    public DeployedVersionRecorder(SystemVersionService versions,
                                   DeployedVersionJpaRepository deployments,
                                   BankJpaRepository banks,
                                   PlatformBankLookup platformBank,
                                   AnnouncementUseCase announcements,
                                   AuditRecorder audit,
                                   JdbcTemplate jdbc,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${modocument.release.record-deployment:true}") boolean enabled,
                                   @Value("${modocument.release.banner-days:7}") int bannerDays) {
        this.versions = versions;
        this.deployments = deployments;
        this.banks = banks;
        this.platformBank = platformBank;
        this.announcements = announcements;
        this.audit = audit;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.bannerDuration = Duration.ofDays(Math.max(1, Math.min(bannerDays, 90)));
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newTx = template;
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly.setReadOnly(true);
        this.readOnlyNewTx = readOnly;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Deployment recording is off (modocument.release.record-deployment=false).");
            return;
        }
        try {
            record(versions.version());
        } catch (RuntimeException e) {
            log.error("Could not record the deployment of version {} (the application keeps "
                    + "running; the version endpoint reports deployedAt null).", versions.version(), e);
        }
    }

    /**
     * Claims {@code version} and, when this call wins, audits every bank and
     * publishes the RELEASE banner. Safe to call concurrently from several
     * processes: exactly one call per version returns {@link Outcome#RECORDED}.
     */
    public Outcome record(String version) {
        if (version == null || version.isBlank()) {
            log.info("No build version (no build-info); deployment not recorded.");
            return Outcome.SKIPPED;
        }
        Instant now = Instant.now();
        String commit = versions.commit();
        String environment = versions.environment();
        if (!claim(version, commit, now, environment)) {
            log.info("Version {} was already recorded in {}; nothing to do.", version, environment);
            return Outcome.ALREADY_RECORDED;
        }
        log.info("Version {} ({}) deployed in {}: auditing every bank and announcing it.",
                version, commit, environment);
        int audited = auditEveryBank(version, commit, environment, now);
        announce(version);
        log.info("Deployment of {} recorded: VERSION_DEPLOYED written in {} bank(s).", version, audited);
        return Outcome.RECORDED;
    }

    /**
     * The insert-if-absent: true when this call inserted the row. A row that
     * already exists (seen before inserting, or as the duplicate key of a
     * concurrent insert) answers false; any other failure propagates.
     *
     * <p>A plain JDBC insert, not {@code EntityManager.persist}: the losing
     * task's duplicate key is an expected outcome here, and Hibernate would
     * log it at ERROR on every rolling deploy.
     */
    boolean claim(String version, String commit, Instant deployedAt, String environment) {
        try {
            Boolean inserted = newTx.execute(status -> {
                if (deployments.existsById(version)) {
                    return false;
                }
                jdbc.update("INSERT INTO DeployedVersion (Version, GitCommit, DeployedAt, Environment) "
                                + "VALUES (?, ?, ?, ?)",
                        version, commit, OffsetDateTime.ofInstant(deployedAt, ZoneOffset.UTC),
                        environment);
                return true;
            });
            return Boolean.TRUE.equals(inserted);
        } catch (DataIntegrityViolationException e) {
            // The concurrent winner's commit made our insert a duplicate key;
            // anything else (the row is still absent) is a real failure.
            if (Boolean.TRUE.equals(readOnlyNewTx.execute(status -> deployments.existsById(version)))) {
                return false;
            }
            throw e;
        }
    }

    private int auditEveryBank(String version, String commit, String environment, Instant deployedAt) {
        List<Integer> bankIds;
        try {
            // runAsSystem: the cross-tenant listing of the RLS-protected Bank table.
            bankIds = TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status ->
                    banks.findAll().stream().map(Bank::getBankId).sorted().toList()));
        } catch (Exception e) {
            log.error("VERSION_DEPLOYED not audited: the banks could not be listed.", e);
            return 0;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", version);
        evidence.put("commit", commit);
        evidence.put("environment", environment);
        evidence.put("deployedAt", deployedAt.toString());
        int audited = 0;
        for (int bankId : bankIds == null ? List.<Integer>of() : bankIds) {
            try {
                // runAs(bank): the audit row is written inside that tenant, so
                // the RLS WITH CHECK passes and it lands in that bank's trail.
                TenantContext.runAs(bankId, () -> audit.record(
                        AuditEventSpec.builder(VERSION_DEPLOYED, AuditOutcome.SUCCESS)
                                .entity("DeployedVersion", version)
                                .bankId(bankId)
                                .newValues(evidence)
                                .build()));
                audited++;
            } catch (RuntimeException e) {
                log.error("VERSION_DEPLOYED could not be audited for bank {} (continuing).", bankId, e);
            }
        }
        return audited;
    }

    private void announce(String version) {
        OptionalInt platform;
        try {
            platform = platformBank.platformBankId();
        } catch (RuntimeException e) {
            log.error("RELEASE banner for {} not published: the platform bank could not be resolved.",
                    version, e);
            return;
        }
        if (platform.isEmpty()) {
            log.info("No platform bank yet: no RELEASE banner for {}.", version);
            return;
        }
        int platformBankId = platform.getAsInt();
        try {
            // runAs(platform bank): the banner row is the platform's, so the
            // RLS WITH CHECK of its insert needs that tenant bound.
            TenantContext.runAs(platformBankId, () -> {
                announcements.publishRelease(platformBankId, version, bannerDuration);
            });
        } catch (RuntimeException e) {
            log.error("RELEASE banner for {} could not be published.", version, e);
        }
    }
}
