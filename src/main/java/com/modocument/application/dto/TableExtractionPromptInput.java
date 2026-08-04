package com.modocument.application.dto;

import com.modocument.domain.entity.TemplateFields;

import java.util.List;

/** Input for the TableExtraction prompt: document text, hints, and batch mode. */
public class TableExtractionPromptInput extends PromptInput {

    private final String documentText;
    private final TableExtractionHints hints;
    private final TableExtractionMode mode;
    private final List<String> fixedColumns;
    private final TableRowBatch batch;
    private final List<TemplateFields> templateFields;

    public TableExtractionPromptInput(String documentText) {
        this(documentText, null, TableExtractionMode.FULL, null, null, null);
    }

    public TableExtractionPromptInput(String documentText, TableExtractionHints hints) {
        this(documentText, hints, TableExtractionMode.FULL, null, null, null);
    }

    public TableExtractionPromptInput(String documentText, TableExtractionHints hints,
                                      TableExtractionMode mode, List<String> fixedColumns,
                                      TableRowBatch batch) {
        this(documentText, hints, mode, fixedColumns, batch, null);
    }

    public TableExtractionPromptInput(String documentText, TableExtractionHints hints,
                                      TableExtractionMode mode, List<String> fixedColumns,
                                      TableRowBatch batch, List<TemplateFields> templateFields) {
        this.documentText = documentText;
        this.hints = hints;
        this.mode = mode == null ? TableExtractionMode.FULL : mode;
        this.fixedColumns = fixedColumns;
        this.batch = batch;
        this.templateFields = templateFields == null ? List.of() : templateFields;
    }

    public String getDocumentText() {
        return documentText;
    }

    public TableExtractionHints getHints() {
        return hints;
    }

    public TableExtractionMode getMode() {
        return mode;
    }

    public List<String> getFixedColumns() {
        return fixedColumns;
    }

    public TableRowBatch getBatch() {
        return batch;
    }

    public List<TemplateFields> getTemplateFields() {
        return templateFields;
    }
}
