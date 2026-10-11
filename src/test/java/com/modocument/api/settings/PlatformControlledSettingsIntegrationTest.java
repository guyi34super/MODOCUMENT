package com.modocument.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform-controlled BANK settings over real HTTP: the allowed API-client
 * authentication methods ({@code integration.auth.allow*}) can differ per
 * bank, but only the platform owner sets a bank's value
 * ({@code /api/platform/banks/{bankId}/settings}, PLATFORM_SETTING_UPDATE
 * naming the bank, approved by a second platform admin). The bank sees the
 * value read-only; with no per-bank value every bank inherits the platform
 * value, so nothing changes until the platform sets one.
 */
class PlatformControlledSettingsIntegrationTest extends SettingsIntegrationSupport {

    private static final String API_KEYS = SettingKeys.INTEGRATION_ALLOW_API_KEYS;

    @Autowired
    private IntegrationSettings integrationSettings;
    @Autowired
    private ApiClientAuthenticator authenticator;

    @Test
    void withoutPerBankValuesEveryBankInheritsAndSeesTheFlagsReadOnly() throws Exception {
        JsonNode page = body(getBank(bearer(admin)).andExpect(status().isOk()));
        for (String key : List.of(SettingKeys.INTEGRATION_ALLOW_OAUTH2, API_KEYS,
                SettingKeys.INTEGRATION_ALLOW_MTLS)) {
            JsonNode flag = setting(page, key);
            assertThat(flag.get("platformControlled").asBoolean()).as(key).isTrue();
            assertThat(flag.get("scope").asText()).isEqualTo("BANK");
            assertThat(flag.get("value").asBoolean()).isTrue();
            assertThat(flag.get("source").asText()).isEqualTo("DEFAULT");
        }
        // An ordinary bank setting is not platform-controlled.
        assertThat(setting(page, SettingKeys.INTEGRATION_RUN_TIMEOUT)
                .get("platformControlled").asBoolean()).isFalse();

        for (ApiAuthMethod method : ApiAuthMethod.values()) {
            assertThat(integrationSettings.isAuthMethodAllowed(method, bank.getBankId()))
                    .isTrue();
            assertThat(authenticator.methodAllowed(method, bank.getBankId())).isTrue();
        }

        // The bank cannot change them.
        putBank(bearer(admin), change(API_KEYS, "false", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_LOCKED"))
                .andExpect(jsonPath("$.key").value(API_KEYS));
        assertThat(storedValue(bank.getBankId(), API_KEYS)).isNull();
    }

    @Test
    void thePlatformSetsOneBanksValueThroughASecondPlatformAdmin() throws Exception {
        int bankId = bank.getBankId();
        JsonNode page = body(getForBank(platformBearer(platformAdmin), bankId)
                .andExpect(status().isOk()));
        // ws27 adds the bank's sensitive-read audit switch (Data protection tab).
        assertThat(keys(page)).containsExactly(SettingKeys.AUDIT_READ_AUDIT_ENABLED,
                SettingKeys.INTEGRATION_ALLOW_OAUTH2, API_KEYS,
                SettingKeys.INTEGRATION_ALLOW_MTLS);
        assertThat(setting(page, API_KEYS).get("version").asInt()).isZero();

        // MAKER: 202, the payload names the bank, nothing applied yet.
        JsonNode submitted = body(putForBank(platformBearer(platformAdmin), bankId,
                change(API_KEYS, "false", 0)).andExpect(status().isAccepted()));
        assertThat(submitted.get("applied")).isEmpty();
        JsonNode pending = submitted.get("pendingChange");
        int changeId = pending.get("pendingChangeId").asInt();
        assertThat(pending.get("changeType").asText()).isEqualTo("PLATFORM_SETTING_UPDATE");
        assertThat(pending.get("payload").get("bankId").asInt()).isEqualTo(bankId);
        assertThat(pending.get("payload").get("changes").get(0).get("to").asBoolean())
                .isFalse();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankId))
                .isTrue();

        // The key is frozen for THIS bank only while the change waits.
        assertThat(setting(body(getForBank(platformBearer(platformAdmin), bankId)), API_KEYS)
                .get("pendingChangeId").asInt()).isEqualTo(changeId);
        putForBank(platformBearer(platformAdmin), bankId, change(API_KEYS, "true", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CHANGE_PENDING"));
        assertThat(setting(body(getForBank(platformBearer(platformAdmin),
                otherBank.getBankId())), API_KEYS).get("pendingChangeId").isNull()).isTrue();
        assertThat(setting(body(getPlatform(platformBearer(platformAdmin))), API_KEYS)
                .get("pendingChangeId").isNull()).isTrue();

        // The maker cannot approve; a second platform admin can.
        approve(platformBearer(platformAdmin), changeId).andExpect(status().isConflict());
        approve(platformBearer(secondPlatformAdmin), changeId).andExpect(status().isOk());
        assertThat(statusInDb(changeId)).isEqualTo("APPLIED");

        // The row lives in the TARGET bank; only that bank changed.
        assertThat(storedValue(bankId, API_KEYS)).isEqualTo("false");
        assertThat(storedValue(platformBank.getBankId(), API_KEYS)).isNull();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankId))
                .isFalse();
        assertThat(authenticator.methodAllowed(ApiAuthMethod.API_KEY, bankId)).isFalse();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.MTLS, bankId))
                .isTrue();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY,
                otherBank.getBankId())).isTrue();
        // Audited in the platform bank, naming the bank.
        assertThat(auditCount("PLATFORM_SETTING_UPDATED", platformBank.getBankId(), API_KEYS))
                .isEqualTo(1);
        String newValues = jdbc.queryForObject("SELECT NewValues FROM AuditEvent "
                        + "WHERE Action = 'PLATFORM_SETTING_UPDATED' AND BankId = ? "
                        + "AND EntityId = ?", String.class, platformBank.getBankId(), API_KEYS);
        assertThat(newValues).contains("BANK_BY_PLATFORM").contains(String.valueOf(bankId));

        // The bank sees the platform's value for it, read-only.
        JsonNode bankFlag = setting(body(getBank(bearer(admin))), API_KEYS);
        assertThat(bankFlag.get("value").asBoolean()).isFalse();
        assertThat(bankFlag.get("source").asText()).isEqualTo("BANK");
        assertThat(bankFlag.get("platformControlled").asBoolean()).isTrue();
        putBank(bearer(admin), change(API_KEYS, "null", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_LOCKED"));

        // Back to inheriting: null, again through a second platform admin.
        int resetId = body(putForBank(platformBearer(secondPlatformAdmin), bankId,
                change(API_KEYS, "null", 1)).andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
        approve(platformBearer(platformAdmin), resetId).andExpect(status().isOk());
        assertThat(storedValue(bankId, API_KEYS)).isNull();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, bankId))
                .isTrue();
    }

    @Test
    void thePlatformValueStaysTheDefaultForBanksWithoutTheirOwn() throws Exception {
        // Platform default off for mTLS; one bank allowed it explicitly.
        int platformChange = body(putPlatform(platformBearer(platformAdmin),
                change(SettingKeys.INTEGRATION_ALLOW_MTLS, "false", 0))
                .andExpect(status().isAccepted())).get("pendingChange")
                .get("pendingChangeId").asInt();
        approve(platformBearer(secondPlatformAdmin), platformChange)
                .andExpect(status().isOk());
        int bankChange = body(putForBank(platformBearer(platformAdmin), bank.getBankId(),
                change(SettingKeys.INTEGRATION_ALLOW_MTLS, "true", 0))
                .andExpect(status().isAccepted())).get("pendingChange")
                .get("pendingChangeId").asInt();
        approve(platformBearer(secondPlatformAdmin), bankChange).andExpect(status().isOk());

        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.MTLS, bank.getBankId()))
                .isTrue();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.MTLS,
                otherBank.getBankId())).isFalse();
        assertThat(integrationSettings.isAuthMethodAllowed(ApiAuthMethod.MTLS)).isFalse();
        assertThat(setting(body(getBank(bearer(foreignAdmin))),
                SettingKeys.INTEGRATION_ALLOW_MTLS).get("source").asText())
                .isEqualTo("PLATFORM");
    }

    @Test
    void perBankRequestsAreCheckedLikeEveryOtherSettingsWrite() throws Exception {
        String token = platformBearer(platformAdmin);
        // Only platform-controlled keys.
        putForBank(token, bank.getBankId(), change(SettingKeys.INTEGRATION_RUN_TIMEOUT,
                "\"30m\"", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"));
        // Unknown bank, the platform bank.
        getForBank(token, 987_654_321).andExpect(status().isNotFound());
        putForBank(token, 987_654_321, change(API_KEYS, "false", 0))
                .andExpect(status().isNotFound());
        getForBank(token, platformBank.getBankId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USE_PLATFORM_SETTINGS"));
        // Bank users never reach it.
        getForBank(bearer(admin), bank.getBankId()).andExpect(status().isForbidden());
        putForBank(bearer(admin), bank.getBankId(), change(API_KEYS, "false", 0))
                .andExpect(status().isForbidden());
        // The generic submit path judges a per-bank payload the same way.
        mvc.perform(post("/api/admin/changes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"PLATFORM_SETTING_UPDATE\",\"payload\":"
                                + "{\"bankId\":" + bank.getBankId() + ",\"changes\":[{\"key\":\""
                                + SettingKeys.PASSWORD_MIN_LENGTH
                                + "\",\"to\":20,\"baseVersion\":0}]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"));
        assertThat(storedValue(bank.getBankId(), API_KEYS)).isNull();
    }

    private ResultActions getForBank(String token, int bankId) throws Exception {
        return mvc.perform(get("/api/platform/banks/" + bankId + "/settings")
                .header("Authorization", token));
    }

    private ResultActions putForBank(String token, int bankId, String body) throws Exception {
        return mvc.perform(put("/api/platform/banks/" + bankId + "/settings")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static List<String> keys(JsonNode page) {
        List<String> keys = new ArrayList<>();
        for (JsonNode group : page.get("groups")) {
            for (JsonNode setting : group.get("settings")) {
                keys.add(setting.get("key").asText());
            }
        }
        return keys;
    }
}
