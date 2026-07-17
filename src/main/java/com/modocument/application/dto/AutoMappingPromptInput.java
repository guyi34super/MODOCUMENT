package com.modocument.application.dto;

public class AutoMappingPromptInput extends PromptInput {

    private String extractedFields;
    private String schemaFields;

    public AutoMappingPromptInput() {
    }

    public AutoMappingPromptInput(String extractedFields, String schemaFields) {
        this.extractedFields = extractedFields;
        this.schemaFields = schemaFields;
    }

    public String getExtractedFields() {
        return extractedFields;
    }

    public void setExtractedFields(String extractedFields) {
        this.extractedFields = extractedFields;
    }

    public String getSchemaFields() {
        return schemaFields;
    }

    public void setSchemaFields(String schemaFields) {
        this.schemaFields = schemaFields;
    }
}
