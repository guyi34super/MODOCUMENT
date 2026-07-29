package com.modocument.application.dto;

/**
 * Input for the ChatEditPatch prompt: a user instruction, the full table's
 * column list, and only the rows that need modifying (with their validation
 * errors) — so the model never sees rows it should not touch.
 */
public class ChatEditPatchPromptInput extends PromptInput {

    private final String instruction;
    private final String columnsJson;
    private final String errorRowsJson;

    public ChatEditPatchPromptInput(String instruction, String columnsJson, String errorRowsJson) {
        this.instruction = instruction;
        this.columnsJson = columnsJson;
        this.errorRowsJson = errorRowsJson;
    }

    public String getInstruction() {
        return instruction;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public String getErrorRowsJson() {
        return errorRowsJson;
    }
}
