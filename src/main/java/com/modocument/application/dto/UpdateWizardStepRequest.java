package com.modocument.application.dto;

/** Body of PUT /api/ingestion/jobs/{id}/wizard-step: MAPPING, VALIDATION or EXPORT. */
public class UpdateWizardStepRequest {

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
