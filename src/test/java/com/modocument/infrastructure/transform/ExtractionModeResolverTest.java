package com.modocument.infrastructure.transform;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.transform.code.CodeTableExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AC-EXT-01: mode = template mode ?? bank default setting ?? AI_WITH_CODE_FALLBACK. */
class ExtractionModeResolverTest {

    private final SettingsReader settings = mock(SettingsReader.class);
    private final ExtractionModeResolver resolver = new ExtractionModeResolver(settings);

    @Test
    void theTemplatesOwnModeWinsWithoutReadingSettings() {
        assertThat(resolver.resolve(ExtractionMode.AI_ONLY, 3)).isEqualTo(ExtractionMode.AI_ONLY);
        verifyNoInteractions(settings);
    }

    @Test
    void otherwiseTheBankDefaultApplies() {
        when(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, 3)).thenReturn("CODE_ONLY");
        when(settings.getEnumForPlatform(SettingKeys.EXTRACTION_DEFAULT_MODE)).thenReturn("AI_ONLY");

        assertThat(resolver.resolve(null, 3)).isEqualTo(ExtractionMode.CODE_ONLY);
        // No bank known: the platform layer.
        assertThat(resolver.resolve(null, null)).isEqualTo(ExtractionMode.AI_ONLY);
    }

    @Test
    void anUnreadableOrUnknownSettingFallsBackToTheShippedDefault() {
        when(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, 3))
                .thenThrow(new IllegalArgumentException("unknown key"));
        when(settings.getEnum(SettingKeys.EXTRACTION_DEFAULT_MODE, 4)).thenReturn("SOMETIMES");

        assertThat(resolver.resolve(null, 3)).isEqualTo(ExtractionMode.AI_WITH_CODE_FALLBACK);
        assertThat(resolver.resolve(null, 4)).isEqualTo(ExtractionMode.AI_WITH_CODE_FALLBACK);
        assertThat(new ExtractionModeResolver(null).resolve(null, 3))
                .isEqualTo(ExtractionMode.AI_WITH_CODE_FALLBACK);
    }

    @Test
    void theMinimumConfidenceIsTheBanksSetting() {
        when(settings.getDecimal(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, 3))
                .thenReturn(new BigDecimal("0.60"));

        assertThat(resolver.minConfidence(3)).isEqualTo(0.60);
        assertThat(new ExtractionModeResolver(null).minConfidence(3))
                .isEqualTo(CodeTableExtractor.DEFAULT_MIN_CONFIDENCE);
    }

    @Test
    void modeNamesParseCaseInsensitivelyAndBlankMeansInherit() {
        assertThat(ExtractionMode.parse(" code_only ")).isEqualTo(ExtractionMode.CODE_ONLY);
        assertThat(ExtractionMode.parse("")).isNull();
        assertThat(ExtractionMode.parse(null)).isNull();
        assertThat(ExtractionMode.parseLenient("nonsense")).isNull();
    }
}
