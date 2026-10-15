package com.modocument.doccreation.usecase;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.stereotype.Component;

/**
 * The document-creation settings of the caller's bank (ws20 SettingsReader,
 * read per call; with nothing stored, the application.yml value).
 */
@Component
public class DocCreationSettings {

    private final SettingsReader settings;

    public DocCreationSettings(SettingsReader settings) {
        this.settings = settings;
    }

    /**
     * Template AI assistance: the bank's {@code doccreation.sectionAiEnabled}
     * AND its AI master switch ({@code ai.enabled}). Code detection runs
     * either way; this only lets the engine ask the model to improve it.
     */
    public boolean templateAiAllowed() {
        return settings.getBoolForCurrentBank(SettingKeys.DOCCREATION_SECTION_AI_ENABLED)
                && settings.getBoolForCurrentBank(SettingKeys.AI_ENABLED);
    }

    /** {@code doccreation.pdfMinFontSize}: points, 4–10 (default 6). */
    public int pdfMinFontSize() {
        return settings.getIntForCurrentBank(SettingKeys.DOCCREATION_PDF_MIN_FONT_SIZE);
    }
}
