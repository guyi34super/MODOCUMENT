package com.modocument.application.usecase;

import com.modocument.application.dto.InferredSchema;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.infrastructure.prompt.SchemaInferencePrompt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InferSchemaUseCaseTest {

    private final ExtractionJobStore jobs = mock(ExtractionJobStore.class);
    private final ProcessedRecordStore records = mock(ProcessedRecordStore.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private final ExtractionAuditLog audit = mock(ExtractionAuditLog.class);
    private final ExtractionLlmProperties extractionLlm = new ExtractionLlmProperties();
    private final InferSchemaUseCase useCase = new InferSchemaUseCase(
            jobs, records, List.of(new SchemaInferencePrompt()), ai, audit,
            new PiiTokenizer(new PrivacyProperties()), extractionLlm);

    @Test
    void masksSampleValuesAndDetokenizesInferredExamples() {
        extractionLlm.setModelId("mistral:latest");
        ExtractionJob job = mock(ExtractionJob.class);
        when(job.getDataSpec()).thenReturn(null);
        when(jobs.getById("j1")).thenReturn(Optional.of(job));

        ProcessedRecord record = mock(ProcessedRecord.class);
        when(record.getFields()).thenReturn(Map.of("Email", "ada@acme.com", "Age", "36"));
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(List.of(record));

        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new InferredSchema(List.of(
                        new InferredSchema.InferredField("Email", "string", "[EMAIL_1]"),
                        new InferredSchema.InferredField("Age", "int", "36"))));

        InferredSchema schema = useCase.infer("j1");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class),
                org.mockito.ArgumentMatchers.eq("mistral:latest"));
        assertThat(prompt.getValue()).contains("[EMAIL_1]");
        assertThat(prompt.getValue()).doesNotContain("ada@acme.com");

        assertThat(schema.fields()).extracting(InferredSchema.InferredField::example)
                .containsExactly("ada@acme.com", "36");
    }
}
