package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/ingestion/jobs when referencing an already-uploaded file. */
public class CreateIngestionJobRequest {

    /** A stored name (varchar(255) everywhere it is recorded). */
    @NotBlank
    @Size(max = 255)
    private String fileName;

    @Positive
    private int templateId;

    private Boolean autoRun;

    @Size(max = 20)
    private String runUntil;

    private Boolean wait;

    public CreateIngestionJobRequest() {
    }

    public CreateIngestionJobRequest(String fileName, int templateId, Boolean autoRun) {
        this.fileName = fileName;
        this.templateId = templateId;
        this.autoRun = autoRun;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public Boolean getAutoRun() {
        return autoRun;
    }

    public void setAutoRun(Boolean autoRun) {
        this.autoRun = autoRun;
    }

    public String getRunUntil() {
        return runUntil;
    }

    public void setRunUntil(String runUntil) {
        this.runUntil = runUntil;
    }

    public Boolean getWait() {
        return wait;
    }

    public void setWait(Boolean wait) {
        this.wait = wait;
    }
}
