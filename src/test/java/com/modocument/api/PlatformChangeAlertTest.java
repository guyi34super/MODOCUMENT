package com.modocument.api;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.Role;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contracts §5: the {@code platform-change} rule raises a HIGH alert when a
 * platform admin changes a bank. A platform admin reactivates a suspended
 * customer bank (the direct lifecycle write, PLATFORM_BANKS); the
 * BANK_REACTIVATED rows it audits in the platform bank and in the customer
 * bank each raise an IMMEDIATE HIGH alert in that bank, without a sweep, and
 * the alert reaches the phone path (the loopback webhook). Like the other
 * detection gate tests, its rows stay (alerts reference them).
 */
class PlatformChangeAlertTest extends DetectionGateTestSupport {

    @Test
    void aPlatformAdminsBankChangeRaisesAHighPlatformChangeAlert() throws Exception {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        AppUser platformAdmin = PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "alert-pc");
        Bank customer = newBank("PCA");
        customer.setStatus(BankStatus.SUSPENDED);
        customer.setSuspendedAt(Instant.now());
        customer = banks.save(customer);

        mvc.perform(post("/api/platform/banks/" + customer.getBankId() + "/reactivate")
                        .header("Authorization", bearer(platformAdmin)))
                .andExpect(status().isOk());

        for (Bank auditBank : List.of(platform, customer)) {
            AlertLog alert = awaitAlert(auditBank, "platform-change", customer);
            assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
            assertThat(alert.getSummary()).contains("BANK_REACTIVATED");
            assertThat(alert.getAuditEventId()).isEqualTo(jdbc.queryForObject(
                    "SELECT AuditEventId FROM AuditEvent WHERE Action = 'BANK_REACTIVATED' "
                            + "AND BankId = ? AND EntityType = 'Bank' AND EntityId = ?",
                    UUID.class, auditBank.getBankId(),
                    String.valueOf(customer.getBankId())));
        }
        Bank finalCustomer = customer;
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !webhookAlerts(finalCustomer, "platform-change").isEmpty());
    }

    /** The bank's platform-change alert about one customer bank (other tests share the platform bank). */
    private AlertLog awaitAlert(Bank bank, String rule, Bank about) {
        await().atMost(Duration.ofSeconds(15)).until(() -> about(bank, rule, about) != null);
        return about(bank, rule, about);
    }

    private AlertLog about(Bank bank, String rule, Bank about) {
        return alerts(bank, rule).stream()
                .filter(a -> a.getAuditEventId() != null && jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AuditEvent WHERE AuditEventId = ? AND EntityId = ?",
                        Integer.class, a.getAuditEventId(), String.valueOf(about.getBankId())) > 0)
                .findFirst().orElse(null);
    }
}
