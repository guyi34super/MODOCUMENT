package com.modocument.infrastructure.housekeeping;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.settings.SettingsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA, optimization round, test plan H3 (contracts §6): the per-bank
 * {@code integration.historyRetention} setting is honoured by the daily
 * housekeeping — a bank that keeps 10 days loses its 20-day-old finished
 * runs while a bank on the 90-day default keeps them — and a stored value
 * outside the 7d..400d range is clamped (3d acts as 7d, 900d as 400d), so a
 * bad row can never make the purge more aggressive than the catalogue
 * allows. Shares the API suites' context (HousekeepingJobTest's too).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HousekeepingRetentionSettingQaTest {

    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    private HousekeepingJob job;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private SettingsCache settingsCache;
    @Autowired
    private JdbcTemplate jdbc;

    private final Instant now = Instant.now();
    private Bank shortRetention;
    private Bank defaultRetention;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        shortRetention = banks.save(new Bank("HQS" + tag, "Housekeeping QA short"));
        defaultRetention = banks.save(new Bank("HQD" + tag, "Housekeeping QA default"));
    }

    @AfterEach
    void cleanUp() {
        for (Bank bank : new Bank[] {shortRetention, defaultRetention}) {
            jdbc.update("DELETE FROM BankSetting WHERE BankId = ?", bank.getBankId());
            jdbc.update("DELETE FROM IntegrationRun WHERE BankId = ?", bank.getBankId());
        }
        settingsCache.reload();
        banks.delete(shortRetention);
        banks.delete(defaultRetention);
    }

    private void retention(Bank bank, String value) {
        jdbc.update("DELETE FROM BankSetting WHERE BankId = ? AND SettingKey = ?", bank.getBankId(),
                "integration.historyRetention");
        jdbc.update("INSERT INTO BankSetting (BankId, SettingKey, ValueText, Version, UpdatedAt) "
                        + "VALUES (?, 'integration.historyRetention', ?, 1, ?)",
                bank.getBankId(), value, Timestamp.from(now));
        settingsCache.reload();
    }

    private long finishedRun(Bank bank, Duration ago) {
        IntegrationRun run = new IntegrationRun();
        run.setBankId(bank.getBankId());
        run.setConnectionId(1L);
        run.setOperation(IntegrationRun.OP_WRITE);
        run.setTriggerType(FlowTrigger.MANUAL);
        Instant at = now.minus(ago);
        run.setScheduledFor(at);
        run.setStatus(RunStatus.SUCCEEDED);
        run.setCreatedAt(at);
        run.setFinishedAt(at);
        return runs.save(run).getId();
    }

    private BankHousekeepingSummary housekeep(Bank bank) throws Exception {
        return TenantContext.runAs(bank.getBankId(), () -> job.housekeepBank(bank.getBankId(), now, 0));
    }

    @Test
    void aBanksOwnRetentionIsHonouredAndOtherBanksKeepTheDefault() throws Exception {
        retention(shortRetention, "10d");
        long shortOld = finishedRun(shortRetention, DAY.multipliedBy(20));
        long shortYoung = finishedRun(shortRetention, DAY.multipliedBy(5));
        long defaultOld = finishedRun(defaultRetention, DAY.multipliedBy(20));

        BankHousekeepingSummary s = housekeep(shortRetention);
        BankHousekeepingSummary d = housekeep(defaultRetention);

        assertThat(s.historyRetentionDays()).isEqualTo(10);
        assertThat(s.integrationRuns()).isEqualTo(1);
        assertThat(runs.existsById(shortOld)).isFalse();
        assertThat(runs.existsById(shortYoung)).isTrue();
        assertThat(d.historyRetentionDays()).isEqualTo(90);
        assertThat(d.integrationRuns()).isZero();
        assertThat(runs.existsById(defaultOld)).isTrue();
    }

    @Test
    void storedValuesOutsideTheRangeAreClamped() throws Exception {
        retention(shortRetention, "3d");
        long fiveDaysOld = finishedRun(shortRetention, DAY.multipliedBy(5));
        long eightDaysOld = finishedRun(shortRetention, DAY.multipliedBy(8));

        BankHousekeepingSummary low = housekeep(shortRetention);

        assertThat(low.historyRetentionDays()).as("3d is clamped up to the 7d minimum").isEqualTo(7);
        assertThat(runs.existsById(fiveDaysOld)).isTrue();
        assertThat(runs.existsById(eightDaysOld)).isFalse();

        retention(shortRetention, "900d");
        assertThat(housekeep(shortRetention).historyRetentionDays())
                .as("900d is clamped down to the 400d maximum").isEqualTo(400);
    }
}
