package com.modocument.application.usecase;

import com.modocument.application.port.AiFilesService;
import com.modocument.infrastructure.prompt.AutoMappingPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoMapUseCaseTest {

    private final AiFilesService ai = mock(AiFilesService.class);
    private final AutoMapUseCase useCase = new AutoMapUseCase(List.of(new AutoMappingPrompt()), ai);

    private static final List<String> TEMPLATE_FIELDS = List.of("FirstName", "LastName", "HireDate");

    @Test
    void exactAndNormalizedMatchesNeverTouchTheLlm() {
        Map<String, String> mapping = useCase.map(
                List.of("FirstName", "last_name", "HIRE DATE"), TEMPLATE_FIELDS);

        assertThat(mapping).containsEntry("FirstName", "FirstName")
                .containsEntry("last_name", "LastName")
                .containsEntry("HIRE DATE", "HireDate");
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void leftoversGoToTheLlmAndInvalidTargetsBecomeNull() {
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(Map.of("Start", "HireDate", "Nickname", "SomethingElse"));

        Map<String, String> mapping = useCase.map(
                List.of("FirstName", "Start", "Nickname"), TEMPLATE_FIELDS);

        assertThat(mapping).containsEntry("FirstName", "FirstName")
                .containsEntry("Start", "HireDate");
        // The LLM's invented target is not a template field, so it is unmapped.
        assertThat(mapping.get("Nickname")).isNull();
    }
}
