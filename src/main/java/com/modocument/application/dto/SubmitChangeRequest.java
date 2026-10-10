package com.modocument.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/admin/changes (ws10 maker side). {@code payload}
 * is the type-specific request — validated and, where needed, sanitized by
 * the change type's applier at submit time.
 */
public class SubmitChangeRequest {

    /** A {@code ChangeType} name, e.g. USER_ROLE_CHANGE. */
    @NotBlank
    @Size(max = 40)
    private String changeType;

    @NotNull
    private JsonNode payload;

    @Size(max = 500)
    private String comment;

    public SubmitChangeRequest() {
    }

    public SubmitChangeRequest(String changeType, JsonNode payload, String comment) {
        this.changeType = changeType;
        this.payload = payload;
        this.comment = comment;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
