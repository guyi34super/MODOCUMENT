package com.modocument.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settings under maker/checker over real HTTP (ws20): an OPERATIONAL change
 * applies at once (200, audited SETTING_UPDATED with old/new); a SECURITY
 * change becomes a pending SETTING_UPDATE (202) that leaves the value
 * untouched, blocks further changes of the key (409 SETTING_CHANGE_PENDING),
 * cannot be approved by its own maker (409, gate B7) and applies when a
 * second admin approves (audited SECURITY_SETTING_UPDATED). The consumers'
 * read path ({@link SettingsReader}) sees each value right after commit.
 */
class SettingsMakerCheckerIntegrationTest extends SettingsIntegrationSupport {

    @Autowired
    private SettingsReader settings;

    @Test
    void anOperationalChangeAppliesAtOnce() throws Exception {
        assertThat(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, bank.getBankId()))
                .isEqualTo("AI_WITH_CODE_FALLBACK");

        JsonNode result = body(putBank(bearer(admin),
                change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"CODE_ONLY\"", 0))
                .andExpect(status().isOk()));
        assertThat(result.get("pendingChange").isNull()).isTrue();
        JsonNode applied = result.get("applied").get(0);
        assertThat(applied.get("key").asText()).isEqualTo(SettingKeys.EXTRACTION_DEFAULT_MODE);
        assertThat(applied.get("value").asText()).isEqualTo("CODE_ONLY");
        assertThat(applied.get("source").asText()).isEqualTo("BANK");
        assertThat(applied.get("version").asInt()).isEqualTo(1);

        assertThat(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, bank.getBankId()))
                .isEqualTo("CODE_ONLY");
        // Other banks are untouched.
        assertThat(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE,
                otherBank.getBankId())).isEqualTo("AI_WITH_CODE_FALLBACK");
        assertThat(auditCount("SETTING_UPDATED", bank.getBankId(),
                SettingKeys.EXTRACTION_DEFAULT_MODE)).isEqualTo(1);
        String newValues = jdbc.queryForObject("SELECT NewValues FROM AuditEvent "
                + "WHERE Action = 'SETTING_UPDATED' AND BankId = ? AND EntityId = ?",
                String.class, bank.getBankId(), SettingKeys.EXTRACTION_DEFAULT_MODE);
        assertThat(newValues).contains("CODE_ONLY").contains("BANK");

        // A stale version is refused; the current one applies; null resets.
        putBank(bearer(secondAdmin), change(SettingKeys.EXTRACTION_DEFAULT_MODE, "\"AI_ONLY\"",
                0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_VERSION_CONFLICT"));
        putBank(bearer(secondAdmin), change(SettingKeys.EXTRACTION_DEFAULT_MODE, "null", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].source").value("DEFAULT"))
                .andExpect(jsonPath("$.applied[0].version").value(2));
        assertThat(storedValue(bank.getBankId(), SettingKeys.EXTRACTION_DEFAULT_MODE)).isNull();
        assertThat(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, bank.getBankId()))
                .isEqualTo("AI_WITH_CODE_FALLBACK");
    }

    @Test
    void aSecurityChangeWaitsForASecondAdmin() throws Exception {
        // MAKER: 202, nothing applied yet.
        JsonNode submitted = body(putBank(bearer(admin), "{\"changes\":["
                + item(SettingKeys.PASSWORD_MIN_LENGTH, "16", 0) + "],"
                + "\"comment\":\"stronger passwords\"}")
                .andExpect(status().isAccepted()));
        assertThat(submitted.get("applied")).isEmpty();
        JsonNode pending = submitted.get("pendingChange");
        assertThat(pending.get("changeType").asText()).isEqualTo("SETTING_UPDATE");
        assertThat(pending.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(pending.get("targetEntityId").asText())
                .isEqualTo(SettingKeys.PASSWORD_MIN_LENGTH);
        JsonNode payloadChange = pending.get("payload").get("changes").get(0);
        assertThat(payloadChange.get("from").isNull()).isTrue();
        assertThat(payloadChange.get("to").asInt()).isEqualTo(16);
        assertThat(payloadChange.get("baseVersion").asInt()).isZero();
        int pendingId = pending.get("pendingChangeId").asInt();

        assertThat(settings.getInt(SettingKeys.PASSWORD_MIN_LENGTH, bank.getBankId()))
                .isEqualTo(12);
        JsonNode view = setting(body(getBank(bearer(admin))), SettingKeys.PASSWORD_MIN_LENGTH);
        assertThat(view.get("pendingChangeId").asInt()).isEqualTo(pendingId);
        assertThat(view.get("value").asInt()).isEqualTo(12);

        // The key is frozen while the change is open.
        putBank(bearer(secondAdmin), change(SettingKeys.PASSWORD_MIN_LENGTH, "14", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CHANGE_PENDING"));

        // MAKER == CHECKER: the B7 gate.
        approve(bearer(admin), pendingId).andExpect(status().isConflict());
        assertThat(statusInDb(pendingId)).isEqualTo("REQUESTED");
        assertThat(settings.getInt(SettingKeys.PASSWORD_MIN_LENGTH, bank.getBankId()))
                .isEqualTo(12);

        // A DIFFERENT admin approves: applied, audited, visible at once.
        approve(bearer(secondAdmin), pendingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(settings.getInt(SettingKeys.PASSWORD_MIN_LENGTH, bank.getBankId()))
                .isEqualTo(16);
        assertThat(storedValue(bank.getBankId(), SettingKeys.PASSWORD_MIN_LENGTH))
                .isEqualTo("16");
        assertThat(auditCount("SECURITY_SETTING_UPDATED", bank.getBankId(),
                SettingKeys.PASSWORD_MIN_LENGTH)).isEqualTo(1);
        JsonNode after = setting(body(getBank(bearer(admin))), SettingKeys.PASSWORD_MIN_LENGTH);
        assertThat(after.get("source").asText()).isEqualTo("BANK");
        assertThat(after.get("version").asInt()).isEqualTo(1);
        assertThat(after.get("pendingChangeId").isNull()).isTrue();
        // The row records the maker as its author.
        assertThat(jdbc.queryForObject("SELECT UpdatedByUserId FROM BankSetting WHERE BankId = ? "
                        + "AND SettingKey = ?", Integer.class, bank.getBankId(),
                SettingKeys.PASSWORD_MIN_LENGTH)).isEqualTo(admin.getAppUserId());
    }

    @Test
    void aMixedRequestAppliesTheOperationalPartAndQueuesTheSecurityPart() throws Exception {
        JsonNode result = body(putBank(bearer(admin), "{\"changes\":["
                + item(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD, "0.8", 0) + ","
                + item(SettingKeys.RATELIMIT_UPLOADS, "40", 0) + ","
                + item(SettingKeys.SESSION_IDLE_TIMEOUT, "\"8h\"", 0) + "]}")
                .andExpect(status().isAccepted()));
        assertThat(result.get("applied")).hasSize(1);
        assertThat(result.get("applied").get(0).get("value").decimalValue())
                .isEqualByComparingTo("0.8");
        JsonNode changes = result.get("pendingChange").get("payload").get("changes");
        assertThat(changes).hasSize(2);
        assertThat(result.get("pendingChange").get("targetEntityId").isNull()).isTrue();
        assertThat(settings.getDecimal(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD,
                bank.getBankId())).isEqualByComparingTo("0.8");
        assertThat(settings.getInt(SettingKeys.RATELIMIT_UPLOADS, bank.getBankId()))
                .isEqualTo(20);

        // An APPROVER (not an admin) may be the checker too.
        approve(bearer(approver), result.get("pendingChange").get("pendingChangeId").asInt())
                .andExpect(status().isOk());
        assertThat(settings.getInt(SettingKeys.RATELIMIT_UPLOADS, bank.getBankId()))
                .isEqualTo(40);
        assertThat(settings.getDuration(SettingKeys.SESSION_IDLE_TIMEOUT, bank.getBankId()))
                .hasHours(8);
    }

    @Test
    void anInvalidPartRefusesTheWholeRequest() throws Exception {
        putBank(bearer(admin), "{\"changes\":["
                + item(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD, "0.8", 0) + ","
                + item(SettingKeys.RATELIMIT_UPLOADS, "4000", 0) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        assertThat(storedValue(bank.getBankId(), SettingKeys.MAPPING_CONFIDENCE_THRESHOLD))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?",
                Integer.class, bank.getBankId())).isZero();
    }

    @Test
    void aPlatformSecurityChangeNeedsASecondPlatformAdmin() throws Exception {
        int pendingId = body(putPlatform(platformBearer(platformAdmin),
                change(SettingKeys.JWT_TTL_MINUTES, "10", 0))
                .andExpect(status().isAccepted()))
                .get("pendingChange").get("pendingChangeId").asInt();
        approve(platformBearer(platformAdmin), pendingId).andExpect(status().isConflict());
        // A bank admin can't even see it (another bank's change).
        approve(bearer(admin), pendingId).andExpect(status().isNotFound());
        approve(platformBearer(secondPlatformAdmin), pendingId).andExpect(status().isOk());
        assertThat(settings.getInt(SettingKeys.JWT_TTL_MINUTES, bank.getBankId()))
                .isEqualTo(10);
        assertThat(settings.getIntForPlatform(SettingKeys.JWT_TTL_MINUTES)).isEqualTo(10);
        assertThat(auditCount("PLATFORM_SETTING_UPDATED", platformBank.getBankId(),
                SettingKeys.JWT_TTL_MINUTES)).isEqualTo(1);
    }
}
