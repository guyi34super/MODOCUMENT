package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.mapping.MappingContext;
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
import com.modocument.domain.model.WizardStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        public Optional<IngestionJob> getByIdAndOwner(int id, int ownerUserId) {
            return getById(id).filter(j -> j.getOwnerUserId() == ownerUserId);
        }

        @Override
        public List<IngestionJob> list() {
            return List.copyOf(store.values());
        }

        @Override
        public List<IngestionJob> listByOwner(int ownerUserId) {
            return store.values().stream()
                    .filter(j -> j.getOwnerUserId() == ownerUserId)
                    .toList();
        }

        @Override
        public IngestionJob save(IngestionJob job) {
            if (job.getIngestionJobId() == 0) {
                job.setIngestionJobId(nextId++);
            }
            store.put(job.getIngestionJobId(), job);
            return job;
        }

        @Override
        public List<String> listRecentMappingJson(int templateId, int ownerUserId, int limit) {
            return store.values().stream()
                    .filter(j -> j.getTemplateId() == templateId)
                    .filter(j -> j.getOwnerUserId() == ownerUserId)
                    .filter(j -> j.getCurrentStep() == JobStep.VALIDATED
                            || j.getCurrentStep() == JobStep.EXPORTED)
                    .filter(j -> j.getMappingJson() != null)
                    .sorted((a, b) -> Integer.compare(b.getIngestionJobId(), a.getIngestionJobId()))
                    .limit(limit)
                    .map(IngestionJob::getMappingJson)
                    .toList();
        }

        @Override
        public int countByTemplateId(int templateId) {
            return (int) store.values().stream()
                    .filter(j -> j.getTemplateId() == templateId)
                    .count();
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
    private static final int USER = 1;

    private final IngestionJobUseCase useCase = new IngestionJobUseCase(
            jobs, templates, templateFields, importHistory, importDetails,
            parseStep, autoMap, validateRows, () -> USER);

    private static final TableData PARSED = new TableData(
            List.of("first_name"), List.of(Map.of("first_name", "Ada")));

    @BeforeEach
    void setUp() {
        when(templates.getByIdAndOwner(1, USER)).thenReturn(Optional.of(new Template(1, "T", null)));
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
    void resumeUntilMappedStopsBeforeValidation() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId(), JobStep.MAPPED);

        assertThat(job.getCurrentStep()).isEqualTo(JobStep.MAPPED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getMappedRowsJson()).contains("Ada");
        assertThat(job.getValidationResultJson()).isNull();
        verify(validateRows, times(0)).handle(any());
    }

    @Test
    void resumeRunsAllStagesAndPersistsCheckpoints() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of(
                new RowValidationResult(1, "FirstName", "Ada"),
                new RowValidationResult(2, "FirstName", "", "Value is required.", "REQUIRED")));

        IngestionJob job = useCase.create("people.csv", 1);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.UPLOADED);
        assertThat(job.getImportHistoryId()).isEqualTo(42);

        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getParsedTableJson()).contains("first_name");
        assertThat(job.getMappingJson()).contains("FirstName");
        assertThat(job.getMappedRowsJson()).contains("Ada");
        // Only the failing entries are checkpointed; the passing one is dropped.
        assertThat(job.getValidationResultJson()).contains("Value is required.");
        assertThat(job.getValidationResultJson()).doesNotContain("Ada");
        // One ImportDetails record per stage (Tansforming/Mapping/Validating).
        verify(importDetails, times(3)).add(any());
    }

    @Test
    void parseFailureMarksJobFailedAndResumeRetriesFromStart() {
        when(parseStep.parse(anyString()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
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
        when(autoMap.map(any(MappingContext.class)))
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
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
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
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
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
    void setWizardStepPersistsPosition() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId());

        job = useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);
        assertThat(job.getWizardStep()).isEqualTo(WizardStep.VALIDATION);
        assertThat(jobs.getById(job.getIngestionJobId()).orElseThrow().getWizardStep())
                .isEqualTo(WizardStep.VALIDATION);

        // Idempotent last-write-wins.
        job = useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);
        assertThat(job.getWizardStep()).isEqualTo(WizardStep.VALIDATION);
    }

    @Test
    void setWizardStepRejectsUnreachableStep() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));

        IngestionJob job = useCase.create("people.csv", 1);
        int jobId = useCase.resume(job.getIngestionJobId(), JobStep.MAPPED).getIngestionJobId();

        assertThatThrownBy(() -> useCase.setWizardStep(jobId, WizardStep.VALIDATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not reachable");

        assertThat(useCase.setWizardStep(jobId, WizardStep.MAPPING).getWizardStep())
                .isEqualTo(WizardStep.MAPPING);
    }

    @Test
    void remapClampsWizardStepToMapping() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId());
        useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);

        job = useCase.remap(job.getIngestionJobId());

        assertThat(job.getWizardStep()).isEqualTo(WizardStep.MAPPING);
    }

    @Test
    void updateMappingClampsWizardStepToMapping() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId());
        useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);

        job = useCase.updateMapping(job.getIngestionJobId(), Map.of("first_name", "FirstName"));

        assertThat(job.getWizardStep()).isEqualTo(WizardStep.MAPPING);
    }

    @Test
    void revalidateKeepsWizardStep() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId());
        useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);

        job = useCase.revalidate(job.getIngestionJobId());

        assertThat(job.getWizardStep()).isEqualTo(WizardStep.VALIDATION);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
    }

    @Test
    void updateRowsPersistsEditsAndClearsValidation() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId());
        useCase.setWizardStep(job.getIngestionJobId(), WizardStep.VALIDATION);

        job = useCase.updateRows(job.getIngestionJobId(), List.of(Map.of("FirstName", "Grace")));

        assertThat(job.getMappedRowsJson()).contains("Grace");
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.MAPPED);
        assertThat(job.getValidationResultJson()).isNull();
        assertThat(job.getExportInfoJson()).isNull();
        assertThat(job.getWizardStep()).isEqualTo(WizardStep.MAPPING);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void updateRowsRejectsUnknownColumnKey() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        int jobId = useCase.resume(job.getIngestionJobId()).getIngestionJobId();

        assertThatThrownBy(() -> useCase.updateRows(jobId, List.of(Map.of("Nope", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope");
    }

    @Test
    void updateRowsRejectsRowCountMismatch() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        int jobId = useCase.resume(job.getIngestionJobId()).getIngestionJobId();

        assertThatThrownBy(() -> useCase.updateRows(jobId,
                List.of(Map.of("FirstName", "Ada"), Map.of("FirstName", "Grace"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
    }

    @Test
    void updateRowsRequiresMappedRows() {
        IngestionJob job = useCase.create("people.csv", 1);

        assertThatThrownBy(() -> useCase.updateRows(job.getIngestionJobId(),
                List.of(Map.of("FirstName", "Ada"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no mapped rows");
    }

    @Test
    void updateRowsRejectsCancelledJob() {
        IngestionJob job = useCase.create("people.csv", 1);
        useCase.cancel(job.getIngestionJobId());

        assertThatThrownBy(() -> useCase.updateRows(job.getIngestionJobId(),
                List.of(Map.of("FirstName", "Ada"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void recordExportSetsWizardStepExport() {
        when(parseStep.parse(anyString())).thenReturn(PARSED);
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of("first_name", "FirstName"));
        when(validateRows.handle(any())).thenReturn(List.of());

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId());

        job = useCase.recordExport(job, Map.of("format", "csv"));

        assertThat(job.getWizardStep()).isEqualTo(WizardStep.EXPORT);
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.EXPORTED);
    }

    @Test
    void emptyParseResultFailsWithClearMessage() {
        when(parseStep.parse(anyString())).thenReturn(new TableData(List.of(), List.of()));

        IngestionJob job = useCase.create("scan.png", 1);
        job = useCase.resume(job.getIngestionJobId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("No tabular data");
    }

    @Test
    void updateMappingRefusesTwoColumnsOnTheSameField() {
        when(parseStep.parse(anyString())).thenReturn(new TableData(
                List.of("first_name", "fname"),
                List.of(Map.of("first_name", "Ada", "fname", "Ada"))));
        when(autoMap.map(any(MappingContext.class))).thenReturn(Map.of());

        IngestionJob job = useCase.create("people.csv", 1);
        useCase.resume(job.getIngestionJobId(), JobStep.MAPPED);

        Map<String, String> mapping = new java.util.LinkedHashMap<>();
        mapping.put("first_name", "FirstName");
        mapping.put("fname", "FirstName");

        assertThatThrownBy(() -> useCase.updateMapping(job.getIngestionJobId(), mapping))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only one source column");
    }

    @Test
    void headerOnlyFileSaysSoInsteadOfBlamingOcr() {
        when(parseStep.parse(anyString()))
                .thenReturn(new TableData(List.of("FirstName"), List.of()));

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId(), JobStep.MAPPED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("no data rows").doesNotContain("OCR");
    }

    @Test
    void aResumeWithNothingToDoKeepsTheRecordedFailure() {
        when(parseStep.parse(anyString())).thenThrow(new IllegalStateException("boom"));

        IngestionJob job = useCase.create("people.csv", 1);
        job = useCase.resume(job.getIngestionJobId(), JobStep.VALIDATED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);

        // UPLOADED is already >= UPLOADED, so this resume runs no stage at all
        // and must not quietly erase why the last one failed.
        job = useCase.resume(job.getIngestionJobId(), JobStep.UPLOADED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("boom");
    }

    // ---- step completion ---------------------------------------------------

    /** Template with two fields, so "every field mapped" can actually fail. */
    private void templateWithTwoFields() {
        TemplateFields firstName = new TemplateFields();
        firstName.setFieldName("FirstName");
        TemplateFields email = new TemplateFields();
        email.setFieldName("Email");
        when(templateFields.listByTemplateId(1)).thenReturn(List.of(firstName, email));
    }

    private IngestionJob jobAt(JobStep step, String mappedColumnsJson, String validationJson) {
        // No explicit id: the in-memory repository assigns one, so several
        // fixtures in a single test do not overwrite each other.
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(USER);
        job.setTemplateId(1);
        job.setCurrentStep(step);
        job.setMappedRowsJson(mappedColumnsJson);
        job.setValidationResultJson(validationJson);
        return jobs.save(job);
    }

    private static final String BOTH_FIELDS =
            "{\"columns\":[\"FirstName\",\"Email\"],\"rows\":[{\"FirstName\":\"Ada\"}]}";
    private static final String ONE_FIELD =
            "{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"Ada\"}]}";
    private static final String NO_ERRORS = "[]";
    private static final String ONE_ERROR =
            "[{\"row\":1,\"fieldName\":\"Email\",\"rawValue\":\"x\","
                    + "\"errorMessage\":\"Email format is invalid.\",\"ruleType\":\"REGEX\"}]";

    @Test
    void unfinishedMappingOpensNothingBeyondMapping() {
        templateWithTwoFields();

        // Validation has run, but one template field never got a source column.
        IngestionJob job = jobAt(JobStep.VALIDATED, ONE_FIELD, NO_ERRORS);

        assertThat(useCase.unmappedFields(job)).containsExactly("Email");
        assertThat(useCase.reachableSteps(job)).containsExactly(WizardStep.MAPPING);
        assertThat(useCase.blockedReason(job, WizardStep.VALIDATION))
                .contains("map every template field first")
                .contains("Email");
    }

    @Test
    void validationErrorsOpenValidationButNotExport() {
        templateWithTwoFields();

        IngestionJob job = jobAt(JobStep.VALIDATED, BOTH_FIELDS, ONE_ERROR);

        assertThat(useCase.validationErrorCount(job)).isEqualTo(1);
        assertThat(useCase.reachableSteps(job))
                .containsExactly(WizardStep.MAPPING, WizardStep.VALIDATION);
        assertThat(useCase.blockedReason(job, WizardStep.EXPORT))
                .contains("1 row still fails validation");
    }

    @Test
    void aCleanValidatedJobOpensEveryStep() {
        templateWithTwoFields();

        IngestionJob job = jobAt(JobStep.VALIDATED, BOTH_FIELDS, NO_ERRORS);

        assertThat(useCase.reachableSteps(job))
                .containsExactly(WizardStep.MAPPING, WizardStep.VALIDATION, WizardStep.EXPORT);
        assertThat(useCase.unmappedFields(job)).isEmpty();
    }

    @Test
    void validationNotRunYetOpensNothingBeyondMapping() {
        templateWithTwoFields();

        IngestionJob job = jobAt(JobStep.MAPPED, BOTH_FIELDS, null);

        assertThat(useCase.reachableSteps(job)).containsExactly(WizardStep.MAPPING);
        assertThat(useCase.blockedReason(job, WizardStep.VALIDATION))
                .isEqualTo("run validation on the mapped rows first");
    }

    @Test
    void nothingMappedAtAllOpensNothingBeyondMapping() {
        templateWithTwoFields();

        IngestionJob job = jobAt(JobStep.PARSED, null, null);

        assertThat(useCase.reachableSteps(job)).containsExactly(WizardStep.MAPPING);
        assertThat(useCase.unmappedFields(job)).containsExactly("FirstName", "Email");
    }

    @Test
    void setWizardStepRefusesAStepTheJobHasNotEarned() {
        templateWithTwoFields();
        IngestionJob withErrors = jobAt(JobStep.VALIDATED, BOTH_FIELDS, ONE_ERROR);

        assertThatThrownBy(() -> useCase.setWizardStep(withErrors.getIngestionJobId(),
                WizardStep.EXPORT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 row still fails validation");

        // The step it HAS earned is still accepted.
        assertThat(useCase.setWizardStep(withErrors.getIngestionJobId(), WizardStep.VALIDATION)
                .getWizardStep()).isEqualTo(WizardStep.VALIDATION);
    }

    @Test
    void setWizardStepRefusesValidationWhileAFieldIsUnmapped() {
        templateWithTwoFields();
        IngestionJob partial = jobAt(JobStep.VALIDATED, ONE_FIELD, NO_ERRORS);

        assertThatThrownBy(() -> useCase.setWizardStep(partial.getIngestionJobId(),
                WizardStep.VALIDATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email");
    }

    @Test
    void requireExportableNamesWhatIsBlockingTheExport() {
        templateWithTwoFields();

        assertThatThrownBy(() -> useCase.requireExportable(
                jobAt(JobStep.VALIDATED, BOTH_FIELDS, ONE_ERROR)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 row still fails validation");

        assertThatThrownBy(() -> useCase.requireExportable(
                jobAt(JobStep.VALIDATED, ONE_FIELD, NO_ERRORS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Email");

        assertThatCode(() -> useCase.requireExportable(
                jobAt(JobStep.VALIDATED, BOTH_FIELDS, NO_ERRORS)))
                .doesNotThrowAnyException();
    }

    @Test
    void requireExportableStillRefusesACancelledJob() {
        templateWithTwoFields();
        IngestionJob job = jobAt(JobStep.VALIDATED, BOTH_FIELDS, NO_ERRORS);
        job.setStatus(JobStatus.CANCELLED);

        assertThatThrownBy(() -> useCase.requireExportable(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void viewCarriesTheGatesTheUiRenders() {
        templateWithTwoFields();

        var view = useCase.view(jobAt(JobStep.VALIDATED, ONE_FIELD, ONE_ERROR));

        assertThat(view.unmappedFields()).containsExactly("Email");
        assertThat(view.reachableSteps()).containsExactly("MAPPING");
        assertThat(view.validationErrorCount()).isEqualTo(1);
    }

    @Test
    void viewsLookUpEachTemplateOnce() {
        templateWithTwoFields();
        jobAt(JobStep.VALIDATED, BOTH_FIELDS, NO_ERRORS);
        IngestionJob second = new IngestionJob();
        second.setTemplateId(1);
        second.setCurrentStep(JobStep.MAPPED);
        second.setMappedRowsJson(ONE_FIELD);
        jobs.save(second);

        assertThat(useCase.views(jobs.list())).hasSize(2);
        verify(templateFields, times(1)).listByTemplateId(1);
    }
}
