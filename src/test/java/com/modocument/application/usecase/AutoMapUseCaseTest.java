package com.modocument.application.usecase;

import com.modocument.application.mapping.MappingContext;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import com.modocument.infrastructure.prompt.AutoMappingPrompt;
import com.modocument.infrastructure.rule.RegexRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoMapUseCaseTest {

    private final AiFilesService ai = mock(AiFilesService.class);
    private final ValidationRuleRepository ruleRepository = mock(ValidationRuleRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final MappingProperties properties = new MappingProperties();
    private final AutoMapUseCase useCase = new AutoMapUseCase(
            List.of(new AutoMappingPrompt()), ai,
            new ValueShapeScorer(ruleRepository, List.of(new RegexRule())),
            jobs, properties);

    private static final List<TemplateFields> TEMPLATE_FIELDS =
            fields("FirstName", "LastName", "HireDate");

    private static List<TemplateFields> fields(String... names) {
        return java.util.Arrays.stream(names).map(name -> {
            TemplateFields field = new TemplateFields();
            field.setFieldName(name);
            return field;
        }).toList();
    }

    private static MappingContext context(List<String> columns, List<TemplateFields> fields) {
        return new MappingContext(columns, fields, List.of(), 1, 1);
    }

    @Test
    void exactAndNormalizedMatchesNeverTouchTheLlm() {
        Map<String, String> mapping = useCase.map(
                context(List.of("FirstName", "last_name", "HIRE DATE"), TEMPLATE_FIELDS));

        assertThat(mapping).containsEntry("FirstName", "FirstName")
                .containsEntry("last_name", "LastName")
                .containsEntry("HIRE DATE", "HireDate");
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void aliasAndSimilarityResolveAbbreviatedHeadersWithoutTheLlm() {
        Map<String, String> mapping = useCase.map(
                context(List.of("fname", "Surname"), TEMPLATE_FIELDS));

        assertThat(mapping).containsEntry("fname", "FirstName")
                .containsEntry("Surname", "LastName");
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void valueShapeResolvesAnonymousColumnsThroughTheFieldsOwnRules() {
        List<TemplateFields> fields = fields("Email", "Notes");
        when(ruleRepository.getRulesByTemplate(1)).thenReturn(List.of(
                rule("Email", "{\"Pattern\":\"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$\"}")));
        List<Map<String, String>> rows = List.of(
                Map.of("Column3", "ada@acme.com"), Map.of("Column3", "grace@acme.com"),
                Map.of("Column3", "joan@acme.com"), Map.of("Column3", "mary@acme.com"),
                Map.of("Column3", "rosa@acme.com"));

        Map<String, String> mapping = useCase.map(
                new MappingContext(List.of("Column3"), fields, rows, 1, 1));

        assertThat(mapping).containsEntry("Column3", "Email");
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void historyResolvesColumnsSeenInPastJobs() {
        when(jobs.listRecentMappingJson(1, 1, properties.getHistoryJobs())).thenReturn(List.of(
                "{\"Nom\":\"FirstName\"}", "{\"Nom\":\"FirstName\"}"));

        Map<String, String> mapping = useCase.map(context(List.of("Nom"), TEMPLATE_FIELDS));

        assertThat(mapping).containsEntry("Nom", "FirstName");
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void eachTemplateFieldIsAssignedOnlyOnceHighestScoreWins() {
        properties.setLlmFallbackEnabled(false);

        Map<String, String> mapping = useCase.map(
                context(List.of("first_name", "fname"), TEMPLATE_FIELDS));

        // The normalized match wins FirstName; the alias candidate must not
        // steal or duplicate it.
        assertThat(mapping).containsEntry("first_name", "FirstName");
        assertThat(mapping.get("fname")).isNull();
    }

    @Test
    void lowConfidenceColumnsStayUnmappedWhenTheLlmIsDisabled() {
        properties.setLlmFallbackEnabled(false);

        Map<String, String> mapping = useCase.map(
                context(List.of("Zebra_Quotient"), TEMPLATE_FIELDS));

        assertThat(mapping.get("Zebra_Quotient")).isNull();
        verify(ai, never()).sendForObject(anyString(), any());
    }

    @Test
    void llmSeesOnlyUnresolvedColumnsAndUnassignedFields() {
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(Map.of("Start", "HireDate", "Nickname", "SomethingElse"));

        Map<String, String> mapping = useCase.map(
                context(List.of("FirstName", "Start", "Nickname"), TEMPLATE_FIELDS));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class));
        // Resolved column and its assigned field never travel to the LLM.
        assertThat(prompt.getValue()).doesNotContain("FirstName");
        assertThat(prompt.getValue()).contains("Start").contains("Nickname");

        assertThat(mapping).containsEntry("FirstName", "FirstName")
                .containsEntry("Start", "HireDate");
        // The LLM's invented target is not an offered field, so it is unmapped.
        assertThat(mapping.get("Nickname")).isNull();
    }

    @Test
    void llmFailureLeavesColumnsUnmappedInsteadOfFailingTheStage() {
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("aifiles.openai.api-key must be configured."));

        assertThatCode(() -> {
            Map<String, String> mapping = useCase.map(
                    context(List.of("Zebra_Quotient"), TEMPLATE_FIELDS));
            assertThat(mapping.get("Zebra_Quotient")).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void historyLookupFailureDegradesSilently() {
        properties.setLlmFallbackEnabled(false);
        when(jobs.listRecentMappingJson(anyInt(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> useCase.map(context(List.of("Nom"), TEMPLATE_FIELDS)))
                .doesNotThrowAnyException();
    }

    private static ValidationRule rule(String fieldName, String configJson) {
        ValidationRule rule = new ValidationRule();
        rule.setTemplateId(1);
        rule.setFieldName(fieldName);
        rule.setRuleType(RuleType.REGEX);
        rule.setConfigJson(configJson);
        return rule;
    }
}
