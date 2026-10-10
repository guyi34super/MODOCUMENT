package com.modocument.infrastructure.settings;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped-default guardrail (ws20): out-of-limit yml/env values are a
 * WARN by default and a refused boot with
 * {@code modocument.settings.enforce-guardrails=true}.
 */
class SettingsStartupCheckTest {

    private static SettingsCatalogue catalogue(MockEnvironment environment) {
        return new SettingsCatalogue(SettingDefinitions.ALL, environment);
    }

    @Test
    void shippedDefaultsInsideTheirLimitsPassEvenWhenEnforced() {
        SettingsStartupCheck check = new SettingsStartupCheck(
                catalogue(new MockEnvironment()), true);
        assertThat(check.violations()).isEmpty();
    }

    @Test
    void outOfLimitDefaultsWarnWhenNotEnforced() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("modocument.retention.customer-data-days", "90")
                .withProperty("modocument.security.mfa.require-for-admin", "false");
        assertThatCode(() -> new SettingsStartupCheck(catalogue(environment), false))
                .doesNotThrowAnyException();
        assertThat(new SettingsStartupCheck(catalogue(environment), false).violations())
                .hasSize(2);
    }

    @Test
    void outOfLimitDefaultsRefuseTheBootWhenEnforced() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("modocument.security.password.min-length", "8")
                .withProperty("modocument.ratelimit.enabled", "false");
        assertThatThrownBy(() -> new SettingsStartupCheck(catalogue(environment), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("security.password.minLength = 8")
                .hasMessageContaining("ratelimit.enabled = false");
    }
}
