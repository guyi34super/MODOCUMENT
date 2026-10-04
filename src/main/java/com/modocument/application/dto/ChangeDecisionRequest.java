package com.modocument.application.dto;

import jakarta.validation.constraints.Size;

/** JSON body of POST /api/admin/changes/{id}/approve and /reject (ws10 checker side). */
public class ChangeDecisionRequest {

    @Size(max = 500)
    private String comment;

    public ChangeDecisionRequest() {
    }

    public ChangeDecisionRequest(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
