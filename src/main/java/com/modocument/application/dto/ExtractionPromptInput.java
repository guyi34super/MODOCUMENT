package com.modocument.application.dto;

public class ExtractionPromptInput extends PromptInput {

    private String fileContent = "";
    private String model = "";

    public ExtractionPromptInput() {
    }

    public ExtractionPromptInput(String fileContent, String model) {
        this.fileContent = fileContent;
        this.model = model;
    }

    public String getFileContent() {
        return fileContent;
    }

    public void setFileContent(String fileContent) {
        this.fileContent = fileContent;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
