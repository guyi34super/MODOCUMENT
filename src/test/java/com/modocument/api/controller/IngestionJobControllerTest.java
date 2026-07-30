package com.modocument.api.controller;

import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.UpdateRowsRequest;
import com.modocument.application.dto.UpdateWizardStepRequest;
import com.modocument.application.usecase.ChatEditTableUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionJobControllerTest {

    private final IngestionJobUseCase useCase = mock(IngestionJobUseCase.class);
    private final ChatEditTableUseCase chatEdit = mock(ChatEditTableUseCase.class);
    private final UploadStorage uploadStorage = mock(UploadStorage.class);
    private final SupportedDocumentTypes supportedTypes = mock(SupportedDocumentTypes.class);
    private final IngestionJobController controller =
            new IngestionJobController(useCase, chatEdit, uploadStorage, supportedTypes);

    private static IngestionJob validatedJob(WizardStep wizardStep) {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setTemplateId(1);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setWizardStep(wizardStep);
        return job;
    }

    /**
     * Views are built by the use case (it owns the step-reachability rule), so
     * a controller test has to stub that call as well as the action itself.
     */
    private void stubView(IngestionJob job) {
        when(useCase.view(job)).thenReturn(IngestionJobDto.from(
                job, List.of(), List.of("MAPPING", "VALIDATION", "EXPORT")));
    }

    @Test
    void rejectsUnsupportedUploadTypeWithSupportedList() {
        when(supportedTypes.isSupported("virus.exe")).thenReturn(false);
        when(supportedTypes.describeSupported()).thenReturn("csv, pdf, xlsx");
        MockMultipartFile file =
                new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[] {1});

        ResponseEntity<?> result = controller.createFromUpload(file, 1, true, "MAPPED");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().toString()).contains("Supported types").contains("csv");
    }

    @Test
    void rejectsUnsupportedExistingFileType() {
        when(supportedTypes.isSupported(anyString())).thenReturn(false);
        when(supportedTypes.describeSupported()).thenReturn("csv, pdf, xlsx");

        ResponseEntity<?> result = controller.createFromExisting(
                new com.modocument.application.dto.CreateIngestionJobRequest("file.zip", 1, true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().toString()).contains("Supported types");
    }

    @Test
    void updateWizardStepDelegatesWithParsedEnumAndReturnsDto() {
        IngestionJob job = validatedJob(WizardStep.VALIDATION);
        when(useCase.setWizardStep(7, WizardStep.VALIDATION)).thenReturn(job);
        stubView(job);

        ResponseEntity<?> result =
                controller.updateWizardStep(7, new UpdateWizardStepRequest("validation"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        IngestionJobDto dto = (IngestionJobDto) result.getBody();
        assertThat(dto.wizardStep()).isEqualTo("VALIDATION");
    }

    @Test
    void updateWizardStepRejectsBlankValue() {
        ResponseEntity<?> result =
                controller.updateWizardStep(7, new UpdateWizardStepRequest("  "));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateRowsDelegatesAndReturnsDto() {
        List<Map<String, String>> rows = List.of(Map.of("FirstName", "Grace"));
        IngestionJob job = validatedJob(null);
        job.setCurrentStep(JobStep.MAPPED);
        when(useCase.updateRows(7, rows)).thenReturn(job);
        stubView(job);

        ResponseEntity<?> result = controller.updateRows(7, new UpdateRowsRequest(rows));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        IngestionJobDto dto = (IngestionJobDto) result.getBody();
        assertThat(dto.currentStep()).isEqualTo("MAPPED");
    }

    @Test
    void updateRowsRejectsEmptyRows() {
        assertThat(controller.updateRows(7, new UpdateRowsRequest(null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.updateRows(7, new UpdateRowsRequest(List.of())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateWizardStepRejectsUnknownValue() {
        assertThatThrownBy(() ->
                controller.updateWizardStep(7, new UpdateWizardStepRequest("teleport")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teleport");
    }
}
