package com.modocument.infrastructure.settings;

import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.domain.model.settings.SettingSource;
import com.modocument.domain.model.settings.SettingValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolution order and guardrails of the settings layer (ws20): bank
 * override → platform value → shipped default, stored values clamped into
 * [max(hardMin, bankMin), min(hardMax, bankMax)], invalid stored values
 * skipped, the shipped default honoured as configured (its hard-limit check
 * is the startup check's job) but kept inside the platform's bank bounds.
 */
class SettingsResolverTest {

    private static final int PLATFORM = 1;
    private static final int BANK_A = 10;
    private static final int BANK_B = 20;

    private MockEnvironment environment;
    private SettingsCatalogue catalogue;
    private SettingsCache cache;
    private SettingsResolver resolver;
    private final List<BankSetting> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment()
                .withProperty("modocument.security.password.min-length", "14")
                .withProperty("modocument.security.session.idle-timeout", "8h")
                .withProperty("modocument.ratelimit.window-millis", "7200000")
                .withProperty("modocument.retention.customer-data-days", "45")
                .withProperty("modocument.alerts.rules[0].name", "account-locked")
                .withProperty("modocument.alerts.rules[1].name", "export-burst")
                .withProperty("modocument.alerts.rules[1].threshold", "7")
                .withProperty("modocument.alerts.rules[1].window", "15m");
        rebuild();
    }

    private void rebuild() {
        catalogue = new SettingsCatalogue(SettingDefinitions.ALL, environment);
        cache = mock(SettingsCache.class);
        resolver = new SettingsResolver(catalogue, cache);
        snapshot();
    }

    private void snapshot() {
        when(cache.current()).thenReturn(SettingsSnapshot.build(3L, OptionalInt.of(PLATFORM),
                rows, catalogue));
    }

    private void store(int bankId, String key, String value, String bankMin, String bankMax,
                       int version) {
        BankSetting row = new BankSetting(bankId, key);
        row.setValueText(value);
        row.setBankMinText(bankMin);
        row.setBankMaxText(bankMax);
        row.setVersion(version);
        rows.add(row);
        snapshot();
    }

    private SettingsResolver.Resolution resolve(String key, int bankId) {
        return resolver.resolve(catalogue.require(key), bankId);
    }

    @Test
    void withNoStoredValueTheShippedPropertyIsTheDefault() {
        SettingsResolver.Resolution r = resolve(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A);
        assertThat(r.value()).isEqualTo(14);
        assertThat(r.source()).isEqualTo(SettingSource.DEFAULT);
        assertThat(r.clamped()).isFalse();
        assertThat(r.version()).isZero();
        assertThat(r.min()).isEqualTo(12);
        assertThat(r.max()).isEqualTo(128);
        // A setting without a property falls back to its code default.
        assertThat(resolver.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK_A))
                .isEqualTo("AI_WITH_CODE_FALLBACK");
        assertThat(resolver.getDecimal(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, BANK_A))
                .isEqualByComparingTo("0.75");
    }

    @Test
    void bankOverrideWinsOverPlatformValueWhichWinsOverTheDefault() {
        store(PLATFORM, SettingKeys.PASSWORD_MIN_LENGTH, "16", null, null, 2);
        assertThat(resolve(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A).source())
                .isEqualTo(SettingSource.PLATFORM);
        assertThat(resolver.getInt(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A)).isEqualTo(16);

        store(BANK_A, SettingKeys.PASSWORD_MIN_LENGTH, "20", null, null, 5);
        SettingsResolver.Resolution a = resolve(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A);
        assertThat(a.value()).isEqualTo(20);
        assertThat(a.source()).isEqualTo(SettingSource.BANK);
        assertThat(a.version()).isEqualTo(5);
        // Bank B is untouched by bank A's override.
        assertThat(resolver.getInt(SettingKeys.PASSWORD_MIN_LENGTH, BANK_B)).isEqualTo(16);
        // The platform bank itself sees the platform layer.
        assertThat(resolve(SettingKeys.PASSWORD_MIN_LENGTH, PLATFORM).version()).isEqualTo(2);
        assertThat(resolver.getIntForPlatform(SettingKeys.PASSWORD_MIN_LENGTH)).isEqualTo(16);
    }

    @Test
    void storedValuesAreClampedIntoHardLimitsAndPlatformBounds() {
        store(PLATFORM, SettingKeys.RATELIMIT_UPLOADS, null, "5", "50", 1);
        store(BANK_A, SettingKeys.RATELIMIT_UPLOADS, "150", null, null, 1);
        store(BANK_B, SettingKeys.RATELIMIT_UPLOADS, "2", null, null, 1);

        SettingsResolver.Resolution a = resolve(SettingKeys.RATELIMIT_UPLOADS, BANK_A);
        assertThat(a.value()).isEqualTo(50);
        assertThat(a.clamped()).isTrue();
        assertThat(a.min()).isEqualTo(5);
        assertThat(a.max()).isEqualTo(50);
        assertThat(resolver.getInt(SettingKeys.RATELIMIT_UPLOADS, BANK_B)).isEqualTo(5);

        // A stored value beyond the HARD limit (limits tightened by a release).
        store(BANK_A, SettingKeys.PASSWORD_MIN_LENGTH, "500", null, null, 1);
        SettingsResolver.Resolution p = resolve(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A);
        assertThat(p.value()).isEqualTo(128);
        assertThat(p.clamped()).isTrue();
    }

    @Test
    void theShippedDefaultIsHonouredAsConfiguredButKeptInsidePlatformBounds() {
        // 45 days is outside the gate-C5 ceiling (30): the startup check
        // reports it; the resolver keeps the configured behaviour.
        SettingsResolver.Resolution r = resolve(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, BANK_A);
        assertThat(r.value()).isEqualTo(45);
        assertThat(r.clamped()).isFalse();
        assertThat(catalogue.defaultViolations())
                .extracting(v -> v.definition().key())
                .containsExactly(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS);

        // Once the platform bounds the range, the default is clamped into it.
        store(PLATFORM, SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, null, "7", "14", 1);
        SettingsResolver.Resolution bounded =
                resolve(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, BANK_A);
        assertThat(bounded.value()).isEqualTo(14);
        assertThat(bounded.source()).isEqualTo(SettingSource.DEFAULT);
        assertThat(bounded.clamped()).isTrue();
    }

    @Test
    void invalidStoredValuesAreSkippedAndTheSettingInherits() {
        store(PLATFORM, SettingKeys.PASSWORD_MIN_LENGTH, "sixteen", null, null, 1);
        store(BANK_A, SettingKeys.EXTRACTION_DEFAULT_MODE, "MAGIC", null, null, 1);
        store(BANK_A, SettingKeys.MFA_REQUIRE_FOR_ADMINS, "false", null, null, 1);
        store(BANK_A, "no.such.setting", "1", null, null, 1);

        assertThat(resolve(SettingKeys.PASSWORD_MIN_LENGTH, BANK_A).source())
                .isEqualTo(SettingSource.DEFAULT);
        assertThat(resolver.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK_A))
                .isEqualTo("AI_WITH_CODE_FALLBACK");
        // A locked switch can never be turned off by a stored row.
        assertThat(resolver.getBool(SettingKeys.MFA_REQUIRE_FOR_ADMINS, BANK_A)).isTrue();
        // The version of the skipped row is still the concurrency token.
        assertThat(resolve(SettingKeys.EXTRACTION_DEFAULT_MODE, BANK_A).version()).isEqualTo(1);
    }

    @Test
    void platformScopeSettingsIgnoreBankRows() {
        store(BANK_A, SettingKeys.JWT_TTL_MINUTES, "5", null, null, 1);
        assertThat(resolver.getInt(SettingKeys.JWT_TTL_MINUTES, BANK_A)).isEqualTo(15);
        store(PLATFORM, SettingKeys.JWT_TTL_MINUTES, "10", null, null, 1);
        assertThat(resolver.getInt(SettingKeys.JWT_TTL_MINUTES, BANK_A)).isEqualTo(10);
        assertThat(resolver.getIntForPlatform(SettingKeys.JWT_TTL_MINUTES)).isEqualTo(10);
    }

    @Test
    void invertedOrInvalidPlatformBoundsAreIgnored() {
        store(PLATFORM, SettingKeys.RATELIMIT_EXPORTS, null, "100", "10", 1);
        SettingsResolver.Resolution r = resolve(SettingKeys.RATELIMIT_EXPORTS, BANK_A);
        assertThat(r.min()).isEqualTo(1);
        assertThat(r.max()).isEqualTo(600);
        assertThat(r.bankMin()).isNull();
    }

    @Test
    void typedGettersCoverEveryTypeAndRefuseMistakes() {
        assertThat(resolver.getDuration(SettingKeys.SESSION_IDLE_TIMEOUT, BANK_A))
                .isEqualTo(Duration.ofHours(8));
        // A bare-number duration property in its declared unit.
        assertThat(resolver.getDurationForPlatform(SettingKeys.RATELIMIT_WINDOW))
                .isEqualTo(Duration.ofHours(2));
        assertThat(resolver.getBool(SettingKeys.AI_ENABLED, BANK_A)).isTrue();
        assertThat(resolver.getDecimalForPlatform(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD))
                .isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(resolver.getEnumForPlatform(SettingKeys.ALERT_WEBHOOK_MIN_SEVERITY))
                .isEqualTo("INFO");
        assertThat(resolver.getBoolForPlatform(SettingKeys.INTEGRATION_ALLOW_MTLS)).isTrue();

        assertThatThrownBy(() -> resolver.getInt(SettingKeys.AI_ENABLED, BANK_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOOLEAN");
        assertThatThrownBy(() -> resolver.getInt("no.such.key", BANK_A))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alertRuleSettingsReadTheirRuleByName() {
        SettingDefinition threshold = catalogue.require(SettingKeys.ALERT_EXPORT_BURST_THRESHOLD);
        assertThat(catalogue.resolvedPropertyName(threshold))
                .isEqualTo("modocument.alerts.rules[1].threshold");
        assertThat(resolver.getIntForPlatform(SettingKeys.ALERT_EXPORT_BURST_THRESHOLD))
                .isEqualTo(7);
        assertThat(resolver.getDurationForPlatform(SettingKeys.ALERT_EXPORT_BURST_WINDOW))
                .isEqualTo(Duration.ofMinutes(15));
        // A rule absent from the configuration: the code default applies.
        assertThat(resolver.getIntForPlatform(SettingKeys.ALERT_ADMIN_BURST_THRESHOLD))
                .isEqualTo(30);
    }

    @Test
    void canonicalTextRoundTripsEveryType() {
        assertThat(SettingValues.format(com.modocument.domain.model.settings.SettingType.DURATION,
                SettingValues.parseDuration("PT120S", null))).isEqualTo("2m");
        assertThat(SettingValues.parseDuration("90s", null)).isEqualTo(Duration.ofSeconds(90));
        assertThat(SettingValues.parseDuration("3600000", java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> SettingValues.parseDuration("15", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unit");
        assertThat(SettingValues.format(com.modocument.domain.model.settings.SettingType.DECIMAL,
                new BigDecimal("0.700"))).isEqualTo("0.7");
        assertThat(SettingValues.same(com.modocument.domain.model.settings.SettingType.DECIMAL,
                new BigDecimal("0.70"), new BigDecimal("0.7"))).isTrue();
    }
}
