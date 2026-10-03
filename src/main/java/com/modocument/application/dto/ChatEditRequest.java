package com.modocument.application.dto;

import jakarta.validation.constraints.Size;

/** Body of POST /api/ingestion/jobs/{id}/chat-edit. */
public class ChatEditRequest {

    @Size(max = 8000)
    private String instruction;
    /** Optional 1-based row hint from the validation stepper. */
    private Integer row;
    /** Optional column hint from the validation stepper. */
    @Size(max = 255)
    private String column;

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

    public Integer getRow() {
        return row;
    }

    public void setRow(Integer row) {
        this.row = row;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }
}
