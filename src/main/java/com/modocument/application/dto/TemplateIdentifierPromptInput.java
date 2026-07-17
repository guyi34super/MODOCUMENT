package com.modocument.application.dto;

public class TemplateIdentifierPromptInput extends PromptInput {

    private String filePath = "";

    public TemplateIdentifierPromptInput() {
    }

    public TemplateIdentifierPromptInput(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
}
