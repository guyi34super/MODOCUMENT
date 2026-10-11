package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-032: the rule catalogue is configuration data, validated fail-fast,
 * and the SHIPPED defaults (application.yml) are pinned here — changing a
 * default threshold is a visible, reviewed test change.
 */
class AlertRuleCatalogueTest {

    private static AlertProperties.Rule rule(String name, AlertRule.Trigger trigger, String... actions) {
        AlertProperties.Rule rule = new AlertProperties.Rule();
        rule.setName(name);
        rule.setTrigger(trigger);
        rule.setActions(List.of(actions));
        return rule;
    }

    private static AuditFacts facts(String action, AuditOutcome outcome, String role) {
        return new AuditFacts(UUID.randomUUID(), Instant.now(), 3, action, outcome, 42,
                "jane.doe@bank.example", role, "203.0.113.9", "AppUser", "42");
    }

    /** The application.yml catalogue, bound exactly as Spring Boot binds it. */
    private static Map<String, AlertRule> shippedCatalogue() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml.get(0));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        AlertProperties properties = binder.bind("modocument.alerts", Bindable.of(AlertProperties.class))
                .orElseThrow(() -> new AssertionError("modocument.alerts missing from application.yml"));
        return new AlertRuleCatalogue(properties).all().stream()
                .collect(Collectors.toMap(AlertRule::name, Function.identity()));
    }

    @Test
    void theShippedCatalogueCoversTheGateSignalsWithTheDocumentedThresholds() throws Exception {
        Map<String, AlertRule> rules = shippedCatalogue();

        // IMMEDIATE: the D1/D2 single-event signals.
        for (String[] expected : new String[][] {
                {"account-locked", "ACCOUNT_LOCKED"},
                {"refresh-token-reuse", "TOKEN_REFRESH_REUSE_DETECTED"},
                {"export-blocked", "EXPORT_BLOCKED"},
                {"export-over-cap", "EXPORT_OVER_CAP_WITH_REASON"},
                {"sod-violation", "SOD_VIOLATION_FOUND"},
                {"ai-prompt-refused", "AI_PROMPT_REFUSED"},
                {"admin-login", "LOGIN_SUCCESS"}}) {
            AlertRule rule = rules.get(expected[0]);
            assertThat(rule).as(expected[0]).isNotNull();
            assertThat(rule.trigger()).isEqualTo(AlertRule.Trigger.IMMEDIATE);
            assertThat(rule.actions()).contains(expected[1]);
        }
        assertThat(rules.get("export-over-cap").severity()).isEqualTo(AlertSeverity.INFO);
        assertThat(rules.get("admin-login").actorRoles()).containsExactly("ADMIN");

        // WINDOWED: >= 5 failed logins per user / per ip within 5 minutes.
        for (String name : List.of("login-failures-per-user", "login-failures-per-ip")) {
            AlertRule rule = rules.get(name);
            assertThat(rule.trigger()).isEqualTo(AlertRule.Trigger.WINDOWED);
            assertThat(rule.threshold()).isEqualTo(5);
            assertThat(rule.window()).isEqualTo(Duration.ofMinutes(5));
        }
        assertThat(rules.get("login-failures-per-user").groupBy()).isEqualTo(AlertRule.GroupBy.ACTOR);
        assertThat(rules.get("login-failures-per-ip").groupBy()).isEqualTo(AlertRule.GroupBy.IP);
        assertThat(rules.get("authorization-denied-burst").threshold()).isEqualTo(10);
        assertThat(rules.get("admin-action-burst").matchesAnyAction()).isTrue();
        assertThat(rules.get("admin-action-burst").actorRoles()).containsExactly("ADMIN");
        assertThat(rules.get("export-burst").actions()).containsExactly("EXPORT_RUN");

        // Every window keeps detection inside the 5-minute target's spirit:
        // the sweep runs each minute regardless of the aggregation window.
        assertThat(rules.values()).allSatisfy(rule ->
                assertThat(rule.window()).isBetween(Duration.ofMinutes(1), Duration.ofHours(24)));
    }

    /** Contracts §5: any platform-owner change is one HIGH alert, no threshold. */
    @Test
    void aPlatformAdminsChangeMatchesTheHighPlatformChangeRule() throws Exception {
        AlertRule rule = shippedCatalogue().get("platform-change");

        assertThat(rule).isNotNull();
        assertThat(rule.trigger()).isEqualTo(AlertRule.Trigger.IMMEDIATE);
        assertThat(rule.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(rule.threshold()).isEqualTo(1);
        for (String action : List.of("PLATFORM_SETTING_UPDATED", "PLATFORM_BOOTSTRAPPED",
                "BANK_ONBOARDED", "BANK_SUSPENDED", "BANK_REACTIVATED", "BANK_ADMIN_ADDED",
                "EGRESS_RULE_APPROVED")) {
            assertThat(rule.matches(facts(action, AuditOutcome.SUCCESS, "PLATFORM_ADMIN")))
                    .as(action).isTrue();
        }
        assertThat(rule.matches(facts("SETTING_UPDATED", AuditOutcome.SUCCESS, "ADMIN")))
                .as("a bank's own setting change is not a platform change").isFalse();
    }

    /**
     * AC-EXT-15: {@code parse-fallback-surge} — 20 INGESTION_PARSE_FALLBACK
     * audits per bank within an hour raise one MEDIUM alert (the threshold
     * and window are the platform settings alerts.parseFallbackSurge.*).
     */
    @Test
    void fallbackSurge() throws Exception {
        AlertRule rule = shippedCatalogue().get("parse-fallback-surge");

        assertThat(rule).as("parse-fallback-surge is shipped").isNotNull();
        assertThat(rule.trigger()).isEqualTo(AlertRule.Trigger.WINDOWED);
        assertThat(rule.actions()).containsExactly("INGESTION_PARSE_FALLBACK");
        assertThat(rule.threshold()).isEqualTo(20);
        assertThat(rule.window()).isEqualTo(Duration.ofHours(1));
        assertThat(rule.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(rule.groupBy()).isEqualTo(AlertRule.GroupBy.BANK);

        // Every role's fallback counts (machine-created jobs included) …
        assertThat(rule.matches(facts("INGESTION_PARSE_FALLBACK", AuditOutcome.SUCCESS, "READ_WRITE"))).isTrue();
        assertThat(rule.matches(facts("INGESTION_PARSE_FALLBACK", AuditOutcome.SUCCESS, null))).isTrue();
        // … but nothing else does: a breaker opening has its own rule.
        assertThat(rule.matches(facts("AI_CIRCUIT_OPENED", AuditOutcome.SUCCESS, "READ_WRITE"))).isFalse();
        assertThat(rule.matches(facts("EXPORT_RUN", AuditOutcome.SUCCESS, "READ_WRITE"))).isFalse();

        // Grouped per bank: two users' fallbacks in one bank are one surge
        // (windows are counted per bank; the label names the bank).
        AuditFacts alice = new AuditFacts(UUID.randomUUID(), Instant.now(), 3, "INGESTION_PARSE_FALLBACK",
                AuditOutcome.SUCCESS, 41, "alice@bank.example", "READ_WRITE", "203.0.113.9", "IngestionJob", "1");
        AuditFacts bob = new AuditFacts(UUID.randomUUID(), Instant.now(), 3, "INGESTION_PARSE_FALLBACK",
                AuditOutcome.SUCCESS, 42, "bob@bank.example", "ADMIN", "198.51.100.7", "IngestionJob", "2");
        AuditFacts otherBank = new AuditFacts(UUID.randomUUID(), Instant.now(), 4, "INGESTION_PARSE_FALLBACK",
                AuditOutcome.SUCCESS, 43, "carol@other.example", "READ_WRITE", "192.0.2.1", "IngestionJob", "3");
        assertThat(rule.groupKey(alice)).isEqualTo(rule.groupKey(bob));
        assertThat(rule.groupLabel(alice)).isEqualTo("bank 3");
        assertThat(rule.groupLabel(otherBank)).isEqualTo("bank 4");
    }

    @Test
    void matchingHonoursActionsOutcomesRolesAndNeverAlertsOnItsOwnEvents() {
        AlertProperties.Rule raw = rule("admin-burst", AlertRule.Trigger.WINDOWED, "*");
        raw.setActorRoles(List.of("admin"));
        raw.setOutcomes(List.of("success"));
        raw.setThreshold(3);
        AlertRule rule = AlertRuleCatalogue.build(List.of(raw)).get(0);

        assertThat(rule.matchesAnyAction()).isTrue();
        assertThat(rule.matches(facts("TEMPLATE_UPDATED", AuditOutcome.SUCCESS, "ADMIN"))).isTrue();
        assertThat(rule.matches(facts("TEMPLATE_UPDATED", AuditOutcome.DENIED, "ADMIN"))).isFalse();
        assertThat(rule.matches(facts("TEMPLATE_UPDATED", AuditOutcome.SUCCESS, "READ_WRITE"))).isFalse();
        assertThat(rule.matches(facts("ALERT_SENT", AuditOutcome.SUCCESS, "ADMIN")))
                .as("ALERT_* rows never alert (no feedback loop)").isFalse();
    }

    @Test
    void groupLabelsAreScrubbed() {
        AlertProperties.Rule raw = rule("r", AlertRule.Trigger.IMMEDIATE, "LOGIN_FAILED");
        AlertRule rule = AlertRuleCatalogue.build(List.of(raw)).get(0);
        AuditFacts unknownAccount = new AuditFacts(UUID.randomUUID(), Instant.now(), 1,
                "LOGIN_FAILED", AuditOutcome.FAILED, null, "jane.doe@bank.example", null,
                "203.0.113.9", null, null);

        assertThat(rule.groupKey(unknownAccount)).isEqualTo("email:jane.doe@bank.example");
        assertThat(rule.groupLabel(unknownAccount)).isEqualTo("account j***@bank.example");
    }

    @Test
    void immediateRulesAlwaysFireOnOneEvent() {
        AlertProperties.Rule raw = rule("r", AlertRule.Trigger.IMMEDIATE, "ACCOUNT_LOCKED");
        raw.setThreshold(9);
        assertThat(AlertRuleCatalogue.build(List.of(raw)).get(0).threshold()).isEqualTo(1);
    }

    @Test
    void disabledRulesAreSkipped() {
        AlertProperties.Rule raw = rule("off", AlertRule.Trigger.IMMEDIATE, "ACCOUNT_LOCKED");
        raw.setEnabled(false);
        assertThat(AlertRuleCatalogue.build(List.of(raw))).isEmpty();
    }

    @Test
    void brokenRulesFailFast() {
        AlertProperties.Rule noTrigger = rule("a", null, "ACCOUNT_LOCKED");
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(noTrigger)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trigger");

        AlertProperties.Rule noActions = rule("b", AlertRule.Trigger.WINDOWED);
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(noActions)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("action");

        AlertProperties.Rule tinyWindow = rule("c", AlertRule.Trigger.WINDOWED, "LOGIN_FAILED");
        tinyWindow.setWindow(Duration.ofSeconds(10));
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(tinyWindow)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("window");

        AlertProperties.Rule zero = rule("d", AlertRule.Trigger.WINDOWED, "LOGIN_FAILED");
        zero.setThreshold(0);
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(zero)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("threshold");

        AlertProperties.Rule badOutcome = rule("e", AlertRule.Trigger.WINDOWED, "LOGIN_FAILED");
        badOutcome.setOutcomes(List.of("MAYBE"));
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(badOutcome)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outcome");

        AlertProperties.Rule one = rule("dup", AlertRule.Trigger.IMMEDIATE, "ACCOUNT_LOCKED");
        AlertProperties.Rule two = rule("dup", AlertRule.Trigger.IMMEDIATE, "LOGIN_FAILED");
        assertThatThrownBy(() -> AlertRuleCatalogue.build(List.of(one, two)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }
}
