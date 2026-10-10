package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Incoming request from the API layer. Contains just the file name
 * (e.g. "sample.pdf") previously uploaded to wwwroot/uploads.
 */
public class TransformRequest {

    @NotBlank
    @Size(max = 255)
    private String fileName = "";

    /**
     * ws23, optional: the template to extract into. Given, the AI template
     * classification is skipped — which is what lets the transform run with
     * code-based extraction when the AI is off or unavailable.
     */
    @Positive
    private Integer templateId;

    public TransformRequest() {
    }

    public TransformRequest(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Integer templateId) {
        this.templateId = templateId;
    }
}
