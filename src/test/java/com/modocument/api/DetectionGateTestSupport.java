package com.modocument.api;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.WebhookStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Shared Spring context of the ws18 gate tests (D1 alerting, D2 export caps,
 * E6 SIEM feed, the synthetic probe): ONE context for all of them — the
 * dynamic properties live on this class only, so Spring's context cache
 * sees the same configuration from every subclass. Both webhook channels
 * (alert webhook, SIEM push) point at the loopback {@link WebhookStub}; the
 * SIEM feed has no settle lag (events are read back immediately); the push
 * forwarder serves bank {@value #PUSH_BANK_CODE}.
 */
@SpringBootTest(properties = {
        "modocument.storage.root=target/ws18-storage",
        "modocument.siem.feed.settle=0s",
        "modocument.siem.webhook.bank-code=" + DetectionGateTestSupport.PUSH_BANK_CODE,
        "modocument.siem.webhook.secret=" + DetectionGateTestSupport.WEBHOOK_SECRET,
        "modocument.alerts.webhook.secret=" + DetectionGateTestSupport.WEBHOOK_SECRET,
        "modocument.alerts.delivery.attempts=1",
        // Lets an ADMIN sign in with a password alone, so admin/root use is
        // exercised through the real login (MFA has its own gate tests).
        "modocument.security.mfa.require-for-admin=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class DetectionGateTestSupport {

    static final String PUSH_BANK_CODE = "WS18PUSH";
    static final String WEBHOOK_SECRET = "ws18-webhook-signing-secret";
    static final String ALERT_PATH = "/ws18/alerts";
    static final String SIEM_PATH = "/ws18/siem";

    /** One audit row's identity and time (for the "< 5 minutes" evidence). */
    record AuditRow(UUID id, Instant occurredAt, String newValues) {
    }

    @DynamicPropertySource
    static void webhookUrls(DynamicPropertyRegistry registry) {
        registry.add("modocument.alerts.webhook.url", () -> WebhookStub.get().url(ALERT_PATH));
        registry.add("modocument.siem.webhook.url", () -> WebhookStub.get().url(SIEM_PATH));
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected BankJpaRepository banks;
    @Autowired
    protected AppUserJpaRepository users;
    @Autowired
    protected AlertLogJpaRepository alertLogs;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected JdbcTemplate jdbc;

    protected static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected Bank newBank(String prefix) {
        String code = (prefix + tag()).toUpperCase().substring(0, Math.min(16, prefix.length() + 8));
        return banks.save(new Bank(code, prefix + " Bank"));
    }

    protected AppUser newUser(Bank bank, String name, Role role, String passwordHash) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + tag() + "@ws18.example.com");
        user.setPasswordHash(passwordHash == null ? "{noop}unused" : passwordHash);
        user.setDisplayName(name);
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        user.setUpdatedDate(LocalDateTime.now());
        return users.save(user);
    }

    protected String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    /** The bank's alerts for one rule, newest first. */
    protected List<AlertLog> alerts(Bank bank, String rule) {
        return alertLogs.findByBankIdOrderByCreatedAtDescAlertLogIdDesc(bank.getBankId(),
                        PageRequest.of(0, 200)).stream()
                .filter(a -> a.getRuleName().equals(rule))
                .toList();
    }

    /** Waits (immediate path is asynchronous) for the bank's first alert of a rule. */
    protected AlertLog awaitAlert(Bank bank, String rule) {
        await().atMost(Duration.ofSeconds(15)).until(() -> !alerts(bank, rule).isEmpty());
        return alerts(bank, rule).get(0);
    }

    /** The bank's audit rows of one action, oldest first. */
    protected List<AuditRow> auditRows(Bank bank, String action) {
        return jdbc.query("SELECT AuditEventId, OccurredAt, CAST(NewValues AS VARCHAR) "
                        + "FROM AuditEvent WHERE BankId = ? AND Action = ? ORDER BY OccurredAt",
                (rs, i) -> new AuditRow(rs.getObject(1, UUID.class), rs.getObject(2, Instant.class),
                        rs.getString(3)),
                bank.getBankId(), action);
    }

    /** Webhook bodies received for this bank's alerts of one rule. */
    protected List<WebhookStub.Received> webhookAlerts(Bank bank, String rule) {
        return WebhookStub.get().receivedOn(ALERT_PATH).stream()
                .filter(r -> r.body().contains("\"bankId\":" + bank.getBankId() + ",")
                        && r.body().contains("\"rule\":\"" + rule + "\""))
                .toList();
    }
}
