package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming request from the API layer. Contains just the file name
 * (e.g. "sample.pdf") previously uploaded to wwwroot/uploads.
 */
public class TransformRequest {

    @NotBlank
    @Size(max = 255)
    private String fileName = "";

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
}
