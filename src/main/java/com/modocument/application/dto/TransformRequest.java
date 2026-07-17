package com.modocument.application.dto;

/**
 * Incoming request from the API layer. Contains just the file name
 * (e.g. "sample.pdf") previously uploaded to wwwroot/uploads.
 */
public class TransformRequest {

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
