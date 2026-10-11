package com.modocument.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settings API contract (ws20, docs/phase2/contracts.md §3): response
 * shapes of both layers, who may call what (READ_WRITE 403, a bank admin
 * 403 on the platform layer, a platform-bank caller 409 on the bank layer),
 * the refusal codes, and how platform bounds narrow (and clamp) a bank.
 */
class SettingsApiTest extends SettingsIntegrationSupport {

    @Test
    void bankPageHasTheContractShape() throws Exception {
        JsonNode page = body(getBank(bearer(admin)).andExpect(status().isOk()));
        assertThat(page.get("layer").asText()).isEqualTo("BANK");
        assertThat(page.get("revision").isIntegralNumber()).isTrue();
        List<String> groups = new ArrayList<>();
        page.get("groups").forEach(group -> groups.add(group.get("id").asText()));
        // Security alerts are platform-only: no such tab at the bank layer.
        assertThat(groups).containsExactly("signIn", "rateLimits", "dataProtection",
                "aiExtraction", "integrations");

        JsonNode minLength = setting(page, SettingKeys.PASSWORD_MIN_LENGTH);
        for (String field : List.of("key", "type", "tier", "scope", "group", "label",
                "description", "unit", "value", "source", "default", "min", "max", "hardMin",
                "hardMax", "allowed", "requiresApproval", "requiresRestart", "locked",
                "clamped", "pendingChangeId", "version")) {
            assertThat(minLength.has(field)).as(field).isTrue();
        }
        assertThat(minLength.has("bankMin")).isFalse();
        assertThat(minLength.get("type").asText()).isEqualTo("INT");
        assertThat(minLength.get("tier").asText()).isEqualTo("SECURITY");
        assertThat(minLength.get("scope").asText()).isEqualTo("BANK");
        assertThat(minLength.get("value").asInt()).isEqualTo(12);
        assertThat(minLength.get("source").asText()).isEqualTo("DEFAULT");
        assertThat(minLength.get("default").asInt()).isEqualTo(12);
        assertThat(minLength.get("min").asInt()).isEqualTo(12);
        assertThat(minLength.get("max").asInt()).isEqualTo(128);
        assertThat(minLength.get("requiresApproval").asBoolean()).isTrue();
        assertThat(minLength.get("locked").asBoolean()).isFalse();
        assertThat(minLength.get("pendingChangeId").isNull()).isTrue();
        assertThat(minLength.get("version").asInt()).isZero();

        JsonNode idle = setting(page, SettingKeys.SESSION_IDLE_TIMEOUT);
        assertThat(idle.get("value").asText()).isEqualTo("12h");
        assertThat(idle.get("hardMin").asText()).isEqualTo("15m");
        JsonNode mfaAdmins = setting(page, SettingKeys.MFA_REQUIRE_FOR_ADMINS);
        assertThat(mfaAdmins.get("locked").asBoolean()).isTrue();
        assertThat(mfaAdmins.get("allowed")).hasSize(1);
        JsonNode mode = setting(page, SettingKeys.EXTRACTION_DEFAULT_MODE);
        assertThat(mode.get("tier").asText()).isEqualTo("OPERATIONAL");
        assertThat(mode.get("requiresApproval").asBoolean()).isFalse();
        assertThat(mode.get("allowed")).hasSize(3);
        assertThat(setting(page, SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE).get("value")
                .decimalValue()).isEqualByComparingTo("0.75");
    }

    @Test
    void platformPageHasEverySettingWithBankBounds() throws Exception {
        JsonNode page = body(getPlatform(platformBearer(platformAdmin))
                .andExpect(status().isOk()));
        assertThat(page.get("layer").asText()).isEqualTo("PLATFORM");
        assertThat(page.get("groups")).hasSize(6);
        JsonNode jwt = setting(page, SettingKeys.JWT_TTL_MINUTES);
        assertThat(jwt.get("scope").asText()).isEqualTo("PLATFORM");
        assertThat(jwt.get("hasBankBounds").asBoolean()).isFalse();
        JsonNode uploads = setting(page, SettingKeys.RATELIMIT_UPLOADS);
        assertThat(uploads.get("hasBankBounds").asBoolean()).isTrue();
        assertThat(uploads.has("bankMin")).isTrue();
        assertThat(uploads.get("bankMin").isNull()).isTrue();
        assertThat(uploads.get("min").asInt()).isEqualTo(1);
        assertThat(uploads.get("max").asInt()).isEqualTo(200);
    }

