package com.modocument.infrastructure.file;

import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Per-bank parsing settings (ws20) in the SHAPE the parsing code already
 * takes: {@link #forBank(int)} returns a fresh {@link ParsingProperties}
 * copy of the yml-bound bean with the bank's editable values overlaid, so
 * downstream signatures ({@code TableExtractionBatchPlanner.plan(..., ParsingProperties)})
 * stay unchanged — resolve once per job, pass the copy down.
 *
 * <p>Overlaid (docs/settings/catalogue.md, "AI & extraction"):
 * <ul>
 *   <li>{@code parsing.deterministicEnabled}, {@code parsing.combinedExtractionMapping}
 *       — per bank;</li>
 *   <li>{@code parsing.llmBatchSize}, {@code parsing.llmBatchConcurrency}
 *       — platform-wide (provider capacity is shared);</li>
 *   <li>{@code parsing.excel.llmEnabled} — per bank, via
 *       {@link #excelForBank(int)}.</li>
 * </ul>
 * {@code llmSinglePassMaxRows} stays yml-only. With nothing stored every
 * copy equals the yml bean field for field. Copies are never shared, so a
 * caller may not mutate the yml bean through them (and vice versa).
 */
@Component
public class ParsingSettings {

    private final ParsingProperties defaults;
    private final SettingsReader settings;

    public ParsingSettings(ParsingProperties defaults, SettingsReader settings) {
        this.defaults = defaults;
        this.settings = settings;
    }

    /** The parsing knobs in force for one bank's jobs (a fresh copy). */
    public ParsingProperties forBank(int bankId) {
        ParsingProperties copy = copyOfDefaults();
        copy.setDeterministicEnabled(
                settings.getBool(SettingKeys.PARSING_DETERMINISTIC_ENABLED, bankId));
        copy.setCombinedExtractionMapping(
                settings.getBool(SettingKeys.PARSING_COMBINED_EXTRACTION_MAPPING, bankId));
        copy.setLlmBatchSize(settings.getInt(SettingKeys.PARSING_LLM_BATCH_SIZE, bankId));
        copy.setLlmBatchConcurrency(
                settings.getInt(SettingKeys.PARSING_LLM_BATCH_CONCURRENCY, bankId));
        return copy;
    }

    /** {@link #forBank(int)} for the bank bound to this thread, else the platform layer. */
    public ParsingProperties forCurrentBank() {
        Optional<Integer> bank = TenantContext.currentBankId();
        if (bank.isPresent()) {
            return forBank(bank.get());
        }
        ParsingProperties copy = copyOfDefaults();
        copy.setDeterministicEnabled(
                settings.getBoolForPlatform(SettingKeys.PARSING_DETERMINISTIC_ENABLED));
        copy.setCombinedExtractionMapping(
                settings.getBoolForPlatform(SettingKeys.PARSING_COMBINED_EXTRACTION_MAPPING));
        copy.setLlmBatchSize(settings.getIntForPlatform(SettingKeys.PARSING_LLM_BATCH_SIZE));
        copy.setLlmBatchConcurrency(
                settings.getIntForPlatform(SettingKeys.PARSING_LLM_BATCH_CONCURRENCY));
        return copy;
    }

    /** The Excel switch in force for one bank's jobs (a fresh copy). */
    public ExcelParsingProperties excelForBank(int bankId) {
        ExcelParsingProperties copy = new ExcelParsingProperties();
        copy.setLlmEnabled(settings.getBool(SettingKeys.PARSING_EXCEL_LLM_ENABLED, bankId));
        return copy;
    }

    /** Whether deterministic parsing runs first, for the caller's bank. */
    public boolean deterministicEnabledForCurrentBank() {
        return settings.getBoolForCurrentBank(SettingKeys.PARSING_DETERMINISTIC_ENABLED);
    }

    private ParsingProperties copyOfDefaults() {
        ParsingProperties copy = new ParsingProperties();
        copy.setLlmBatchSize(defaults.getLlmBatchSize());
        copy.setLlmSinglePassMaxRows(defaults.getLlmSinglePassMaxRows());
        copy.setLlmBatchConcurrency(defaults.getLlmBatchConcurrency());
        copy.setDeterministicEnabled(defaults.isDeterministicEnabled());
        copy.setCombinedExtractionMapping(defaults.isCombinedExtractionMapping());
        return copy;
    }
}
