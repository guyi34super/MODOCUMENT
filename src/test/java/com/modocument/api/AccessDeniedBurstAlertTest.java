package com.modocument.api;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.alerting.AlertSweepJob;
import com.modocument.infrastructure.audit.JpaAuditRecorder;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA, optimization round, test plan A15/A20 (contracts §4): the
 * {@code access-denied-burst} WINDOWED rule (MEDIUM, group ACTOR_OR_IP,
 * default threshold 3 within 5 minutes) fires ONCE when a user or an
 * address reaches the threshold of ACCESS_DENIED rows in the window, and
 * not below it. ACCESS_DENIED rows are aggregated per minute, so the rows
 * of the window stand for minutes with refusals; the tests seed one row per
 * minute directly and drive the sweep ({@link AlertSweepJob#runOnce()}).
 * The aggregation itself (many probes in one minute -> at most two rows
 * whose counts add up) is pinned through the real HTTP path.
 */
class AccessDeniedBurstAlertTest extends DetectionGateTestSupport {

    private static final String RULE = "access-denied-burst";
    private static final Pattern COUNT = Pattern.compile("\"count\":(\\d+)");

    @Autowired
    private AlertSweepJob sweep;
    @Autowired
    private JpaAuditRecorder recorder;
    @Autowired
    private IngestionJobJpaRepository jobs;

    private Bank bank;
    private AppUser prober;

    @BeforeEach
    void seed() {
        bank = newBank("ADB");
        prober = newUser(bank, "adb-prober", Role.READ_WRITE, null);
    }

    private void deniedAt(Instant occurredAt, Integer actorUserId, String ip) {
        jdbc.update("INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, ActorUserId, Ip, "
                        + "Action, EntityType, EntityId, Outcome, NewValues) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACCESS_DENIED', 'IngestionJob', '1', 'DENIED', ?)",
                UUID.randomUUID(), Timestamp.from(occurredAt), bank.getBankId(), actorUserId, ip,
                "{\"kind\":\"NOT_FOUND_PROBE\",\"count\":1}");
    }

    private List<AlertLog> raisedForBank(List<AlertLog> raised) {
        return raised.stream()
                .filter(a -> a.getBankId() == bank.getBankId() && a.getRuleName().equals(RULE))
                .toList();
    }

    @Test
    void firesOnceAtTheThresholdForOneUserAndNotBelow() {
        Instant now = Instant.now();
        deniedAt(now.minus(Duration.ofMinutes(3)), prober.getAppUserId(), "10.29.0.1");
        deniedAt(now.minus(Duration.ofMinutes(2)), prober.getAppUserId(), "10.29.0.2");

        assertThat(raisedForBank(sweep.runOnce())).as("2 minutes with refusals < threshold 3").isEmpty();

        deniedAt(now.minus(Duration.ofMinutes(1)), prober.getAppUserId(), "10.29.0.3");
        List<AlertLog> raised = raisedForBank(sweep.runOnce());

        assertThat(raised).hasSize(1);
        AlertLog alert = raised.get(0);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(alert.getEventCount()).isEqualTo(3);
        assertThat(alert.getSummary()).contains("ACCESS_DENIED")
                .contains("user #" + prober.getAppUserId());
        // Grouped by the user, not the (changing) address.
        assertThat(alert.getSummary()).doesNotContain("10.29.0.");

        // A sustained burst inside the window is still ONE alert (dedupe).
        deniedAt(now, prober.getAppUserId(), "10.29.0.4");
        assertThat(raisedForBank(sweep.runOnce())).isEmpty();
        assertThat(alerts(bank, RULE)).hasSize(1);
    }

    @Test
    void anonymousRefusalsGroupByAddressAndOldRowsAreOutsideTheWindow() {
        Instant now = Instant.now();
        String ip = "10.29." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        // Two rows of the window plus one older than the 5-minute window: below threshold.
        deniedAt(now.minus(Duration.ofMinutes(9)), null, ip);
        deniedAt(now.minus(Duration.ofMinutes(2)), null, ip);
        deniedAt(now.minus(Duration.ofMinutes(1)), null, ip);
        // Another address's refusals never add up with this one's.
        deniedAt(now.minus(Duration.ofMinutes(1)), null, ip + "9");
        assertThat(raisedForBank(sweep.runOnce())).isEmpty();

        deniedAt(now, null, ip);
        List<AlertLog> raised = raisedForBank(sweep.runOnce());
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getEventCount()).isEqualTo(3);
        assertThat(raised.get(0).getSummary()).contains("ip " + ip);
    }

    @Test
    void fiftyProbesInOneMinuteAreAggregatedIntoAtMostTwoRowsWhoseCountsAddUp() throws Exception {
        AppUser owner = newUser(bank, "adb-owner", Role.READ_WRITE, null);
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner.getAppUserId());
        job.setBankId(bank.getBankId());
        job.setFileName("adb-" + UUID.randomUUID() + ".csv");
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        job.setCreatedDate(LocalDateTime.now());
        job.setUpdatedDate(LocalDateTime.now());
        job = jobs.save(job);

        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/api/ingestion/jobs/" + job.getIngestionJobId())
                            .header("Authorization", bearer(prober)))
                    .andExpect(status().isNotFound());
        }
        recorder.flushAggregatedDenials(Instant.now().plusSeconds(120));

        List<AuditRow> rows = auditRows(bank, "ACCESS_DENIED");
        assertThat(rows).as("one opening row + one flushed row for the minute").hasSize(2);
        int total = 0;
        for (AuditRow row : rows) {
            Matcher m = COUNT.matcher(row.newValues());
            assertThat(m.find()).as("count in " + row.newValues()).isTrue();
            total += Integer.parseInt(m.group(1));
        }
        assertThat(total).as("the counts add up to the refusals").isEqualTo(50);
    }
}