    @Test
    void readWriteUsersAndApproversCannotReachSettings() throws Exception {
        getBank(bearer(writer)).andExpect(status().isForbidden());
        putBank(bearer(writer), change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"", 0))
                .andExpect(status().isForbidden());
        getBank(bearer(approver)).andExpect(status().isForbidden());
        putBank(bearer(approver), change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"",
                0)).andExpect(status().isForbidden());
        assertThat(storedValue(bank.getBankId(), SettingKeys.EXTRACTION_DEFAULT_MODE)).isNull();
    }

    @Test
    void theplatformLayerIsClosedToBankAdminsAndToPlatformRolesOutsideThePlatformBank()
            throws Exception {
        // A bank admin lacks platform:read / platform:settings.
        getPlatform(bearer(admin)).andExpect(status().isForbidden());
        putPlatform(bearer(admin), change(SettingKeys.JWT_TTL_MINUTES, "10", 0))
                .andExpect(status().isForbidden());
        // A PLATFORM_ADMIN token for a user outside the platform bank.
        getPlatform(bearer(admin, Role.PLATFORM_ADMIN)).andExpect(status().isForbidden());
        putPlatform(bearer(admin, Role.PLATFORM_ADMIN),
                change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"", 0))
                .andExpect(status().isForbidden());
        assertThat(storedValue(platformBank.getBankId(), SettingKeys.EXTRACTION_DEFAULT_MODE))
                .isNull();
        // A platform admin has no bank-layer permission at all.
        getBank(platformBearer(platformAdmin)).andExpect(status().isForbidden());
    }

    @Test
    void aBankRoleInThePlatformBankIsSentToThePlatformLayer() throws Exception {
        getBank(bearer(platformAdmin, Role.ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USE_PLATFORM_SETTINGS"));
        putBank(bearer(platformAdmin, Role.ADMIN),
                change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USE_PLATFORM_SETTINGS"));
    }

    @Test
    void refusalsCarryTheContractCodes() throws Exception {
        putBank(bearer(admin), change("no.such.setting", "1", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
        // A platform-only setting is unknown at the bank layer.
        putBank(bearer(admin), change(SettingKeys.JWT_TTL_MINUTES, "10", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"));
        putBank(bearer(admin), change(SettingKeys.RETENTION_ENABLED, "false", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_LOCKED"));
        putBank(bearer(admin), change(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, "31", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.key").value(SettingKeys.RETENTION_CUSTOMER_DATA_DAYS));
        putBank(bearer(admin), change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"MAGIC\"", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        // Wrong JSON type, missing version, empty and duplicate changes: plain 400s.
        putBank(bearer(admin), change(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, "true", 0))
                .andExpect(status().isBadRequest());
        putBank(bearer(admin), "{\"changes\":[{\"key\":\"" + SettingKeys.AI_ENABLED
                + "\",\"value\":false}]}")
                .andExpect(status().isBadRequest());
        putBank(bearer(admin), "{\"changes\":[]}").andExpect(status().isBadRequest());
        putBank(bearer(admin), "{\"changes\":[" + item(SettingKeys.EXTRACTION_DEFAULT_MODE,
                "\"CODE_ONLY\"", 0) + "," + item(SettingKeys.EXTRACTION_DEFAULT_MODE,
                "\"AI_ONLY\"", 0) + "]}").andExpect(status().isBadRequest());
        // A duration needs a unit.
        putBank(bearer(admin), change(SettingKeys.INTEGRATION_RUN_TIMEOUT, "30", 0))
                .andExpect(status().isBadRequest());
        // Nothing of the above was stored.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BankSetting WHERE BankId = ?",
                Integer.class, bank.getBankId())).isZero();
    }

    @Test
    void platformBoundsNarrowTheBankRangeAndClampAnExistingOverride() throws Exception {
        // The bank sets an operational override at the top of the hard range.
        putBank(bearer(admin), change(SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN, "400000", 0))
                .andExpect(status().isOk());

        // The platform narrows the bank range (maker/checker: bounds are SECURITY).
        int pending = body(putPlatform(platformBearer(platformAdmin),
                "{\"changes\":[{\"key\":\"" + SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN
                        + "\",\"bankMin\":100,\"bankMax\":100000,\"expectedVersion\":0}],"
                        + "\"comment\":\"cap runs\"}")
                .andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
        approve(platformBearer(secondPlatformAdmin), pending).andExpect(status().isOk());

        JsonNode rows = setting(body(getBank(bearer(admin))),
                SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN);
        assertThat(rows.get("min").asInt()).isEqualTo(100);
        assertThat(rows.get("max").asInt()).isEqualTo(100000);
        assertThat(rows.get("value").asInt()).isEqualTo(100000);
        assertThat(rows.get("clamped").asBoolean()).isTrue();
        assertThat(rows.get("source").asText()).isEqualTo("BANK");
        assertThat(rows.get("hardMax").asInt()).isEqualTo(500000);

        // A new bank value outside the narrowed range is refused.
        putBank(bearer(admin), change(SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN, "200000",
                rows.get("version").asInt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        // The platform view shows the bounds it set.
        JsonNode platformView = setting(body(getPlatform(platformBearer(platformAdmin))),
                SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN);
        assertThat(platformView.get("bankMin").asInt()).isEqualTo(100);
        assertThat(platformView.get("bankMax").asInt()).isEqualTo(100000);
        assertThat(platformView.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void platformValueChangesFollowTheTierAndRespectTheBounds() throws Exception {
        // Operational platform default: at once; every bank inherits it.
        putPlatform(platformBearer(platformAdmin),
                change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].source").value("PLATFORM"))
                .andExpect(jsonPath("$.pendingChange").isEmpty());
        JsonNode inherited = setting(body(getBank(bearer(foreignAdmin))),
                SettingKeys.EXTRACTION_DEFAULT_MODE);
        assertThat(inherited.get("value").asText()).isEqualTo("CODE_ONLY");
        assertThat(inherited.get("source").asText()).isEqualTo("PLATFORM");
        assertThat(auditCount("SETTING_UPDATED", platformBank.getBankId(),
                SettingKeys.EXTRACTION_DEFAULT_MODE)).isEqualTo(1);
        // JSON null resets the platform value (an ABSENT value would mean "unchanged").
        putPlatform(platformBearer(platformAdmin),
                change(SettingKeys.EXTRACTION_DEFAULT_MODE, "null", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].source").value("DEFAULT"))
                .andExpect(jsonPath("$.applied[0].version").value(2));
        putPlatform(platformBearer(platformAdmin), "{\"changes\":[{\"key\":\""
                + SettingKeys.EXTRACTION_DEFAULT_MODE + "\",\"expectedVersion\":2}]}")
                .andExpect(status().isBadRequest());

        // A platform-only security value: maker/checker.
        putPlatform(platformBearer(platformAdmin), change(SettingKeys.JWT_TTL_MINUTES, "10", 0))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.changeType")
                        .value("PLATFORM_SETTING_UPDATE"));
        // Bounds that exclude the platform value itself, or inverted ones: refused.
        putPlatform(platformBearer(platformAdmin), "{\"changes\":[{\"key\":\""
                + SettingKeys.RATELIMIT_UPLOADS + "\",\"value\":50,\"bankMax\":40,"
                + "\"expectedVersion\":0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        putPlatform(platformBearer(platformAdmin), "{\"changes\":[{\"key\":\""
                + SettingKeys.RATELIMIT_UPLOADS + "\",\"bankMin\":40,\"bankMax\":10,"
                + "\"expectedVersion\":0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        // Bounds on a setting that has none.
        putPlatform(platformBearer(platformAdmin), "{\"changes\":[{\"key\":\""
                + SettingKeys.AI_ENABLED + "\",\"bankMin\":true,\"expectedVersion\":0}]}")
                .andExpect(status().isBadRequest());
    }
}
