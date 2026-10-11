package com.modocument.infrastructure.housekeeping;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IdempotencyKey;
import com.modocument.domain.entity.IntegrationEvent;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.IntegrationRunEvent;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.entity.RevokedToken;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IdempotencyKeyJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.persistence.RevokedTokenJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws27 daily housekeeping on the H2 schema, one test per rule: what is old
 * enough goes, everything else — younger rows, PENDING deliveries, runs and
 * events they still need, another bank's rows — stays; the work is bounded
 * per run; each bank gets its HOUSEKEEPING_COMPLETED evidence. The same
 * rules under row level security: HousekeepingRlsPgIT.
 */
@SpringBootTest
@ActiveProfiles("test")
class HousekeepingJobTest {

    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    private HousekeepingJob job;
    @Autowired
    private HousekeepingPurger purger;
    @Autowired
    private HousekeepingProperties properties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private RevokedTokenJpaRepository revokedTokens;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private IntegrationRunEventJpaRepository runEvents;
    @Autowired
    private OutboundDeliveryJpaRepository deliveries;
    @Autowired
    private IntegrationEventJpaRepository events;
    @Autowired
    private IdempotencyKeyJpaRepository idempotencyKeys;
    @Autowired
    private JdbcTemplate jdbc;

    private final Instant now = Instant.now();
    private Bank bankA;
    private Bank bankB;
    private AppUser userA;
    private AppUser userB;

    @BeforeEach
    void seedTwoBanks() {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = banks.save(new Bank("HKA" + tag, "Housekeeping A"));
        bankB = banks.save(new Bank("HKB" + tag, "Housekeeping B"));
        userA = user("hk-a-" + tag, bankA);
        userB = user("hk-b-" + tag, bankB);
    }

    @AfterEach
    void cleanUp() {
        for (Bank bank : List.of(bankA, bankB)) {
            int id = bank.getBankId();
            jdbc.update("DELETE FROM IntegrationRunEvent WHERE BankId = ?", id);
            jdbc.update("DELETE FROM OutboundDelivery WHERE BankId = ?", id);
            jdbc.update("DELETE FROM IntegrationRun WHERE BankId = ?", id);
            jdbc.update("DELETE FROM IntegrationEvent WHERE BankId = ?", id);
            jdbc.update("DELETE FROM IdempotencyKey WHERE BankId = ?", id);
        }
        for (AppUser user : List.of(userA, userB)) {
            refreshTokens.findByUserId(user.getAppUserId()).forEach(refreshTokens::delete);
            users.deleteById(user.getAppUserId());
        }
        banks.delete(bankA);
        banks.delete(bankB);
    }

    // ------------------------------------------------------------ tokens

    @Test
    void refreshTokensExpiredOrRevokedMoreThanThirtyDaysAgoGo() throws Exception {
        UUID expiredLongAgo = refreshToken(userA, now.minus(DAY.multipliedBy(31)), null);
        UUID revokedLongAgo = refreshToken(userA, now.plus(DAY.multipliedBy(10)),
                now.minus(DAY.multipliedBy(31)));
        UUID revokedRecently = refreshToken(userA, now.plus(DAY), now.minus(DAY));
        UUID expiredRecently = refreshToken(userA, now.minus(DAY), null);
        UUID live = refreshToken(userA, now.plus(DAY.multipliedBy(7)), null);
        UUID otherBank = refreshToken(userB, now.minus(DAY.multipliedBy(31)), null);

        int deleted = TenantContext.runAs(bankA.getBankId(), () ->
                purger.refreshTokens(bankA.getBankId(), now.minus(DAY.multipliedBy(30))));

        assertThat(deleted).isEqualTo(2);
        assertThat(refreshTokens.findAllById(List.of(expiredLongAgo, revokedLongAgo))).isEmpty();
        assertThat(refreshTokens.findAllById(List.of(revokedRecently, expiredRecently, live,
                otherBank))).hasSize(4);
    }

    @Test
    void revokedTokensGoOncePastTheirTokensExpiry() throws Exception {
        UUID expired = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        revokedTokens.save(new RevokedToken(expired, now.minus(Duration.ofHours(1))));
        revokedTokens.save(new RevokedToken(live, now.plus(Duration.ofMinutes(10))));
        try {
            int deleted = TenantContext.runAsSystem(() -> purger.revokedTokens(now));

            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(revokedTokens.existsById(expired)).isFalse();
            assertThat(revokedTokens.existsById(live)).isTrue();
        } finally {
            revokedTokens.deleteById(live);
        }
    }

    // ------------------------------------------------------------ integration history

