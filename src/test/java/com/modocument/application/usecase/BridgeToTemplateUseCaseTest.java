package com.modocument.application.usecase;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.mapping.MappingContext;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionAuditLog;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionJobStore;
import com.modocument.infrastructure.extraction.memory.InMemoryProcessedRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BridgeToTemplateUseCaseTest {

    private final InMemoryExtractionJobStore extractionJobs = new InMemoryExtractionJobStore();
    private final InMemoryProcessedRecordStore processedRecords = new InMemoryProcessedRecordStore();
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository templateFields = mock(TemplateFieldsRepository.class);
    private final AutoMapUseCase autoMap = mock(AutoMapUseCase.class);
    private final ValidateRowsUseCase validateRows = mock(ValidateRowsUseCase.class);
    private final CleanRecordsUseCase cleanRecords = mock(CleanRecordsUseCase.class);
    private final IngestionJobRepository ingestionJobs = mock(IngestionJobRepository.class);
    private final ImportHistoryRepository importHistory = mock(ImportHistoryRepository.class);
    private final ImportDetailsRepository importDetails = mock(ImportDetailsRepository.class);

    private static final int USER = 1;
    private final BridgeToTemplateUseCase useCase = new BridgeToTemplateUseCase(
            extractionJobs, processedRecords, templates, templateFields, autoMap, validateRows,
            cleanRecords, ingestionJobs, importHistory, importDetails,
            new InMemoryExtractionAuditLog(), FixedUser.provider(USER), 500);

    private ExtractionJob job;

    @BeforeEach
    void setUp() {
        job = new ExtractionJob();
        job.setSourceId("s1");
        job.setStatus(ExtractionJobStatus.COMPLETED);
        job = extractionJobs.save(job);

        when(templates.getAccessible(1, USER, com.modocument.domain.entity.Bank.DEFAULT_BANK_ID)).thenReturn(Optional.of(new Template(1, "T", "T")));
        TemplateFields nameField = mock(TemplateFields.class);
        when(nameField.getFieldName()).thenReturn("Name");
        when(templateFields.listByTemplateId(1)).thenReturn(List.of(nameField));
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("Name", "Name"));
        when(validateRows.handle(any())).thenReturn(
                List.of(new RowValidationResult(0, "Name", "Ada", null),
                        new RowValidationResult(1, "Name", "", "Required")));
        when(importHistory.add(any())).thenAnswer(invocation -> {
            ImportHistory history = invocation.getArgument(0);
            history.setImportHistoryId(77);
            return history;
        });
        when(ingestionJobs.save(any())).thenAnswer(invocation -> {
            IngestionJob saved = invocation.getArgument(0);
            saved.setIngestionJobId(42);
            return saved;
        });
    }

    private void addRecords(int count) {
        for (int i = 0; i < count; i++) {
            ProcessedRecord record = new ProcessedRecord();
            record.setJobId(job.getId());
            record.setFields(Map.of("Name", "Person" + i));
            record.setDedupKey("k" + i);
            processedRecords.saveAllIfNew(List.of(record));
        }
    }

    @Test
    void bridgeCreatesValidatedIngestionJobWithAuditRows() {
        addRecords(2);

        BridgeToTemplateUseCase.BridgeResult result = useCase.bridge(job.getId(), 1, null, false);

        assertThat(result.ingestionJobId()).isEqualTo(42);
        assertThat(result.importHistoryId()).isEqualTo(77);
        assertThat(result.rowsBridged()).isEqualTo(2);
        assertThat(result.invalidValues()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();

        ArgumentCaptor<IngestionJob> saved = ArgumentCaptor.forClass(IngestionJob.class);
        verify(ingestionJobs).save(saved.capture());
        assertThat(saved.getValue().getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        assertThat(saved.getValue().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(saved.getValue().getFileName()).isEqualTo("extraction:" + job.getId());
        assertThat(saved.getValue().getMappedRowsJson()).contains("Person0");

        // Transforming, Mapping, and Validating audit rows are written.
        verify(importDetails, times(3)).add(any(ImportDetails.class));
    }

    @Test
    void bridgeCapsRowsAndReportsTruncation() {
        addRecords(5);

        BridgeToTemplateUseCase.BridgeResult result = useCase.bridge(job.getId(), 1, 3, false);

        assertThat(result.rowsBridged()).isEqualTo(3);
        assertThat(result.rowsAvailable()).isEqualTo(5);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void llmCleanIsInvokedWhenRequested() {
        addRecords(1);
        when(cleanRecords.clean(anyList(), anyList(), any()))
                .thenReturn(List.of(Map.of("Name", "Cleaned")));

        useCase.bridge(job.getId(), 1, null, true);

        verify(cleanRecords).clean(anyList(), anyList(), any());
        ArgumentCaptor<IngestionJob> saved = ArgumentCaptor.forClass(IngestionJob.class);
        verify(ingestionJobs).save(saved.capture());
        assertThat(saved.getValue().getMappedRowsJson()).contains("Cleaned");
    }

    @Test
    void bridgeRequiresCompletedJobAndRecords() {
        job.setStatus(ExtractionJobStatus.RUNNING);
        extractionJobs.save(job);
        assertThatThrownBy(() -> useCase.bridge(job.getId(), 1, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not completed");

        job.setStatus(ExtractionJobStatus.COMPLETED);
        extractionJobs.save(job);
        assertThatThrownBy(() -> useCase.bridge(job.getId(), 1, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no processed records");
    }

    @Test
    void bridgeRequiresExistingTemplate() {
        addRecords(1);
        when(templates.getById(anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.bridge(job.getId(), 9, null, false))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
