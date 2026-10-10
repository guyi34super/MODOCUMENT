package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.api.settings.SettingsIntegrationSupport;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SETTING_UPDATE / PLATFORM_SETTING_UPDATE appliers re-judge a change at
 * APPROVAL time (ws20): a row that moved since submission answers 409
 * SETTING_VERSION_CONFLICT, limits the platform narrowed meanwhile answer
 * 400 SETTING_OUT_OF_RANGE — and either way the change is FAILED (with a
 * CHANGE_APPLY_FAILED audit row) and nothing is written. Submissions through
 * the generic maker endpoint get the same validation, with {@code from}
 * recomputed from the database.
 */
class SettingUpdateApplierTest extends SettingsIntegrationSupport {

    private int submit(String key, String jsonValue) throws Exception {
        return body(putBank(bearer(admin), change(key, jsonValue, 0))
                .andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
    }

    @Test
    void aRowThatMovedSinceSubmissionFailsTheChange() throws Exception {
        int pendingId = submit(SettingKeys.PASSWORD_MIN_LENGTH, "16");
        // Another writer changed the row after submission (version 0 -> 1).
        jdbc.update("INSERT INTO BankSetting (BankId, SettingKey, ValueText, Version, UpdatedAt) "
                + "VALUES (?, ?, '13', 1, CURRENT_TIMESTAMP)", bank.getBankId(),
                SettingKeys.PASSWORD_MIN_LENGTH);

        approve(bearer(secondAdmin), pendingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.key").value(SettingKeys.PASSWORD_MIN_LENGTH));

        assertThat(statusInDb(pendingId)).isEqualTo("FAILED");
        assertThat(storedValue(bank.getBankId(), SettingKeys.PASSWORD_MIN_LENGTH))
                .isEqualTo("13");
        assertThat(auditCount("CHANGE_APPLY_FAILED", bank.getBankId(),
                String.valueOf(pendingId))).isEqualTo(1);
        assertThat(auditCount("SECURITY_SETTING_UPDATED", bank.getBankId(),
                SettingKeys.PASSWORD_MIN_LENGTH)).isZero();
        // FAILED releases the key: a fresh change can be submitted.
        putBank(bearer(admin), change(SettingKeys.PASSWORD_MIN_LENGTH, "16", 1))
                .andExpect(status().isAccepted());
    }

    @Test
    void limitsNarrowedBeforeApprovalFailTheChange() throws Exception {
        int bankChange = submit(SettingKeys.RATELIMIT_UPLOADS, "150");

        int platformChange = body(putPlatform(platformBearer(platformAdmin),
                "{\"changes\":[{\"key\":\"" + SettingKeys.RATELIMIT_UPLOADS
                        + "\",\"bankMax\":100,\"expectedVersion\":0}]}")
                .andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
        approve(platformBearer(secondPlatformAdmin), platformChange).andExpect(status().isOk());

        approve(bearer(secondAdmin), bankChange)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        assertThat(statusInDb(bankChange)).isEqualTo("FAILED");
        assertThat(storedValue(bank.getBankId(), SettingKeys.RATELIMIT_UPLOADS)).isNull();
    }

    @Test
    void theGenericMakerEndpointIsJudgedTheSameWay() throws Exception {
        // A forged "from" is replaced by the stored value; out-of-range is refused.
        JsonNode accepted = body(mvc.perform(post("/api/admin/changes")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"SETTING_UPDATE\",\"payload\":{\"changes\":"
                                + "[{\"key\":\"" + SettingKeys.LOCKOUT_ALLOWED_ATTEMPTS
                                + "\",\"from\":99,\"to\":4,\"baseVersion\":0}]}}"))
                .andExpect(status().isAccepted()));
        JsonNode stored = accepted.get("payload").get("changes").get(0);
        assertThat(stored.get("from").isNull()).isTrue();
        assertThat(stored.get("to").asInt()).isEqualTo(4);

        mvc.perform(post("/api/admin/changes")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"SETTING_UPDATE\",\"payload\":{\"changes\":"
                                + "[{\"key\":\"" + SettingKeys.LOCKOUT_CAP_MINUTES
                                + "\",\"to\":5,\"baseVersion\":0}]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        // A READ_WRITE user is no settings maker.
        mvc.perform(post("/api/admin/changes")
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"SETTING_UPDATE\",\"payload\":{\"changes\":"
                                + "[{\"key\":\"" + SettingKeys.LOCKOUT_CAP_MINUTES
                                + "\",\"to\":60,\"baseVersion\":0}]}}"))
                .andExpect(status().isForbidden());
        // A bank admin cannot submit platform changes.
        mvc.perform(post("/api/admin/changes")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"PLATFORM_SETTING_UPDATE\",\"payload\":"
                                + "{\"changes\":[{\"key\":\"" + SettingKeys.JWT_TTL_MINUTES
                                + "\",\"to\":5,\"baseVersion\":0}]}}"))
                .andExpect(status().isForbidden());
    }
}
