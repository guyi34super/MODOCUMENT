package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of PUT /api/ingestion/jobs/{id}/wizard-step: MAPPING, VALIDATION or EXPORT. */
public class UpdateWizardStepRequest {

    @NotBlank
    @Size(max = 20)
    private String wizardStep;

    public UpdateWizardStepRequest() {
    }

    public UpdateWizardStepRequest(String wizardStep) {
        this.wizardStep = wizardStep;
    }

    public String getWizardStep() {
        return wizardStep;
    }

    public void setWizardStep(String wizardStep) {
        this.wizardStep = wizardStep;
    }
}
