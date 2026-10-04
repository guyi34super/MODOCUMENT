package com.modocument.application.usecase;

import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.infrastructure.prompt.DataCleaningPrompt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanRecordsUseCaseTest {

    private final AiFilesService ai = mock(AiFilesService.class);
    private final ExtractionLlmProperties extractionLlm = new ExtractionLlmProperties();
    private final CleanRecordsUseCase useCase = new CleanRecordsUseCase(
            List.of(new DataCleaningPrompt()), ai, new PiiTokenizer(new PrivacyProperties()),
            extractionLlm);

    private static final List<String> COLUMNS = List.of("Email", "Amount");
    private static final List<Map<String, String>> ROWS =
            List.of(Map.of("Email", "ada@acme.com", "Amount", " 1,250.00 "));

    @Test
    void masksSensitiveCellsInPromptAndDetokenizesResponse() {
        extractionLlm.setModelId("mistral:latest");
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new TableData(COLUMNS,
                        List.of(Map.of("Email", "[EMAIL_1]", "Amount", "1250.00"))));

        List<Map<String, String>> cleaned = useCase.clean(COLUMNS, ROWS, null);

        ArgumentCaptor<PromptEnvelope> prompt = ArgumentCaptor.forClass(PromptEnvelope.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class));
        assertThat(prompt.getValue().text()).contains("[EMAIL_1]");
        assertThat(prompt.getValue().text()).doesNotContain("ada@acme.com");
        // Non-sensitive cells travel raw so the model can actually clean them.
        assertThat(prompt.getValue().text()).contains("1,250.00");

        assertThat(cleaned).containsExactly(Map.of("Email", "ada@acme.com", "Amount", "1250.00"));
    }

    @Test
    void keepsOriginalRowsOnMismatchedBatchOrFailure() {
        extractionLlm.setModelId("mistral:latest");
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new TableData(COLUMNS, List.of()));
        assertThat(useCase.clean(COLUMNS, ROWS, null)).isEqualTo(ROWS);

        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("no key"));
        assertThat(useCase.clean(COLUMNS, ROWS, null)).isEqualTo(ROWS);
    }
}
