package com.modocument.application.usecase;

import com.modocument.application.dto.InferredSchema;
import com.modocument.application.dto.InferredSchemaResult;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.ai.AiUnavailableException;
import com.modocument.infrastructure.prompt.SchemaInferencePrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.ResourceAccessException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InferSchemaUseCaseTest {

    private final ExtractionJobStore jobs = mock(ExtractionJobStore.class);
    private final ProcessedRecordStore records = mock(ProcessedRecordStore.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private final ExtractionAuditLog audit = mock(ExtractionAuditLog.class);
    private final AiPreflight preflight = mock(AiPreflight.class);
    private final ExtractionLlmProperties extractionLlm = new ExtractionLlmProperties();
    private final InferSchemaUseCase useCase = new InferSchemaUseCase(
            jobs, records, List.of(new SchemaInferencePrompt()), ai, audit,
            new PiiTokenizer(new PrivacyProperties()), extractionLlm, preflight);

    @BeforeEach
    void job() {
        ExtractionJob job = mock(ExtractionJob.class);
        when(job.getDataSpec()).thenReturn(null);
        when(job.getBankId()).thenReturn(7);
        when(jobs.getById("j1")).thenReturn(Optional.of(job));
        when(preflight.unavailableReason(7)).thenReturn(Optional.empty());
    }

    @Test
    void masksSampleValuesAndDetokenizesInferredExamples() {
        extractionLlm.setModelId("mistral:latest");
        sample(Map.of("Email", "ada@acme.com", "Age", "36"));

        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new InferredSchema(List.of(
                        new InferredSchema.InferredField("Email", "string", "[EMAIL_1]"),
                        new InferredSchema.InferredField("Age", "int", "36"))));

        InferredSchemaResult schema = useCase.infer("j1");

        ArgumentCaptor<PromptEnvelope> prompt = ArgumentCaptor.forClass(PromptEnvelope.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class));
        assertThat(prompt.getValue().text()).contains("[EMAIL_1]");
        assertThat(prompt.getValue().text()).doesNotContain("ada@acme.com");

        assertThat(schema.fields()).extracting(InferredSchema.InferredField::example)
                .containsExactly("ada@acme.com", "36");
        assertThat(schema.generatedBy()).isEqualTo(ParsedBy.AI);
        assertThat(schema.fallbackReason()).isNull();
    }

    /** AC-EXT-17: AI switched off → types inferred from the sample values, no model call. */
    @Test
    void heuristicFallback() {
        when(preflight.unavailableReason(7)).thenReturn(Optional.of(FallbackReason.AI_DISABLED));
        sample(row("Customer Name", "Ada", "Age", "36", "Balance", "1,250.50",
                        "Opened", "2024-01-05", "Active", "yes", "Account No", "00123"),
                row("Customer Name", "Bob", "Age", "41", "Balance", "80",
                        "Opened", "15/02/2024", "Active", "no", "Account No", "00456"));

        InferredSchemaResult schema = useCase.infer("j1");

        verifyNoInteractions(ai);
        verify(audit, never()).add(any());
        assertThat(schema.generatedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(schema.fallbackReason()).isEqualTo(FallbackReason.AI_DISABLED);
        assertThat(schema.fields()).extracting(InferredSchema.InferredField::name)
                .containsExactly("Customer_Name", "Age", "Balance", "Opened", "Active", "Account_No");
        assertThat(schema.fields()).extracting(InferredSchema.InferredField::dataType)
                .containsExactly("string", "int", "decimal", "date", "boolean", "string");
        assertThat(schema.fields().get(0).example()).isEqualTo("Ada");
    }

    @Test
    void circuitOpenFromTheGatewayFallsBackToCode() {
        sample(Map.of("Amount", "12.5"));
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new AiUnavailableException("open", FallbackReason.CIRCUIT_OPEN, 30L));

        InferredSchemaResult schema = useCase.infer("j1");

        assertThat(schema.generatedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(schema.fallbackReason()).isEqualTo(FallbackReason.CIRCUIT_OPEN);
        assertThat(schema.fields()).singleElement()
                .satisfies(f -> assertThat(f.dataType()).isEqualTo("decimal"));
    }

    @Test
    void transportFailureAndEmptyReplyAreClassifiedLikeTheParseStage() {
        sample(Map.of("When", "2024-01-05T10:15:00Z"));
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("read timed out"))
                .thenReturn(new InferredSchema(List.of()));

        InferredSchemaResult timedOut = useCase.infer("j1");
        InferredSchemaResult empty = useCase.infer("j1");

        assertThat(timedOut.fallbackReason()).isEqualTo(FallbackReason.TIMEOUT);
        assertThat(timedOut.fields()).singleElement()
                .satisfies(f -> assertThat(f.dataType()).isEqualTo("datetime"));
        assertThat(empty.generatedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(empty.fallbackReason()).isEqualTo(FallbackReason.INVALID_OUTPUT);
    }

    @SafeVarargs
    private void sample(Map<String, String>... rows) {
        List<ProcessedRecord> sample = Arrays.stream(rows).map(fields -> {
            ProcessedRecord record = mock(ProcessedRecord.class);
            when(record.getFields()).thenReturn(fields);
            return record;
        }).toList();
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(sample);
    }

    private static Map<String, String> row(String... keyValues) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
