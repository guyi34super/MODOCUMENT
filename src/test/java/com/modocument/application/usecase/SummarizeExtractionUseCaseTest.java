package com.modocument.application.usecase;

import com.modocument.application.dto.ExtractionSummaryResult;
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
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;
import com.modocument.infrastructure.ai.AiNotConfiguredException;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.prompt.ExtractionSummaryPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SummarizeExtractionUseCaseTest {

    private final ExtractionJobStore jobs = mock(ExtractionJobStore.class);
    private final ExtractionTaskQueue queue = mock(ExtractionTaskQueue.class);
    private final ProcessedRecordStore records = mock(ProcessedRecordStore.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private final ExtractionAuditLog audit = mock(ExtractionAuditLog.class);
    private final AiPreflight preflight = mock(AiPreflight.class);
    private final ExtractionLlmProperties extractionLlm = new ExtractionLlmProperties();
    private final SummarizeExtractionUseCase useCase = new SummarizeExtractionUseCase(
            jobs, queue, records, List.of(new ExtractionSummaryPrompt()), ai, audit,
            new PiiTokenizer(new PrivacyProperties()), extractionLlm, preflight);
    private final ExtractionJob job = mock(ExtractionJob.class);

    @BeforeEach
    void job() {
        when(job.getBankId()).thenReturn(7);
        when(jobs.getById("j1")).thenReturn(Optional.of(job));
        when(preflight.unavailableReason(7)).thenReturn(Optional.empty());
    }

    @Test
    void masksSampleValuesAndDetokenizesTheProseSummary() {
        extractionLlm.setModelId("mistral:latest");
        ProcessedRecord record = mock(ProcessedRecord.class);
        when(record.getFields()).thenReturn(Map.of("Email", "ada@acme.com"));
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(List.of(record));
        when(ai.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("Most records look like [EMAIL_1] with clean data.");

        ExtractionSummaryResult summary = useCase.summarize("j1");

        ArgumentCaptor<PromptEnvelope> prompt = ArgumentCaptor.forClass(PromptEnvelope.class);
        verify(ai).sendPrompt(prompt.capture());
        assertThat(prompt.getValue().text()).contains("[EMAIL_1]");
        assertThat(prompt.getValue().text()).doesNotContain("ada@acme.com");

        assertThat(summary.summary()).isEqualTo("Most records look like ada@acme.com with clean data.");
        assertThat(summary.generatedBy()).isEqualTo(ParsedBy.AI);
        assertThat(summary.fallbackReason()).isNull();
    }

    /** AC-EXT-17: circuit open → summary from counters, quoting no record value. */
    @Test
    void heuristicFallback() {
        when(preflight.unavailableReason(7)).thenReturn(Optional.of(FallbackReason.CIRCUIT_OPEN));
        when(job.getStatus()).thenReturn(ExtractionJobStatus.COMPLETED);
        Map<String, Long> counters = new HashMap<>();
        counters.put(ExtractionJob.RECORDS_RAW, 5L);
        counters.put(ExtractionJob.RECORDS_DEDUPED, 1L);
        when(job.getCounters()).thenReturn(counters);
        Map<ExtractionTaskStatus, Long> tasks = new LinkedHashMap<>();
        tasks.put(ExtractionTaskStatus.COMPLETED, 3L);
        when(queue.countByStatus("j1")).thenReturn(tasks);
        when(records.countByJob("j1")).thenReturn(4L);
        List<ProcessedRecord> rows = List.of(
                record("r1", "Email", "ada@acme.com", "City", "Paris"),
                record("r2", "Email", "bob@acme.com", "City", "Paris"),
                record("r3", "Email", "", "City", "Lyon"),
                record("r4", "Email", "eve@acme.com", "City", "Paris"));
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(rows);

        ExtractionSummaryResult summary = useCase.summarize("j1");

        verifyNoInteractions(ai);
        assertThat(summary.generatedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(summary.fallbackReason()).isEqualTo(FallbackReason.CIRCUIT_OPEN);
        assertThat(summary.summary())
                .contains("Run status: COMPLETED")
                .contains("4 processed record(s) from 5 raw record(s), 1 deduplicated")
                .contains("3 task(s) (3 completed)")
                .contains("2 field(s):")
                .contains("- Email: 75% filled, 3 distinct value(s)")
                .contains("- City: 100% filled, 2 distinct value(s)")
                .contains("AI unavailable: CIRCUIT_OPEN")
                .doesNotContain("ada@acme.com", "Paris");
    }

    @Test
    void aFailedModelCallFallsBackWithTheClassifiedReason() {
        when(records.findBatch(anyString(), any(), anyInt())).thenReturn(List.of());
        when(ai.sendPrompt(any(PromptEnvelope.class)))
                .thenThrow(new AiNotConfiguredException("no key"));

        ExtractionSummaryResult summary = useCase.summarize("j1");

        assertThat(summary.generatedBy()).isEqualTo(ParsedBy.CODE);
        assertThat(summary.fallbackReason()).isEqualTo(FallbackReason.NOT_CONFIGURED);
        assertThat(summary.summary()).contains("0 processed record(s)");
    }

    private static ProcessedRecord record(String id, String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put(keyValues[i], keyValues[i + 1]);
        }
        ProcessedRecord record = mock(ProcessedRecord.class);
        when(record.getId()).thenReturn(id);
        when(record.getFields()).thenReturn(fields);
        return record;
    }
}
