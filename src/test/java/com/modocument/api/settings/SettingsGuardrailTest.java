package com.modocument.api.settings;

import com.modocument.application.usecase.SettingChangeService;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.infrastructure.settings.SettingDefinitions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hard limits hold for EVERY catalogued setting over HTTP (ws20): one
 * step below hardMin and one step above hardMax answer 400
 * SETTING_OUT_OF_RANGE at the setting's own layer (bank endpoint for
 * BANK-scope settings, platform endpoint otherwise), a value outside an
 * ENUM's allowed list too, and a locked setting refuses any value with
 * SETTING_LOCKED — before anything is stored or queued.
 */
class SettingsGuardrailTest extends SettingsIntegrationSupport {

    static Stream<SettingDefinition> rangedSettings() {
        return SettingDefinitions.ALL.stream()
                .filter(definition -> definition.type().isRanged() && !definition.locked());
    }

    static Stream<SettingDefinition> lockedSettings() {
        return SettingDefinitions.ALL.stream().filter(SettingDefinition::locked);
    }

    static Stream<SettingDefinition> enumSettings() {
        return SettingDefinitions.ALL.stream()
                .filter(definition -> definition.type() == SettingType.ENUM
                        && !definition.locked());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangedSettings")
    void oneStepOutsideTheHardLimitsIsRefused(SettingDefinition definition) throws Exception {
        for (Object outside : new Object[] {
                step(definition, definition.hardMin(), -1),
                step(definition, definition.hardMax(), +1)}) {
            put(definition, json(definition, outside))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"))
                    .andExpect(jsonPath("$.key").value(definition.key()));
        }
        assertNothingStored(definition);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockedSettings")
    void aLockedSettingRefusesEveryValue(SettingDefinition definition) throws Exception {
        String only = definition.allowedValues().get(0);
        String other = definition.type() == SettingType.BOOLEAN
                ? String.valueOf(!Boolean.parseBoolean(only)) : "\"OTHER\"";
        for (String value : new String[] {other, json(definition,
                definition.parse(only)), "null"}) {
            put(definition, value)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SETTING_LOCKED"));
        }
        assertNothingStored(definition);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumSettings")
    void anEnumOutsideItsAllowedValuesIsRefused(SettingDefinition definition) throws Exception {
        put(definition, "\"NOT_A_CHOICE\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        assertNothingStored(definition);
    }

    private org.springframework.test.web.servlet.ResultActions put(SettingDefinition definition,
                                                                   String jsonValue)
            throws Exception {
        String body = change(definition.key(), jsonValue, 0);
        return definition.scope() == SettingScope.BANK
                ? putBank(bearer(admin), body)
                : putPlatform(platformBearer(platformAdmin), body);
    }

    private void assertNothingStored(SettingDefinition definition) {
        int bankId = definition.scope() == SettingScope.BANK
                ? bank.getBankId() : platformBank.getBankId();
        assertThat(storedValue(bankId, definition.key())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?",
                Integer.class, bankId)).isZero();
    }

    /** The next representable value beyond a limit, in the type's natural step. */
    private static Object step(SettingDefinition definition, Object limit, int direction) {
        return switch (definition.type()) {
            case INT -> (Integer) limit + direction;
            case DECIMAL -> ((BigDecimal) limit).add(new BigDecimal("0.01")
                    .multiply(BigDecimal.valueOf(direction)));
            case DURATION -> ((Duration) limit).plusSeconds(direction);
            default -> throw new IllegalArgumentException(definition.key());
        };
    }

    private static String json(SettingDefinition definition, Object value) {
        Object json = SettingChangeService.toJson(definition, value);
        return json instanceof String text ? "\"" + text + "\"" : String.valueOf(json);
    }
}
