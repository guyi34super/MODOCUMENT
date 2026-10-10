package com.modocument.infrastructure.settings;

import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingGroup;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.domain.model.settings.SettingValues;
import com.modocument.infrastructure.alerting.AlertProperties;
import com.modocument.infrastructure.alerting.AlertRule;
import com.modocument.infrastructure.alerting.AlertRuleCatalogue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings catalogue may never put infrastructure wiring or secrets in
 * the UI (ws20, plan §1 "Never in UI"), and must describe the real
 * configuration:
 * <ul>
 *   <li>no key or property looks like a secret, key, token, credential,
 *       URL/URI, host list, CORS origin, path/root, username, client id,
 *       AI model, cron or multipart limit;</li>
 *   <li>every property exists — in the shipped application.yml, or as an
 *       {@code @Value("${name:default}")} default in the main sources — and
 *       its shipped default equals the catalogue's code default (so the
 *       docs and the code-default fallback never drift from production);</li>
 *   <li>with the shipped configuration no default violates its limits, so
 *       {@code MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true} boots;</li>
 *   <li>{@code SettingKeys} and the catalogue list the same keys.</li>
 * </ul>
 */
class SettingsCatalogueSafetyTest {

    /**
     * Secret / wiring vocabulary. Narrow look-aheads let through the
     * legitimate shapes: a password's minimum length or change quota, a
     * token's lifetime or PII tokenization, and the plural "keys".
     */
    static final Pattern DENY = Pattern.compile("(?i)("
            + "secret|credential|private|passphrase"
            + "|passw(or)?d(?![.\\-_]?(min|change))"
            + "|key(?!s)"
            + "|token(?![.\\-_]?(ttl|ize))"
            + "|urls?(?![a-z])|uris?(?![a-z])|origins|allow-?hosts|host-?list"
            + "|root|path|username|client-?id|issuer|cert"
            + "|model|cron|dsn|jdbc|datasource|flyway|multipart|max-(file|request)-size"
            + "|cors|ssrf|storage|bootstrap|bank-?code)");

    private static StandardEnvironment shipped;

    @BeforeAll
    static void loadShippedConfiguration() throws IOException {
        // application.yml exactly as shipped: no environment variables or
        // system properties, so every ${ENV:default} resolves to its default.
        shipped = new StandardEnvironment();
        shipped.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        shipped.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        shipped.getPropertySources().addFirst(yaml.get(0));
    }

    @Test
    void theDenyListCatchesEveryNeverInUiProperty() {
        for (String forbidden : List.of(
                "modocument.security.jwt.secret", "modocument.security.mfa.key",
                "aifiles.openai.api-key", "aifiles.openai.base-url", "aifiles.openai.model-id",
                "spring.ai.openai.chat.options.model", "spring.datasource.url",
                "spring.datasource.username", "spring.datasource.password",
                "spring.flyway.user", "modocument.cors.allowed-origins",
                "modocument.security.ssrf.allow-hosts", "modocument.storage.root",
                "modocument.retention.cron", "modocument.sod.cron",
                "spring.servlet.multipart.max-file-size", "modocument.alerts.webhook.url",
                "modocument.alerts.webhook.secret", "modocument.siem.webhook.bank-code",
                "modocument.oidc.providers.entra.client-id",
                "modocument.oidc.providers.entra.client-secret",
                "modocument.oidc.providers.entra.issuer-uri",
                "modocument.doccreation.soffice-path", "modocument.security.permit-dev-paths",
                "integration.connection.apiKey", "integration.bearerToken",
                "ai.baseUrl", "security.mfa.encryptionKey")) {
            assertThat(DENY.matcher(forbidden).find()).as(forbidden).isTrue();
        }
    }

