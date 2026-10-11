package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA, optimization round, test plan R1-R4 (contracts §3): viewing the audit
 * log is itself audited — one {@code AUDIT_LOG_VIEWED} row per actor per 15
 * minutes, carrying the actor, the bank and the filters; another actor gets
 * its own row; reading audit rows never emits {@code SENSITIVE_DATA_VIEWED}
 * (no recursion); the bank admin cannot switch the platform-controlled
 * {@code audit.readAuditEnabled}. The 15-minute expiry itself is a clock
 * test: {@code AuditThrottleTest#dedupeWritesTheFirstReadPerWindowAndReleaseRetries}.
 */
class AuditViewAuditQaTest extends DetectionGateTestSupport {

    private Bank bank;
    private AppUser admin;
    private AppUser approver;

    @BeforeEach
    void seed() {
        bank = newBank("AVQ");
        admin = newUser(bank, "avq-admin", Role.ADMIN, null);
        approver = newUser(bank, "avq-approver", Role.APPROVER, null);
    }

    private List<AuditRow> views(AppUser actor) {
        return jdbc.query("SELECT AuditEventId, OccurredAt, CAST(NewValues AS VARCHAR) FROM AuditEvent "
                        + "WHERE BankId = ? AND Action = 'AUDIT_LOG_VIEWED' AND ActorUserId = ? "
                        + "ORDER BY OccurredAt",
                (rs, i) -> new AuditRow(rs.getObject(1, java.util.UUID.class),
                        rs.getObject(2, java.time.Instant.class), rs.getString(3)),
                bank.getBankId(), actor.getAppUserId());
    }

    @Test
    void theFirstViewIsOneRowWithTheFiltersAndRepeatsByTheSameActorAreNot() throws Exception {
        mvc.perform(get("/api/admin/audit").param("outcome", "DENIED").param("entityType", "IngestionJob")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        List<AuditRow> first = views(admin);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).newValues()).contains("DENIED").contains("IngestionJob");

        // "Load more", other filters, the action picker, the export page: no new view row.
        mvc.perform(get("/api/admin/audit").param("limit", "1").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit/actions").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").param("action", "LOGIN_FAILED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(views(admin)).as("deduplicated within 15 minutes").hasSize(1);

        // Another actor of the same bank: its own row.
        mvc.perform(get("/api/admin/audit").header("Authorization", bearer(approver)))
                .andExpect(status().isOk());
        assertThat(views(approver)).hasSize(1);
        assertThat(views(admin)).hasSize(1);
    }

    @Test
    void readingAuditRowsNeverAuditsReadsOfTheViewedRows() throws Exception {
        // Something to read: the admin's own user-list read (one SENSITIVE_DATA_VIEWED).
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        int sensitiveBefore = auditRows(bank, "SENSITIVE_DATA_VIEWED").size();

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/admin/audit").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/audit/export").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(auditRows(bank, "SENSITIVE_DATA_VIEWED")).as("no recursion").hasSize(sensitiveBefore);
        assertThat(views(admin)).hasSize(1);
        assertThat(auditRows(bank, "AUDIT_LOG_EXPORTED")).hasSize(1);
    }

    @Test
    void theBankAdminCannotSwitchTheReadAuditSetting() throws Exception {
        mvc.perform(put("/api/admin/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[{\"key\":\"audit.readAuditEnabled\",\"value\":false,"
                                + "\"expectedVersion\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_LOCKED"))
                .andExpect(jsonPath("$.key").value("audit.readAuditEnabled"));
        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM BankSetting WHERE BankId = ? "
                + "AND SettingKey = 'audit.readAuditEnabled'", Integer.class, bank.getBankId());
        assertThat(stored).isZero();
    }
}
