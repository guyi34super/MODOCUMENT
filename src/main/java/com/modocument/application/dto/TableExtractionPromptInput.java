package com.modocument.application.dto;

/** Input for the TableExtraction prompt: raw document text to tabularize. */
public class TableExtractionPromptInput extends PromptInput {

    private final String documentText;

    public TableExtractionPromptInput(String documentText) {
        this.documentText = documentText;
    }

    public String getDocumentText() {
        return documentText;
    }
}
