package com.modocument.api.controller;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.RawRecordStore;
import com.modocument.application.usecase.BridgeToTemplateUseCase;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.InferSchemaUseCase;
import com.modocument.application.usecase.SummarizeExtractionUseCase;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.job.JobWaitService;
import com.modocument.testsupport.RecordingAuditRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws28 read audit: peeking at an extraction job's records records
 * SENSITIVE_DATA_VIEWED for that job (the recorder deduplicates per actor,
 * job and hour — JpaAuditRecorderThrottleTest); a job the caller cannot
 * read records nothing.
 */
class ExtractionJobControllerAuditTest {

    private final ExtractionJobUseCase jobs = mock(ExtractionJobUseCase.class);
    private final RecordingAuditRecorder audit = new RecordingAuditRecorder();
    private final ExtractionJobController controller = new ExtractionJobController(jobs,
            mock(BridgeToTemplateUseCase.class), mock(InferSchemaUseCase.class),
            mock(SummarizeExtractionUseCase.class), mock(RawRecordStore.class),
            mock(ProcessedRecordStore.class), mock(ExtractionAuditLog.class),
            mock(JobWaitService.class));

    {
        controller.setAuditRecorder(audit);
    }

    @Test
    void readingRecordsIsAuditedForTheJob() {
        when(jobs.get("job-9")).thenReturn(new ExtractionJob());

        controller.records("job-9", "processed", null, 50);

        List<AuditEventSpec> reads = audit.of("SENSITIVE_DATA_VIEWED");
        assertThat(reads).singleElement().satisfies(spec -> {
            assertThat(spec.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(spec.entityType()).isEqualTo("ExtractionJob");
            assertThat(spec.entityId()).isEqualTo("job-9");
            assertThat(spec.newValues()).isEqualTo(Map.of("view", "extraction-records"));
        });
    }

    @Test
    void anUnreadableJobRecordsNoRead() {
        when(jobs.get("other")).thenThrow(new NoSuchElementException("not found"));

        assertThatThrownBy(() -> controller.records("other", "raw", null, 50))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(audit.recorded()).isEmpty();
    }
}
