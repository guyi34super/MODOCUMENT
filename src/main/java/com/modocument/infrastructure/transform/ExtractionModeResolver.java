package com.modocument.infrastructure.transform;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.transform.code.CodeTableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Which {@link ExtractionMode} a document is parsed in, and the code
 * extractor's acceptance threshold (ws23): the template's own mode, else the
 * bank's {@code extraction.defaultMode} setting, else
 * {@link ExtractionMode#DEFAULT}. A null bank reads the platform layer. An
 * unreadable setting never blocks a parse — it resolves to the shipped
 * default with a WARN.
 */
@Component
public class ExtractionModeResolver {

    private static final Logger log = LoggerFactory.getLogger(ExtractionModeResolver.class);

    private final SettingsReader settings;

    public ExtractionModeResolver(SettingsReader settings) {
        this.settings = settings;
    }

    public ExtractionMode resolve(ExtractionMode templateMode, Integer bankId) {
        if (templateMode != null) {
            return templateMode;
        }
        if (settings == null) {
            return ExtractionMode.DEFAULT;
        }
        try {
            String value = bankId == null
                    ? settings.getEnumForPlatform(SettingKeys.EXTRACTION_DEFAULT_MODE)
                    : settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, bankId);
            ExtractionMode mode = ExtractionMode.parseLenient(value);
            return mode == null ? ExtractionMode.DEFAULT : mode;
        } catch (RuntimeException e) {
            log.warn("Could not read extraction.defaultMode for bank {}: {}",
                    bankId, e.getClass().getSimpleName());
            return ExtractionMode.DEFAULT;
        }
    }

    /** {@code extraction.code.minConfidence} for the bank (0.75 shipped). */
    public double minConfidence(Integer bankId) {
        if (settings == null) {
            return CodeTableExtractor.DEFAULT_MIN_CONFIDENCE;
        }
        try {
            BigDecimal value = bankId == null
                    ? settings.getDecimalForPlatform(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE)
                    : settings.getDecimal(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, bankId);
            return value == null ? CodeTableExtractor.DEFAULT_MIN_CONFIDENCE : value.doubleValue();
        } catch (RuntimeException e) {
            log.warn("Could not read extraction.code.minConfidence for bank {}: {}",
                    bankId, e.getClass().getSimpleName());
            return CodeTableExtractor.DEFAULT_MIN_CONFIDENCE;
        }
    }
}