    @Test
    void noKeyOrPropertyLooksLikeASecretOrInfrastructureWiring() {
        List<String> offenders = new ArrayList<>();
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            check(definition.key(), offenders);
            if (definition.propertyName() != null) {
                check(definition.propertyName(), offenders);
            }
        }
        assertThat(offenders).as("secret/URL-like settings must stay out of the catalogue")
                .isEmpty();
    }

    @Test
    void everyPropertyExistsAndShipsTheCatalogueDefault() throws IOException {
        String mainSources = mainSources();
        List<String> problems = new ArrayList<>();
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            String property = definition.propertyName();
            if (property == null) {
                continue;
            }
            String resolved = SettingsCatalogue.resolvePropertyName(property, shipped);
            String shippedText;
            if (resolved != null && shipped.containsProperty(resolved)) {
                shippedText = shipped.getProperty(resolved);
            } else {
                // Not in application.yml: must be consumed through an
                // @Value("${name:default}") whose default we compare.
                Matcher valueDefault = Pattern.compile("\\$\\{" + Pattern.quote(property)
                        + ":([^}]*)}").matcher(mainSources);
                if (!valueDefault.find()) {
                    problems.add(definition.key() + ": property " + property
                            + " is neither in application.yml nor an @Value default");
                    continue;
                }
                shippedText = valueDefault.group(1);
            }
            Object shippedValue = definition.type() == SettingType.ENUM
                    ? definition.parse(shippedText)
                    : SettingValues.parse(definition.type(), shippedText,
                    definition.propertyUnit());
            if (!SettingValues.same(definition.type(), shippedValue, definition.codeDefault())) {
                problems.add(definition.key() + ": ships " + shippedText + " but the catalogue "
                        + "says " + definition.format(definition.codeDefault()));
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theShippedConfigurationBootsWithGuardrailsEnforced() {
        SettingsCatalogue catalogue = new SettingsCatalogue(SettingDefinitions.ALL, shipped);
        assertThat(catalogue.defaultViolations()).isEmpty();
        new SettingsStartupCheck(catalogue, true);
        // Every list selector (alert rules by name) resolved.
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            if (definition.propertyName() != null && definition.propertyName().contains("[")) {
                assertThat(catalogue.resolvedPropertyName(definition))
                        .as(definition.propertyName()).matches(".*\\[\\d+].*");
            }
        }
    }

    @Test
    void theShippedAlertCatalogueWatchesSettingsChanges() {
        AlertProperties properties = Binder.get(shipped)
                .bind("modocument.alerts", Bindable.of(AlertProperties.class))
                .orElseThrow(() -> new AssertionError("modocument.alerts missing"));
        List<AlertRule> rules = new AlertRuleCatalogue(properties).all();
        AlertRule security = rules.stream()
                .filter(rule -> rule.name().equals("security-setting-changed"))
                .findFirst().orElseThrow();
        assertThat(security.trigger()).isEqualTo(AlertRule.Trigger.IMMEDIATE);
        assertThat(security.actions()).containsExactly("SECURITY_SETTING_UPDATED");
        assertThat(security.severity()).isEqualTo(AlertSeverity.MEDIUM);
        AlertRule platform = rules.stream()
                .filter(rule -> rule.name().equals("platform-change"))
                .findFirst().orElseThrow();
        assertThat(platform.trigger()).isEqualTo(AlertRule.Trigger.IMMEDIATE);
        assertThat(platform.actions())
                .containsExactlyInAnyOrder("PLATFORM_SETTING_UPDATED", "PLATFORM_BOOTSTRAPPED",
                        "BANK_ONBOARDED", "BANK_SUSPENDED", "BANK_REACTIVATED",
                        "BANK_ADMIN_ADDED", "EGRESS_RULE_APPROVED");
        assertThat(platform.groupBy()).isEqualTo(AlertRule.GroupBy.ENTITY);
        assertThat(platform.severity()).isEqualTo(AlertSeverity.HIGH);
        AlertRule privileged = rules.stream()
                .filter(rule -> rule.name().equals("privileged-change"))
                .findFirst().orElseThrow();
        assertThat(privileged.actions()).contains("USER_PASSWORD_RESET", "USER_MFA_RESET");
        // ws23 actions alert as soon as the extraction workstream audits them.
        AlertRule surge = rules.stream()
                .filter(rule -> rule.name().equals("parse-fallback-surge"))
                .findFirst().orElseThrow();
        assertThat(surge.trigger()).isEqualTo(AlertRule.Trigger.WINDOWED);
        assertThat(surge.actions()).containsExactly("INGESTION_PARSE_FALLBACK");
        assertThat(surge.threshold()).isEqualTo(20);
        assertThat(surge.window()).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(surge.severity()).isEqualTo(AlertSeverity.MEDIUM);
        AlertRule circuit = rules.stream()
                .filter(rule -> rule.name().equals("ai-circuit-opened"))
                .findFirst().orElseThrow();
        assertThat(circuit.trigger()).isEqualTo(AlertRule.Trigger.IMMEDIATE);
        assertThat(circuit.actions()).containsExactly("AI_CIRCUIT_OPENED");
        assertThat(circuit.severity()).isEqualTo(AlertSeverity.HIGH);
    }

    /**
     * Alert tuning is generated per WINDOWED rule: every windowed rule of the
     * shipped catalogue has a threshold AND a window setting naming it, and
     * every alert-tuning setting names a shipped windowed rule. Adding a
     * windowed rule to application.yml without its two settings (or
     * renaming a rule) fails here.
     */
    @Test
    void everyShippedWindowedRuleHasItsThresholdAndWindowSettings() {
        AlertProperties properties = Binder.get(shipped)
                .bind("modocument.alerts", Bindable.of(AlertProperties.class))
                .orElseThrow(() -> new AssertionError("modocument.alerts missing"));
        Set<String> windowed = new HashSet<>();
        for (AlertRule rule : new AlertRuleCatalogue(properties).all()) {
            if (rule.trigger() == AlertRule.Trigger.WINDOWED) {
                windowed.add(rule.name());
            }
        }
        Pattern tuning = Pattern.compile(
                "^modocument\\.alerts\\.rules\\[([^\\]]+)]\\.(threshold|window)$");
        Set<String> thresholds = new HashSet<>();
        Set<String> windows = new HashSet<>();
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            if (definition.propertyName() == null) {
                continue;
            }
            Matcher matcher = tuning.matcher(definition.propertyName());
            if (matcher.matches()) {
                (matcher.group(2).equals("threshold") ? thresholds : windows)
                        .add(matcher.group(1));
                assertThat(definition.group()).as(definition.key())
                        .isEqualTo(SettingGroup.SECURITY_ALERTS);
            }
        }
        assertThat(thresholds)
                .as("alertThreshold(...) settings in SettingDefinitions vs WINDOWED rules in "
                        + "application.yml")
                .isEqualTo(windowed);
        assertThat(windows)
                .as("alertWindow(...) settings in SettingDefinitions vs WINDOWED rules in "
                        + "application.yml")
                .isEqualTo(windowed);
    }

    @Test
    void settingKeysAndTheCatalogueListTheSameKeys() throws IllegalAccessException {
        Set<String> constants = new HashSet<>();
        for (Field field : SettingKeys.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)
                    && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }
        Set<String> catalogued = new HashSet<>();
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            assertThat(catalogued.add(definition.key())).as("duplicate " + definition.key())
                    .isTrue();
        }
        assertThat(catalogued).isEqualTo(constants);
    }

    @Test
    void everyTabHasSettingsAndAlertTuningIsPlatformOnly() {
        for (SettingGroup group : SettingGroup.values()) {
            assertThat(SettingDefinitions.ALL).as(group.id())
                    .anyMatch(definition -> definition.group() == group);
        }
        assertThat(SettingDefinitions.ALL)
                .filteredOn(definition -> definition.group() == SettingGroup.SECURITY_ALERTS)
                .allMatch(definition -> definition.scope() == SettingScope.PLATFORM);
    }

    private static void check(String name, List<String> offenders) {
        Matcher matcher = DENY.matcher(name);
        if (matcher.find()) {
            offenders.add(name + " (matches '" + matcher.group() + "')");
        }
    }

    private static String mainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }
}
