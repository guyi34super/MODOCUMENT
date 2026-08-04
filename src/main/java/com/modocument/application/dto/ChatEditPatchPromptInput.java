package com.modocument.application.dto;

import com.modocument.application.usecase.ChatEditScope;

/**
 * Input for scoped chat-edit patch prompts: a user instruction, the full
 * table's column list, and a scope-specific payload (one cell, one row,
 * one column's error cells, or all error rows).
 */
public class ChatEditPatchPromptInput extends PromptInput {

    private final ChatEditScope scope;
    private final String instruction;
    private final String columnsJson;
    private final String payloadJson;

    public ChatEditPatchPromptInput(ChatEditScope scope,
                                    String instruction,
                                    String columnsJson,
                                    String payloadJson) {
        this.scope = scope;
        this.instruction = instruction;
        this.columnsJson = columnsJson;
        this.payloadJson = payloadJson;
    }

    public ChatEditScope getScope() {
        return scope;
    }

    public String getInstruction() {
        return instruction;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public String getPayloadJson() {
        return payloadJson;
    }
}