    @Test
    void finishedRunsTerminalDeliveriesAndEventsOlderThanTheWindowGo() throws Exception {
        int bank = bankA.getBankId();
        Instant old = now.minus(DAY.multipliedBy(100));
        Instant recent = now.minus(DAY);

        // Runs.
        long oldSucceeded = run(bank, RunStatus.SUCCEEDED, old);
        runEvents.save(new IntegrationRunEvent(bank, oldSucceeded, "INFO", "started"));
        runEvents.save(new IntegrationRunEvent(bank, oldSucceeded, "INFO", "finished"));
        long oldFailedWithDeliveredDelivery = run(bank, RunStatus.FAILED, old);
        long oldDelivered = delivery(bank, oldFailedWithDeliveredDelivery, null,
                DeliveryStatus.DELIVERED, old);
        long oldWithPendingDelivery = run(bank, RunStatus.SUCCEEDED, old);
        long pendingOfOldRun = delivery(bank, oldWithPendingDelivery, null,
                DeliveryStatus.PENDING, old);
        long oldButStillRunning = run(bank, RunStatus.RUNNING, null);
        setCreatedAt(oldButStillRunning, old);
        long recentSucceeded = run(bank, RunStatus.SUCCEEDED, recent);
        long otherBankOld = run(bankB.getBankId(), RunStatus.SUCCEEDED, old);

        // Deliveries without a run.
        long oldDead = delivery(bank, null, null, DeliveryStatus.DEAD, old);
        long recentDelivered = delivery(bank, null, null, DeliveryStatus.DELIVERED, recent);

        // Events.
        long oldUnreferenced = event(bank, old);
        long oldNeededByPending = event(bank, old);
        long pendingForEvent = delivery(bank, null, oldNeededByPending, DeliveryStatus.PENDING,
                old);
        long oldReferencedByDelivered = event(bank, old);
        delivery(bank, null, oldReferencedByDelivered, DeliveryStatus.DELIVERED, old);
        long recentEvent = event(bank, recent);
        long otherBankEvent = event(bankB.getBankId(), old);

        BankHousekeepingSummary summary = TenantContext.runAs(bank, () ->
                job.housekeepBank(bank, now, 0));

        assertThat(summary.historyRetentionDays()).isEqualTo(90);
        assertThat(summary.outboundDeliveries()).isEqualTo(3); // oldDelivered, oldDead, event's DELIVERED
        assertThat(summary.integrationRuns()).isEqualTo(2);
        assertThat(summary.integrationRunEvents()).isEqualTo(2);
        assertThat(summary.integrationEvents()).isEqualTo(2);

        assertThat(runs.findAllById(List.of(oldSucceeded, oldFailedWithDeliveredDelivery)))
                .isEmpty();
        assertThat(runEvents.findByBankIdAndRunIdOrderByIdAsc(bank, oldSucceeded)).isEmpty();
        assertThat(runs.findAllById(List.of(oldWithPendingDelivery, oldButStillRunning,
                recentSucceeded, otherBankOld))).hasSize(4);
        assertThat(deliveries.findAllById(List.of(oldDelivered, oldDead))).isEmpty();
        assertThat(deliveries.findAllById(List.of(pendingOfOldRun, recentDelivered,
                pendingForEvent))).hasSize(3);
        assertThat(events.findAllById(List.of(oldUnreferenced, oldReferencedByDelivered)))
                .isEmpty();
        assertThat(events.findAllById(List.of(oldNeededByPending, recentEvent, otherBankEvent)))
                .hasSize(3);

        Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ?"
                + " AND BankId = ? AND Outcome = 'SUCCESS' AND EntityType = 'Bank'",
                Integer.class, HousekeepingJob.ACTION, bank);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void expiredIdempotencyKeysGoInOneSweep() throws Exception {
        int bank = bankA.getBankId();
        long expired = idempotencyKey(bank, "expired", now.minus(Duration.ofHours(1)));
        long live = idempotencyKey(bank, "live", now.plus(Duration.ofHours(23)));
        long otherBank = idempotencyKey(bankB.getBankId(), "expired-b",
                now.minus(Duration.ofHours(1)));

        int deleted = TenantContext.runAs(bank, () -> purger.expiredIdempotencyKeys(bank, now));

        assertThat(deleted).isEqualTo(1);
        assertThat(idempotencyKeys.existsById(expired)).isFalse();
        assertThat(idempotencyKeys.existsById(live)).isTrue();
        assertThat(idempotencyKeys.existsById(otherBank)).isTrue();
    }

    // ------------------------------------------------------------ bounded + whole job

