package com.modocument.application.usecase;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.infrastructure.prompt.ExtractionSummaryPrompt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class SummarizeExtractionUseCaseTest {

    private final ExtractionJobStore jobs = mock(ExtractionJobStore.class);
    private final ExtractionTaskQueue queue = mock(ExtractionTaskQueue.class);
    private final ProcessedRecordStore records = mock(ProcessedRecordStore.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private final ExtractionAuditLog audit = mock(ExtractionAuditLog.class);
    private final ExtractionLlmProperties extractionLlm = new ExtractionLlmProperties();
    private final SummarizeExtractionUseCase useCase = new SummarizeExtractionUseCase(
            jobs, queue, records, List.of(new ExtractionSummaryPrompt()), ai, audit,
            new PiiTokenizer(new PrivacyProperties()), extractionLlm);

    @Test
    void masksSampleValuesAndDetokenizesTheProseSummary() {
        extractionLlm.setModelId("mistral:latest");
        ExtractionJob job = mock(ExtractionJob.class);
        when(jobs.getById("j1")).thenReturn(Optional.of(job));
        ProcessedRecord record = mock(ProcessedRecord.class);
        when(record.getFields()).thenReturn(Map.of("Email", "ada@acme.com"));
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(List.of(record));
        when(ai.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("Most records look like [EMAIL_1] with clean data.");

        String summary = useCase.summarize("j1");

        ArgumentCaptor<PromptEnvelope> prompt = ArgumentCaptor.forClass(PromptEnvelope.class);
        verify(ai).sendPrompt(prompt.capture());
        assertThat(prompt.getValue().text()).contains("[EMAIL_1]");
        assertThat(prompt.getValue().text()).doesNotContain("ada@acme.com");

        assertThat(summary).isEqualTo("Most records look like ada@acme.com with clean data.");
    }
}
