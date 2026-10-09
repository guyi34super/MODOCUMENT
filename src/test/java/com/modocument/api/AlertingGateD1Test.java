package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AlertStatus;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.alerting.AlertSweepJob;
import com.modocument.infrastructure.alerting.WebhookAlertChannel;
import com.modocument.testsupport.WebhookStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate D1 (SEC-032): security alerts for failed logins, admin/root use and
 * large exports reach a person in under 5 minutes.
 *
 * <ul>
 *   <li>WINDOWED: five failed logins for one account -> one sweep run ->
 *       an AlertLog row for login-failures-per-user (5 events) AND the phone
 *       path called (the loopback webhook receives the signed-off JSON),
 *       the row SENT with the webhook among its channels; a second sweep
 *       inside the window raises nothing (dedupe: one alert per burst);</li>
 *   <li>IMMEDIATE: ACCOUNT_LOCKED alerts without any sweep, created within
 *       seconds of the audit row;</li>
 *   <li>admin/root use: an ADMIN sign-in raises admin-login;</li>
 *   <li>visibility: GET /api/admin/alerts shows the bank's alerts to its
 *       admin, none of them to another bank's admin, and refuses non-admins.</li>
 * </ul>
 * The export half of D1 (large exports) is ExportCapGateD2Test.
 */