    @Test
    void eachRunIsBoundedAndABacklogDrainsOverSeveralRuns() throws Exception {
        int bank = bankA.getBankId();
        for (int i = 0; i < 5; i++) {
            idempotencyKey(bank, "backlog-" + i, now.minus(Duration.ofHours(1)));
        }
        HousekeepingProperties small = new HousekeepingProperties();
        small.setBatchSize(2);
        small.setMaxBatches(2);
        HousekeepingPurger bounded = new HousekeepingPurger(small, transactionManager);
        ReflectionTestUtils.setField(bounded, "entityManager", entityManager);

        int first = TenantContext.runAs(bank, () -> bounded.expiredIdempotencyKeys(bank, now));
        int second = TenantContext.runAs(bank, () -> bounded.expiredIdempotencyKeys(bank, now));

        assertThat(first).isEqualTo(4); // 2 statements x 2 rows, then the run stops
        assertThat(second).isEqualTo(1);
    }

    @Test
    void theJobSweepsEveryBankAndAuditsEach() {
        refreshToken(userB, now.minus(DAY.multipliedBy(40)), null);
        long expired = idempotencyKey(bankA.getBankId(), "job", now.minus(DAY));

        List<BankHousekeepingSummary> summaries = job.runOnce();

        assertThat(summaries).extracting(BankHousekeepingSummary::bankId)
                .contains(bankA.getBankId(), bankB.getBankId());
        assertThat(summaries).filteredOn(s -> s.bankId() == bankB.getBankId())
                .singleElement().extracting(BankHousekeepingSummary::refreshTokens).isEqualTo(1);
        assertThat(idempotencyKeys.existsById(expired)).isFalse();
        for (Bank bank : List.of(bankA, bankB)) {
            Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent"
                            + " WHERE Action = ? AND BankId = ?", Integer.class,
                    HousekeepingJob.ACTION, bank.getBankId());
            assertThat(audited).as("bank %s", bank.getCode()).isEqualTo(1);
        }
        assertThat(properties.isEnabled()).as("off in the test profile").isFalse();
    }

    // ------------------------------------------------------------ seed helpers

    private AppUser user(String prefix, Bank bank) {
        AppUser entity = new AppUser();
        entity.setEmail(prefix.toLowerCase() + "@example.com");
        entity.setPasswordHash("hash");
        entity.setDisplayName(prefix);
        entity.setBankId(bank.getBankId());
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        return users.save(entity);
    }

    private UUID refreshToken(AppUser user, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setJti(UUID.randomUUID());
        token.setUserId(user.getAppUserId());
        token.setFamilyId(UUID.randomUUID());
        token.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        token.setIssuedAt(expiresAt.minus(DAY.multipliedBy(7)));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return refreshTokens.save(token).getJti();
    }

    private long run(int bank, RunStatus status, Instant finishedAt) {
        IntegrationRun run = new IntegrationRun();
        run.setBankId(bank);
        run.setConnectionId(1L);
        run.setOperation(IntegrationRun.OP_WRITE);
        run.setTriggerType(FlowTrigger.MANUAL);
        run.setScheduledFor(finishedAt == null ? now : finishedAt);
        run.setStatus(status);
        run.setCreatedAt(finishedAt == null ? now : finishedAt);
        run.setFinishedAt(finishedAt);
        return runs.save(run).getId();
    }

    private void setCreatedAt(long runId, Instant createdAt) {
        IntegrationRun run = runs.findById(runId).orElseThrow();
        run.setCreatedAt(createdAt);
        runs.save(run);
    }

    private long delivery(int bank, Long runId, Long eventId, DeliveryStatus status,
                          Instant createdAt) {
        OutboundDelivery delivery = new OutboundDelivery();
        delivery.setBankId(bank);
        delivery.setFlowId(1L);
        delivery.setRunId(runId);
        delivery.setEventId(eventId);
        delivery.setPayloadKind(PayloadKind.JOB_STATUS);
        delivery.setPayloadRef("job:1");
        delivery.setStatus(status);
        delivery.setNextAttemptAt(status == DeliveryStatus.PENDING ? now : null);
        delivery.setCreatedAt(createdAt);
        return deliveries.save(delivery).getId();
    }

    private long event(int bank, Instant occurredAt) {
        IntegrationEvent event = new IntegrationEvent();
        event.setBankId(bank);
        event.setType(FlowEvent.INGESTION_VALIDATED);
        event.setEntityType("IngestionJob");
        event.setEntityId("1");
        event.setOccurredAt(occurredAt);
        return events.save(event).getId();
    }

    private long idempotencyKey(int bank, String key, Instant expiresAt) {
        IdempotencyKey row = new IdempotencyKey();
        row.setBankId(bank);
        row.setApiClientId(1);
        row.setIdemKey(key + "-" + UUID.randomUUID());
        row.setRequestHash("0".repeat(64));
        row.setCreatedAt(expiresAt.minus(Duration.ofHours(24)));
        row.setExpiresAt(expiresAt);
        return idempotencyKeys.save(row).getIdempotencyKeyId();
    }
}
