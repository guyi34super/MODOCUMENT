package com.modocument.application.dto;

/** Body of POST /api/ingestion/jobs/{id}/chat-edit. */
public class ChatEditRequest {

    private String instruction;

    public ChatEditRequest() {
    }

    public ChatEditRequest(String instruction) {
        this.instruction = instruction;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }
}
