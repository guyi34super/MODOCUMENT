package com.modocument.application.mapping;

import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Per-bank column-mapping settings (ws20) in the SHAPE the mapping code
 * already takes: {@link #forBank(int)} returns a fresh {@link MappingProperties}
 * copy of the yml-bound bean with the bank's editable values overlaid —
 * {@code mapping.llmFallbackEnabled} and {@code mapping.confidenceThreshold}
 * (docs/settings/catalogue.md, "AI & extraction"). Everything else (LLM
 * scope and sample sizes, history depth, shape budget, aliases) stays
 * yml-only. With nothing stored every copy equals the yml bean field for
 * field; copies are never shared.
 */
@Component
public class MappingSettings {

    private final MappingProperties defaults;
    private final SettingsReader settings;

    public MappingSettings(MappingProperties defaults, SettingsReader settings) {
        this.defaults = defaults;
        this.settings = settings;
    }

    /** The mapping knobs in force for one bank's jobs (a fresh copy). */
    public MappingProperties forBank(int bankId) {
        MappingProperties copy = copyOfDefaults();
        copy.setLlmFallbackEnabled(settings.getBool(SettingKeys.MAPPING_LLM_FALLBACK_ENABLED, bankId));
        copy.setConfidenceThreshold(
                settings.getDecimal(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD, bankId).doubleValue());
        return copy;
    }

    /** {@link #forBank(int)} for the bank bound to this thread, else the platform layer. */
    public MappingProperties forCurrentBank() {
        Optional<Integer> bank = TenantContext.currentBankId();
        if (bank.isPresent()) {
            return forBank(bank.get());
        }
        MappingProperties copy = copyOfDefaults();
        copy.setLlmFallbackEnabled(
                settings.getBoolForPlatform(SettingKeys.MAPPING_LLM_FALLBACK_ENABLED));
        copy.setConfidenceThreshold(settings
                .getDecimalForPlatform(SettingKeys.MAPPING_CONFIDENCE_THRESHOLD).doubleValue());
        return copy;
    }

    private MappingProperties copyOfDefaults() {
        MappingProperties copy = new MappingProperties();
        copy.setLlmFallbackEnabled(defaults.isLlmFallbackEnabled());
        copy.setLlmScope(defaults.getLlmScope());
        copy.setLlmSampleSize(defaults.getLlmSampleSize());
        copy.setConfidenceThreshold(defaults.getConfidenceThreshold());
        copy.setSampleSize(defaults.getSampleSize());
        copy.setHistoryJobs(defaults.getHistoryJobs());
        copy.setShapePairBudget(defaults.getShapePairBudget());
        copy.setAliases(defaults.getAliases() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(defaults.getAliases()));
        return copy;
    }
}
