package com.modocument.api.controller;

import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.UpdateWizardStepRequest;
import com.modocument.application.usecase.ChatEditTableUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.infrastructure.file.UploadStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionJobControllerTest {

    private final IngestionJobUseCase useCase = mock(IngestionJobUseCase.class);
    private final ChatEditTableUseCase chatEdit = mock(ChatEditTableUseCase.class);
    private final UploadStorage uploadStorage = mock(UploadStorage.class);
    private final IngestionJobController controller =
            new IngestionJobController(useCase, chatEdit, uploadStorage);

    private static IngestionJob validatedJob(WizardStep wizardStep) {
        IngestionJob job = new IngestionJob();
        job.setIngestionJobId(7);
        job.setTemplateId(1);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setStatus(JobStatus.COMPLETED);
        job.setWizardStep(wizardStep);
        return job;
    }

    @Test
    void updateWizardStepDelegatesWithParsedEnumAndReturnsDto() {
        when(useCase.setWizardStep(7, WizardStep.VALIDATION))
                .thenReturn(validatedJob(WizardStep.VALIDATION));

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
    void updateWizardStepRejectsUnknownValue() {
        assertThatThrownBy(() ->
                controller.updateWizardStep(7, new UpdateWizardStepRequest("teleport")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teleport");
    }
}
