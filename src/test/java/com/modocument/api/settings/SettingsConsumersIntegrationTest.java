package com.modocument.api.settings;

import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.domain.model.settings.SettingsChangedEvent;
import com.modocument.infrastructure.alerting.AlertRule;
import com.modocument.infrastructure.alerting.AlertRuleCatalogue;
import com.modocument.infrastructure.persistence.BankSettingJpaRepository;
import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws20 Wave 2 in the real application context: a value stored in
 * BankSetting reaches its consumer after the snapshot reload — over HTTP
 * for a rate-limit bucket (for that bank's users only), through the
 * {@link SettingsChangedEvent} for the pre-computed alert catalogue, and
 * through a settings API write for the password policy.
 */
class SettingsConsumersIntegrationTest extends SettingsIntegrationSupport {

    @Autowired
    private BankSettingJpaRepository settingRows;
    @Autowired
    private ApiRateLimitInterceptor apiRateLimits;
    @Autowired
    private AlertRuleCatalogue alertRules;
    @Autowired
    private PasswordPolicy passwordPolicy;

    @Test
    void aBankRateLimitOverrideMetersThatBanksUsersOnly() throws Exception {
        store(bank.getBankId(), SettingKeys.RATELIMIT_PASSWORD_CHANGE, "1");
        assertThat(apiRateLimits.limitFor("password-change", bank.getBankId())).isEqualTo(1);
        assertThat(apiRateLimits.limitFor("password-change", otherBank.getBankId())).isEqualTo(5);

        // The empty body is a 400 AFTER the interceptor spent the permit.
        changePassword(bearer(writer)).andExpect(status().isBadRequest());
        changePassword(bearer(writer)).andExpect(status().isTooManyRequests());
        changePassword(bearer(foreignAdmin)).andExpect(status().isBadRequest());
        changePassword(bearer(foreignAdmin)).andExpect(status().isBadRequest());
    }

    @Test
    void theAlertCatalogueRebuildsOnTheSettingsChangedEvent() {
        AlertRule before = rule("export-burst");
        assertThat(before.threshold()).isEqualTo(5);

        store(platformBank.getBankId(), SettingKeys.ALERT_EXPORT_BURST_THRESHOLD, "9");
        store(platformBank.getBankId(), SettingKeys.ALERT_EXPORT_BURST_WINDOW, "20m");

        // Only the SettingsChangedEvent of the reload rebuilds the catalogue.
        assertThat(rule("export-burst").threshold()).isEqualTo(9);
        assertThat(rule("export-burst").window()).isEqualTo(Duration.ofMinutes(20));
        assertThat(alertRules.windowed()).contains(rule("export-burst"));

        // Removing the stored value restores the yml rule on the next reload.
        settingRows.deleteAll(settingRows.findAll().stream()
                .filter(row -> row.getBankId() == platformBank.getBankId()).toList());
        settingsCache.reload();
        assertThat(rule("export-burst")).isEqualTo(before);
    }

    @Test
    void anApprovedPasswordMinimumChangeAppliesToThatBankAtOnce() throws Exception {
        // Security tier: maker/checker; the checker's approval reloads this task.
        int pending = body(putBank(bearer(admin),
                change(SettingKeys.PASSWORD_MIN_LENGTH, "16", 0))
                .andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
        assertThat(passwordPolicy.minLength(bank.getBankId())).isEqualTo(12);
        approve(bearer(secondAdmin), pending).andExpect(status().isOk());

        assertThat(passwordPolicy.minLength(bank.getBankId())).isEqualTo(16);
        assertThat(passwordPolicy.minLength(otherBank.getBankId())).isEqualTo(12);
    }

    private void store(int bankId, String key, String value) {
        BankSetting row = new BankSetting(bankId, key);
        row.setValueText(value);
        row.setVersion(1);
        row.setUpdatedAt(Instant.now());
        settingRows.saveAndFlush(row);
        settingsCache.reload();
    }

    private AlertRule rule(String name) {
        return alertRules.all().stream().filter(rule -> rule.name().equals(name))
                .findFirst().orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(String token)
            throws Exception {
        return mvc.perform(put("/api/auth/password").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
}
