package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobUseCaseTest {

    /** Minimal in-memory repository so per-step saves behave like real persistence. */
    private static class InMemoryJobs implements IngestionJobRepository {
        private final Map<Integer, IngestionJob> store = new HashMap<>();
        private int nextId = 1;

        @Override
        public Optional<IngestionJob> getById(int id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<IngestionJob> list() {
            return List.copyOf(store.values());
        }

        @Override
        public IngestionJob save(IngestionJob job) {
            if (job.getIngestionJobId() == 0) {
                job.setIngestionJobId(nextId++);
            }
            store.put(job.getIngestionJobId(), job);
            return job;
        }
    }

    private final InMemoryJobs jobs = new InMemoryJobs();
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository templateFields = mock(TemplateFieldsRepository.class);
    private final ImportHistoryRepository importHistory = mock(ImportHistoryRepository.class);
    private final ImportDetailsRepository importDetails = mock(ImportDetailsRepository.class);
    private final IngestionParseStep parseStep = mock(IngestionParseStep.class);
    private final AutoMapUseCase autoMap = mock(AutoMapUseCase.class);
    private final ValidateRowsUseCase validateRows = mock(ValidateRowsUseCase.class);

    private final IngestionJobUseCase useCase = new IngestionJobUseCase(
            jobs, templates, templateFields, importHistory, importDetails,
            parseStep, autoMap, validateRows);

    private static final TableData PARSED = new TableData(
            List.of("first_name"), List.of(Map.of("first_name", "Ada")));

    @BeforeEach
    void setUp() {
        when(templates.getById(1)).thenReturn(Optional.of(new Template(1, "T", null)));
        when(importHistory.add(any(ImportHistory.class))).thenAnswer(invocation -> {
            ImportHistory h = invocation.getArgument(0);
            h.setImportHistoryId(42);
            return h;
        });
        TemplateFields firstName = new TemplateFields();
        firstName.setFieldName("FirstName");
        when(templateFields.listByTemplateId(1)).thenReturn(List.of(firstName));
    }

    @Test
    void resumeRunsAllStagesAndPersistsCheckpoints() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(anyList(), anyList())).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(
                List.of(new RowValidationResult(1, "FirstName", "Ada")));

        IngestionJob job = useCase.create("people.csv", 1);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.UPLOADED);
        assertThat(job.getImportHistoryId()).isEqualTo(42);

        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getParsedTableJson()).contains("first_name");
        assertThat(job.getMappingJson()).contains("FirstName");
        assertThat(job.getMappedRowsJson()).contains("Ada");
        assertThat(job.getValidationResultJson()).contains("FirstName");
        // One ImportDetails record per stage (Tansforming/Mapping/Validating).
        verify(importDetails, times(3)).add(any());
    }

    @Test
    void parseFailureMarksJobFailedAndResumeRetriesFromStart() {
        when(parseStep.parse(anyString()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(PARSED);
        when(autoMap.map(anyList(), anyList())).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.UPLOADED);
        assertThat(job.getErrorMessage()).contains("boom");

        job = useCase.resume(job.getIngestionJobId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
    }

    @Test
    void mapFailureKeepsParsedCheckpointAndResumeSkipsParse() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(anyList(), anyList()))
                .thenThrow(new IllegalStateException("llm down"))
                .thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.PARSED);
        assertThat(job.getParsedTableJson()).isNotNull();

        job = useCase.resume(job.getIngestionJobId());
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        verify(parseStep, times(1)).parse(anyString());
    }

    @Test
    void updateMappingRebuildsRowsAndClearsValidation() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(anyList(), anyList())).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId());
        assertThat(job.getValidationResultJson()).isNotNull();

        job = useCase.updateMapping(job.getIngestionJobId(), Map.of("first_name", "FirstName"));

        assertThat(job.getCurrentStep()).isEqualTo(JobStep.MAPPED);
        assertThat(job.getValidationResultJson()).isNull();
        assertThat(job.getMappedRowsJson()).contains("Ada");
    }

    @Test
    void updateMappingRejectsUnknownTemplateField() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(anyList(), anyList())).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        int jobId = useCase.resume(job.getIngestionJobId()).getIngestionJobId();

        assertThatThrownBy(() -> useCase.updateMapping(jobId, Map.of("first_name", "Nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope");
    }

    @Test
    void cancelledJobCannotBeResumed() {
        IngestionJob job = useCase.create("people.csv", 1);
        useCase.cancel(job.getIngestionJobId());

        assertThatThrownBy(() -> useCase.resume(job.getIngestionJobId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void emptyParseResultFailsWithClearMessage() {
        when(parseStep.parse(anyString())).thenReturn(new TableData(List.of(), List.of()));

        IngestionJob job = useCase.create("scan.png", 1);
        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("No tabular data");
    }
}