class AlertingGateD1Test extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Correct-Horse-Battery-9!";

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AlertSweepJob sweep;
    @Autowired
    private AuditRecorder audit;

    private Bank bank;
    private AppUser admin;
    private AppUser member;
    private String clientIp;

    @BeforeEach
    void seed() {
        bank = newBank("D1A");
        admin = newUser(bank, "d1-admin", Role.ADMIN, null);
        member = newUser(bank, "d1-member", Role.READ_WRITE, passwordEncoder.encode(PASSWORD));
        clientIp = "10.18." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    private void login(AppUser user, String password, int expectedStatus) throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", password))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void fiveFailedLoginsRaiseOneWindowedAlertThatReachesThePhonePath() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(member, "wrong-password-" + i, 401);
        }
        // Not locked yet (the 6th attempt would lock), so nothing immediate:
        assertThat(alerts(bank, "account-locked")).isEmpty();
        assertThat(alerts(bank, "login-failures-per-user")).isEmpty();

        List<AlertLog> raised = sweep.runOnce();

        AlertLog alert = raised.stream()
                .filter(a -> a.getBankId() == bank.getBankId()
                        && a.getRuleName().equals("login-failures-per-user"))
                .findFirst().orElseThrow();
        assertThat(alert.getEventCount()).isEqualTo(5);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alert.getSummary())
                .contains("5 LOGIN_FAILED event(s) within 5m")
                .contains("user #" + member.getAppUserId())
                .doesNotContain(member.getEmail());
        // The per-ip rule sees the same burst from the same address.
        assertThat(raised).anyMatch(a -> a.getBankId() == bank.getBankId()
                && a.getRuleName().equals("login-failures-per-ip"));

        // The phone path: the webhook received it, and the row records the delivery.
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !webhookAlerts(bank, "login-failures-per-user").isEmpty());
        WebhookStub.Received received = webhookAlerts(bank, "login-failures-per-user").get(0);
        assertThat(received.header("X-MoDocument-Signature"))
                .as("body signed with the configured secret")
                .isEqualTo("sha256=" + WebhookAlertChannel.hmac(WEBHOOK_SECRET, received.body()));
        JsonNode body = MAPPER.readTree(received.body());
        assertThat(body.get("text").asText()).contains("login-failures-per-user");
        assertThat(body.get("alert").get("severity").asText()).isEqualTo("HIGH");
        assertThat(body.get("alert").get("eventCount").asInt()).isEqualTo(5);

        await().atMost(Duration.ofSeconds(15)).until(() ->
                alertLogs.findById(alert.getAlertLogId()).orElseThrow().getStatus() == AlertStatus.SENT);
        AlertLog delivered = alertLogs.findById(alert.getAlertLogId()).orElseThrow();
        assertThat(delivered.getChannel()).contains("log").contains("webhook");
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(Duration.between(delivered.getCreatedAt(), delivered.getDeliveredAt()))
                .isLessThan(Duration.ofMinutes(5));
        Integer sentAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'ALERT_SENT' AND BankId = ? "
                        + "AND EntityId = ?",
                Integer.class, bank.getBankId(), String.valueOf(alert.getAlertLogId()));
        assertThat(sentAudits).as("ALERT_SENT per accepting channel (log + webhook)").isEqualTo(2);

        // Dedupe: the same burst inside the window raises nothing more.
        List<AlertLog> again = sweep.runOnce();
        assertThat(again).noneMatch(a -> a.getBankId() == bank.getBankId());
        assertThat(alerts(bank, "login-failures-per-user")).hasSize(1);
    }

    @Test
    void accountLockedAlertsImmediatelyWithoutTheSweep() throws Exception {
        for (int i = 0; i < 6; i++) {
            login(member, "wrong-password-" + i, 401);
        }

        AlertLog alert = awaitAlert(bank, "account-locked");

        AuditRow locked = auditRows(bank, "ACCOUNT_LOCKED").get(0);
        assertThat(alert.getAuditEventId()).isEqualTo(locked.id());
        assertThat(Duration.between(locked.occurredAt(), alert.getCreatedAt()))
                .as("detection latency (audit row -> alert row)")
                .isLessThan(Duration.ofSeconds(30));
        assertThat(alert.getSummary()).contains("ACCOUNT_LOCKED").contains("user #" + member.getAppUserId());

        await().atMost(Duration.ofSeconds(15))
                .until(() -> !webhookAlerts(bank, "account-locked").isEmpty());
        // No sweep ran in this test: the windowed rules have not fired.
        assertThat(alerts(bank, "login-failures-per-user")).isEmpty();
    }

    @Test
    void anAdministratorSigningInRaisesAPrivilegedUseAlert() throws Exception {
        AppUser root = newUser(bank, "d1-root", Role.ADMIN, passwordEncoder.encode(PASSWORD));

        login(root, PASSWORD, 200);

        AlertLog alert = awaitAlert(bank, "admin-login");
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.LOW);
        assertThat(alert.getSummary()).contains("LOGIN_SUCCESS").contains("user #" + root.getAppUserId());
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !webhookAlerts(bank, "admin-login").isEmpty());
    }

    @Test
    void alertsAreVisibleToTheBanksAdminOnly() throws Exception {
        audit.record(AuditEventSpec.builder("ACCOUNT_LOCKED", AuditOutcome.DENIED)
                .actorUserId(member.getAppUserId())
                .bankId(bank.getBankId())
                .entity("AppUser", String.valueOf(member.getAppUserId()))
                .build());
        AlertLog alert = awaitAlert(bank, "account-locked");

        MvcResult own = mvc.perform(get("/api/admin/alerts").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode ownRows = MAPPER.readTree(own.getResponse().getContentAsString());
        assertThat(ownRows).anyMatch(row -> row.get("alertId").asInt() == alert.getAlertLogId()
                && row.get("rule").asText().equals("account-locked")
                && row.get("severity").asText().equals("HIGH"));

        // Another bank's admin: none of bank A's alerts, only its own bank's.
        Bank other = newBank("D1B");
        AppUser otherAdmin = newUser(other, "d1-other-admin", Role.ADMIN, null);
        MvcResult foreign = mvc.perform(get("/api/admin/alerts")
                        .header("Authorization", bearer(otherAdmin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode foreignRows = MAPPER.readTree(foreign.getResponse().getContentAsString());
        List<Integer> ownIds = alertLogs.findAll().stream()
                .filter(a -> a.getBankId() == bank.getBankId())
                .map(AlertLog::getAlertLogId).toList();
        assertThat(foreignRows).noneMatch(row -> ownIds.contains(row.get("alertId").asInt()));
        for (JsonNode row : foreignRows) {
            assertThat(alertLogs.findById(row.get("alertId").asInt()).orElseThrow().getBankId())
                    .isEqualTo(other.getBankId());
        }

        // Not an admin: refused.
        mvc.perform(get("/api/admin/alerts").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

}
