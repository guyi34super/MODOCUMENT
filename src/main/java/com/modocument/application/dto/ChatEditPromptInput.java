package com.modocument.application.dto;

/** Input for the ChatEdit prompt: a user instruction plus the current table JSON. */
public class ChatEditPromptInput extends PromptInput {

    private final String instruction;
    private final String tableJson;

    public ChatEditPromptInput(String instruction, String tableJson) {
        this.instruction = instruction;
        this.tableJson = tableJson;
    }

    public String getInstruction() {
        return instruction;
    }

    public String getTableJson() {
        return tableJson;
    }
}
