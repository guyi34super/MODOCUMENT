package com.modocument.application.usecase;

import com.modocument.application.dto.ChatEditPatchResponse;
import com.modocument.application.dto.RowPatch;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.infrastructure.prompt.ChatEditPatchPrompt;
import com.modocument.infrastructure.prompt.ChatEditPrompt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEditTableUseCaseTest {

    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final AiFilesService ai = mock(AiFilesService.class);
    private static final int USER = 1;
    private final ChatEditProperties chatEditProperties = new ChatEditProperties();
    private final ChatEditTableUseCase useCase = new ChatEditTableUseCase(
            jobs, List.of(new ChatEditPrompt(), new ChatEditPatchPrompt()), ai,
            new PiiTokenizer(new PrivacyProperties()), () -> USER, chatEditProperties);

    private IngestionJob jobWithMappedRows() {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setMappedRowsJson("{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"ada\"}]}");
        job.setValidationResultJson("[]");
        job.setExportInfoJson("{}");
        job.setOwnerUserId(USER);
        when(jobs.getByIdAndOwner(7, USER)).thenReturn(Optional.of(job));
        when(jobs.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        return job;
    }

    /** Three rows; rows 1 and 3 are valid, row 2 fails REQUIRED + email format. */
    private IngestionJob jobWithValidationErrors() {
        IngestionJob job = jobWithMappedRows();
        job.setMappedRowsJson("{\"columns\":[\"FirstName\",\"Email\"],\"rows\":["
                + "{\"FirstName\":\"ada\",\"Email\":\"ada@x.com\"},"
                + "{\"FirstName\":\"\",\"Email\":\"bad@example.com\"},"
                + "{\"FirstName\":\"grace\",\"Email\":\"grace@x.com\"}]}");
        job.setValidationResultJson("["
                + "{\"row\":1,\"fieldName\":\"FirstName\",\"rawValue\":\"ada\",\"errorMessage\":null,\"ruleType\":null},"
                + "{\"row\":2,\"fieldName\":\"FirstName\",\"rawValue\":\"\",\"errorMessage\":\"Value is required.\",\"ruleType\":\"REQUIRED\"},"
                + "{\"row\":2,\"fieldName\":\"Email\",\"rawValue\":\"bad@example.com\",\"errorMessage\":\"Email format is invalid.\",\"ruleType\":\"REGEX\"},"
                + "{\"row\":3,\"fieldName\":\"FirstName\",\"rawValue\":\"grace\",\"errorMessage\":null,\"ruleType\":null}]");
        return job;
    }

    @Test
    void appliesModifiedTableAndClearsStaleCheckpoints() {
        jobWithMappedRows();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName"), List.of(Map.of("FirstName", "Ada"))));

        IngestionJob result = useCase.handle(7, "Capitalize all first names");

        assertThat(result.getMappedRowsJson()).contains("Ada");
        assertThat(result.getValidationResultJson()).isNull();
        assertThat(result.getExportInfoJson()).isNull();
        assertThat(result.getCurrentStep()).isEqualTo(JobStep.MAPPED);
        assertThat(result.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void clampsWizardStepBackToMapping() {
        IngestionJob job = jobWithMappedRows();
        job.setWizardStep(WizardStep.VALIDATION);
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName"), List.of(Map.of("FirstName", "Ada"))));

        IngestionJob result = useCase.handle(7, "Capitalize all first names");

        assertThat(result.getWizardStep()).isEqualTo(WizardStep.MAPPING);
    }

    @Test
    void rejectsBlankInstruction() {
        assertThatThrownBy(() -> useCase.handle(7, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyModelResult() {
        jobWithMappedRows();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of(), List.of()));

        assertThatThrownBy(() -> useCase.handle(7, "Do something"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty table");
    }

    @Test
    void chunkedPathSendsOnlyErrorRowsAndMergesThePatch() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(
                        List.of(new RowPatch(2, Map.of("FirstName", "Ada")))));

        IngestionJob result = useCase.handle(7, "Fix the validation errors");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("patch mode");
        assertThat(prompt.getValue()).contains("Value is required.");
        assertThat(prompt.getValue()).contains("\"row\":2");
        assertThat(prompt.getValue()).doesNotContain("ada");
        assertThat(prompt.getValue()).doesNotContain("grace");
        assertThat(prompt.getValue()).contains("[EMAIL_1]");
        assertThat(prompt.getValue()).doesNotContain("bad@example.com");

        assertThat(result.getMappedRowsJson()).contains("Ada").contains("ada").contains("grace");
        assertThat(result.getValidationResultJson()).isNull();
        assertThat(result.getCurrentStep()).isEqualTo(JobStep.MAPPED);
    }

    @Test
    void cellScopedEditSendsSingleCellPayload() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(
                        List.of(new RowPatch(2, Map.of("Email", "fixed@example.com")))));

        useCase.handle(7, "Fix it", 2, "Email");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("ONE cell");
        assertThat(prompt.getValue()).contains("\"column\":\"Email\"");
        assertThat(prompt.getValue()).contains("Email format is invalid.");
        assertThat(prompt.getValue()).doesNotContain("Value is required.");
        assertThat(prompt.getValue()).doesNotContain("grace");
    }

    @Test
    void rowScopedEditSendsSingleRowPayload() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(
                        List.of(new RowPatch(2, Map.of("FirstName", "Ada")))));

        useCase.handle(7, "Fix this row", 2, "Email");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("ONE row");
        assertThat(prompt.getValue()).contains("\"row\":2");
        assertThat(prompt.getValue()).contains("Value is required.");
        assertThat(prompt.getValue()).contains("Email format is invalid.");
        assertThat(prompt.getValue()).doesNotContain("grace");
    }

    @Test
    void columnScopedEditSendsOnlyThatColumnsErrorCells() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(
                        List.of(new RowPatch(2, Map.of("Email", "fixed@example.com")))));

        useCase.handle(7, "Fix Email column");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("ONE column");
        assertThat(prompt.getValue()).contains("\"columns\":[\"Email\"]");
        assertThat(prompt.getValue()).contains("Email format is invalid.");
        assertThat(prompt.getValue()).doesNotContain("Value is required.");
        assertThat(prompt.getValue()).doesNotContain("grace");
    }

    @Test
    void patchEchoingATokenIsDetokenizedToTheOriginalValue() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("FirstName", "Ada", "Email", "[EMAIL_1]")))));

        IngestionJob result = useCase.handle(7, "Fix all errors: fill the name, keep the email");

        assertThat(result.getMappedRowsJson()).contains("bad@example.com");
        assertThat(result.getMappedRowsJson()).doesNotContain("[EMAIL_1]");
    }

    @Test
    void fullTablePromptIsTokenizedAndResponseDetokenized() {
        jobWithMappedRows(); // FirstName "ada", validation "[]" -> full-table path
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName"), List.of(Map.of("FirstName", "[NAME_1]"))));

        IngestionJob result = useCase.handle(7, "Deduplicate rows");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("[NAME_1]");
        assertThat(prompt.getValue()).doesNotContain("ada");

        assertThat(result.getMappedRowsJson()).contains("ada");
        assertThat(result.getMappedRowsJson()).doesNotContain("[NAME_1]");
    }

    @Test
    void fallsBackToFullTableWhenValidationHasNoErrors() {
        IngestionJob job = jobWithMappedRows();
        job.setValidationResultJson("["
                + "{\"row\":1,\"fieldName\":\"FirstName\",\"rawValue\":\"ada\",\"errorMessage\":null,\"ruleType\":null}]");
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName"), List.of(Map.of("FirstName", "Ada"))));

        useCase.handle(7, "Capitalize all first names");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).sendForObject(prompt.capture(), any(ParameterizedTypeReference.class), anyString());
        assertThat(prompt.getValue()).contains("return the FULL");
    }

    @Test
    void fullTableKeepsOurColumnsAndIgnoresInventedOnes() {
        jobWithMappedRows(); // columns: ["FirstName"]
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName", "Ssn"),
                        List.of(Map.of("FirstName", "Ada", "Ssn", "123-45-6789"))));

        IngestionJob result = useCase.handle(7, "Capitalize all first names");

        assertThat(result.getMappedRowsJson()).contains("Ada");
        assertThat(result.getMappedRowsJson()).doesNotContain("Ssn");
        assertThat(result.getMappedRowsJson()).doesNotContain("123-45-6789");
    }

    @Test
    void fullTableRejectsARowCountChange() {
        IngestionJob job = jobWithMappedRows(); // one row
        String before = job.getMappedRowsJson();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new TableData(List.of("FirstName"),
                        List.of(Map.of("FirstName", "Ada"), Map.of("FirstName", "Grace"))));

        assertThatThrownBy(() -> useCase.handle(7, "Capitalize all first names"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 rows but the table has 1");
        assertThat(job.getMappedRowsJson()).isEqualTo(before);
    }

    @Test
    void rejectsPatchRowOutOfRange() {
        IngestionJob job = jobWithValidationErrors();
        String before = job.getMappedRowsJson();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(
                        List.of(new RowPatch(99, Map.of("FirstName", "Ada")))));

        assertThatThrownBy(() -> useCase.handle(7, "Fix the validation errors"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the table");
        assertThat(job.getMappedRowsJson()).isEqualTo(before);
        assertThat(job.getValidationResultJson()).isNotNull();
    }

    @Test
    void ignoresUnknownPatchColumns() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("FirstName", "Ada", "Bogus", "x")))));

        IngestionJob result = useCase.handle(7, "Fix the validation errors");

        assertThat(result.getMappedRowsJson()).contains("Ada");
        assertThat(result.getMappedRowsJson()).doesNotContain("Bogus");
    }

    @Test
    void rejectsEmptyPatch() {
        IngestionJob job = jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of()));

        assertThatThrownBy(() -> useCase.handle(7, "Fix the validation errors"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no row changes");
        // Failing before persistence keeps the validation checkpoint intact.
        assertThat(job.getValidationResultJson()).isNotNull();
    }

    // --- Guardrails: scope clamping -----------------------------------------

    @Test
    void cellScopedEditDropsOutOfScopeCells() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("Email", "fixed@example.com", "FirstName", "HACKED")))));

        IngestionJob result = useCase.handle(7, "Fix it", 2, "Email");

        assertThat(result.getMappedRowsJson()).contains("fixed@example.com");
        assertThat(result.getMappedRowsJson()).doesNotContain("HACKED");
    }

    @Test
    void rowScopedEditDropsPatchesForOtherRows() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("FirstName", "Ada")),
                        new RowPatch(3, Map.of("FirstName", "HACKED")))));

        IngestionJob result = useCase.handle(7, "Fix this row", 2, "FirstName");

        assertThat(result.getMappedRowsJson()).contains("Ada").contains("grace");
        assertThat(result.getMappedRowsJson()).doesNotContain("HACKED");
    }

    @Test
    void columnScopedEditDropsChangesToOtherColumns() {
        jobWithValidationErrors();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("Email", "fixed@example.com", "FirstName", "HACKED")))));

        IngestionJob result = useCase.handle(7, "Fix Email column");

        assertThat(result.getMappedRowsJson()).contains("fixed@example.com");
        assertThat(result.getMappedRowsJson()).doesNotContain("HACKED");
    }

    @Test
    void rejectsWhenPatchOnlyChangesOutOfScopeCells() {
        IngestionJob job = jobWithValidationErrors();
        String before = job.getMappedRowsJson();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(new ChatEditPatchResponse(List.of(
                        new RowPatch(2, Map.of("FirstName", "HACKED")))));

        assertThatThrownBy(() -> useCase.handle(7, "Fix it", 2, "Email"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside what you asked to edit");
        assertThat(job.getMappedRowsJson()).isEqualTo(before);
        assertThat(job.getValidationResultJson()).isNotNull();
    }

    // --- Guardrails: full-table change budget -------------------------------

    /** Two columns, five rows, no validation errors -> full-table path (10 cells). */
    private IngestionJob jobFullTable() {
        IngestionJob job = jobWithMappedRows();
        job.setMappedRowsJson("{\"columns\":[\"A\",\"B\"],\"rows\":["
                + "{\"A\":\"a1\",\"B\":\"b1\"},"
                + "{\"A\":\"a2\",\"B\":\"b2\"},"
                + "{\"A\":\"a3\",\"B\":\"b3\"},"
                + "{\"A\":\"a4\",\"B\":\"b4\"},"
                + "{\"A\":\"a5\",\"B\":\"b5\"}]}");
        job.setValidationResultJson("[]");
        return job;
    }

    private static TableData table2x5(List<Map<String, String>> rows) {
        return new TableData(List.of("A", "B"), rows);
    }

    @Test
    void fullTableNonBulkOverRatioIsRejected() {
        IngestionJob job = jobFullTable();
        String before = job.getMappedRowsJson();
        // 7 of 10 cells changed (0.7 > 0.6) with a non-bulk instruction.
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(table2x5(List.of(
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "b4"),
                        Map.of("A", "a5", "B", "b5"))));

        assertThatThrownBy(() -> useCase.handle(7, "Tidy up the values"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("looks broader");
        assertThat(job.getMappedRowsJson()).isEqualTo(before);
    }

    @Test
    void fullTableBulkOverRatioIsApplied() {
        jobFullTable();
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(table2x5(List.of(
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "Y"),
                        Map.of("A", "X", "B", "b4"),
                        Map.of("A", "a5", "B", "b5"))));

        IngestionJob result = useCase.handle(7, "Uppercase all the values");

        assertThat(result.getMappedRowsJson()).contains("X").contains("Y");
    }

    @Test
    void fullTableUnderRatioIsApplied() {
        jobFullTable();
        // Only 1 of 10 cells changed (0.1 < 0.6).
        when(ai.sendForObject(anyString(), any(ParameterizedTypeReference.class), anyString()))
                .thenReturn(table2x5(List.of(
                        Map.of("A", "X", "B", "b1"),
                        Map.of("A", "a2", "B", "b2"),
                        Map.of("A", "a3", "B", "b3"),
                        Map.of("A", "a4", "B", "b4"),
                        Map.of("A", "a5", "B", "b5"))));

        IngestionJob result = useCase.handle(7, "Correct the first A value");

        assertThat(result.getMappedRowsJson()).contains("\"A\":\"X\"");
    }
}
