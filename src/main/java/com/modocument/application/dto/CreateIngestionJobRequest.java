package com.modocument.application.dto;

/** JSON body of POST /api/ingestion/jobs when referencing an already-uploaded file. */
public class CreateIngestionJobRequest {

    private String fileName;
    private int templateId;
    private Boolean autoRun;

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
}
